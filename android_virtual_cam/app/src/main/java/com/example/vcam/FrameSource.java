package com.example.vcam;

public interface FrameSource {
    void start(FrameSink sink) throws Exception;

    void stop();

    boolean isRunning();

    String getName();
}
