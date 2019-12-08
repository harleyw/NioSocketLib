package com.hhw.niosocket;

public interface NioEvHandlerConnectFail {
    public int NotifyConnectFail(NioHandle handle, Exception error, Object attachment);
}
