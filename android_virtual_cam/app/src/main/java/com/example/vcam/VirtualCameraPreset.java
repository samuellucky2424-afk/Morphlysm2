package com.example.vcam;

public enum VirtualCameraPreset {
    LOW(480, 854, 24, 2),
    DEFAULT(720, 1280, 30, 3),
    HIGH(1080, 1920, 30, 3);

    public final int width;
    public final int height;
    public final int fps;
    public final int frameSlots;

    VirtualCameraPreset(int width, int height, int fps, int frameSlots) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.frameSlots = frameSlots;
    }
}
