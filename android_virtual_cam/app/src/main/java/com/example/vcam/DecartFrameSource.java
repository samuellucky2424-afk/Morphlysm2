package com.example.vcam;

public final class DecartFrameSource implements FrameSource {
    private FrameSink sink;
    private volatile boolean running;

    @Override
    public void start(FrameSink sink) {
        this.sink = sink;
        this.running = true;
    }

    public void publishI420(byte[] i420, int width, int height, long timestampNs) {
        if (running && sink != null) {
            sink.onI420Frame(i420, width, height, timestampNs);
        }
    }

    public void publishRgba(byte[] rgba, int width, int height, long timestampNs) {
        if (running && sink != null) {
            sink.onRgbaFrame(rgba, width, height, timestampNs);
        }
    }

    public void publishNv21(byte[] nv21, int width, int height, long timestampNs) {
        if (running && sink != null) {
            sink.onNv21Frame(nv21, width, height, timestampNs);
        }
    }

    @Override
    public void stop() {
        running = false;
        sink = null;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public String getName() {
        return "DecartFrameSource";
    }
}
