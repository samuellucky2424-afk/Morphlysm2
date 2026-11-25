package top.niunaijun.blackboxa.vcam;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import com.example.vcam.Nv21Converter;
import com.example.vcam.VirtualCameraBridge;
import com.example.vcam.VirtualFrame;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;

import top.niunaijun.blackbox.BlackBoxCore;

/**
 * Camera1 open path used by WhatsApp video calls. The native setup is replaced
 * before it can reach cameraserver, so a live virtual source is not evicted.
 */
public final class Camera1Intercept {
    private static final String TAG = "VCAM_OPEN";
    private static final int PREVIEW_MSG = 0x10;
    private static final long FRAME_INTERVAL_MS = 33L;
    private static final int FRONT_SENSOR_ORIENTATION = 270;
    private static final int BACK_SENSOR_ORIENTATION = 90;
    private static final Map<Object, State> CAMERAS = new IdentityHashMap<>();

    private Camera1Intercept() {
    }

    public static boolean interceptNativeSetup(Object camera, int cameraId) {
        boolean blackProcess = false;
        String packageName = null;
        boolean live = false;
        try {
            blackProcess = BlackBoxCore.get().isBlackProcess();
            packageName = BlackBoxCore.getAppPackageName();
            live = VirtualCameraBridge.hasLiveSource(VirtualCameraBridge.DEFAULT_SLOT);
        } catch (Throwable throwable) {
            Log.w(TAG, "path=Camera1.native_setup state_unavailable", throwable);
        }
        Log.i(TAG, "path=Camera1.native_setup package=" + packageName
                + " cameraId=" + cameraId
                + " blackProcess=" + blackProcess
                + " live=" + live);
        if (!blackProcess || !live || camera == null) {
            Log.i(TAG, "path=Camera1.native_setup real_fallback package=" + packageName
                    + " cameraId=" + cameraId);
            return false;
        }
        State state = new State();
        state.cameraId = cameraId;
        state.parameters = defaultParameters();
        synchronized (CAMERAS) {
            CAMERAS.put(camera, state);
        }
        Log.i(TAG, "path=Camera1.native_setup intercepted package=" + packageName
                + " cameraId=" + cameraId);
        return true;
    }

    public static boolean isVirtual(Object camera) {
        if (camera == null) {
            return false;
        }
        synchronized (CAMERAS) {
            return CAMERAS.containsKey(camera);
        }
    }

    public static void onPreviewTarget(Object camera, Object target, boolean texture) {
        State state = state(camera);
        if (state == null) {
            return;
        }
        Surface surface = null;
        try {
            if (texture && target instanceof SurfaceTexture) {
                surface = new Surface((SurfaceTexture) target);
            } else if (target instanceof Surface) {
                surface = (Surface) target;
            }
            releaseSurface(state);
            state.ownsSurface = texture;
            state.surface = surface;
            Log.i(TAG, "path=Camera1." + (texture ? "setPreviewTexture" : "setPreviewSurface")
                    + " intercepted cameraId=" + state.cameraId
                    + " size=" + state.width + "x" + state.height
                    + " sensorOrientation=" + sensorOrientation(state.cameraId));
            ensurePump(state, camera);
        } catch (Throwable throwable) {
            Log.e(TAG, "path=Camera1.preview_target_failed cameraId=" + state.cameraId, throwable);
        }
    }

    public static void onStartPreview(Object camera) {
        State state = state(camera);
        if (state == null) {
            return;
        }
        state.previewing = true;
        Log.i(TAG, "path=Camera1.startPreview intercepted cameraId=" + state.cameraId
                + " size=" + state.width + "x" + state.height
                + " sensorOrientation=" + sensorOrientation(state.cameraId)
                + " displayOrientation=" + state.displayOrientation);
        ensurePump(state, camera);
    }

    public static void onDisplayOrientation(Object camera, int degrees) {
        State state = state(camera);
        if (state == null) {
            return;
        }
        state.displayOrientation = degrees;
        Log.i(TAG, "path=Camera1.setDisplayOrientation stored cameraId=" + state.cameraId
                + " degrees=" + degrees
                + " sensorOrientation=" + sensorOrientation(state.cameraId)
                + " facing=" + (isFront(state.cameraId) ? "front" : "back"));
    }

    public static boolean fillCameraInfo(int cameraId, Object info) {
        if (info == null || !isCloneProcess() || (cameraId != 0 && cameraId != 1)) {
            return false;
        }
        boolean front = isFront(cameraId);
        int orientation = front ? FRONT_SENSOR_ORIENTATION : BACK_SENSOR_ORIENTATION;
        try {
            Field facing = info.getClass().getField("facing");
            Field orientationField = info.getClass().getField("orientation");
            facing.setInt(info, front ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK);
            orientationField.setInt(info, orientation);
            try {
                Field shutter = info.getClass().getField("canDisableShutterSound");
                shutter.setBoolean(info, true);
            } catch (Throwable ignored) {
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "path=Camera1.getCameraInfo failed cameraId=" + cameraId, throwable);
            return false;
        }
        Log.i(TAG, "path=Camera1.getCameraInfo cameraId=" + cameraId
                + " facing=" + (front ? "front" : "back")
                + " orientation=" + orientation);
        return true;
    }

    public static void onHasPreviewCallback(Object camera, boolean installed, boolean manualBuffer) {
        State state = state(camera);
        if (state == null) {
            return;
        }
        state.callbackInstalled = installed;
        state.manualBuffer = manualBuffer;
        if (!installed) {
            synchronized (state.buffers) {
                state.buffers.clear();
            }
        }
        Log.i(TAG, "path=Camera1.previewCallback cameraId=" + state.cameraId
                + " installed=" + installed
                + " manualBuffer=" + manualBuffer
                + " size=" + state.width + "x" + state.height);
        if (installed) {
            ensurePump(state, camera);
        }
    }

    public static void onAddCallbackBuffer(Object camera, byte[] buffer, int msgType) {
        State state = state(camera);
        if (state == null || buffer == null || msgType != PREVIEW_MSG) {
            return;
        }
        synchronized (state.buffers) {
            state.buffers.addLast(buffer);
        }
    }

    public static void onStopPreview(Object camera) {
        State state = state(camera);
        if (state == null) {
            return;
        }
        state.previewing = false;
        stopPump(state);
        Log.i(TAG, "path=Camera1.stopPreview intercepted cameraId=" + state.cameraId);
    }

    public static void onRelease(Object camera) {
        State state;
        synchronized (CAMERAS) {
            state = CAMERAS.remove(camera);
        }
        if (state != null) {
            stopPump(state);
            releaseSurface(state);
            Log.i(TAG, "path=Camera1.release intercepted cameraId=" + state.cameraId);
        }
    }

    public static String onGetParameters(Object camera) {
        State state = state(camera);
        if (state == null || state.parameters == null) {
            return defaultParameters();
        }
        return state.parameters;
    }

    public static void onSetParameters(Object camera, String parameters) {
        State state = state(camera);
        if (state == null) {
            return;
        }
        if (parameters != null && !parameters.isEmpty()) {
            state.parameters = parameters;
            int[] size = previewSize(parameters);
            if (size != null) {
                state.width = size[0];
                state.height = size[1];
            }
        }
        Log.i(TAG, "path=Camera1.setParameters intercepted cameraId=" + state.cameraId
                + " size=" + state.width + "x" + state.height);
    }

    private static State state(Object camera) {
        synchronized (CAMERAS) {
            return CAMERAS.get(camera);
        }
    }

    private static void ensurePump(State state, Object camera) {
        if (state.pump != null) {
            return;
        }
        state.pump = new Pump(camera);
        Thread thread = new Thread(state.pump, "vcam-camera1-" + state.cameraId);
        thread.setDaemon(true);
        state.pumpThread = thread;
        thread.start();
    }

    private static void stopPump(State state) {
        if (state == null || state.pump == null) {
            return;
        }
        state.pump.running = false;
        state.pump = null;
        state.pumpThread = null;
    }

    private static boolean isCloneProcess() {
        try {
            return BlackBoxCore.get().isBlackProcess();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isFront(int cameraId) {
        return cameraId == 1;
    }

    static int sensorOrientation(int cameraId) {
        return isFront(cameraId) ? FRONT_SENSOR_ORIENTATION : BACK_SENSOR_ORIENTATION;
    }

    private static void deliverFrame(Object camera, State state) {
        int width = Math.max(2, state.width & ~1);
        int height = Math.max(2, state.height & ~1);
        byte[] nv21 = sensorFrame(state.cameraId, width, height);
        drawPreview(state, nv21, width, height);
        if (!state.callbackInstalled || !state.previewing) {
            return;
        }
        Object callback = previewCallback(camera);
        if (callback == null) {
            return;
        }
        boolean withBuffer = booleanField(camera, "mWithBuffer");
        boolean oneShot = booleanField(camera, "mOneShot");
        byte[] payload;
        if (withBuffer) {
            byte[] supplied;
            synchronized (state.buffers) {
                supplied = state.buffers.pollFirst();
            }
            int needed = width * height * 3 / 2;
            if (supplied == null || supplied.length < needed) {
                if (state.shortBufferLogs < 3) {
                    state.shortBufferLogs++;
                    Log.w(TAG, "path=Camera1.onPreviewFrame waiting_buffer cameraId=" + state.cameraId
                            + " needed=" + needed
                            + " supplied=" + (supplied == null ? 0 : supplied.length));
                }
                return;
            }
            System.arraycopy(nv21, 0, supplied, 0, needed);
            payload = supplied;
        } else {
            payload = nv21;
        }
        Runnable invoke = () -> {
            try {
                Method method = callback.getClass().getMethod("onPreviewFrame", byte[].class, Camera.class);
                method.invoke(callback, payload, camera);
            } catch (Throwable throwable) {
                Log.w(TAG, "path=Camera1.onPreviewFrame invoke_failed cameraId=" + state.cameraId, throwable);
            }
        };
        Handler handler = eventHandler(camera);
        if (handler != null) {
            handler.post(invoke);
        } else {
            invoke.run();
        }
        state.delivered++;
        if (state.delivered <= 3 || state.delivered % 30 == 0) {
            Log.i(TAG, "path=Camera1.onPreviewFrame delivered count=" + state.delivered
                    + " cameraId=" + state.cameraId
                    + " size=" + width + "x" + height
                    + " format=NV21"
                    + " withBuffer=" + withBuffer
                    + " sensorOrientation=" + sensorOrientation(state.cameraId)
                    + " displayOrientation=" + state.displayOrientation);
        }
        if (oneShot) {
            state.callbackInstalled = false;
            try {
                Field field = camera.getClass().getDeclaredField("mPreviewCallback");
                field.setAccessible(true);
                field.set(camera, null);
            } catch (Throwable ignored) {
            }
        }
    }

    private static byte[] sensorFrame(int cameraId, int width, int height) {
        VirtualFrame source = VirtualCameraBridge.latestOrPlaceholder(VirtualCameraBridge.DEFAULT_SLOT, width, height);
        byte[] nv21 = source == null ? null : source.nv21;
        int sourceWidth = source == null ? width : source.width;
        int sourceHeight = source == null ? height : source.height;
        if (nv21 == null) {
            nv21 = Nv21Converter.waitingForDecart(sourceWidth, sourceHeight);
        }
        int rotation = (360 - sensorOrientation(cameraId)) % 360;
        if (rotation == 90 || rotation == 270) {
            nv21 = Nv21Converter.rotateNv21(nv21, sourceWidth, sourceHeight, rotation);
            int swapped = sourceWidth;
            sourceWidth = sourceHeight;
            sourceHeight = swapped;
        }
        return coverNv21(nv21, sourceWidth, sourceHeight, width, height);
    }

    private static byte[] coverNv21(byte[] source, int sourceWidth, int sourceHeight, int width, int height) {
        if (sourceWidth == width && sourceHeight == height) {
            return source;
        }
        float scale = Math.max(width / (float) sourceWidth, height / (float) sourceHeight);
        int scaledWidth = Math.max(width, (Math.round(sourceWidth * scale) + 1) & ~1);
        int scaledHeight = Math.max(height, (Math.round(sourceHeight * scale) + 1) & ~1);
        byte[] scaled = Nv21Converter.resizeNv21(source, sourceWidth, sourceHeight, scaledWidth, scaledHeight);
        if (scaledWidth == width && scaledHeight == height) {
            return scaled;
        }
        byte[] output = new byte[width * height * 3 / 2];
        int cropX = Math.max(0, (scaledWidth - width) / 2) & ~1;
        int cropY = Math.max(0, (scaledHeight - height) / 2) & ~1;
        int sourceFrame = scaledWidth * scaledHeight;
        int outputFrame = width * height;
        for (int y = 0; y < height; y++) {
            System.arraycopy(scaled, (cropY + y) * scaledWidth + cropX, output, y * width, width);
        }
        for (int y = 0; y < height / 2; y++) {
            System.arraycopy(
                    scaled,
                    sourceFrame + (cropY / 2 + y) * scaledWidth + cropX,
                    output,
                    outputFrame + y * width,
                    width
            );
        }
        return output;
    }

    private static void drawPreview(State state, byte[] nv21, int width, int height) {
        Surface surface = state.surface;
        if (surface == null || !surface.isValid()) {
            return;
        }
        Bitmap bitmap = null;
        Canvas canvas = null;
        try {
            bitmap = Nv21Converter.nv21ToBitmap(nv21, width, height);
            canvas = surface.lockCanvas(null);
            if (canvas == null) {
                return;
            }
            canvas.drawColor(Color.BLACK);
            canvas.drawBitmap(bitmap, null, new Rect(0, 0, canvas.getWidth(), canvas.getHeight()), null);
        } catch (IllegalStateException released) {
            Log.w(TAG, "path=Camera1.preview_surface_released cameraId=" + state.cameraId);
            state.surface = null;
        } catch (Throwable throwable) {
            Log.w(TAG, "path=Camera1.preview_draw_failed cameraId=" + state.cameraId
                    + " error=" + throwable.getClass().getSimpleName());
        } finally {
            if (canvas != null) {
                try {
                    surface.unlockCanvasAndPost(canvas);
                } catch (Throwable ignored) {
                }
            }
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    private static Object previewCallback(Object camera) {
        try {
            Field field = camera.getClass().getDeclaredField("mPreviewCallback");
            field.setAccessible(true);
            return field.get(camera);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean booleanField(Object camera, String name) {
        try {
            Field field = camera.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getBoolean(camera);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Handler eventHandler(Object camera) {
        try {
            Field field = camera.getClass().getDeclaredField("mEventHandler");
            field.setAccessible(true);
            Object value = field.get(camera);
            return value instanceof Handler ? (Handler) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void releaseSurface(State state) {
        if (state == null || state.surface == null) {
            return;
        }
        if (state.ownsSurface) {
            try {
                state.surface.release();
            } catch (Throwable ignored) {
            }
        }
        state.surface = null;
    }

    private static final class Pump implements Runnable {
        private final Object camera;
        volatile boolean running = true;

        Pump(Object camera) {
            this.camera = camera;
        }

        @Override
        public void run() {
            while (running) {
                long started = SystemClock.uptimeMillis();
                State state = state(camera);
                if (state == null) {
                    return;
                }
                try {
                    deliverFrame(camera, state);
                } catch (Throwable throwable) {
                    Log.w(TAG, "path=Camera1.frame_loop_failed", throwable);
                }
                long sleep = FRAME_INTERVAL_MS - (SystemClock.uptimeMillis() - started);
                if (sleep > 0) {
                    try {
                        Thread.sleep(sleep);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private static int[] previewSize(String parameters) {
        int index = parameters.indexOf("preview-size=");
        if (index < 0) {
            return null;
        }
        int start = index + "preview-size=".length();
        int end = parameters.indexOf(';', start);
        if (end < 0) {
            end = parameters.length();
        }
        String value = parameters.substring(start, end);
        int split = value.indexOf('x');
        if (split <= 0) {
            return null;
        }
        try {
            int width = Integer.parseInt(value.substring(0, split));
            int height = Integer.parseInt(value.substring(split + 1));
            if (width >= 2 && height >= 2) {
                return new int[]{width, height};
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private static String defaultParameters() {
        return "preview-size-values=1280x720,640x480,320x240;"
                + "preview-size=640x480;"
                + "preview-format-values=yuv420sp;"
                + "preview-format=yuv420sp;"
                + "preview-frame-rate-values=15,30;"
                + "preview-frame-rate=30;"
                + "preview-fps-range-values=(15000,30000);"
                + "preview-fps-range=15000,30000;"
                + "picture-size-values=1280x720,640x480;"
                + "picture-size=640x480;"
                + "picture-format-values=jpeg;"
                + "picture-format=jpeg;"
                + "jpeg-thumbnail-width=0;"
                + "jpeg-thumbnail-height=0;"
                + "jpeg-thumbnail-size-values=0x0;"
                + "jpeg-quality=90;"
                + "focus-mode-values=infinity,fixed;"
                + "focus-mode=fixed;"
                + "flash-mode-values=off;"
                + "flash-mode=off;"
                + "whitebalance-values=auto;"
                + "whitebalance=auto;"
                + "antibanding-values=auto,off;"
                + "antibanding=auto;"
                + "scene-mode-values=auto;"
                + "scene-mode=auto;"
                + "zoom=0;"
                + "max-zoom=0;"
                + "zoom-supported=false;"
                + "smooth-zoom-supported=false;"
                + "video-size-values=1280x720,640x480;"
                + "video-size=640x480;"
                + "preferred-preview-size-for-video=640x480;"
                + "recording-hint=false;"
                + "video-stabilization-supported=false;"
                + "auto-whitebalance-lock-supported=false;"
                + "auto-exposure-lock-supported=false;"
                + "exposure-compensation=0;"
                + "max-exposure-compensation=0;"
                + "min-exposure-compensation=0;"
                + "exposure-compensation-step=0;"
                + "horizontal-view-angle=60;"
                + "vertical-view-angle=45;";
    }

    private static final class State {
        int cameraId;
        int width = 640;
        int height = 480;
        String parameters;
        boolean previewing;
        boolean ownsSurface;
        boolean callbackInstalled;
        boolean manualBuffer;
        int displayOrientation = 90;
        int delivered;
        int shortBufferLogs;
        Surface surface;
        Pump pump;
        Thread pumpThread;
        final ArrayDeque<byte[]> buffers = new ArrayDeque<>();
    }
}
