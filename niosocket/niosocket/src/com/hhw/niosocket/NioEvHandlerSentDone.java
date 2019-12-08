package com.hhw.niosocket;

public interface NioEvHandlerSentDone {
    public int NotifySendDone(NioHandle handle, int bytesSent, Object attachment);
}
