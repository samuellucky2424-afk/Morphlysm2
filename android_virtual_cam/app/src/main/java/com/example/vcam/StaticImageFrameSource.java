package com.example.vcam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;

public final class StaticImageFrameSource implements FrameSource {
    private final File imageFile;
    private final int width;
    private final int height;
    private final int fps;
    private volatile boolean running;
    private Thread worker;

    public StaticImageFrameSource(File imageFile, int width, int height, int fps) {
        this.imageFile = imageFile;
        this.width = width;
        this.height = height;
        this.fps = Math.max(1, fps);
    }

    @Override
    public void start(final FrameSink sink) throws Exception {
        if (running) {
            return;
        }
        final Bitmap bitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath());
        if (bitmap == null) {
            throw new IllegalArgumentException("Unsupported static image source: " + imageFile);
        }
        final byte[] nv21 = Nv21Converter.bitmapToNv21(bitmap, width, height);
        bitmap.recycle();
        running = true;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                long delayMs = Math.max(1, 1000 / fps);
                while (running) {
                    sink.onNv21Frame(nv21, width, height, System.nanoTime());
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "smokescreen-static-image-source");
        worker.start();
    }

    @Override
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public String getName() {
        return "StaticImageFrameSource";
    }
}
