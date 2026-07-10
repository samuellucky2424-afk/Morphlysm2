package com.example.vcam;

import java.io.File;

public final class MediaFileFrameSource implements FrameSource {
    private final File videoFile;
    private final VideoToFrames decoder;
    private volatile boolean running;

    public MediaFileFrameSource(File videoFile) {
        this.videoFile = videoFile;
        this.decoder = new VideoToFrames();
    }

    @Override
    public void start(final FrameSink sink) throws Exception {
        if (running) {
            return;
        }
        running = true;
        decoder.setFrameSink(sink);
        decoder.setSaveFrames("", OutputImageFormat.NV21);
        try {
            decoder.decode(videoFile.getAbsolutePath());
        } catch (Throwable throwable) {
            running = false;
            throw new Exception("Unable to start media frame source", throwable);
        }
    }

    @Override
    public void stop() {
        running = false;
        decoder.stopDecode();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public String getName() {
        return "MediaFileFrameSource";
    }
}
