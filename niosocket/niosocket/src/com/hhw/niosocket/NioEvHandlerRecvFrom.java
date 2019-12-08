package com.hhw.niosocket;

import java.net.SocketAddress;

public interface NioEvHandlerRecvFrom {
    public int NotifyRecvFrom(NioHandle handle, byte[] payload, SocketAddress remoteAddr, Object attachment);
}
