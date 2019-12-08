package com.hhw.niosocket;

import java.net.SocketAddress;

public interface NioEVHandlerSentToFail {
    public int NotifySentToFail(NioHandle handle, Exception e, SocketAddress ra, Object attachment);
}
