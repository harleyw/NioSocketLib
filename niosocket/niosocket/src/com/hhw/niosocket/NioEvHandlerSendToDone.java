package com.hhw.niosocket;

import java.net.SocketAddress;

public interface NioEvHandlerSendToDone {
    public int NotifySendToDone(NioHandle handle, int bytesSent, SocketAddress ra, Object attachment);
}
