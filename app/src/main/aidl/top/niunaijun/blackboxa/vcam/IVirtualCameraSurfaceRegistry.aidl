package top.niunaijun.blackboxa.vcam;

import android.view.Surface;

interface IVirtualCameraSurfaceRegistry {
    void registerSurface(
            String packageName,
            String processName,
            String cameraId,
            int sessionId,
            int streamId,
            in Surface surface,
            int width,
            int height,
            int surfaceFormat);

    void unregisterStream(String packageName, int sessionId, int streamId);

    void unregisterSession(String packageName, int sessionId);
}
