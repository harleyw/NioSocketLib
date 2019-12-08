package com.hhw.niosocket;

public interface NioEvHandlerSentFail {
    public int NotifySentFail(NioHandle handle, Exception e, Object attachment);
}
