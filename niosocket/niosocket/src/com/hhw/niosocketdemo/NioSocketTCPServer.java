package com.hhw.niosocketdemo;

import java.io.IOException;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import com.hhw.niosocket.NioEvHandlerClosed;
import com.hhw.niosocket.NioEvHandlerNewConnection;
import com.hhw.niosocket.NioEvHandlerRecvData;
import com.hhw.niosocket.NioEvHandlerSentDone;
import com.hhw.niosocket.NioEvHandlerSentFail;
import com.hhw.niosocket.NioHandle;
import com.hhw.niosocket.NioHandle.NioHandleProtocol;
import com.hhw.niosocket.NioHandle.NioHandleRole;
import com.hhw.niosocket.NioService;

public class NioSocketTCPServer implements NioEvHandlerNewConnection, NioEvHandlerRecvData, 
    NioEvHandlerSentDone, NioEvHandlerSentFail, NioEvHandlerClosed {
    private static final Logger logger = LogManager.getLogger(NioSocketTCPServer.class.getName());

    public static void main(String[] args) {
        
        NioSocketTCPServer server = new NioSocketTCPServer();
        NioService service = null;
        service = NioService.getService();
        service.setLogger(logger);
        service.start();
        
        NioHandle sock = null;
        try {
            sock = service.nioCreateHandle(NioHandleRole.SOCKET_SERVER, 
                    NioHandleProtocol.SOCKET_TYPE_TCP, null);
        } catch (IOException e) {
            e.printStackTrace();
            logger.fatal("Failed to create server socket");
            return;
        }
        
        sock.registerNewConnectionCB(server);
        sock.registerClosedCB(server);
        sock.registerRecvDataCB(server);
        sock.registerSentDoneCB(server);
        sock.registerSentFailCB(server);
      
        logger.info("Server stared");
        
        String addr = "10.35.81.88";
        int port = 5051;
        int ret = sock.nioListen(addr, port, null); 
        if(ret != 0) {
            logger.fatal("Server failed to listen on port " + port);
            service.stop();
            return;
        }

        logger.info("Server is listening on "  + addr + ":" + port);
        while(true) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
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
        return 0;
    }

    @Override
    public int NotifySentFail(NioHandle socket, Exception e, Object attachment) {
        logger.error("Failed to send data to client");
        return 0;
    }

    @Override
    public int NotifySendDone(NioHandle socket, int bytesSent, Object attachment) {
        logger.info("Succeeded to send data " + bytesSent + " to client");
        return 0;
    }

    @Override
    public int NotifyRecvData(NioHandle socket, byte[] payload, Object attachment) {
        logger.info("Succeeded to recv data " + payload.length + " from client");
        
        String response = "hello froms server";
        socket.nioSend(response.getBytes());
        return 0;
    }

    @Override
    public int NotifyNewConnection(NioHandle socket, NioHandle newSocket, Object attachment) {
        logger.info("Get new connecton from " + newSocket.getRemoteAddr().toString());
        return 0;
    }
}
