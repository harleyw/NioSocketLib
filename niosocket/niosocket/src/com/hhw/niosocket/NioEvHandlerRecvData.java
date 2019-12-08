package com.hhw.niosocket;

public interface NioEvHandlerRecvData {
    public int NotifyRecvData(NioHandle handle, byte[] payload, Object attachment);
}
