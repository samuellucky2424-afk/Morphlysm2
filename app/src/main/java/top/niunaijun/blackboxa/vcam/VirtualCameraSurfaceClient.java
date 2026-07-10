package top.niunaijun.blackboxa.vcam;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;

public final class VirtualCameraSurfaceClient {
    private static final String HOST_PACKAGE = "top.niunaijun.blackbox";
    private static final String SERVICE_CLASS = "top.niunaijun.blackboxa.vcam.VirtualCameraSurfaceService";
    private static final Object LOCK = new Object();
    private static final List<SurfaceRegistration> PENDING = new ArrayList<>();

    private static IVirtualCameraSurfaceRegistry registry;
    private static boolean binding;
    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (LOCK) {
                registry = IVirtualCameraSurfaceRegistry.Stub.asInterface(service);
                binding = false;
                flushPendingLocked();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (LOCK) {
                registry = null;
                binding = false;
            }
        }
    };

    private VirtualCameraSurfaceClient() {
    }

    public static boolean registerSurface(Context context,
                                          String packageName,
                                          String processName,
                                          String cameraId,
                                          int sessionId,
                                          int streamId,
                                          Surface surface,
                                          int width,
                                          int height,
                                          int surfaceFormat) {
        if (surface == null || !surface.isValid()) {
            return false;
        }
        synchronized (LOCK) {
            if (registry != null && transactRegisterLocked(packageName, processName, cameraId, sessionId, streamId, surface, width, height, surfaceFormat)) {
                return true;
            }
            SurfaceRegistration registration = new SurfaceRegistration(packageName, processName, cameraId, sessionId, streamId, surface, width, height, surfaceFormat);
            PENDING.add(registration);
            boolean bound = ensureBoundLocked(context);
            if (!bound) {
                PENDING.remove(registration);
            }
            return bound;
        }
    }

    public static void unregisterStream(Context context, String packageName, int sessionId, int streamId) {
        synchronized (LOCK) {
            removePendingLocked(packageName, sessionId, streamId);
            if (registry != null) {
                try {
                    registry.unregisterStream(packageName, sessionId, streamId);
                } catch (Throwable ignored) {
                    registry = null;
                    ensureBoundLocked(context);
                }
            }
        }
    }

    public static void unregisterSession(Context context, String packageName, int sessionId) {
        synchronized (LOCK) {
            removePendingSessionLocked(packageName, sessionId);
            if (registry != null) {
                try {
                    registry.unregisterSession(packageName, sessionId);
                } catch (Throwable ignored) {
                    registry = null;
                    ensureBoundLocked(context);
                }
            }
        }
    }

    private static boolean ensureBoundLocked(Context context) {
        if (registry != null || binding) {
            return true;
        }
        Context appContext = context == null ? null : context.getApplicationContext();
        if (appContext == null && context != null) {
            appContext = context;
        }
        if (appContext == null) {
            return false;
        }
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(HOST_PACKAGE, SERVICE_CLASS));
            boolean bound = appContext.bindService(intent, CONNECTION, Context.BIND_AUTO_CREATE);
            if (bound) {
                binding = true;
            }
            return bound;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void flushPendingLocked() {
        if (registry == null || PENDING.isEmpty()) {
            return;
        }
        ArrayList<SurfaceRegistration> pending = new ArrayList<>(PENDING);
        PENDING.clear();
        for (SurfaceRegistration registration : pending) {
            if (!transactRegisterLocked(
                    registration.packageName,
                    registration.processName,
                    registration.cameraId,
                    registration.sessionId,
                    registration.streamId,
                    registration.surface,
                    registration.width,
                    registration.height,
                    registration.surfaceFormat)) {
                PENDING.add(registration);
            }
        }
    }

    private static boolean transactRegisterLocked(String packageName,
                                                  String processName,
                                                  String cameraId,
                                                  int sessionId,
                                                  int streamId,
                                                  Surface surface,
                                                  int width,
                                                  int height,
                                                  int surfaceFormat) {
        try {
            registry.registerSurface(packageName, processName, cameraId, sessionId, streamId, surface, width, height, surfaceFormat);
            return true;
        } catch (Throwable ignored) {
            registry = null;
            return false;
        }
    }

    private static void removePendingLocked(String packageName, int sessionId, int streamId) {
        for (int i = PENDING.size() - 1; i >= 0; i--) {
            SurfaceRegistration registration = PENDING.get(i);
            if (same(registration.packageName, packageName)
                    && registration.sessionId == sessionId
                    && registration.streamId == streamId) {
                PENDING.remove(i);
            }
        }
    }

    private static void removePendingSessionLocked(String packageName, int sessionId) {
        for (int i = PENDING.size() - 1; i >= 0; i--) {
            SurfaceRegistration registration = PENDING.get(i);
            if (same(registration.packageName, packageName) && registration.sessionId == sessionId) {
                PENDING.remove(i);
            }
        }
    }

    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static final class SurfaceRegistration {
        final String packageName;
        final String processName;
        final String cameraId;
        final int sessionId;
        final int streamId;
        final Surface surface;
        final int width;
        final int height;
        final int surfaceFormat;

        SurfaceRegistration(String packageName,
                            String processName,
                            String cameraId,
                            int sessionId,
                            int streamId,
                            Surface surface,
                            int width,
                            int height,
                            int surfaceFormat) {
            this.packageName = packageName;
            this.processName = processName;
            this.cameraId = cameraId;
            this.sessionId = sessionId;
            this.streamId = streamId;
            this.surface = surface;
            this.width = width;
            this.height = height;
            this.surfaceFormat = surfaceFormat;
        }
    }
}
