package top.niunaijun.blackboxa.vcam;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.Surface;

import com.example.vcam.VirtualCameraBridge;
import com.example.vcam.VirtualFrame;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public final class VirtualCameraSurfaceService extends Service {
    private static final String TAG = "VCAM_SURFACE_SERVICE";
    private static final Object LOCK = new Object();
    private static final Map<String, VirtualCameraSurfaceWorker> WORKERS = new HashMap<>();

    private final IVirtualCameraSurfaceRegistry.Stub binder = new IVirtualCameraSurfaceRegistry.Stub() {
        @Override
        public void registerSurface(String packageName,
                                    String processName,
                                    String cameraId,
                                    int sessionId,
                                    int streamId,
                                    Surface surface,
                                    int width,
                                    int height,
                                    int surfaceFormat) throws RemoteException {
            registerSurfaceInternal(packageName, processName, cameraId, sessionId, streamId, surface, width, height, surfaceFormat);
        }

        @Override
        public void unregisterStream(String packageName, int sessionId, int streamId) throws RemoteException {
            unregisterStreamInternal(packageName, sessionId, streamId);
        }

        @Override
        public void unregisterSession(String packageName, int sessionId) throws RemoteException {
            unregisterSessionInternal(packageName, sessionId);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public static void queueFrameProcessing(byte[] rawFrame, int width, int height) {
        if (rawFrame == null || width <= 0 || height <= 0) {
            return;
        }
        ArrayList<VirtualCameraSurfaceWorker> snapshot;
        synchronized (LOCK) {
            if (WORKERS.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<>(WORKERS.values());
        }
        for (VirtualCameraSurfaceWorker worker : snapshot) {
            try {
                worker.queueFrameProcessing(rawFrame, width, height);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void registerSurfaceInternal(String packageName,
                                                String processName,
                                                String cameraId,
                                                int sessionId,
                                                int streamId,
                                                Surface surface,
                                                int width,
                                                int height,
                                                int surfaceFormat) {
        if (surface == null || !surface.isValid()) {
            return;
        }
        String key = key(packageName, sessionId, streamId);
        VirtualCameraSurfaceWorker previous;
        VirtualCameraSurfaceWorker worker = new VirtualCameraSurfaceWorker(
                surface,
                safe(packageName) + "-" + safe(cameraId) + "-" + sessionId + "-" + streamId,
                width,
                height,
                surfaceFormat
        );
        synchronized (LOCK) {
            previous = WORKERS.put(key, worker);
        }
        releaseWorker(previous);
        try {
            VirtualFrame latest = VirtualCameraBridge.latestOrPlaceholder(
                    VirtualCameraBridge.DEFAULT_SLOT,
                    Math.max(2, width),
                    Math.max(2, height)
            );
            if (latest != null && latest.nv21 != null) {
                worker.queueFrameProcessing(latest.nv21, latest.width, latest.height);
            }
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "surface_registered package=" + packageName
                + " process=" + processName
                + " session=" + sessionId
                + " stream=" + streamId
                + " size=" + width + "x" + height
                + " format=" + surfaceFormat);
    }

    private static void unregisterStreamInternal(String packageName, int sessionId, int streamId) {
        VirtualCameraSurfaceWorker worker;
        synchronized (LOCK) {
            worker = WORKERS.remove(key(packageName, sessionId, streamId));
        }
        releaseWorker(worker);
    }

    private static void unregisterSessionInternal(String packageName, int sessionId) {
        ArrayList<VirtualCameraSurfaceWorker> removed = new ArrayList<>();
        String prefix = safe(packageName) + ":" + sessionId + ":";
        synchronized (LOCK) {
            ArrayList<String> keys = new ArrayList<>(WORKERS.keySet());
            for (String key : keys) {
                if (key.startsWith(prefix)) {
                    removed.add(WORKERS.remove(key));
                }
            }
        }
        for (VirtualCameraSurfaceWorker worker : removed) {
            releaseWorker(worker);
        }
    }

    private static void releaseWorker(VirtualCameraSurfaceWorker worker) {
        try {
            if (worker != null) {
                worker.release();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String key(String packageName, int sessionId, int streamId) {
        return safe(packageName) + ":" + sessionId + ":" + streamId;
    }

    private static String safe(String value) {
        return value == null ? "unknown" : value;
    }
}
