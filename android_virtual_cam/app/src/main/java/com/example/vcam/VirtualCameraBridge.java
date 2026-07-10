package com.example.vcam;

import android.content.Context;
import android.util.Log;

public final class VirtualCameraBridge {
    private static final String TAG_FRAME = "VCAM_FRAME";
    private static final String TAG_SOURCE = "VCAM_SOURCE";
    public static final int MAX_SLOTS = 100;
    public static final int DEFAULT_SLOT = 0;
    public static final String SOURCE_GREEN_TEST = "GREEN_TEST";
    public static final String SOURCE_MOVING_COLOR_TEST = "MOVING_COLOR_TEST";
    public static final String SOURCE_DECART = "DECART";
    private static final long SOURCE_READ_INTERVAL_MS = 250;
    private static final long SHARED_READ_INTERVAL_MS = 33;
    private static final long LIVE_FRAME_MAX_AGE_MS = 30000;
    private static final long SOURCE_PUBLISH_HEARTBEAT_MS = 5000;

    private static final SlotState[] SLOTS = new SlotState[MAX_SLOTS];
    private static volatile Context applicationContext;
    private static volatile String activeSource = SOURCE_DECART;
    private static volatile long lastSourcePublishMs;
    private static volatile VirtualFrame directDecartFrame;

    static {
        for (int i = 0; i < SLOTS.length; i++) {
            SLOTS[i] = new SlotState(i);
        }
    }

    private VirtualCameraBridge() {
    }

    public static void setApplicationContext(Context context) {
        if (context != null) {
            applicationContext = context.getApplicationContext();
        }
    }

    public static void setActiveSource(Context context, String source) {
        if (context != null) {
            setApplicationContext(context);
        }
        String normalized = normalizeSource(source);
        String previous = activeSource;
        long now = System.currentTimeMillis();
        activeSource = normalized;
        SlotState slot = slot(DEFAULT_SLOT);
        synchronized (slot) {
            slot.activeSource = normalized;
            slot.lastSourceReadMs = now;
        }
        boolean changed = !normalized.equals(previous);
        boolean heartbeat = now - lastSourcePublishMs >= SOURCE_PUBLISH_HEARTBEAT_MS;
        if (changed || heartbeat) {
            SharedFrameStore.publishSource(applicationContext, DEFAULT_SLOT, normalized);
            lastSourcePublishMs = now;
            Log.i(TAG_SOURCE, (changed ? "active_source_set" : "active_source_heartbeat")
                    + " slot=" + DEFAULT_SLOT + " source=" + normalized);
        }
    }

    public static String getActiveSource(int slotId) {
        return activeSource(slotId);
    }

    public static void ensureLocalSession(int slotId, int width, int height, VirtualCameraPreset preset) {
        SlotState slot = slot(slotId);
        synchronized (slot) {
            if (!slot.running) {
                slot.sessionToken = "local";
                slot.running = true;
                slot.counter = 0;
            }
            slot.width = Math.max(2, width);
            slot.height = Math.max(2, height);
            slot.preset = preset == null ? VirtualCameraPreset.DEFAULT : preset;
        }
    }

    public static void startSession(int slotId, String sessionToken, int width, int height, VirtualCameraPreset preset) {
        SlotState slot = slot(slotId);
        synchronized (slot) {
            slot.sessionToken = sessionToken;
            slot.width = Math.max(2, width);
            slot.height = Math.max(2, height);
            slot.preset = preset == null ? VirtualCameraPreset.DEFAULT : preset;
            slot.running = true;
            slot.latest = null;
            slot.counter = 0;
        }
    }

    public static void stopSession(int slotId, String sessionToken) {
        SlotState slot = slot(slotId);
        synchronized (slot) {
            if (tokenMatches(slot, sessionToken)) {
                slot.running = false;
                slot.latest = null;
                SharedFrameMemory.clear(applicationContext, slotId);
                SharedFrameStore.clear(applicationContext, slotId);
            }
        }
    }

    public static boolean publishNv21(int slotId, String sessionToken, byte[] nv21, int width, int height, long timestampNs) {
        if (nv21 == null || width <= 0 || height <= 0) {
            return false;
        }
        SlotState slot = slot(slotId);
        int safeSlotId = Math.max(0, Math.min(MAX_SLOTS - 1, slotId));
        synchronized (slot) {
            if (!slot.running) {
                slot.running = true;
                slot.sessionToken = sessionToken == null ? "local" : sessionToken;
            }
            if (!tokenMatches(slot, sessionToken)) {
                return false;
            }
            byte[] copy = new byte[Math.min(nv21.length, width * height * 3 / 2)];
            System.arraycopy(nv21, 0, copy, 0, copy.length);
            slot.width = width;
            slot.height = height;
            slot.latest = new VirtualFrame(copy, width, height, timestampNs, ++slot.counter);
        }
        SharedFrameMemory.publish(applicationContext, safeSlotId, nv21, width, height, timestampNs);
        return true;
    }

    /**
     * Publish a Decart frame directly into the in-memory store.
     * This bypasses SharedFrameStore file IPC so the Nv21SurfaceRenderer
     * (which runs the same render loop that proved green-test works) can
     * read Decart pixels without any disk round-trip.
     */
    public static void publishDecartDirect(byte[] nv21, int width, int height) {
        if (nv21 == null || width <= 0 || height <= 0) {
            return;
        }
        byte[] copy = new byte[Math.min(nv21.length, width * height * 3 / 2)];
        System.arraycopy(nv21, 0, copy, 0, copy.length);
        directDecartFrame = new VirtualFrame(copy, width, height, System.nanoTime(), System.currentTimeMillis());
        // Also update the persistent DecartFrameStore for the render loop
        DecartFrameStore.update(nv21, width, height);
    }

    public static VirtualFrame getDirectDecartFrame() {
        return directDecartFrame;
    }

    public static void clearDirectDecartFrame() {
        directDecartFrame = null;
        DecartFrameStore.clear();
    }

    public static boolean copyLatestNv21To(int slotId, byte[] output, int width, int height) {
        if (output == null || width <= 0 || height <= 0) {
            return false;
        }
        VirtualFrame frame = latestOrPlaceholder(slotId, width, height);
        byte[] source = frame.nv21;
        if (frame.width != width || frame.height != height) {
            source = Nv21Converter.resizeNv21(source, frame.width, frame.height, width, height);
        }
        System.arraycopy(source, 0, output, 0, Math.min(source.length, output.length));
        return true;
    }

    public static VirtualFrame latestOrPlaceholder(int slotId, int width, int height) {
        int safeWidth = Math.max(2, width);
        int safeHeight = Math.max(2, height);
        String source = activeSource(slotId);
        if (SOURCE_DECART.equals(source)) {
            // The renderer usually runs inside the cloned app process. Static
            // stores below are process-local, so the shared frame file is the
            // cross-process source of truth and must be polled before returning
            // a cached slot.latest.
            SlotState slot = slot(slotId);
            synchronized (slot) {
                refreshSharedFrameLocked(slot);
                if (slot.latest != null) {
                    return slot.latest;
                }
            }
            // Host-process fallback paths. These are useful for in-app preview,
            // but cloned-app rendering must not depend on them.
            VirtualFrame storeFrame = DecartFrameStore.latestFrame;
            if (storeFrame != null && storeFrame.nv21 != null) {
                return storeFrame;
            }
            VirtualFrame direct = directDecartFrame;
            if (direct != null && direct.nv21 != null) {
                return direct;
            }
            logWaitingForDecart(slotId, safeWidth, safeHeight);
            return new VirtualFrame(Nv21Converter.waitingForDecart(safeWidth, safeHeight), safeWidth, safeHeight, System.nanoTime(), System.currentTimeMillis());
        }
        if (SOURCE_MOVING_COLOR_TEST.equals(source)) {
            long now = System.currentTimeMillis();
            return new VirtualFrame(Nv21Converter.movingColorTest(safeWidth, safeHeight, now), safeWidth, safeHeight, System.nanoTime(), now);
        }
        logGreenTest(slotId, safeWidth, safeHeight);
        return new VirtualFrame(Nv21Converter.greenTest(safeWidth, safeHeight), safeWidth, safeHeight, System.nanoTime(), 0);
    }

    public static boolean hasLiveSource(int slotId) {
        String source = activeSource(slotId);
        if (SOURCE_DECART.equals(source) || SOURCE_MOVING_COLOR_TEST.equals(source) || SOURCE_GREEN_TEST.equals(source)) {
            return true;
        }
        return SharedFrameMemory.hasRecentFrame(applicationContext, slotId, LIVE_FRAME_MAX_AGE_MS)
                || SharedFrameMemory.hasRecentFrame(null, slotId, LIVE_FRAME_MAX_AGE_MS)
                || SharedFrameStore.hasRecentFrame(applicationContext, slotId, LIVE_FRAME_MAX_AGE_MS)
                || SharedFrameStore.hasRecentFrame(null, slotId, LIVE_FRAME_MAX_AGE_MS);
    }

    private static String activeSource(int slotId) {
        SlotState slot = slot(slotId);
        long now = System.currentTimeMillis();
        synchronized (slot) {
            if (now - slot.lastSourceReadMs >= SOURCE_READ_INTERVAL_MS) {
                String stored = SharedFrameStore.readSource(applicationContext, slotId);
                if (stored == null) {
                    stored = SharedFrameStore.readSource(null, slotId);
                }
                String normalized = normalizeSource(stored == null ? activeSource : stored);
                if (!normalized.equals(slot.activeSource)) {
                    Log.i(TAG_SOURCE, "active_source_loaded slot=" + slotId + " source=" + normalized);
                }
                slot.activeSource = normalized;
                activeSource = normalized;
                slot.lastSourceReadMs = now;
            }
            return normalizeSource(slot.activeSource == null ? activeSource : slot.activeSource);
        }
    }

    private static String normalizeSource(String source) {
        if (source == null) {
            return SOURCE_DECART;
        }
        String value = source.trim().toUpperCase(java.util.Locale.US);
        if (value.contains("DECART") || value.contains("CARTOON")) {
            return SOURCE_DECART;
        }
        if (value.contains("MOVING")) {
            return SOURCE_MOVING_COLOR_TEST;
        }
        return SOURCE_DECART;
    }

    private static void logGreenTest(int slotId, int width, int height) {
        SlotState slot = slot(slotId);
        long now = System.currentTimeMillis();
        if (now - slot.lastPlaceholderLogMs < 2000) {
            return;
        }
        slot.lastPlaceholderLogMs = now;
        Log.i(TAG_FRAME, "green_test_frame slot=" + slotId + " requested=" + width + "x" + height);
    }

    private static void logWaitingForDecart(int slotId, int width, int height) {
        SlotState slot = slot(slotId);
        long now = System.currentTimeMillis();
        if (now - slot.lastPlaceholderLogMs < 2000) {
            return;
        }
        slot.lastPlaceholderLogMs = now;
        Log.w(TAG_FRAME, "waiting_for_decart_frame slot=" + slotId + " requested=" + width + "x" + height);
    }

    private static void refreshSharedFrameLocked(SlotState slot) {
        long now = System.currentTimeMillis();
        if (now - slot.lastSharedReadMs < SHARED_READ_INTERVAL_MS) {
            return;
        }
        slot.lastSharedReadMs = now;

        VirtualFrame shared = SharedFrameMemory.readLatest(applicationContext, slot.slotId);
        if (shared == null || shared.nv21 == null) {
            VirtualFrame memoryFallback = SharedFrameMemory.readLatest(null, slot.slotId);
            if (memoryFallback != null && memoryFallback.nv21 != null) {
                shared = memoryFallback;
            }
        }
        if (shared == null || shared.nv21 == null) {
            shared = SharedFrameStore.readLatest(applicationContext, slot.slotId);
        }
        if (shared == null || shared.nv21 == null) {
            VirtualFrame fallback = SharedFrameStore.readLatest(null, slot.slotId);
            if (fallback != null && fallback.nv21 != null) {
                shared = fallback;
            }
        }

        if (shared == null || shared.nv21 == null) {
            maybeLogSharedRefresh(slot, "read_null", 0L, 0L, false);
            return;
        }

        boolean changed = slot.latest == null || shared.counter > slot.lastSharedFrameCounter;
        if (!changed) {
            maybeLogSharedRefresh(slot, "unchanged", shared.counter, shared.nv21.length, false);
            return;
        }

        slot.latest = shared;
        slot.width = shared.width;
        slot.height = shared.height;
        slot.running = true;
        slot.lastSharedFrameCounter = shared.counter;
        slot.counter = Math.max(slot.counter, shared.counter);
        maybeLogSharedRefresh(slot, "updated", shared.counter, shared.nv21.length, true);
    }

    private static void maybeLogSharedRefresh(SlotState slot, String state, long frameCounter, long length, boolean changed) {
        long now = System.currentTimeMillis();
        if (!changed && now - slot.lastSharedRefreshLogMs < 2000L) {
            return;
        }
        if (changed || now - slot.lastSharedRefreshLogMs >= 2000L) {
            slot.lastSharedRefreshLogMs = now;
            Log.d(TAG_FRAME, "shared_frame_refresh state=" + state
                    + " slot=" + slot.slotId
                    + " frameCounter=" + frameCounter
                    + " length=" + length
                    + " lastSharedCounter=" + slot.lastSharedFrameCounter
                    + " latestCounter=" + (slot.latest == null ? 0L : slot.latest.counter));
        }
    }

    private static SlotState slot(int slotId) {
        int safeSlot = Math.max(0, Math.min(MAX_SLOTS - 1, slotId));
        return SLOTS[safeSlot];
    }

    private static boolean tokenMatches(SlotState slot, String token) {
        return slot.sessionToken == null || token == null || "local".equals(slot.sessionToken) || slot.sessionToken.equals(token);
    }

    private static final class SlotState {
        final int slotId;
        String sessionToken;
        String activeSource = SOURCE_DECART;
        int width = 720;
        int height = 1280;
        VirtualCameraPreset preset = VirtualCameraPreset.DEFAULT;
        boolean running;
        long counter;
        VirtualFrame latest;
        long lastSharedReadMs;
        long lastSharedFrameCounter;
        long lastSharedRefreshLogMs;
        long lastSourceReadMs;
        long lastPlaceholderLogMs;

        SlotState(int slotId) {
            this.slotId = slotId;
        }
    }
}
