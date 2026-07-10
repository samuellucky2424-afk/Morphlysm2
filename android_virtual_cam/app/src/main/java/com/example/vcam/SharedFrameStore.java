package com.example.vcam;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

public final class SharedFrameStore {
    private static final int MAGIC = 0x534D4B46; // SMKF
    private static final int SOURCE_MAGIC = 0x534D4B53; // SMKS
    private static final int LEGACY_FRAME_VERSION = 1;
    private static final int FRAME_VERSION = 2;
    private static final int SOURCE_VERSION = 1;
    private static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;
    private static final long FILE_STATE_LOG_INTERVAL_MS = 2000L;
    private static volatile long lastPublishedFrameCounter;
    private static volatile long lastFrameWriteLogMs;
    private static volatile long lastFrameReadLogMs;
    private static volatile long lastFrameMissingLogMs;
    private static volatile long lastFrameReadErrorLogMs;
    private static volatile long lastSourceWriteLogMs;
    private static volatile long lastSourceReadErrorLogMs;

    private SharedFrameStore() {
    }

    public static void publish(Context context, int slotId, byte[] nv21, int width, int height, long timestampNs) {
        if (nv21 == null || width <= 0 || height <= 0) {
            return;
        }
        writeFrame(context, safeSlot(slotId), nv21, width, height, timestampNs);
    }

    public static void publishSource(Context context, int slotId, String source) {
        if (source == null || source.trim().isEmpty()) {
            return;
        }
        writeSource(context, safeSlot(slotId), source.trim(), System.currentTimeMillis());
    }

    public static String readSource(Context context, int slotId) {
        File file = SharedFramePathResolver.getSourceFile(context, safeSlot(slotId));
        if (!file.exists()) {
            return null;
        }
        return readSource(context, file);
    }

    public static VirtualFrame readLatest(Context context, int slotId) {
        File file = SharedFramePathResolver.getSlotFile(context, safeSlot(slotId));
        if (!file.exists()) {
            if (shouldLogFrameMissing()) {
                SharedFramePathResolver.logFileState("consumer_frame_missing", context, file, null);
            }
            return null;
        }
        VirtualFrame frame = readFrame(context, file);
        if (frame != null && shouldLogFrameRead()) {
            SharedFramePathResolver.logFileState(
                    "consumer_frame_read width=" + frame.width + " height=" + frame.height + " counter=" + frame.counter,
                    context,
                    file,
                    null
            );
        }
        return frame;
    }

    public static long frameLastModified(Context context, int slotId) {
        File file = SharedFramePathResolver.getSlotFile(context, safeSlot(slotId));
        try {
            return file.exists() ? file.lastModified() : 0L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public static long frameLength(Context context, int slotId) {
        File file = SharedFramePathResolver.getSlotFile(context, safeSlot(slotId));
        try {
            return file.exists() ? file.length() : 0L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public static boolean hasRecentFrame(Context context, int slotId, long maxAgeMs) {
        File file = SharedFramePathResolver.getSlotFile(context, safeSlot(slotId));
        long now = System.currentTimeMillis();
        boolean recent = file.exists() && now - file.lastModified() <= maxAgeMs;
        if (!recent && shouldLogFrameMissing()) {
            SharedFramePathResolver.logFileState("consumer_frame_not_recent maxAgeMs=" + maxAgeMs, context, file, null);
        }
        return recent;
    }

    public static void clear(Context context, int slotId) {
        int safeSlot = safeSlot(slotId);
        File file = SharedFramePathResolver.getSlotFile(context, safeSlot);
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        File tmp = SharedFramePathResolver.getTempSlotFile(context, safeSlot);
        if (tmp.exists()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        File source = SharedFramePathResolver.getSourceFile(context, safeSlot);
        if (source.exists()) {
            //noinspection ResultOfMethodCallIgnored
            source.delete();
        }
        File sourceTmp = SharedFramePathResolver.getTempSourceFile(context, safeSlot);
        if (sourceTmp.exists()) {
            //noinspection ResultOfMethodCallIgnored
            sourceTmp.delete();
        }
    }

    private static void writeFrame(Context context, int slotId, byte[] nv21, int width, int height, long timestampNs) {
        File target = SharedFramePathResolver.getSlotFile(context, slotId);
        File tmp = SharedFramePathResolver.getTempSlotFile(context, slotId);
        try {
            File dir = target.getParentFile();
            if (dir == null || (!dir.exists() && !dir.mkdirs())) {
                if (shouldLogFrameWrite()) {
                    SharedFramePathResolver.logFileState("producer_frame_dir_unavailable", context, target, null);
                }
                return;
            }
            long frameCounter = nextFrameCounter();
            DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(tmp, false))
            );
            try {
                int length = Math.min(nv21.length, width * height * 3 / 2);
                output.writeInt(MAGIC);
                output.writeInt(FRAME_VERSION);
                output.writeInt(width);
                output.writeInt(height);
                output.writeLong(timestampNs);
                output.writeLong(frameCounter);
                output.writeInt(length);
                output.write(nv21, 0, length);
            } finally {
                output.close();
            }
            if (target.exists()) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
            boolean renamed = tmp.renameTo(target);
            if (shouldLogFrameWrite()) {
                SharedFramePathResolver.logFileState(
                        "producer_frame_written slot=" + slotId + " width=" + width + " height=" + height
                                + " counter=" + frameCounter + " renamed=" + renamed,
                        context,
                        target,
                        null
                );
            }
        } catch (Throwable error) {
            if (shouldLogFrameWrite()) {
                SharedFramePathResolver.logFileState("producer_frame_write_exception slot=" + slotId, context, target, error);
            }
        }
    }

    private static void writeSource(Context context, int slotId, String source, long timestampMs) {
        File target = SharedFramePathResolver.getSourceFile(context, slotId);
        File tmp = SharedFramePathResolver.getTempSourceFile(context, slotId);
        try {
            File dir = target.getParentFile();
            if (dir == null || (!dir.exists() && !dir.mkdirs())) {
                return;
            }
            DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(tmp, false))
            );
            try {
                output.writeInt(SOURCE_MAGIC);
                output.writeInt(SOURCE_VERSION);
                output.writeLong(timestampMs);
                output.writeUTF(source);
            } finally {
                output.close();
            }
            if (target.exists()) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
            boolean renamed = tmp.renameTo(target);
            if (shouldLogSourceWrite()) {
                SharedFramePathResolver.logFileState(
                        "producer_source_written slot=" + slotId + " source=" + source + " renamed=" + renamed,
                        context,
                        target,
                        null
                );
            }
        } catch (Throwable error) {
            if (shouldLogSourceWrite()) {
                SharedFramePathResolver.logFileState("producer_source_write_exception slot=" + slotId, context, target, error);
            }
        }
    }

    private static VirtualFrame readFrame(Context context, File file) {
        try {
            DataInputStream input = new DataInputStream(
                    new BufferedInputStream(new FileInputStream(file))
            );
            try {
                if (input.readInt() != MAGIC) {
                    return null;
                }
                int version = input.readInt();
                if (version != LEGACY_FRAME_VERSION && version != FRAME_VERSION) {
                    return null;
                }
                int width = input.readInt();
                int height = input.readInt();
                long timestampNs = input.readLong();
                long frameCounter = version >= FRAME_VERSION ? input.readLong() : file.lastModified();
                int length = input.readInt();
                int expected = width * height * 3 / 2;
                if (width <= 0 || height <= 0 || length <= 0 || length > MAX_FRAME_BYTES || length > expected) {
                    return null;
                }
                byte[] nv21 = new byte[length];
                input.readFully(nv21);
                return new VirtualFrame(nv21, width, height, timestampNs, frameCounter);
            } finally {
                input.close();
            }
        } catch (Throwable error) {
            if (shouldLogFrameReadError()) {
                SharedFramePathResolver.logFileState("consumer_frame_read_exception", context, file, error);
            }
            return null;
        }
    }

    private static String readSource(Context context, File file) {
        try {
            DataInputStream input = new DataInputStream(
                    new BufferedInputStream(new FileInputStream(file))
            );
            try {
                if (input.readInt() != SOURCE_MAGIC || input.readInt() != SOURCE_VERSION) {
                    return null;
                }
                input.readLong();
                String source = input.readUTF();
                return source == null || source.trim().isEmpty() ? null : source.trim();
            } finally {
                input.close();
            }
        } catch (Throwable error) {
            if (shouldLogSourceReadError()) {
                SharedFramePathResolver.logFileState("consumer_source_read_exception", context, file, error);
            }
            return null;
        }
    }

    private static int safeSlot(int slotId) {
        return Math.max(0, Math.min(VirtualCameraBridge.MAX_SLOTS - 1, slotId));
    }

    private static synchronized long nextFrameCounter() {
        long next = System.currentTimeMillis();
        if (next <= lastPublishedFrameCounter) {
            next = lastPublishedFrameCounter + 1L;
        }
        lastPublishedFrameCounter = next;
        return next;
    }

    private static boolean shouldLogFrameWrite() {
        long now = System.currentTimeMillis();
        if (now - lastFrameWriteLogMs < FILE_STATE_LOG_INTERVAL_MS) {
            return false;
        }
        lastFrameWriteLogMs = now;
        return true;
    }

    private static boolean shouldLogFrameRead() {
        long now = System.currentTimeMillis();
        if (now - lastFrameReadLogMs < FILE_STATE_LOG_INTERVAL_MS) {
            return false;
        }
        lastFrameReadLogMs = now;
        return true;
    }

    private static boolean shouldLogFrameMissing() {
        long now = System.currentTimeMillis();
        if (now - lastFrameMissingLogMs < FILE_STATE_LOG_INTERVAL_MS) {
            return false;
        }
        lastFrameMissingLogMs = now;
        return true;
    }

    private static boolean shouldLogFrameReadError() {
        long now = System.currentTimeMillis();
        if (now - lastFrameReadErrorLogMs < FILE_STATE_LOG_INTERVAL_MS) {
            return false;
        }
        lastFrameReadErrorLogMs = now;
        return true;
    }

    private static boolean shouldLogSourceWrite() {
        long now = System.currentTimeMillis();
        if (now - lastSourceWriteLogMs < FILE_STATE_LOG_INTERVAL_MS) {
            return false;
        }
        lastSourceWriteLogMs = now;
        return true;
    }

    private static boolean shouldLogSourceReadError() {
        long now = System.currentTimeMillis();
        if (now - lastSourceReadErrorLogMs < FILE_STATE_LOG_INTERVAL_MS) {
            return false;
        }
        lastSourceReadErrorLogMs = now;
        return true;
    }
}
