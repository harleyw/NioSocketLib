package com.hhw.niosocketdemo;

import java.io.IOException;
import java.net.SocketAddress;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.hhw.niosocket.NioEvHandlerClosed;
import com.hhw.niosocket.NioEvHandlerNewConnection;
import com.hhw.niosocket.NioEvHandlerRecvData;
import com.hhw.niosocket.NioEvHandlerRecvFrom;
import com.hhw.niosocket.NioEvHandlerSentDone;
import com.hhw.niosocket.NioEvHandlerSentFail;
import com.hhw.niosocket.NioHandle;
import com.hhw.niosocket.NioService;
import com.hhw.niosocket.NioHandle.NioHandleProtocol;
import com.hhw.niosocket.NioHandle.NioHandleRole;

public class NioSocketUDP implements NioEvHandlerRecvFrom, NioEvHandlerSentDone, NioEvHandlerSentFail {

    private static final Logger logger = LogManager.getLogger(NioSocketTCPServer.class.getName());
    public static void main(String[] args) {
        
        NioSocketUDP udpTerm = new NioSocketUDP();
        NioService service = null;
        service = NioService.getService();
        service.setLogger(logger);
        service.start();
        
        NioHandle sock = null;
        String local = "10.35.81.88";
        int localPort = 5050;
        try {
            sock = service.nioCreateHandle(NioHandleRole.SOCKET_ROLE_NA, 
                    NioHandleProtocol.SOCKET_TYPE_UDP, local, localPort, null);
        } catch (IOException e) {
            e.printStackTrace();
            logger.fatal("Failed to create server socket");
            return;
        }
        
        sock.registerRecvFromCB(udpTerm);
        sock.registerSentDoneCB(udpTerm);
        sock.registerSentFailCB(udpTerm);
      
        logger.info("Server stared");
        
        String addr = "10.35.81.88";
        int port = 5051;
        String payload = "UDP send to";
        int ret = sock.nioSendTo(payload.getBytes(), addr, port);
        if(ret < 0) {
            logger.fatal("udp socket failed to send data on port " + port);
            service.stop();
            return;
        }

        logger.info("UDP bouncing is started btw " + local + ":" + localPort + " and " + addr + ":" + port);
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
    public int NotifySentFail(NioHandle handle, Exception e, Object attachment) {
        logger.info("Failed to send data to " + handle.getRemoteAddr());
        return 0;
    }

    @Override
    public int NotifySendDone(NioHandle handle, int bytesSent, Object attachment) {
        logger.info("Send " + bytesSent + " bytes data to " + handle.getRemoteAddr());
        return 0;
    }

    @Override
    public int NotifyRecvFrom(NioHandle handle, byte[] payload, SocketAddress remoteAddr, Object attachment) {
        // TODO Auto-generated method stub
        return 0;
    }

}
