package com.example.vcam;

public interface FrameSink {
    void onNv21Frame(byte[] nv21, int width, int height, long timestampNs);

    void onI420Frame(byte[] i420, int width, int height, long timestampNs);

    void onRgbaFrame(byte[] rgba, int width, int height, long timestampNs);
}
