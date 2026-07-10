package com.example.vcam;

import android.util.Log;

/**
 * A persistent, thread-safe singleton store for Decart frames.
 *
 * The Decart receiver pushes every new frame here via {@link #update}.
 * The WhatsApp render loop ({@link Nv21SurfaceRenderer}) reads the latest
 * frame from here on each tick, ensuring continuous rendering even if
 * frames arrive faster or slower than the render rate.
 */
public final class DecartFrameStore {
    private static final String TAG = "DECART_STORE";

    /** The most recent Decart frame (NV21 pixels). */
    public static volatile VirtualFrame latestFrame;

    /** Nanosecond timestamp of the latest frame. */
    public static volatile long latestTimestamp;

    /** Monotonically increasing frame counter. */
    public static volatile long frameCounter;

    private DecartFrameStore() {
    }

    /**
     * Called by the Decart frame receiver every time a new frame is produced.
     * This is the only write path — it must be called from the Decart pipeline.
     */
    public static synchronized void update(byte[] nv21, int width, int height) {
        if (nv21 == null || width <= 0 || height <= 0) {
            return;
        }
        long counter = ++frameCounter;
        long timestamp = System.nanoTime();
        latestTimestamp = timestamp;
        byte[] copy = new byte[Math.min(nv21.length, width * height * 3 / 2)];
        System.arraycopy(nv21, 0, copy, 0, copy.length);
        latestFrame = new VirtualFrame(copy, width, height, timestamp, counter);

        if (counter <= 5 || counter % 30 == 0) {
            Log.d(TAG, "Decart: received frameCounter=" + counter
                    + " timestamp=" + timestamp
                    + " " + width + "x" + height);
        }
    }

    /** Clear the store (e.g. on disconnect). */
    public static synchronized void clear() {
        latestFrame = null;
        frameCounter = 0;
        latestTimestamp = 0;
        Log.d(TAG, "store_cleared");
    }
}
