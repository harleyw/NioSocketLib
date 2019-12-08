package com.hhw.niosocket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;

import com.hhw.niosocket.NioHandle.NioContext;
import com.hhw.niosocket.NioHandle.NioHandleProtocol;
import com.hhw.niosocket.NioHandle.NioHandleRole;

public class NioService {

    private static NioService gRervice = null;
    private static int m_workerNum = 1;
    
    private static final int    NIO_SERVICE_WAIT_FOREVER = 0;
    private static final long   NIO_SERVICE_FUTURE = 0;
    
    private static final int    NIO_BUFFER_CAPACTIY = 128;
    
    enum NioServiceState {
        SERVICE_STATE_INIT, 
        SERVICE_STATE_STARTING, 
        SERVICE_STATE_STARTED, 
        SERVICE_STATE_STOPPED,
        SERVICE_STATE_DEGRADED }
    
    private ArrayList<NioServiceWorker> m_workers;
    private NioServiceState               m_state;
    private ReentrantReadWriteLock      m_timerLock;
    private ReentrantLock              m_selectLock;
    private ArrayList<NioHandle>        m_sortedTimers;
    private Logger                      m_logger = null;

    private void log(Level level, String msg) {
        if(m_logger == null) {
            return;
        }
        
        m_logger.log(level, msg);
    }
    
    protected int addTimer(NioHandle timerHandle) {
        log(Level.DEBUG, "timer lock write lock");
        m_timerLock.writeLock().lock();
        boolean isExist = m_sortedTimers.contains(timerHandle);
        if(!isExist) {
            m_sortedTimers.add(timerHandle);
            Collections.sort(m_sortedTimers, new Comparator<NioHandle>() {

                @Override
                public int compare(NioHandle o1, NioHandle o2) {
                    if(o1.m_expireTime > o2.m_expireTime)
                        return 1;
                    else if(o1.m_expireTime < o2.m_expireTime)
                        return -1;
                    return 0;
                }});
        }
        log(Level.DEBUG, "timer lock write unlock");
        m_timerLock.writeLock().unlock();
        return 0;
    }
    
    protected boolean removeTimer(NioHandle timerHandle) {
        log(Level.DEBUG, "timer lock write lock");
        m_timerLock.writeLock().lock();
        boolean ret = m_sortedTimers.remove(timerHandle);
        log(Level.DEBUG, "timer lock write unlock");
        m_timerLock.writeLock().unlock();
        return ret;
    }
    
    protected void addTimerClean(NioHandle timerHandle) {
        log(Level.DEBUG, "timer lock write lock");
        m_timerLock.writeLock().lock();
        m_sortedTimers.remove(timerHandle);
        m_sortedTimers.add(timerHandle);
        log(Level.DEBUG, "timer lock write unlock");
        m_timerLock.writeLock().unlock();
        
        return;
    }
    
    protected ArrayList<NioHandle> removeExpiredTimers(long expiredTime) {
        ArrayList<NioHandle> set = new ArrayList<NioHandle>();
        log(Level.DEBUG, "timer lock write lock");
        m_timerLock.writeLock().lock();
        m_sortedTimers.forEach(new Consumer<NioHandle>() {

            @Override
            public void accept(NioHandle arg0) {
                if(expiredTime >= arg0.m_expireTime) {
                    set.add(arg0);
                }
                
            }});
        m_sortedTimers.removeAll(set);
        log(Level.DEBUG, "timer lock write unlock");
        m_timerLock.writeLock().unlock();
        return set;
    }
    class NioServiceWorker extends Thread {
        
        private Selector m_selector = null;
        private boolean m_shutdown = false;
        boolean m_started = false;
        
        public NioServiceWorker(String name) {
            super(name);
        }

        @Override
        public void run() {
            int keys = 0;
            m_started = true;
            log(Level.DEBUG, "service worker " + this.toString() + "started");
            log(Level.DEBUG, "service worker " + this.toString() + " m_shutdown = " + (m_shutdown?"true":"false"));
            
            log(Level.DEBUG, "Locking in worker at the 1st time");
            m_selectLock.lock();
            while(!m_shutdown) {
                long timeout = getLatestTimeout();
                long nextTimeout = System.currentTimeMillis() + timeout;
                
                log(Level.DEBUG, "Unlocking before step into select");
                m_selectLock.unlock();
                try {
                    log(Level.DEBUG, "Worker "+ this.toString() + " step into select(" + timeout + ")");
                    //*****************************
                    keys = m_selector.select(timeout);
                    //*****************************
                    log(Level.DEBUG, "Worker "+ this.toString() + " step out of select(" + timeout + ")");
                } catch (IOException e) {
                    e.printStackTrace();
                    m_shutdown = true;
                    break;
                } finally {
                    m_selectLock.lock();
                    log(Level.DEBUG, "Locked after step out select");
                }
                
                // Handling the expired timers
                if((timeout != NIO_SERVICE_WAIT_FOREVER) &&
                   (System.currentTimeMillis() >= nextTimeout)) {
                    ArrayList<NioHandle> expiredTimers = removeExpiredTimers(nextTimeout);
                    Iterator<NioHandle> it = expiredTimers.iterator();
                    while(it.hasNext()) {
                        NioHandle timerHandle = it.next();
                        switch (timerHandle.getTimerType()) {
                        case NioHandle.NIO_TIMER_TYPE_EXTERNAL:
                            log(Level.DEBUG, "Worker "+ this.toString() + " handle external timer");
                            timerHandle.handleExternalTimeout();
                            break;
                        case NioHandle.NIO_TIMER_TYPE_INTERNAL_SEND:
                            log(Level.DEBUG, "Worker "+ this.toString() + " handle internal sending timer");
                            timerHandle.handleInternalSendTimeout();
                            break;
                        case NioHandle.NIO_TIMER_TYPE_INTERNAL_CONN:
                            log(Level.DEBUG, "Worker "+ this.toString() + " handle internal connecting timer");
                            timerHandle.handleInternalConnTimeout();
                            break;
                        }
                    }
                }
                
                if(keys == 0) {
                    continue;
                }
                
                Set<SelectionKey> selectedKeys = m_selector.selectedKeys();
                Iterator<SelectionKey> keyIterator = selectedKeys.iterator();
                while(keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    NioHandle handle = (NioHandle) key.attachment();
                    Object attachment = handle.m_attachment;
                    
                    if(key.isValid() && key.isAcceptable()) {
                        // CASE: recved a new connection
                        log(Level.INFO, "Worker "+ this.toString() + " handle recving a new connection on socket " + handle.getSocket().toString());
                        handle.handleAcceptable();
                    } else if(key.isValid() && key.isConnectable()) {
                        // CASE: connected to remote side successfully
                        log(Level.INFO, "Worker "+ this.toString() + " connected to remote side");
                        handle.handleConnectable();
                    }
                    
                    if(key.isValid() && key.isWritable()) {
                        // CASE: Ready to write data out
                        log(Level.INFO, "Worker "+ this.toString() + " ready to send data to remote side");
                        handle.handleWritable();
                    }
                    
                    if(key.isValid() && key.isReadable()) {
                        // CASE: data or error recved
                        SelectableChannel sock = key.channel();
                        byte[] segment = null;
                        ByteBuffer data = ByteBuffer.allocate(NIO_BUFFER_CAPACTIY);
                        ByteArrayOutputStream combinedBuf = new ByteArrayOutputStream();
                        boolean closed = false;
                        
                        if(sock instanceof SocketChannel) {
                            // TCP
                            log(Level.INFO, "Worker "+ this.toString() + " recved from remote side - TCP");
                            
                            SocketChannel ts = (SocketChannel) sock;
                            int readin = -1;
                            do {
                                data.clear(); //make buffer ready for writing
                                
                                try {
                                    readin = ts.read(data);
                                }
                                catch (NotYetConnectedException e) {
                                    log(Level.ERROR, "Get failure on read: " + e.getMessage());
                                    if(handle.m_recvErrCB != null) {
                                        handle.m_recvErrCB.NotifyRecvErr(handle, e, handle.getAttachment());
                                    }
                                    break;
                                }
                                catch (ClosedChannelException e) {
                                    closed = true;
                                    log(Level.ERROR, "Get failure on read: " + e.getMessage());
                                    break;
                                }
                                catch (IOException e) {
                                    e.printStackTrace();
                                    log(Level.ERROR, "Get failure on read: " + e.getMessage()); 
                                    if(handle.m_recvErrCB != null) {
                                        handle.m_recvErrCB.NotifyRecvErr(handle, e, handle.getAttachment());
                                    } else {
                                        log(Level.WARN, "No callback for handling recv error from remote site");
                                    }
                                    break;
                                }
                                
                                data.flip();  //make buffer ready for read

                                segment = new byte[data.remaining()];
                                data.get(segment);
                                try {
                                    combinedBuf.write(segment);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                    break;
                                }
                            } while(readin != 0 && readin != -1); // end of while read
                            
                            // if has data should call notify
                            if(combinedBuf.toByteArray().length != 0) {
                                if(handle.m_recvDataCB != null) {
                                    handle.m_recvDataCB.NotifyRecvData(handle, combinedBuf.toByteArray(), attachment);
                                } else {
                                    log(Level.WARN, "No callback for handling recv data from remote site");
                                }
                            }
                            
                            // if the socket is closed by peer, should close it.
                            if (closed | (readin == -1)) {
                                if(handle.m_closedCB != null) {
                                    handle.m_closedCB.NotifyClosed(handle, handle.getAttachment());
                                } else {
                                    log(Level.WARN, "No callback for handling closure from remote site");
                                }
                                handle.nioClose();
                            }
                        } else if(sock instanceof DatagramChannel) {
                            // UDP
                            log(Level.INFO, "Worker "+ this.toString() + " recved from remote side - UDP");
                            if(handle.m_recvFromCB == null) {
                                keyIterator.remove();
                                continue;
                            }
                            DatagramChannel us = (DatagramChannel) sock;
                            SocketAddress ra = null;
                            do {
                                data.clear(); //make buffer ready for writing
                                try {
                                    ra = us.receive(data);
                                }
                                catch (ClosedChannelException e) {
                                    closed = true;
                                    break;
                                }
                                catch (SecurityException e) {
                                    e.printStackTrace();
                                    break;
                                }
                                catch (IOException e) {
                                    e.printStackTrace();
                                    if(handle.m_recvErrCB != null) {
                                        handle.m_recvErrCB.NotifyRecvErr(handle, e, handle.getAttachment());
                                    } else {
                                        log(Level.WARN, "No callback for handling error for UDP");
                                    }
                                    break;
                                }
                                
                                data.flip();  //make buffer ready for read

                                segment = new byte[data.remaining()];
                                data.get(segment);
                                try {
                                    combinedBuf.write(segment);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                    break;
                                }
                            }  while(ra != null); // end of while receive
                            
                            if(combinedBuf.toByteArray().length != 0) {
                                if(handle.m_recvFromCB != null) {
                                    handle.m_recvFromCB.NotifyRecvFrom(handle, combinedBuf.toByteArray(), ra, attachment);
                                } else {
                                    log(Level.WARN, "No callback for handling data from remote site");
                                }
                            }
                            
                            // TODO: should handle udp endpoint is closed by other thread??????
                            if (closed) {
                                if(handle.m_closedCB != null) {
                                    handle.m_closedCB.NotifyClosed(handle, handle.getAttachment());
                                } else {
                                    log(Level.WARN, "No callback for handling closure from system");
                                }
                                handle.nioClose();
                            }
                        }
                    } // end of if readable
                    
                    keyIterator.remove();
                }
                //selectedKeys.clear();
            } // end of while
            
            log(Level.DEBUG, "Unlocking in worker at the last time");
            m_selectLock.unlock();
            try {
                // TODO: May need to considering if we need to 
                // reassign the socket to other worker.
                m_selector.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            super.run();
        }

        @Override
        public synchronized void start() {
            try {
                m_selector = Selector.open();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            m_shutdown = false;
            super.start();
        }
        
        public synchronized void shutdown() {
            m_shutdown = true;
            m_selector.wakeup();
        }
    }
    
    private NioService() {
        m_workers = new ArrayList<NioServiceWorker>();
        m_state = NioServiceState.SERVICE_STATE_INIT;
    }

    private NioServiceWorker getBalancedWorker() {
        int size = -1;
        NioServiceWorker balancedWorker = null;
        for(Iterator<NioServiceWorker> i = m_workers.iterator();i.hasNext();) {
            NioServiceWorker w = i.next();
            int curSize = w.m_selector.keys().size();
            if(size == -1) {
                size =  curSize;
                balancedWorker = w;
            } else if(size > curSize) {
                size =  curSize;
                balancedWorker = w;
            }
        } // end of for
        
        return balancedWorker;
    }

    private Selector getBalancedSelector() {
        int size = -1;
        Selector balancedSel = null;
        for(Iterator<NioServiceWorker> i = m_workers.iterator();i.hasNext();) {
            NioServiceWorker w = i.next();
            int curSize = w.m_selector.keys().size();
            if(size == -1) {
                size =  curSize;
                balancedSel = w.m_selector;
            } else if(size > curSize) {
                size =  curSize;
                balancedSel = w.m_selector;
            }
        } // end of for
        
        return balancedSel;
    }

    private long getLatestTimeout() {
        log(Level.DEBUG, "timer lock read lock");
        m_timerLock.readLock().lock();
        if(m_sortedTimers.isEmpty()) {
            log(Level.DEBUG, "timer lock read unlock");
            m_timerLock.readLock().unlock();
            return NIO_SERVICE_WAIT_FOREVER;
        }
        long nextTimeout = m_sortedTimers.get(0).m_expireTime;
        log(Level.DEBUG, "timer lock read unlock");
        m_timerLock.readLock().unlock();
        
        long now = System.currentTimeMillis();
        long duration = nextTimeout>now? (nextTimeout - now) : 1;
        return duration;
    }

    void wakeup(Selector sel) {
        sel.wakeup();
    }

    SelectionKey register(AbstractSelectableChannel socket, Selector selector, int key, Object attachment) throws IOException {
        log(Level.DEBUG, "Locking in register");
        m_selectLock.lock();
        SelectionKey retKey = null;
        try {
            retKey = socket.register(selector, key, attachment);
        }
        finally {
            m_selectLock.unlock();
            log(Level.DEBUG, "Unlocked in register");
        }
        return retKey;
    }
    
    SelectionKey registerWithWakeup(AbstractSelectableChannel socket, Selector selector, int key, Object attachment) throws IOException {
        log(Level.DEBUG, "Locking in register");
        m_selectLock.lock();
        SelectionKey retKey = null;
        try {
            selector.wakeup();
            retKey = socket.register(selector, key, attachment);
        } 
        finally {
            m_selectLock.unlock();
            log(Level.DEBUG, "Unlocked in register");
        }
        return retKey;
    }

    public static int getWorkerNum() {
        return m_workerNum;
    }

    public static void configWorkerNum(int workerNum) {
        m_workerNum = workerNum;
    }

    public static synchronized NioService getService() {
        if (gRervice == null) {
            gRervice = new NioService();
        }
        return gRervice;
    }

    public Logger getLogger() {
        return m_logger;
    }

    public void setLogger(Logger logger) {
        m_logger = logger;
        return;
    }

    public int start() {
        if((m_state == NioServiceState.SERVICE_STATE_STARTED) || 
           (m_state == NioServiceState.SERVICE_STATE_STARTING)) {
            return -1;
        }
        
        m_workers = new ArrayList<NioServiceWorker>();
        m_sortedTimers = new ArrayList<NioHandle>();
        m_timerLock = new ReentrantReadWriteLock();
        m_selectLock = new ReentrantLock();
        
        m_state = NioServiceState.SERVICE_STATE_STARTING;
        for(int i=0; i<m_workerNum; i++) {
            NioServiceWorker worker = new NioServiceWorker("worker");
            m_workers.add(worker);
            worker.start();
        }
    
        while(true) {
            int i = 0;
            for(i=0; i<m_workerNum; i++) {
                NioServiceWorker worker = m_workers.get(i);
                if(worker.m_started == false) {
                    break;
                }
            }
            
            // if all worker start up, set the service as started up;
            if(i >=m_workerNum) {
                break;
            }
        }
        
        m_state = NioServiceState.SERVICE_STATE_STARTED;
        return 0;
    }

    public void stop() {
        for(Iterator<NioServiceWorker> i = m_workers.iterator();i.hasNext();) {
            NioServiceWorker w = i.next();
            w.shutdown();
        }
        m_state = NioServiceState.SERVICE_STATE_STOPPED;
        return;
    }

    public NioServiceState getServiceState() {
        return m_state;
    }

    public NioHandle nioCreateHandle(NioHandleRole role, NioHandleProtocol protocol, 
            Object attachment) throws IOException {
        Selector sel= getBalancedSelector();
        NioHandle handle = new NioHandle(sel, role, protocol, attachment, m_logger);
    
        return handle;
    }

    public NioHandle nioCreateHandle(NioHandleRole role, NioHandleProtocol protocol, 
            String localAddr, int port, Object attachment) throws IOException {
        Selector sel= getBalancedSelector();
        NioHandle handle = new NioHandle(sel, role, protocol, localAddr, port, attachment, m_logger);

        return handle;
    }

    public NioHandle nioCreateHandle(NioHandleRole role, NioHandleProtocol protocol, 
            SocketAddress localAddr, Object attachment) throws IOException {
        Selector sel= getBalancedSelector();
        NioHandle handle = new NioHandle(sel, role, protocol, localAddr, attachment, m_logger);

        return handle;
    }

    public NioHandle nioSetTimer(long duration, NioEvHandlerTimeout cb, Object attachment) {
        long now = System.currentTimeMillis();
        long expired = now + duration;
        NioServiceWorker worker = getBalancedWorker();
        NioHandle handle = new NioHandle(worker.m_selector, duration, expired, attachment, m_logger);
        handle.registerTimeoutCB(cb);
        addTimer(handle);
        
        wakeup(worker.m_selector);
        return handle;
    }
    
//    public NioHandle nioSetInternalTimer(long duration, SocketAddress remoteAddr) {
//        long now = System.currentTimeMillis();
//        long expired = now + duration;
//        NioServiceWorker worker = getBalancedWorker();
//        NioHandle handle = new NioHandle(worker.m_selector, duration, expired, remoteAddr, m_logger);
//        addTimer(handle);
//        
//        wakeup(worker.m_selector);
//        return handle;
//    }
    
    public int nioKillTimer(NioHandle timerHandle) {
        boolean succ = removeTimer(timerHandle);
        timerHandle.m_duration = 0;
        timerHandle.m_expireTime = 0;
        if(!succ) {
            return NioHandle.NIO_FAILED_TO_KILL_TIMER;
        }
        
        return 0;
    }
}
