package com.hhw.niosocket;

public interface NioEvHandlerNewConnection {
    public int NotifyNewConnection(NioHandle oldHandle, NioHandle newHandle, Object attachment);
}
