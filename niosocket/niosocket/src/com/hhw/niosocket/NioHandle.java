package com.hhw.niosocket;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.IllegalBlockingModeException;
import java.nio.channels.IllegalSelectorException;
import java.nio.channels.NoConnectionPendingException;
import java.nio.channels.NotYetBoundException;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.concurrent.TimeoutException;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;

import com.hhw.niosocket.NioService.NioServiceWorker;

public class NioHandle {
    public static final int NIO_FAILED_TO_ADD4SELECT = -1;
    public static final int NIO_FAILED_TO_CONNECT = -2;
    public static final int NIO_FAILED_TO_LISTEN = -3;
    public static final int NIO_INCORRECT_SOCKET_TYPE = -4;
    public static final int NIO_FAILED_TO_SEND = -5;
    public static final int NIO_INCORRECT_SOCKET_STATE = -6;
    public static final int NIO_INVALID_REMOTE_ADDRESS = -7;
    public static final int NIO_INVALID_LOCAL_ADDRESS = -8;
    public static final int NIO_FAILED_TO_SET_OPTION = -9;
    public static final int NIO_INVALID_HANDLE_OPERATION = -10;
    public static final int NIO_FAILED_TO_KILL_TIMER = -11;
    public static final int NIO_FAILED_TO_GET_SERVICE = -12;
    public static final int NIO_FAILED_TO_ADD_TIMER = -13;
    public static final int NIO_FAILED_TO_ACCEPT = -14;
    public static final int NIO_RET_SOCKET_CLOSED = -15;
    public static final int NIO_RET_SELECTOR_CLOSED = -16;
    public static final int NIO_RET_SOCKET_IS_BLOCKING_MODE = -17;
    public static final int NIO_RET_WRONG_SELECTOR = -18;
    public static final int NIO_RET_SOCKET_KEY_IS_CANCELED = -19;
    public static final int NIO_RET_SOCKET_KEY_IS_INVALID = -20;
    public static final int NIO_RET_SOCKET_TYPE_IS_INVALID = -21;
    public static final int NIO_RET_FAIL_TO_FINISH_CONNECT = -22;
    public static final int NIO_RET_UNKNOWN_ERROR = -23;
    
    public enum NioHandleRole {SOCKET_CLIENT, SOCKET_SERVER, SOCKET_ROLE_NA}
    public enum NioHandleProtocol {SOCKET_TYPE_TCP, SOCKET_TYPE_UDP, SOCKET_TYPE_SCTP}
    public enum NioHandleState {
                            SOCKET_STATE_INIT,
                            SOCKET_STATE_LISTENING,
                            SOCKET_STATE_ACCEPTED,
                            SOCKET_STATE_CONNECTING,
                            SOCKET_STATE_CONNECTED,
                            SOCKET_STATE_CLOSED }

    public static final int NIO_HANDLE_TYPE_SOCKET = 0;
    public static final int NIO_HANDLE_TYPE_TIMER = 1;
    
    public static final int NIO_TIMER_TYPE_EXTERNAL = 0;
    public static final int NIO_TIMER_TYPE_INTERNAL_SEND = 1;
    public static final int NIO_TIMER_TYPE_INTERNAL_CONN = 2;
    
    public static final int NIO_NEW_CONNECTION_ACCEPTED = 0;
    public static final int NIO_NEW_CONNECTION_REJECTED = 1;
    
    //public static final int NIO_SOCKET_ERROR_NOT_YET_CONNECTED = -2;

    private static String   entry = "Entry: ";
    private static String   exit  = "Exit: ";


    class NioContext {
        NioHandle   m_handle;
        Object      m_attachment;
        
        public NioContext(NioHandle handle, Object attachment) {
            super();
            m_handle = handle;
            m_attachment = attachment;
        }
    }
    
    private Logger          m_logger        = null;
    private int             m_handleType    = 0;
    private int             m_timerType     = 0;
    private SocketAddress   m_localAddr     = null;
    private SocketAddress   m_remoteAddr    = null;
    private Selector        m_selector      = null;
    private AbstractSelectableChannel       m_socket        = null;
    private SelectionKey    m_selectKey     = null;

    private ArrayList<ByteBuffer> m_payloadList = null;
    private ArrayList<SocketAddress> m_remoteList = null;
    private Hashtable<NioHandle, SocketAddress> m_timerHandle2Addr = null;
    private long            m_sendTimeout   = Long.MAX_VALUE;
    private long            m_connTimeout   = Long.MAX_VALUE;
    Object                  m_attachment    = null;
    NioHandleState          m_state;
    
    protected long          m_duration      = 0;
    protected long          m_expireTime    = 0;
    
    // callbacks for read/write/new connection/close events
    protected NioEvHandlerClosed        m_closedCB      = null; // TCP
    protected NioEvHandlerConnected     m_connectSuccCB = null; // TCP
    protected NioEvHandlerConnectFail   m_connectFailCB = null; // TCP
    protected NioEvHandlerNewConnection m_newConnCB     = null; // TCP
    protected NioEvHandlerRecvData      m_recvDataCB    = null; // TCP
    protected NioEvHandlerRecvFrom      m_recvFromCB    = null; // UDP
    protected NioEvHandlerRecvErr       m_recvErrCB     = null; // TCP UDP
    protected NioEvHandlerSentDone      m_sentDoneCB    = null; // TCP
    protected NioEvHandlerSendToDone    m_sentToDoneCB  = null; // UDP
    protected NioEvHandlerSentFail      m_sentFailCB    = null; // TCP
    protected NioEVHandlerSentToFail    m_sentToFailCB  = null; // UDP
    protected NioEvHandlerTimeout       m_timeoutCB     = null; // Timer


    private void log(Level level, String msg) {
        if(m_logger == null) {
            return;
        }
        
        m_logger.log(level, msg);
    }
    
    NioHandle(Selector sel, NioHandleRole role, 
            NioHandleProtocol protocol, Object attachment, 
            Logger logger) throws IOException {
        m_logger = logger;
        m_handleType = NIO_HANDLE_TYPE_SOCKET;
        m_localAddr = new InetSocketAddress(0);
        m_selector = sel;
        m_attachment = attachment;
        m_payloadList = new ArrayList<ByteBuffer>();
        m_remoteList = new ArrayList<SocketAddress>();
        
        switch (protocol)
        {
        case SOCKET_TYPE_UDP:
            m_socket = DatagramChannel.open();
            ((DatagramChannel)m_socket).bind(m_localAddr);
            m_socket.configureBlocking(false);
            //m_selector.wakeup();
            //m_selectKey = ((DatagramChannel)m_socket).register(m_selector, SelectionKey.OP_READ, this);
            m_selectKey = NioService.getService().registerWithWakeup(
                    m_socket, m_selector, SelectionKey.OP_READ, this);
            m_state = NioHandleState.SOCKET_STATE_CONNECTED;
            m_timerHandle2Addr = new Hashtable<NioHandle, SocketAddress>();
            break;
        case SOCKET_TYPE_TCP:
            switch (role) {
            case SOCKET_CLIENT:
                m_socket = SocketChannel.open();
                break;
            case SOCKET_SERVER:
                m_socket = ServerSocketChannel.open();
                break;
            default:
                throw new IOException();
            }
            m_socket.configureBlocking(false);
            m_state = NioHandleState.SOCKET_STATE_INIT;
            break;
        case SOCKET_TYPE_SCTP:
        default:
            throw new IOException();
        } // end of switch protocol
    }

    NioHandle(Selector sel, NioHandleRole role, 
            NioHandleProtocol protocol, SocketAddress localAddr, 
            Object attachment, Logger logger) throws IOException {
        m_logger = logger;
        m_handleType = NIO_HANDLE_TYPE_SOCKET;
        m_localAddr = localAddr;
        m_selector = sel;
        m_attachment = attachment;
        m_payloadList = new ArrayList<ByteBuffer>();
        m_remoteList = new ArrayList<SocketAddress>();
        
        switch (protocol)
        {
        case SOCKET_TYPE_UDP:
            m_socket = DatagramChannel.open();
            ((DatagramChannel)m_socket).bind(m_localAddr);
            m_socket.configureBlocking(false);
            //m_selector.wakeup();
            //m_selectKey = ((DatagramChannel)m_socket).register(m_selector, SelectionKey.OP_READ, this);
            m_selectKey = NioService.getService().registerWithWakeup(
                    m_socket, m_selector, SelectionKey.OP_READ, this);
            m_state = NioHandleState.SOCKET_STATE_CONNECTED;
            m_timerHandle2Addr = new Hashtable<NioHandle, SocketAddress>();
            break;
        case SOCKET_TYPE_TCP:
            switch (role) {
            case SOCKET_CLIENT:
                m_socket = SocketChannel.open();
                break;
            case SOCKET_SERVER:
                m_socket = ServerSocketChannel.open();
                break;
            default:
                throw new IOException();
            }
            m_socket.configureBlocking(false);
            m_state = NioHandleState.SOCKET_STATE_INIT;
            break;
        case SOCKET_TYPE_SCTP:
        default:
            throw new IOException();
        } // end of switch protocol
    }
    
    NioHandle(Selector sel, NioHandleRole role, 
            NioHandleProtocol protocol, String localAddr, 
            int port, Object attachment, Logger logger) throws IOException {
        m_logger = logger;
        m_handleType = NIO_HANDLE_TYPE_SOCKET;
        m_localAddr = new InetSocketAddress(localAddr, port);
        m_selector = sel;
        m_attachment = attachment;
        m_payloadList = new ArrayList<ByteBuffer>();
        m_remoteList = new ArrayList<SocketAddress>();
        
        switch (protocol) {
        case SOCKET_TYPE_UDP:
            m_socket = DatagramChannel.open();
            ((DatagramChannel)m_socket).bind(m_localAddr);
            m_socket.configureBlocking(false);
            //m_selector.wakeup();
            //m_selectKey = ((DatagramChannel)m_socket).register(m_selector, SelectionKey.OP_READ, this);
            m_selectKey = NioService.getService().registerWithWakeup(
                    m_socket, m_selector, SelectionKey.OP_READ, this);
            m_state = NioHandleState.SOCKET_STATE_CONNECTED;
            m_timerHandle2Addr = new Hashtable<NioHandle, SocketAddress>();
            break;
        case SOCKET_TYPE_TCP:
            switch (role) {
            case SOCKET_CLIENT:
                m_socket = SocketChannel.open();
                break;
            case SOCKET_SERVER:
                m_socket = ServerSocketChannel.open();
                break;
            default:
                throw new IOException();
            }
            m_socket.configureBlocking(false);
            m_state = NioHandleState.SOCKET_STATE_INIT;
            break;
        case SOCKET_TYPE_SCTP:
        default:
            throw new IOException();
        } // end of switch protocol
    }
    
    NioHandle(NioHandle oldHandle, Selector sel, 
            AbstractSelectableChannel socket, SocketAddress remoteAddr, 
            Logger logger) throws IOException {
        m_logger = logger;
        m_handleType = NIO_HANDLE_TYPE_SOCKET;
        m_localAddr = oldHandle.m_localAddr;
        m_remoteAddr = remoteAddr;
        m_selector = sel;
        m_attachment = oldHandle.m_attachment;
        m_payloadList = new ArrayList<ByteBuffer>();
        m_remoteList = new ArrayList<SocketAddress>();
        
        m_socket = socket;
        m_socket.configureBlocking(false);
        m_state = NioHandleState.SOCKET_STATE_ACCEPTED;
        
        m_closedCB = oldHandle.m_closedCB;
        m_connectSuccCB = oldHandle.m_connectSuccCB;
        m_connectFailCB = oldHandle.m_connectFailCB;
        m_newConnCB = oldHandle.m_newConnCB;
        m_recvDataCB = oldHandle.m_recvDataCB;
        m_recvFromCB = oldHandle.m_recvFromCB;
        m_recvErrCB = oldHandle.m_recvErrCB;
        m_sentDoneCB = oldHandle.m_sentDoneCB;
        m_sentFailCB = oldHandle.m_sentFailCB;
        m_timeoutCB = oldHandle.m_timeoutCB;
    }
    
    NioHandle(Selector sel, long duration, long expireTime, Object attachment, Logger logger) {
        m_logger = logger;
        m_handleType = NIO_HANDLE_TYPE_TIMER;
        m_selector = sel;
        m_expireTime = expireTime;
        m_attachment = attachment;
    }
    
    int nioSetTimer(long duration, Object attachment) {
        if((m_handleType == NIO_HANDLE_TYPE_SOCKET) && 
           (m_timerType != NIO_TIMER_TYPE_INTERNAL_SEND) &&
           (m_timerType != NIO_TIMER_TYPE_INTERNAL_CONN)) {
            log(Level.ERROR, "Invalid handle type when set timer");
            return NIO_INVALID_HANDLE_OPERATION;
         }
        
        long now = System.currentTimeMillis();
        long expired = now + duration;
        m_duration = duration;
        m_expireTime = expired;
        
        m_attachment = attachment;
        NioService.getService().addTimer(this);
        m_selector.wakeup();
    
        return 0;
    }

    int nioKillTimer() {
        if((m_handleType == NIO_HANDLE_TYPE_SOCKET) && 
           (m_timerType != NIO_TIMER_TYPE_INTERNAL_SEND) &&
           (m_timerType != NIO_TIMER_TYPE_INTERNAL_CONN)) {
            return NIO_INVALID_HANDLE_OPERATION;
        }
        
        return NioService.getService().nioKillTimer(this);
    }

    private int nioSetInternalTimer(long duration, int timerType, Object attachment) {
        m_timerType = timerType;
        return nioSetTimer(duration, attachment);
    }

    private NioHandle nioSetInternalTimerNew(long duration, int timerType, SocketAddress remoteAddr) {
        long now = System.currentTimeMillis();
        long expired = now + duration;
        m_timerType = timerType;
        NioHandle handle = new NioHandle(m_selector, duration, expired, remoteAddr, m_logger);
        NioService.getService().addTimer(handle);
        
        return handle;
    }
    
    private int nioKillInternalTimer() {
        return nioKillTimer();
    }

    private int registerKey(int keys, Object att, Exception exp) {
        try {
            //m_selector.wakeup();
            //m_selectKey = m_socket.register(m_selector, keys, att);
            m_selectKey = NioService.getService().registerWithWakeup(
                    m_socket, m_selector, keys, att);
        }
        catch (ClosedChannelException e) {
            e.printStackTrace();
            return NIO_RET_SOCKET_CLOSED;
        }
        catch (IllegalBlockingModeException  e) {
            e.printStackTrace();
            return NIO_RET_SOCKET_IS_BLOCKING_MODE;
        }
        catch (IllegalSelectorException  e) {
            e.printStackTrace();
            return NIO_RET_WRONG_SELECTOR;
        }
        catch (CancelledKeyException  e) {
            e.printStackTrace();
            return NIO_RET_SOCKET_KEY_IS_CANCELED;
        }
        catch (IllegalArgumentException e) {
            e.printStackTrace();
            return NIO_RET_SOCKET_KEY_IS_INVALID;
        }
        catch (ClosedSelectorException  e) {
            e.printStackTrace();
            return NIO_RET_SELECTOR_CLOSED;
        } 
        catch (IOException e) {
            e.printStackTrace();
            return NIO_RET_UNKNOWN_ERROR;
        }
    
        return 0;
    }

    protected int handleInternalSendTimeout() {
        if (!(m_socket instanceof SocketChannel)) {
            if(m_sentFailCB != null) {
                TimeoutException e = new TimeoutException("Failed to send data to remote side due to timeout");
                m_sentFailCB.NotifySentFail(this, e, m_attachment);
            }
        } else if(!(m_socket instanceof DatagramChannel)) {
            //SocketAddress ra = (SocketAddress) m_attachment;
            SocketAddress ra = m_remoteList.remove(0);
            DatagramChannel sock = (DatagramChannel) m_socket;
            if(m_sentToFailCB != null) {
                TimeoutException e = new TimeoutException("Failed to send data to remote side due to timeout");
                m_sentToFailCB.NotifySentToFail(this, e, ra, m_attachment);
            }
        } 
        m_selectKey.cancel();
        return 0;
    }

    protected int handleInternalConnTimeout() {
        if(m_connectFailCB != null) {
            TimeoutException e = new TimeoutException("Failed to connnect to remote side due to timeout");
            m_connectFailCB.NotifyConnectFail(this, e, m_attachment);
        }
        m_selectKey.cancel();
        return 0;
    }

    protected int handleExternalTimeout() {
        if (m_timeoutCB != null) 
        {
            m_timeoutCB.NotifyTimeout(m_attachment);
        }
        return 0;
    }

    protected NioHandle handleAcceptable() {
            ServerSocketChannel listenSock = (ServerSocketChannel) m_socket;
            SocketChannel acceptedSock = null;
            NioHandle acceptedHandle = null;
            try {
                acceptedSock = listenSock.accept();
                acceptedSock.configureBlocking(false);
            }
            catch (ClosedChannelException e) {
                if(m_closedCB != null) {
                    m_closedCB.NotifyClosed(this, m_attachment);
                }
    
                if(acceptedSock != null) {
                    try {
                        acceptedSock.close();
                    } catch (IOException e1) {
                        e1.printStackTrace();
                    }
                }
                nioClose();
            }
            catch (NotYetBoundException e) {
                if(m_recvErrCB != null) {
                    m_recvErrCB.NotifyRecvErr(this, e, m_attachment);
                }
                if(acceptedSock != null) {
                    try {
                        acceptedSock.close();
                    } catch (IOException e1) {
                        e1.printStackTrace();
                    }
                }
            }
            catch (SecurityException e) {
                if(m_recvErrCB != null) {
                    m_recvErrCB.NotifyRecvErr(this, e, m_attachment);
                }
                if(acceptedSock != null) {
                    try {
                        acceptedSock.close();
                    } catch (IOException e1) {
                        e1.printStackTrace();
                    }
                }
            }
            catch (IOException e) {
                e.printStackTrace();
                if(acceptedSock != null) {
                    try {
                        acceptedSock.close();
                    } catch (IOException e1) {
                        e1.printStackTrace();
                    }
                }
                return null;
            }
        
            
            try {
    //            acceptedHandle = new NioHandle(m_selector, acceptedSock, SOCKET_CLIENT, SOCKET_TYPE_TCP, 
    //                    m_attachment, m_logger);
                acceptedHandle = new NioHandle(this, m_selector, acceptedSock, 
                        acceptedSock.getRemoteAddress(), m_logger);
            } catch (IOException e) {
                e.printStackTrace();
                if(acceptedSock != null) {
                    try {
                        acceptedSock.close();
                    } catch (IOException e1) {
                        e1.printStackTrace();
                    }
                }
                return null;
            }
            
            int accepted = NIO_NEW_CONNECTION_ACCEPTED;
            if(this.m_newConnCB != null) {
                accepted = m_newConnCB.NotifyNewConnection(this, acceptedHandle, m_attachment);
                
                if(accepted != NIO_NEW_CONNECTION_ACCEPTED) {
                    acceptedHandle.nioClose();
                } else {
                    acceptedHandle.m_state = NioHandleState.SOCKET_STATE_CONNECTED;
                }
            }
            try {
    //            m_selector.wakeup();
    //            acceptedHandle.m_selectKey = 
    //                    acceptedHandle.m_socket.register(m_selector, 
    //                            SelectionKey.OP_READ, 
    //                            acceptedHandle);
                acceptedHandle.m_selectKey = NioService.getService().register(
                        acceptedHandle.m_socket, m_selector, 
                        SelectionKey.OP_READ, acceptedHandle);
            } catch (IOException e) {
                e.printStackTrace();
                acceptedHandle.nioClose();
                return null;
            }
            log(Level.INFO, "New socket " + acceptedHandle.m_socket.toString() + " accepted on listening socket " + m_socket.toString());
            return acceptedHandle;
        }

    protected int handleWritable() {
            int bytesSent = 0;
            
            // should change buffer mode read again? m_unsentData.flip();
            ByteBuffer buf = m_payloadList.get(0);
            if(!buf.hasRemaining()) {
                return 0;
            }
            int remainingBytes = buf.remaining();
            SocketAddress ra = null;
            
            try {
                if (!(m_socket instanceof SocketChannel)) {
                    bytesSent = ((SocketChannel)m_socket).write(buf);
                } else if(!(m_socket instanceof DatagramChannel)) {
                    ra = m_remoteList.get(0);
                    bytesSent = ((DatagramChannel)m_socket).send(buf, ra);
                } else {
                    if(m_sentFailCB != null) {
                        IOException e = new IOException();
                        m_sentFailCB.NotifySentFail(this, e, m_attachment);
                    }
                    return NIO_RET_SOCKET_TYPE_IS_INVALID;
                }
            }
            catch (NotYetConnectedException e) {
                if(m_sentFailCB != null) {
                    m_sentFailCB.NotifySentFail(this, e, m_attachment);
                }
                return NIO_FAILED_TO_SEND;
            }
            catch (ClosedChannelException e) {
                if(m_closedCB != null) {
                    m_closedCB.NotifyClosed(this, m_attachment);
                }
                nioClose();
                return NIO_FAILED_TO_SEND;
            }
            catch (IOException e) {
                return NIO_FAILED_TO_SEND;
            }
            
            
            // since this is non blocking socket, need to
            // register the socket for keep sending.
            if (bytesSent < remainingBytes) {
                try {
                    m_selectKey = NioService.getService().register(
                            m_socket, m_selector, 
                            SelectionKey.OP_WRITE|m_selectKey.interestOps(), this);
                }
                catch (IOException e) {
                    nioKillInternalTimer();
                    if (!(m_socket instanceof SocketChannel)) {
                        if(m_sentFailCB != null) {
                            m_sentFailCB.NotifySentFail(this, e, m_attachment);
                        }
                    } else if(!(m_socket instanceof DatagramChannel)) {
                        if(m_sentFailCB != null) {
                            m_sentFailCB.NotifySentFail(this, e, m_attachment);
                        }
                    }
                    
                    m_selectKey.cancel();
                    return NIO_FAILED_TO_ADD4SELECT;
                }
            } else {
                nioKillInternalTimer();
                m_payloadList.remove(0);
                
                if (!(m_socket instanceof SocketChannel)) {
                    if(m_sentDoneCB != null) {
                        m_sentDoneCB.NotifySendDone(this, bytesSent, m_attachment);
                    }
                } else if(!(m_socket instanceof DatagramChannel)) {
                    ra = m_remoteList.remove(0);
                    if(m_sentToDoneCB != null) {
                        m_sentToDoneCB.NotifySendToDone(this, bytesSent, ra, m_attachment);
                    }
                }
                
                
                // re-register key to OP_READ
                try {
                    m_selectKey = NioService.getService().register(
                            m_socket, m_selector, 
                            SelectionKey.OP_READ, this);
                } catch (IOException e) {
                    nioKillInternalTimer();
                    if(m_sentFailCB != null) {
                        m_sentFailCB.NotifySentFail(this, e, m_attachment);
                    }
                    m_selectKey.cancel();
                    return NIO_FAILED_TO_ADD4SELECT;
                }
            }
            return bytesSent;
        }

    protected int handleConnectable() {
        SocketChannel connectSock = (SocketChannel) m_socket;
        boolean connectSucc = false;
        
        Exception exp = null;
        try {
            connectSucc = connectSock.finishConnect();
        }
        catch (NoConnectionPendingException e) {
            exp = e;
        }
        catch (ClosedChannelException e) {
            exp = e;
        }
        catch (ConnectException e) {
            exp = e;
            log(Level.FATAL, "Connection is refused by remote side");
        }
        catch (IOException e) {
            e.printStackTrace();
            exp = e;
        }
        
        if (connectSucc) {
            try {
                m_selectKey = NioService.getService().register(
                        m_socket, m_selector, 
                        SelectionKey.OP_READ, this);
            } catch (IOException e) {
                log(Level.FATAL, "Failed to register sock on selector");
                e.printStackTrace();
                connectSucc = false;
            }
            if(m_connectSuccCB != null) {
                m_state = NioHandleState.SOCKET_STATE_CONNECTED;
                m_connectSuccCB.NotifyConnected(this, m_attachment);
                return 0;
            }
        } else {
            if(m_connectFailCB != null) {
                m_connectFailCB.NotifyConnectFail(this, exp, m_attachment);
                return NIO_RET_FAIL_TO_FINISH_CONNECT;
            }
        }
    
        return 0;
    }

    /////////////////////////////////////////////////////////////////
    //
    /////////////////////////////////////////////////////////////////
    public SocketAddress getRemoteAddr() {
        return m_remoteAddr;
    }

    /////////////////////////////////////////////////////////////////
    // 
    /////////////////////////////////////////////////////////////////
    public AbstractSelectableChannel getSocket() {
        return m_socket;
    }
    
    /////////////////////////////////////////////////////////////////
    // 
    /////////////////////////////////////////////////////////////////
    public NioHandleState getSocketState() {
        return m_state;
    }

    /////////////////////////////////////////////////////////////////
    // 
    /////////////////////////////////////////////////////////////////
    public Object getAttachment() {
        return m_attachment;
    }

    /////////////////////////////////////////////////////////////////
    // 
    /////////////////////////////////////////////////////////////////
    public long getExpireTime() {
        return m_expireTime;
    }

    /////////////////////////////////////////////////////////////////
    // 
    /////////////////////////////////////////////////////////////////
    protected int getTimerType() {
        return m_timerType;
    }

    /////////////////////////////////////////////////////////////////
    // 
    /////////////////////////////////////////////////////////////////
    public void setRemoteAddr(SocketAddress addr) {
        this.m_remoteAddr = addr;
    }

    /////////////////////////////////////////////////////////////////
    // 
    /////////////////////////////////////////////////////////////////
    public void setRemoteAddr(String ip, int port) {
        m_remoteAddr = new InetSocketAddress(ip, port);
    }

    /////////////////////////////////////////////////////////////////
    // 
    /////////////////////////////////////////////////////////////////
    public void registerClosedCB(NioEvHandlerClosed cb) {
        m_closedCB = cb;
    }
    
    /////////////////////////////////////////////////////////////////
    // 
    /////////////////////////////////////////////////////////////////
    public void registerConnectedCB(NioEvHandlerConnected cb) {
        m_connectSuccCB = cb;
    }
    
    /////////////////////////////////////////////////////////////////
    // 
    /////////////////////////////////////////////////////////////////
    public void registerConnectFailCB(NioEvHandlerConnectFail cb) {
        m_connectFailCB = cb;
    }
    
    /////////////////////////////////////////////////////////////////
    // 
    /////////////////////////////////////////////////////////////////
    public void registerNewConnectionCB(NioEvHandlerNewConnection cb) {
        m_newConnCB = cb;
    }
    
    /////////////////////////////////////////////////////////////////
    // 
    /////////////////////////////////////////////////////////////////
    public void registerRecvDataCB(NioEvHandlerRecvData cb) {
        m_recvDataCB = cb;
    }
    
    /////////////////////////////////////////////////////////////////
    // 
    /////////////////////////////////////////////////////////////////
    public void registerRecvFromCB(NioEvHandlerRecvFrom cb) {
        m_recvFromCB = cb;
    }
    
    /////////////////////////////////////////////////////////////////
    // 
    /////////////////////////////////////////////////////////////////
    public void registerRecvErrCB(NioEvHandlerRecvErr cb) {
        m_recvErrCB = cb;
    }
    
    /////////////////////////////////////////////////////////////////
    // 
    /////////////////////////////////////////////////////////////////
    public void registerSentDoneCB(NioEvHandlerSentDone cb) {
        m_sentDoneCB = cb;
    }
    
    /////////////////////////////////////////////////////////////////
    // 
    /////////////////////////////////////////////////////////////////
    public void registerTimeoutCB(NioEvHandlerTimeout cb) {
        m_timeoutCB = cb;
    }
    
    /////////////////////////////////////////////////////////////////
    // 
    /////////////////////////////////////////////////////////////////
    public void registerSentFailCB(NioEvHandlerSentFail cb) {
        m_sentFailCB = cb;
    }
    
    /////////////////////////////////////////////////////////////////
    // 
    /////////////////////////////////////////////////////////////////
    public int nioClose() {
        if(m_handleType != NIO_HANDLE_TYPE_SOCKET) {
            return NIO_INVALID_HANDLE_OPERATION;
        }
        //m_selectKey.cancel();
        try {
            m_socket.close();
        }
        catch (IOException e) {
            // do nothing.
        }
        m_socket = null;
        m_state = NioHandleState.SOCKET_STATE_CLOSED;
        return 0;
    }

    /////////////////////////////////////////////////////////////////
    // TCP only
    /////////////////////////////////////////////////////////////////
    public int nioConnect(Object attachment) {
        if(m_handleType != NIO_HANDLE_TYPE_SOCKET) {
            return NIO_INVALID_HANDLE_OPERATION;
        }
        if((m_state == NioHandleState.SOCKET_STATE_LISTENING) ||
           (m_state == NioHandleState.SOCKET_STATE_CONNECTING) ||
           (m_state == NioHandleState.SOCKET_STATE_CONNECTED)) {
            return NIO_INCORRECT_SOCKET_STATE;
        }
        
        if (!(m_socket instanceof SocketChannel)) {
            return NIO_INCORRECT_SOCKET_TYPE;
        }
        
        if (m_remoteAddr == null) {
            return NIO_INVALID_REMOTE_ADDRESS;
        }

        boolean succ = false;
        try {
            ((SocketChannel) m_socket).bind(m_localAddr);
            succ = ((SocketChannel) m_socket).connect(m_remoteAddr);
        }
        catch (IOException e) {
            return NIO_FAILED_TO_CONNECT;
        }
        
        if(succ) {
            if (m_connectSuccCB != null) {
                m_connectSuccCB.NotifyConnected(this, m_attachment);
            }
            m_state = NioHandleState.SOCKET_STATE_CONNECTED;
            return 0;
        }
        
        // TODO: should setup a timer for connecting operation.
        nioSetInternalTimer(m_connTimeout, NIO_TIMER_TYPE_INTERNAL_CONN, this);
        
        try {
            //m_selector.wakeup();
            //m_selectKey = m_socket.register(m_selector, SelectionKey.OP_CONNECT, this);
            m_selectKey = NioService.getService().registerWithWakeup(
                    m_socket, m_selector, SelectionKey.OP_CONNECT, this);
        }
        catch (IOException e) {
            return NIO_FAILED_TO_ADD4SELECT;
        }
        m_state = NioHandleState.SOCKET_STATE_CONNECTING;
        return 0;
    }
    
    // TCP only    
    public int nioConnect(SocketAddress remoteAddr, Object attachment) {
        m_remoteAddr = remoteAddr;
        return nioConnect(attachment);
    }

    // TCP only
    public int nioConnect(String remoteAddr, int remotePort, Object attachment) {
        m_remoteAddr = new InetSocketAddress(remoteAddr, remotePort);
        return nioConnect(m_remoteAddr, attachment);
    }
    
    // TCP only
    public int nioListen(Object attachment) {
        String func = "nioListen(Object attachment): ";
        log(Level.DEBUG, entry + func);
        
        if(m_handleType != NIO_HANDLE_TYPE_SOCKET) {
            log(Level.WARN, exit + func + "Invalid handle type " + m_handleType);
            return NIO_INVALID_HANDLE_OPERATION;
        }
        if((m_state == NioHandleState.SOCKET_STATE_LISTENING) ||
           (m_state == NioHandleState.SOCKET_STATE_CONNECTING) ||
           (m_state == NioHandleState.SOCKET_STATE_CONNECTED)) {
            log(Level.WARN, exit + func + "Invalid socket state " + m_state);
            return NIO_INCORRECT_SOCKET_STATE;
        }
        
        if (!(m_socket instanceof ServerSocketChannel)) {
            log(Level.WARN, exit + func + "Invalid SocketChannel type " + m_socket.toString());
            return NIO_INCORRECT_SOCKET_TYPE;
        }
        
        if (m_localAddr == null) {
            log(Level.WARN, exit + func + "Invalid address null");
            return NIO_INVALID_LOCAL_ADDRESS;
        }
        
        try {
            ((ServerSocketChannel)m_socket).bind(m_localAddr);
        }
        catch (IOException e) {
            log(Level.WARN, exit + func + "failed to bind on " + m_localAddr.toString());
            return NIO_FAILED_TO_LISTEN;
        }
        
        try {
            m_attachment = attachment;
            //m_selector.wakeup();
            //m_selectKey = m_socket.register(m_selector, SelectionKey.OP_ACCEPT, this);
            m_selectKey = NioService.getService().registerWithWakeup(
                    m_socket, m_selector, SelectionKey.OP_ACCEPT, this);
        }
        catch (IOException e) {
            log(Level.WARN, exit + func + "failed to add into selector");
            return NIO_FAILED_TO_ADD4SELECT;
        }

        m_state = NioHandleState.SOCKET_STATE_LISTENING;
        log(Level.INFO, exit + func + "socket " + m_socket.toString() +" is listening");
        return 0;
    }
    
    // TCP only
    public int nioListen(SocketAddress localAddr, Object attachment) {
        m_localAddr = localAddr;
        return nioListen(attachment);

    }
    
    // TCP only
    public int nioListen(String localAddr, int localPort, Object attachment) {
        m_localAddr = new InetSocketAddress(localAddr, localPort);
        return nioListen(attachment);
    }
    
    // TCP only
    public int nioSend(byte[] payload) {
        if(m_handleType != NIO_HANDLE_TYPE_SOCKET) {
            return NIO_INVALID_HANDLE_OPERATION;
        }
        if(m_state != NioHandleState.SOCKET_STATE_CONNECTED)
        {
            return NIO_INCORRECT_SOCKET_STATE;
        }
        
        int bytesSent = 0;
        
        ByteBuffer buf = ByteBuffer.allocate(payload.length);
        buf.clear();
        buf.put(payload);
        buf.flip();
        
        if (!(m_socket instanceof SocketChannel)) {
            return NIO_INCORRECT_SOCKET_TYPE;
        }
        
        try {
            bytesSent = ((SocketChannel)m_socket).write(buf);
        }
        catch (NotYetConnectedException e) {
            if(m_recvErrCB != null) {
                m_recvErrCB.NotifyRecvErr(this, e, m_attachment);
            }
            return NIO_FAILED_TO_SEND;
        }
        catch (ClosedChannelException e) {
            if(m_closedCB != null) {
                m_closedCB.NotifyClosed(this, m_attachment);
            }
            nioClose();
            return NIO_FAILED_TO_SEND;
        }
        catch (IOException e) {
            return NIO_FAILED_TO_SEND;
        }
        
        
        // since this is non blocking socket, need to
        // register the socket for keep sending.
        if (bytesSent < payload.length) {
            //            try {
//                m_selectKey = ((SocketChannel)m_socket).register(m_selector, 
//                        SelectionKey.OP_WRITE|m_selectKey.interestOps(), 
//                        this);
//            }
//            catch (IOException e) {
//                return NIO_FAILED_TO_ADD4SELECT;
//            }
            Exception exp = null;
            int ret = registerKey(SelectionKey.OP_WRITE|m_selectKey.interestOps(), this, exp);
            if(ret != 0) {
                return ret;
            }
            
            // add current payload to pending list;
            m_payloadList.add(buf);
            
            // Set a timer for the data sent done.
            nioSetInternalTimer(m_sendTimeout, NIO_TIMER_TYPE_INTERNAL_SEND, this);
        } else {
            if(m_sentDoneCB != null) {
                m_sentDoneCB.NotifySendDone(this, bytesSent, m_attachment);
            }
        }
        return bytesSent;
    }

    /////////////////////////////////////////////////////////////////
    // UDP only
    /////////////////////////////////////////////////////////////////
    public int nioSendTo(byte[] payload, SocketAddress remoteAddr) {
        if(m_handleType != NIO_HANDLE_TYPE_SOCKET) {
            return NIO_INVALID_HANDLE_OPERATION;
        }
        if (!(m_socket instanceof DatagramChannel)) {
            return NIO_INCORRECT_SOCKET_TYPE;
        }
        
        if(remoteAddr == null) {
            return NIO_INVALID_REMOTE_ADDRESS;
        }
        
        ByteBuffer buf = ByteBuffer.allocate(payload.length);
        buf.clear();
        buf.put(payload);
        buf.flip();
        
        int bytesSent = 0; 
        try {
            bytesSent = ((DatagramChannel)m_socket).send(buf, remoteAddr);
        }
        catch (ClosedChannelException e) {
            return NIO_FAILED_TO_SEND;
        }
        catch (IOException e) {
            return NIO_FAILED_TO_SEND;
        }

        // since this is non blocking socket, need to
        // register the socket for keep sending.
        if (bytesSent < payload.length) {
            Exception exp = null;
            int ret = registerKey(SelectionKey.OP_WRITE|m_selectKey.interestOps(), this, exp);
            if(ret != 0) {
                return ret;
            }
            
            // add current payload to pending list;
            m_payloadList.add(buf);
            m_remoteList.add(remoteAddr);
            // TODO: bind timer and remote address together. 
            //m_timerHandle2Addr[] = 
            
            // Set a timer for the data sent done.
            nioSetInternalTimer(m_sendTimeout, NIO_TIMER_TYPE_INTERNAL_SEND, this);
        } else {
            if(m_sentToDoneCB != null) {
                m_sentToDoneCB.NotifySendToDone(this, bytesSent, remoteAddr, m_attachment);
            }
        }

        return bytesSent;
    }
    
    // UDP only
    public int nioSendTo(byte[] payload, String remoteAddr, int port) {
        SocketAddress addr = new InetSocketAddress(remoteAddr, port);
        return nioSendTo(payload, addr);
    }
    
    /////////////////////////////////////////////////////////////////
    // 
    /////////////////////////////////////////////////////////////////
    public <T> int nioSetOption(SocketOption<T> name, T value) {
        try {
            if (!(m_socket instanceof SocketChannel)) {
                ((SocketChannel)m_socket).setOption(name, value);
            } else if (!(m_socket instanceof ServerSocketChannel)) {
                ((ServerSocketChannel)m_socket).setOption(name, value);
            } else if (!(m_socket instanceof DatagramChannel)) {
                ((DatagramChannel)m_socket).setOption(name, value);
            }
        } catch (IOException e) {
            return NIO_FAILED_TO_SET_OPTION;
        }
    
        return 0;
    }

    /////////////////////////////////////////////////////////////////
    // 
    /////////////////////////////////////////////////////////////////
    public int nioSetSendTimeout(long duration) {
        m_sendTimeout = duration;
        return 0;
    }

    /////////////////////////////////////////////////////////////////
    // 
    /////////////////////////////////////////////////////////////////
    public int nioSetConnectTimeout(long duration) {
        m_connTimeout = duration;
        return 0;
    }
}
