package com.example.vcam;

public final class VirtualFrame {
    public final byte[] nv21;
    public final int width;
    public final int height;
    public final long timestampNs;
    public final long counter;

    public VirtualFrame(byte[] nv21, int width, int height, long timestampNs, long counter) {
        this.nv21 = nv21;
        this.width = width;
        this.height = height;
        this.timestampNs = timestampNs;
        this.counter = counter;
    }
}
