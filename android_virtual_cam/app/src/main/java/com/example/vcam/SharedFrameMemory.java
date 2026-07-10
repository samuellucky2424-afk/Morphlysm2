package com.example.vcam;

import android.content.Context;
import android.os.MemoryFile;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

public final class SharedFrameMemory {
    private static final int MAGIC = 0x534D4D46; // SMMF
    private static final int MAGIC_WRITING = 0x534D4D57; // SMMW
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = 36;
    private static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;
    private static final MemoryState[] STATES = new MemoryState[VirtualCameraBridge.MAX_SLOTS];
    private static long lastFrameCounter;

    static {
        for (int i = 0; i < STATES.length; i++) {
            STATES[i] = new MemoryState();
        }
    }

    private SharedFrameMemory() {
    }

    public static void prepare(Context context, int slotId, int width, int height) {
        int length = expectedLength(width, height);
        if (length <= 0) {
            return;
        }
        int slot = safeSlot(slotId);
        int total = HEADER_BYTES + length;
        MemoryState state = STATES[slot];
        synchronized (state) {
            ensureMemoryFile(state, slot, total);
        }
        ensureMappedFile(context, slot, total);
    }

    public static void publish(Context context, int slotId, byte[] nv21, int width, int height, long timestampNs) {
        if (nv21 == null || width <= 0 || height <= 0) {
            return;
        }
        int length = Math.min(nv21.length, expectedLength(width, height));
        if (length <= 0 || length > MAX_FRAME_BYTES) {
            return;
        }
        int slot = safeSlot(slotId);
        long counter = nextFrameCounter();
        writeProcessMemory(slot, nv21, width, height, timestampNs, counter, length);
        writeMappedFrame(context, slot, nv21, width, height, timestampNs, counter, length);
    }

    public static VirtualFrame readLatest(Context context, int slotId) {
        int slot = safeSlot(slotId);
        VirtualFrame frame = readProcessMemory(slot);
        if (frame != null && frame.nv21 != null) {
            return frame;
        }
        return readMappedFrame(context, slot);
    }

    public static boolean hasRecentFrame(Context context, int slotId, long maxAgeMs) {
        File file = getMappedFrameFile(context, safeSlot(slotId));
        try {
            long now = System.currentTimeMillis();
            return file.exists() && now - file.lastModified() <= maxAgeMs;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void clear(Context context, int slotId) {
        int slot = safeSlot(slotId);
        MemoryState state = STATES[slot];
        synchronized (state) {
            closeMemoryFile(state);
        }
        try {
            File file = getMappedFrameFile(context, slot);
            if (file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void writeProcessMemory(int slot, byte[] nv21, int width, int height, long timestampNs, long counter, int length) {
        MemoryState state = STATES[slot];
        synchronized (state) {
            int total = HEADER_BYTES + length;
            if (!ensureMemoryFile(state, slot, total) || state.memoryFile == null) {
                return;
            }
            try {
                state.memoryFile.writeBytes(header(MAGIC_WRITING, width, height, timestampNs, counter, length), 0, 0, HEADER_BYTES);
                state.memoryFile.writeBytes(nv21, 0, HEADER_BYTES, length);
                state.memoryFile.writeBytes(header(MAGIC, width, height, timestampNs, counter, length), 0, 0, HEADER_BYTES);
                state.width = width;
                state.height = height;
                state.length = length;
                state.timestampNs = timestampNs;
                state.counter = counter;
            } catch (Throwable ignored) {
            }
        }
    }

    private static VirtualFrame readProcessMemory(int slot) {
        MemoryState state = STATES[slot];
        synchronized (state) {
            if (state.memoryFile == null || state.length <= 0 || state.length > MAX_FRAME_BYTES) {
                return null;
            }
            try {
                byte[] header = new byte[HEADER_BYTES];
                state.memoryFile.readBytes(header, 0, 0, HEADER_BYTES);
                FrameHeader parsed = parseHeader(ByteBuffer.wrap(header), HEADER_BYTES);
                if (parsed == null) {
                    return null;
                }
                byte[] nv21 = new byte[parsed.length];
                state.memoryFile.readBytes(nv21, HEADER_BYTES, 0, parsed.length);
                return new VirtualFrame(nv21, parsed.width, parsed.height, parsed.timestampNs, parsed.counter);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static void writeMappedFrame(Context context, int slot, byte[] nv21, int width, int height, long timestampNs, long counter, int length) {
        File file = getMappedFrameFile(context, slot);
        try {
            File dir = file.getParentFile();
            if (dir == null || (!dir.exists() && !dir.mkdirs())) {
                return;
            }
            int total = HEADER_BYTES + length;
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw");
                 FileChannel channel = raf.getChannel()) {
                if (raf.length() != total) {
                    raf.setLength(total);
                }
                MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, total);
                buffer.putInt(0, MAGIC_WRITING);
                buffer.position(HEADER_BYTES);
                buffer.put(nv21, 0, length);
                buffer.position(0);
                buffer.put(header(MAGIC, width, height, timestampNs, counter, length));
            }
            //noinspection ResultOfMethodCallIgnored
            file.setLastModified(System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
    }

    private static VirtualFrame readMappedFrame(Context context, int slot) {
        File file = getMappedFrameFile(context, slot);
        try {
            if (!file.exists() || file.length() < HEADER_BYTES) {
                return null;
            }
            try (RandomAccessFile raf = new RandomAccessFile(file, "r");
                 FileChannel channel = raf.getChannel()) {
                long size = channel.size();
                if (size < HEADER_BYTES || size > HEADER_BYTES + MAX_FRAME_BYTES) {
                    return null;
                }
                MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
                FrameHeader header = parseHeader(buffer, size);
                if (header == null) {
                    return null;
                }
                byte[] nv21 = new byte[header.length];
                buffer.position(HEADER_BYTES);
                buffer.get(nv21);
                return new VirtualFrame(nv21, header.width, header.height, header.timestampNs, header.counter);
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean ensureMemoryFile(MemoryState state, int slot, int totalBytes) {
        if (totalBytes <= HEADER_BYTES || totalBytes > HEADER_BYTES + MAX_FRAME_BYTES) {
            return false;
        }
        if (state.memoryFile != null && state.capacity >= totalBytes) {
            return true;
        }
        closeMemoryFile(state);
        try {
            state.memoryFile = new MemoryFile("smokescreen_slot_" + slot, totalBytes);
            state.capacity = totalBytes;
            return true;
        } catch (Throwable ignored) {
            state.memoryFile = null;
            state.capacity = 0;
            return false;
        }
    }

    private static void ensureMappedFile(Context context, int slot, int totalBytes) {
        File file = getMappedFrameFile(context, slot);
        try {
            File dir = file.getParentFile();
            if (dir == null || (!dir.exists() && !dir.mkdirs())) {
                return;
            }
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                if (raf.length() < totalBytes) {
                    raf.setLength(totalBytes);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static File getMappedFrameFile(Context context, int slot) {
        File slotFile = SharedFramePathResolver.getSlotFile(context, slot);
        File dir = slotFile.getParentFile();
        if (dir == null) {
            dir = SharedFramePathResolver.getFrameDirectory(context);
        }
        return new File(dir, "smokescreen_slot_" + slot + ".mmap");
    }

    private static byte[] header(int magic, int width, int height, long timestampNs, long counter, int length) {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES);
        buffer.putInt(magic);
        buffer.putInt(VERSION);
        buffer.putInt(width);
        buffer.putInt(height);
        buffer.putLong(timestampNs);
        buffer.putLong(counter);
        buffer.putInt(length);
        return buffer.array();
    }

    private static FrameHeader parseHeader(ByteBuffer buffer, long totalSize) {
        if (buffer == null || totalSize < HEADER_BYTES) {
            return null;
        }
        int magic = buffer.getInt();
        int version = buffer.getInt();
        int width = buffer.getInt();
        int height = buffer.getInt();
        long timestampNs = buffer.getLong();
        long counter = buffer.getLong();
        int length = buffer.getInt();
        int expected = expectedLength(width, height);
        if (magic != MAGIC
                || version != VERSION
                || width <= 0
                || height <= 0
                || length <= 0
                || length > MAX_FRAME_BYTES
                || length > expected
                || HEADER_BYTES + (long) length > totalSize) {
            return null;
        }
        return new FrameHeader(width, height, timestampNs, counter, length);
    }

    private static int expectedLength(int width, int height) {
        if (width <= 0 || height <= 0) {
            return 0;
        }
        long expected = width * (long) height * 3L / 2L;
        return expected > Integer.MAX_VALUE ? 0 : (int) expected;
    }

    private static synchronized long nextFrameCounter() {
        return ++lastFrameCounter;
    }

    private static int safeSlot(int slotId) {
        return Math.max(0, Math.min(VirtualCameraBridge.MAX_SLOTS - 1, slotId));
    }

    private static void closeMemoryFile(MemoryState state) {
        try {
            if (state.memoryFile != null) {
                state.memoryFile.close();
            }
        } catch (Throwable ignored) {
        }
        state.memoryFile = null;
        state.capacity = 0;
        state.length = 0;
    }

    private static final class MemoryState {
        MemoryFile memoryFile;
        int capacity;
        int width;
        int height;
        int length;
        long timestampNs;
        long counter;
    }

    private static final class FrameHeader {
        final int width;
        final int height;
        final long timestampNs;
        final long counter;
        final int length;

        FrameHeader(int width, int height, long timestampNs, long counter, int length) {
            this.width = width;
            this.height = height;
            this.timestampNs = timestampNs;
            this.counter = counter;
            this.length = length;
        }
    }
}
