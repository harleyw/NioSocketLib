package com.hhw.niosocketdemo;

import java.io.IOException;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import com.hhw.niosocket.NioEvHandlerClosed;
import com.hhw.niosocket.NioEvHandlerConnectFail;
import com.hhw.niosocket.NioEvHandlerConnected;
import com.hhw.niosocket.NioEvHandlerRecvData;
import com.hhw.niosocket.NioEvHandlerSentDone;
import com.hhw.niosocket.NioEvHandlerSentFail;
import com.hhw.niosocket.NioEvHandlerTimeout;
import com.hhw.niosocket.NioHandle;
import com.hhw.niosocket.NioHandle.NioHandleProtocol;
import com.hhw.niosocket.NioHandle.NioHandleRole;
import com.hhw.niosocket.NioService;

public class NioSocketTCPClient implements NioEvHandlerConnected, NioEvHandlerConnectFail, NioEvHandlerRecvData, NioEvHandlerSentDone, 
    NioEvHandlerSentFail, NioEvHandlerClosed, NioEvHandlerTimeout {
    private static final Logger logger = LogManager.getLogger(NioSocketTCPClient.class.getName());
    private static boolean shutdown;

    public static void main(String[] args) {

        NioSocketTCPClient client = new NioSocketTCPClient();
        NioService service = null;
        service = NioService.getService();
        service.setLogger(logger);
        service.start();
        
        NioHandle sock = null;
        try {
            sock = service.nioCreateHandle(NioHandleRole.SOCKET_CLIENT, 
                    NioHandleProtocol.SOCKET_TYPE_TCP, null);
        } catch (IOException e) {
            e.printStackTrace();
            logger.fatal("Failed to create client socket");
            return;
        }
        
        // register all callbacks;
        sock.registerConnectedCB(client);
        sock.registerConnectFailCB(client);
        sock.registerSentDoneCB(client);
        sock.registerSentFailCB(client);
        sock.registerRecvDataCB(client);
        sock.registerClosedCB(client);

        logger.info("Client is started");
        
        String addr = "10.244.102.197";
        int port = 5051;
        
        // connect to the server;
        int ret = sock.nioConnect(addr, port, null);
        if(ret != 0) {
            return;
        }
        
        logger.info("client is connecting on "  + addr + ":" + port);
        shutdown = false;
        while(!shutdown) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
                break;
            }
        }
        
        service.stop();
        return;
    }

    @Override
    public int NotifyClosed(NioHandle socket, Object attachment) {
        logger.info("The socket is closed");
        shutdown = true;
        return 0;
    }

    @Override
    public int NotifySentFail(NioHandle socket, Exception e, Object attachment) {
        logger.fatal("Failed to send data to server");
        return 0;
    }

    @Override
    public int NotifySendDone(NioHandle socket, int bytesSent, Object attachment) {
        logger.info("Succeeded to send data " + bytesSent + " to server");
        return 0;
    }

    @Override
    public int NotifyRecvData(NioHandle socket, byte[] payload, Object attachment) {
        logger.info("Succeeded to recv data from server");
        
        String rsp = new String(payload);
        logger.info("Recv-ed message: " + rsp);
        
        NioHandle timer = NioService.getService().nioSetTimer(5000, this, socket);
        if(timer == null) {
            logger.error("Failed to create timer for sending message");
        }
        return 0;
    }

    @Override
    public int NotifyConnectFail(NioHandle socket, Exception error, Object attachment) {
        logger.info("Failed to connect server");
        
        //TODO: closet the socket?
        socket.nioClose();
        shutdown = true;
        return 0;
    }

    @Override
    public int NotifyConnected(NioHandle socket, Object attachment) {
        logger.info("Succeeded to connect server");
        
        // send data to server;
        String hello = "hello from client";
        socket.nioSend(hello.getBytes());
        
        return 0;
    }

    @Override
    public int NotifyTimeout(Object attachment) {
        logger.info("Time to send hello");
        NioHandle socket = (NioHandle) attachment;
        
        String hello = "hello from client";
        socket.nioSend(hello.getBytes());
        return 0;
    }
}
