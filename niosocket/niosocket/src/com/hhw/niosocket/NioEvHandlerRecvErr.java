package com.hhw.niosocket;

public interface NioEvHandlerRecvErr {
    public int NotifyRecvErr(NioHandle handle, Exception error, Object attachment);
}
