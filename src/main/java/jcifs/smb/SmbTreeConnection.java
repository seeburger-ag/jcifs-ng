/*
 * © 2017 AgNO3 Gmbh & Co. KG
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package jcifs.smb;


import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jcifs.Address;
import jcifs.CIFSContext;
import jcifs.CIFSException;
import jcifs.Configuration;
import jcifs.DfsReferralData;
import jcifs.RuntimeCIFSException;
import jcifs.SmbConstants;
import jcifs.SmbResourceLocator;
import jcifs.SmbTreeHandle;
import jcifs.smb.SmbTransportImpl.ServerData;
import jcifs.util.transport.TransportException;


/**
 * This class encapsulates the logic for switching tree connections
 * 
 * Switching trees can occur either when the tree has been disconnected by failure or idle-timeout - as well as on
 * DFS referrals.
 * 
 * @author mbechler
 *
 */
class SmbTreeConnection {

    private static final Logger log = LoggerFactory.getLogger(SmbTreeConnection.class);

    private final CIFSContext ctx;
    private final SmbTreeConnection delegate;
    private SmbTreeImpl tree;
    private volatile boolean treeAcquired;
    private volatile boolean delegateAcquired;

    private SmbTransportInternal exclusiveTransport;
    private boolean nonPooled;

    private final AtomicLong usageCount = new AtomicLong();

    private static final Random RAND = new Random();


    /**
     * @param ctx
     * 
     */
    public SmbTreeConnection ( CIFSContext ctx ) {
        this.ctx = ctx;
        this.delegate = null;
    }


    /**
     * @param treeConnection
     */
    public SmbTreeConnection ( SmbTreeConnection treeConnection ) {
        this.ctx = treeConnection.ctx;
        this.delegate = treeConnection;
    }


    /**
     * @return the active configuration
     */
    public Configuration getConfig () {
        return this.ctx.getConfig();
    }


    private synchronized SmbTreeImpl getTree () {
        SmbTreeImpl t = this.tree;
        if ( t != null ) {
            return t.acquire(false);
        }
        else if ( this.delegate != null ) {
            this.tree = this.delegate.getTree();
            return this.tree;
        }
        return t;
    }


    /**
     * @return
     */
    private synchronized SmbTreeImpl getTreeInternal () {
        SmbTreeImpl t = this.tree;
        if ( t != null ) {
            return t;
        }
        if ( this.delegate != null ) {
            return this.delegate.getTreeInternal();
        }
        return null;
    }


    /**
     * @param t
     */
    private synchronized void switchTree ( SmbTreeImpl t ) {
        try ( SmbTreeImpl old = getTree() ) {
            if ( old == t ) {
                return;
            }
            boolean wasAcquired = this.treeAcquired;
            log.debug("Switching tree");
            if ( t != null ) {
                log.debug("Acquired tree on switch " + t);
                t.acquire();
                this.treeAcquired = true;
            }
            else {
                this.treeAcquired = false;
            }

            this.tree = t;
            if ( old != null ) {
                if ( wasAcquired ) {
                    // release
                    old.release(true);
                }
            }
            if ( this.delegate != null && this.delegateAcquired ) {
                log.debug("Releasing delegate");
                this.delegateAcquired = false;
                this.delegate.release();
            }
        }
    }


    /**
     * @return tree connection with increased usage count
     */
    public SmbTreeConnection acquire () {
        long usage = this.usageCount.incrementAndGet();
        if ( log.isTraceEnabled() ) {
            log.trace("Acquire tree connection " + usage + " " + this);
        }

        if ( usage == 1 ) {
            synchronized ( this ) {
                try ( SmbTreeImpl t = getTree() ) {
                    if ( t != null ) {
                        if ( !this.treeAcquired ) {
                            if ( log.isDebugEnabled() ) {
                                log.debug("Acquire tree on first usage " + t);
                            }
                            t.acquire();
                            this.treeAcquired = true;
                        }
                    }
                }
                if ( this.delegate != null && !this.delegateAcquired ) {
                    log.debug("Acquire delegate on first usage");
                    this.delegate.acquire();
                    this.delegateAcquired = true;
                }
            }
        }

        return this;

    }


    /**
     * 
     */
    public void release () {
        long usage = this.usageCount.decrementAndGet();
        if ( log.isTraceEnabled() ) {
            log.trace("Release tree connection " + usage + " " + this);
        }

        if ( usage == 0 ) {
            synchronized ( this ) {
                try ( SmbTreeImpl t = getTree() ) {
                    if ( this.treeAcquired && t != null ) {
                        if ( log.isDebugEnabled() ) {
                            log.debug("Tree connection no longer in use, release tree " + t);
                        }
                        this.treeAcquired = false;
                        t.release();
                    }
                }
                if ( this.delegate != null && this.delegateAcquired ) {
                    this.delegateAcquired = false;
                    this.delegate.release();
                }
            }

            SmbTransportInternal et = this.exclusiveTransport;
            if ( et != null ) {
                synchronized ( this ) {
                    try {
                        log.debug("Disconnecting exclusive transport");
                        this.exclusiveTransport = null;
                        this.tree = null;
                        this.treeAcquired = false;
                        et.close();
                        et.disconnect(false, false);
                    }
                    catch ( Exception e ) {
                        log.error("Failed to close exclusive transport", e);
                    }
                }
            }
        }
        else if ( usage < 0 ) {
            log.error("Usage count dropped below zero " + this);
            throw new RuntimeCIFSException("Usage count dropped below zero");
        }
    }


    /**
     * {@inheritDoc}
     *
     * @see java.lang.Object#finalize()
     */
    @Override
    protected void finalize () throws Throwable {
        if ( isConnected() && this.usageCount.get() != 0 ) {
            log.warn("Tree connection was not properly released " + this);
        }
    }


    synchronized void disconnect ( boolean inError ) {
        try ( SmbSessionImpl session = getSession();
              SmbTransportImpl transport = session.getTransport() ) {
            synchronized ( transport ) {
                SmbTreeImpl t = getTreeInternal();
                if ( t != null ) {
                    t.treeDisconnect(inError, true);
                    if ( inError ) {
                        session.logoff(inError, true);
                    }
                    this.tree = null;
                    this.treeAcquired = false;
                }
                else {
                    this.delegate.disconnect(inError);
                }
            }
        }
    }


    void send ( SmbResourceLocatorImpl loc, ServerMessageBlock request, ServerMessageBlock response, RequestParam... params ) throws CIFSException {
        send(loc, request, response, params.length == 0 ? EnumSet.noneOf(RequestParam.class) : EnumSet.copyOf(Arrays.asList(params)));
    }


    void send ( SmbResourceLocatorImpl loc, ServerMessageBlock request, ServerMessageBlock response, Set<RequestParam> params ) throws CIFSException {
        SmbException last = null;
        String savedPath = ( request != null ) ? request.path : null;
        int maxRetries = this.ctx.getConfig().getMaxRequestRetries();
        for ( int retries = 1; retries <= maxRetries; retries++ ) {
            try {
                send0(loc, request, response, params);
                return;
            }
            catch ( SmbException smbe ) {
                // Retrying only makes sense if the invalid parameter is an tree id. If we have a stale file descriptor
                // retrying make no sense, as it will never become available again.
                if ( params.contains(RequestParam.NO_RETRY)
                        || ( ! ( smbe.getCause() instanceof TransportException ) ) && smbe.getNtStatus() != NtStatus.NT_STATUS_INVALID_PARAMETER ) {
                    log.debug("Not retrying", smbe);
                    throw smbe;
                }
                log.debug("send", smbe);
                last = smbe;
            }
            // If we get here, we got the 'The Parameter is incorrect' error or a transport exception
            // Disconnect and try again from scratch.

            if ( log.isDebugEnabled() ) {
                log.debug(String.format("Retrying (%d/%d) request %s", retries, maxRetries, request));
            }
            disconnect(true);

            if ( retries == maxRetries ) {
                break;
            }

            try {
                if ( retries != 1 ) {
                    // backoff, but don't delay the first attempt as there are various reasons that can be fixed
                    // immediately
                    Thread.sleep(500 + RAND.nextInt(1000));
                }
            }
            catch ( InterruptedException e ) {
                log.debug("interrupted sleep in send", e);
            }
            if ( request != null ) {
                // resolveDfs() and tree.send() modify the request packet.
                // I want to restore it before retrying. request.reset()
                // restores almost everything that was modified, except the path.
                request.reset();
                request.path = savedPath;
            }
            if ( response != null )
                response.reset();

            try ( SmbTreeHandle th = connectWrapException(loc) ) {
                log.debug("Have new tree connection for retry");
            }
            catch ( SmbException e ) {
                log.debug("Failed to connect tree on retry", e);
                last = e;
            }
        }
        if ( last != null ) {
            log.debug("All attempts have failed, last exception", last);
            throw last;
        }
    }


    private void send0 ( SmbResourceLocatorImpl loc, ServerMessageBlock request, ServerMessageBlock response, Set<RequestParam> params )
            throws CIFSException, DfsReferral {
        for ( ;; ) {
            ensureDFSResolved(loc, request);
            try ( SmbTreeImpl t = getTree() ) {
                t.send(request, response, params);
                break;
            }
            catch ( DfsReferral dre ) {
                if ( dre.getData().unwrap(DfsReferralDataInternal.class).isResolveHashes() ) {
                    throw dre;
                }
                request.reset();
                log.trace("send0", dre);
            }
        }
    }


    /**
     * @param loc
     * @return tree handle
     * @throws SmbException
     */
    public SmbTreeHandleImpl connectWrapException ( SmbResourceLocatorImpl loc ) throws SmbException {
        try {
            return connect(loc);
        }
        catch ( UnknownHostException uhe ) {
            throw new SmbException("Failed to connect to server", uhe);
        }
        catch ( SmbException se ) {
            throw se;
        }
        catch ( IOException ioe ) {
            throw new SmbException("Failed to connect to server", ioe);
        }
    }


    /**
     * @param loc
     * @return tree handle
     * @throws IOException
     */
    public synchronized SmbTreeHandleImpl connect ( SmbResourceLocatorImpl loc ) throws IOException {
        try ( SmbSessionImpl session = getSession() ) {
            if ( isConnected() ) {
                try ( SmbTransportImpl transport = session.getTransport() ) {
                    if ( transport.tconHostName == null ) {
                        /*
                         * Tree/session thinks it is connected but transport disconnected
                         * under it, reset tree to reflect the truth.
                         */
                        log.debug("Disconnecting failed tree and session");
                        disconnect(true);
                    }
                }
            }

            if ( isConnected() ) {
                log.trace("Already connected");
                return new SmbTreeHandleImpl(loc, this);
            }

            Address addr = loc.getFirstAddress();
            for ( ;; ) {
                try {
                    return connectHost(loc, addr);
                }
                catch ( SmbAuthException sae ) {
                    throw sae; // Prevents account lockout on servers with multiple IPs
                }
                catch ( SmbException se ) {
                    if ( ( addr = loc.getNextAddress() ) == null )
                        throw se;
                    log.debug("Connect failed", se);
                }
            }
        }

    }


    /**
     * @return whether we have a valid tree connection
     */
    @SuppressWarnings ( "resource" )
    public synchronized boolean isConnected () {
        SmbTreeImpl t = getTreeInternal();
        return t != null && t.isConnected();
    }


    /**
     * 
     * @param loc
     * @param addr
     * @return tree handle
     * @throws IOException
     */
    public synchronized SmbTreeHandleImpl connectHost ( SmbResourceLocatorImpl loc, Address addr ) throws IOException {
        try ( SmbTreeImpl t = getTree() ) {
            if ( t != null ) {
                if ( log.isDebugEnabled() ) {
                    log.debug("Tree is " + t);
                }
                try ( SmbSessionImpl session = t.getSession();
                      SmbTransportImpl trans = session.getTransport();
                      SmbTreeImpl ct = connectTree(loc, addr, trans, t) ) {
                    switchTree(ct);
                    return new SmbTreeHandleImpl(loc, this);
                }
            }
        }

        if ( this.nonPooled ) {
            if ( log.isDebugEnabled() ) {
                log.debug("Using exclusive transport for " + this);
            }
            this.exclusiveTransport = this.ctx.getTransportPool().getSmbTransport(this.ctx, addr, loc.getPort(), true, loc.shouldForceSigning())
                    .unwrap(SmbTransportInternal.class);
            SmbTransportInternal trans = this.exclusiveTransport;
            try ( SmbSessionInternal smbSession = trans.getSmbSession(this.ctx).unwrap(SmbSessionInternal.class);
                  SmbTreeImpl uct = smbSession.getSmbTree(loc.getShare(), null).unwrap(SmbTreeImpl.class);
                  SmbTreeImpl ct = connectTree(loc, addr, trans, uct) ) {
                switchTree(ct);
                return new SmbTreeHandleImpl(loc, this);
            }
        }

        try ( SmbTransportInternal trans = this.ctx.getTransportPool().getSmbTransport(this.ctx, addr, loc.getPort(), false, loc.shouldForceSigning())
                .unwrap(SmbTransportInternal.class);
              SmbSessionInternal smbSession = trans.getSmbSession(this.ctx).unwrap(SmbSessionInternal.class);
              SmbTreeImpl uct = smbSession.getSmbTree(loc.getShare(), null).unwrap(SmbTreeImpl.class);
              SmbTreeImpl ct = connectTree(loc, addr, trans, uct) ) {
            switchTree(ct);
            return new SmbTreeHandleImpl(loc, this);
        }
    }


    /**
     * @param loc
     * @param addr
     * @param trans
     * @param t
     * @throws CIFSException
     */
    private SmbTreeImpl connectTree ( SmbResourceLocator loc, Address addr, SmbTransportInternal trans, SmbTreeImpl t ) throws CIFSException {
        if ( log.isDebugEnabled() && trans.isSigningOptional() && !loc.isIPC() && !this.ctx.getConfig().isSigningEnforced() ) {
            log.debug("Signatures for file enabled but not required " + this);
        }

        String hostName = loc.getServerWithDfs();
        DfsReferralData referral = this.ctx.getDfs().resolve(this.ctx, hostName, t.getShare(), null);
        if ( referral != null ) {
            t.markDomainDfs();
            // make sure transport is connected
            trans.ensureConnected();
            t.markConnected();
        }

        try {
            if ( log.isTraceEnabled() ) {
                log.trace("doConnect: " + addr);
            }
            t.treeConnect(null, null);
            return t.acquire();
        }
        catch ( SmbAuthException sae ) {
            log.debug("Authentication failed", sae);
            if ( loc.isIPC() ) { // IPC$ - try "anonymous" credentials
                try ( SmbSessionInternal s = trans.getSmbSession(this.ctx.withAnonymousCredentials()).unwrap(SmbSessionInternal.class);
                      SmbTreeImpl tr = s.getSmbTree(null, null).unwrap(SmbTreeImpl.class) ) {
                    tr.treeConnect(null, null);
                    return tr.acquire();
                }
            }
            else if ( this.ctx.renewCredentials(loc.getURL().toString(), sae) ) {
                log.debug("Trying to renew credentials after auth error");
                try ( SmbSessionInternal s = trans.getSmbSession(this.ctx).unwrap(SmbSessionInternal.class);
                      SmbTreeImpl tr = s.getSmbTree(loc.getShare(), null).unwrap(SmbTreeImpl.class) ) {
                    if ( referral != null ) {
                        tr.markDomainDfs();
                        tr.markConnected();
                    }
                    tr.treeConnect(null, null);
                    return tr.acquire();
                }
            }
            else {
                throw sae;
            }
        }
    }


    SmbResourceLocator ensureDFSResolved ( SmbResourceLocatorImpl loc ) throws CIFSException {
        return ensureDFSResolved(loc, null);
    }


    SmbResourceLocator ensureDFSResolved ( SmbResourceLocatorImpl loc, ServerMessageBlock request ) throws CIFSException {
        if ( request instanceof SmbComClose )
            return loc;

        for ( int retries = 0; retries < 1 + this.ctx.getConfig().getMaxRequestRetries(); retries++ ) {
            try {
                return resolveDfs0(loc, request);
            }
            catch ( NullPointerException npe ) {
                // Bug where transport or tconHostName is null indicates
                // failed to clean up properly from dropped connection.
                log.debug("resolveDfs", npe);
            }
            catch ( SmbException smbe ) {
                // The connection may have been dropped?
                if ( smbe.getNtStatus() != NtStatus.NT_STATUS_NOT_FOUND ) {
                    throw smbe;
                }
                log.debug("resolveDfs", smbe);
            }
            // If we get here, we apparently have a bad connection.
            // Disconnect and try again.
            if ( log.isDebugEnabled() ) {
                log.debug("Retrying (" + retries + ") resolveDfs: " + request);
            }
            disconnect(true);
            try {
                Thread.sleep(500 + RAND.nextInt(5000));
            }
            catch ( InterruptedException e ) {
                log.debug("resolveDfs", e);
            }

            try ( SmbTreeHandle th = connectWrapException(loc) ) {}
        }

        return loc;
    }


    private SmbResourceLocator resolveDfs0 ( SmbResourceLocatorImpl loc, ServerMessageBlock request ) throws CIFSException {
        try ( SmbTreeHandleImpl th = connectWrapException(loc);
              SmbSessionImpl session = th.getSession();
              SmbTransportImpl transport = session.getTransport();
              SmbTreeImpl t = getTree() ) {
            DfsReferralData dr = this.ctx.getDfs().resolve(this.ctx, transport.tconHostName, loc.getShare(), loc.getUNCPath());
            if ( dr != null ) {
                if ( log.isDebugEnabled() ) {
                    log.debug("Info " + transport.tconHostName + "\\" + loc.getShare() + loc.getUNCPath() + " -> " + dr);
                }
                String service = t != null ? t.getService() : null;

                if ( request != null ) {
                    switch ( request.command ) {
                    case ServerMessageBlock.SMB_COM_TRANSACTION:
                    case ServerMessageBlock.SMB_COM_TRANSACTION2:
                        switch ( ( (SmbComTransaction) request ).subCommand & 0xFF ) {
                        case SmbComTransaction.TRANS2_GET_DFS_REFERRAL:
                            break;
                        default:
                            service = "A:";
                        }
                        break;
                    default:
                        service = "A:";
                    }
                }

                String dunc = loc.handleDFSReferral(followReferrals(loc, dr, service), request != null && request.path != null ? request.path : null);

                if ( request != null ) {
                    request.path = dunc;
                    request.flags2 |= SmbConstants.FLAGS2_RESOLVE_PATHS_IN_DFS;
                }

                return loc;
            }
            else if ( t.isInDomainDfs() && ! ( request instanceof NtTransQuerySecurityDesc ) && ! ( request instanceof SmbComClose )
                    && ! ( request instanceof SmbComFindClose2 ) ) {
                throw new SmbException(NtStatus.NT_STATUS_NOT_FOUND, false);
            }
            else {
                log.trace("Not in DFS");
                if ( request != null )
                    request.flags2 &= ~SmbConstants.FLAGS2_RESOLVE_PATHS_IN_DFS;

                return loc;
            }
        }
    }


    /**
     * @param loc
     * @param dr
     * @param service
     * @return final referral
     * @throws SmbException
     */
    private DfsReferralData followReferrals ( SmbResourceLocatorInternal loc, DfsReferralData dr, String service ) throws SmbException {
        SmbException se;
        DfsReferralData start = dr;
        do {
            try {
                if ( log.isTraceEnabled() ) {
                    log.trace("DFS redirect: " + dr);
                }

                Address addr = this.ctx.getNameServiceClient().getByName(dr.getServer());
                try ( SmbTransportInternal trans = this.ctx.getTransportPool()
                        .getSmbTransport(this.ctx, addr, loc.getPort(), false, loc.shouldForceSigning()).unwrap(SmbTransportInternal.class) ) {

                    synchronized ( trans ) {
                        /*
                         * This is a key point. This is where we set the "tree" of this file which
                         * is like changing the rug out from underneath our feet.
                         */
                        /*
                         * Technically we should also try to authenticate here but that means doing the session
                         * setup
                         * and
                         * tree connect separately. For now a simple connect will at least tell us if the host is
                         * alive.
                         * That should be sufficient for 99% of the cases. We can revisit this again for 2.0.
                         */
                        trans.ensureConnected();
                        try ( SmbSessionInternal smbSession = trans.getSmbSession(this.ctx).unwrap(SmbSessionInternal.class);
                              SmbTreeImpl t = smbSession.getSmbTree(dr.getShare(), service).unwrap(SmbTreeImpl.class) ) {
                            switchTree(t);
                        }
                        if ( dr != start ) {
                            dr.unwrap(DfsReferralDataInternal.class).replaceCache();
                        }
                    }
                }
                se = null;
                break;
            }
            catch ( IOException ioe ) {
                log.debug("Error checking dfs root", ioe);
                if ( ioe instanceof SmbException ) {
                    se = (SmbException) ioe;
                }
                else {
                    se = new SmbException("Failed to connect to server " + dr.getServer(), ioe);
                }
            }

            dr = dr.next();
        }
        while ( dr != start );

        if ( se != null )
            throw se;
        return dr;
    }


    /**
     * Use a exclusive connection for this tree
     * 
     * If an exclusive connection is used the caller must make sure that the tree handle is kept alive,
     * otherwise the connection will be disconnected once the usage drops to zero.
     * 
     * @param np
     *            whether to use an exclusive connection
     */
    void setNonPooled ( boolean np ) {
        this.nonPooled = np;
    }


    /**
     * @return the currently connected tid
     */
    @SuppressWarnings ( "resource" )
    public long getTreeId () {
        SmbTreeImpl t = getTreeInternal();
        if ( t == null ) {
            return -1;
        }
        return t.getTreeNum();
    }


    /**
     * 
     * Only call this method while holding a tree handle
     * 
     * @return session that this file has been loaded through
     */
    @SuppressWarnings ( "resource" )
    public SmbSessionImpl getSession () {
        SmbTreeImpl t = getTreeInternal();
        if ( t != null ) {
            return t.getSession();
        }
        return null;
    }


    /**
     * 
     * Only call this method while holding a tree handle
     * 
     * @param cap
     * @return whether the capability is available
     * @throws SmbException
     */
    public boolean hasCapability ( int cap ) throws SmbException {
        try ( SmbSessionImpl s = getSession() ) {
            if ( s != null ) {
                try ( SmbTransportImpl transport = s.getTransport() ) {
                    return transport.hasCapability(cap);
                }
            }
            throw new SmbException("Not connected");
        }
    }


    /**
     * 
     * Only call this method while holding a tree handle
     * 
     * @return server data provided during negotiation
     */
    public ServerData getServerData () {
        try ( SmbSessionImpl session = getSession();
              SmbTransportImpl transport = session.getTransport() ) {
            return transport.server;
        }
    }


    /**
     * 
     * Only call this method while holding a tree handle
     * 
     * @return the service we are connected to
     */
    public String getConnectedService () {
        try ( SmbTreeImpl t = getTree() ) {
            return t.getService();
        }
    }


    /**
     * 
     * Only call this method while holding a tree handle
     * 
     * @return the share we are connected to
     */
    public String getConnectedShare () {
        try ( SmbTreeImpl t = getTree() ) {
            return t.getShare();
        }
    }


    /**
     * 
     * Only call this method while holding a tree handle
     * 
     * @param other
     * @return whether the connection refers to the same tree
     */
    public boolean isSame ( SmbTreeConnection other ) {
        try ( SmbTreeImpl t1 = getTree();
              SmbTreeImpl t2 = other.getTree() ) {
            return t1.equals(t2);
        }
    }

}