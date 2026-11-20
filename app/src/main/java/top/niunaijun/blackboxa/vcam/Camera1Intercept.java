package top.niunaijun.blackboxa.vcam;

import android.graphics.SurfaceTexture;
import android.util.Log;
import android.view.Surface;

import com.example.vcam.Nv21SurfaceRenderer;
import com.example.vcam.VirtualCameraBridge;

import java.util.IdentityHashMap;
import java.util.Map;

import top.niunaijun.blackbox.BlackBoxCore;

/**
 * Camera1 open path used by WhatsApp video calls. The native setup is replaced
 * before it can reach cameraserver, so a live virtual source is not evicted.
 */
public final class Camera1Intercept {
    private static final String TAG = "VCAM_OPEN";
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
            stopRenderer(state);
            state.surface = surface;
            if (surface != null && surface.isValid()) {
                state.renderer = Nv21SurfaceRenderer.start(
                        surface,
                        "camera1-" + state.cameraId,
                        state.width,
                        state.height
                );
            }
            Log.i(TAG, "path=Camera1." + (texture ? "setPreviewTexture" : "setPreviewSurface")
                    + " intercepted cameraId=" + state.cameraId
                    + " size=" + state.width + "x" + state.height
                    + " renderer=" + (state.renderer != null));
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
                + " renderer=" + (state.renderer != null)
                + " size=" + state.width + "x" + state.height);
    }

    public static void onStopPreview(Object camera) {
        State state = state(camera);
        if (state == null) {
            return;
        }
        state.previewing = false;
        stopRenderer(state);
        Log.i(TAG, "path=Camera1.stopPreview intercepted cameraId=" + state.cameraId);
    }

    public static void onRelease(Object camera) {
        State state;
        synchronized (CAMERAS) {
            state = CAMERAS.remove(camera);
        }
        if (state != null) {
            stopRenderer(state);
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

    private static void stopRenderer(State state) {
        if (state == null || state.renderer == null) {
            return;
        }
        try {
            state.renderer.stop();
        } catch (Throwable ignored) {
        }
        state.renderer = null;
    }

    private static void releaseSurface(State state) {
        if (state == null || state.surface == null) {
            return;
        }
        try {
            state.surface.release();
        } catch (Throwable ignored) {
        }
        state.surface = null;
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
        Surface surface;
        Nv21SurfaceRenderer renderer;
    }
}
