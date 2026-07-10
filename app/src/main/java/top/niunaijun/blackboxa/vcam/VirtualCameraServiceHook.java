package top.niunaijun.blackboxa.vcam;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.example.vcam.Nv21SurfaceRenderer;
import com.example.vcam.SharedFrameMemory;
import com.example.vcam.VirtualCameraBridge;
import com.example.vcam.VirtualCameraPreset;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class VirtualCameraServiceHook {
    private static final String TAG = "VCAM_SERVICE";
    private static final String TAG_CALL = "VCAM_CALL_PROBE";
    private static final String CAMERA_SERVICE_NAME = "media.camera";
    private static final int HAL_PIXEL_FORMAT_BLOB = 0x21;
    private static final int DEFAULT_FRAME_DURATION_NS = 33333333;
    private static final long CAMERA_KEEP_ALIVE_MS = 120000L;
    private static final long CAMERA_DISCONNECT_GRACE_MS = 10000L;
    private static volatile boolean rehookLoopStarted;
    private static int nextCameraSessionId;
    private static final Map<String, Long> cameraKeepAliveUntil = new HashMap<>();

    private VirtualCameraServiceHook() {
    }

    public static void install(Context context, String packageName, String processName) {
        try {
            if (!isTargetCameraPackage(packageName)) {
                return;
            }
            if (context != null) {
                VirtualCameraBridge.setApplicationContext(context);
            }
            VirtualCameraThread.ensureStarted();
            SharedFrameMemory.prepare(
                    context,
                    VirtualCameraBridge.DEFAULT_SLOT,
                    VirtualCameraPreset.LOW.width,
                    VirtualCameraPreset.LOW.height
            );
            VirtualCameraBridge.ensureLocalSession(
                    VirtualCameraBridge.DEFAULT_SLOT,
                    VirtualCameraPreset.LOW.width,
                    VirtualCameraPreset.LOW.height,
                    VirtualCameraPreset.LOW
            );
            Class<?> globalClass = Class.forName("android.hardware.camera2.CameraManager$CameraManagerGlobal");
            Method getMethod = findMethod(globalClass, "get");
            Object global = getMethod.invoke(null);
            if (global == null) {
                Log.w(TAG, "CameraManagerGlobal.get returned null for " + packageName);
                return;
            }
            seedCameraIds(global, packageName);

            Field serviceField = findField(global.getClass(), "mCameraService");
            IBinder serviceBinder = loadCameraServiceBinder();
            Object currentService = serviceField.get(global);
            if (isVirtualCameraProxy(currentService)) {
                return;
            }
            Object baseService = unwrapBaseService(currentService);
            if (baseService == null) {
                baseService = loadCameraServiceFromServiceManager(serviceBinder);
            }
            if (baseService == null) {
                Log.w(TAG, "Camera service unavailable for " + packageName);
                return;
            }

            Class<?> cameraServiceInterface = Class.forName("android.hardware.ICameraService");
            Object proxy = Proxy.newProxyInstance(
                    cameraServiceInterface.getClassLoader(),
                    new Class[]{cameraServiceInterface},
                    new CameraServiceHandler(baseService, context, packageName, processName)
            );
            serviceField.set(global, proxy);
            replaceServiceManagerCache(serviceBinder, (IInterface) proxy);
            Log.i(TAG, "Installed Camera2 virtual service hook for " + packageName + "/" + processName);
            logCallProbe(packageName, processName, 0, "service_hook_installed", "context=" + safeContextPackage(context));
            startRehookLoop(context, packageName, processName);
        } catch (Throwable throwable) {
            Log.e(TAG, "Failed to install virtual camera service hook for " + packageName, throwable);
        }
    }

    @SuppressWarnings("unchecked")
    private static void seedCameraIds(Object global, String packageName) {
        try {
            Field deviceStatusField = findField(global.getClass(), "mDeviceStatus");
            Object deviceStatus = deviceStatusField.get(global);
            if (deviceStatus instanceof Map) {
                Map<Object, Object> map = (Map<Object, Object>) deviceStatus;
                Object previousBack = map.put("0", 1);
                Object previousFront = map.put("1", 1);
                Log.i(TAG, "Seeded CameraManager device status for " + packageName
                        + " size=" + map.size()
                        + " previous0=" + previousBack
                        + " previous1=" + previousFront);
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to seed CameraManager device status for " + packageName, throwable);
        }
    }

    private static void startRehookLoop(Context context, String packageName, String processName) {
        if (rehookLoopStarted) {
            return;
        }
        rehookLoopStarted = true;
        Context appContext = context == null ? null : context.getApplicationContext();
        Thread thread = new Thread(() -> {
            for (int i = 0; i < 12; i++) {
                try {
                    Thread.sleep(500L);
                    install(appContext, packageName, processName);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable throwable) {
                    Log.w(TAG, "Rehook attempt failed for " + packageName, throwable);
                }
            }
        }, "smokescreen-vcam-rehook");
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean isTargetCameraPackage(String packageName) {
        if (packageName == null) {
            return false;
        }
        return "com.whatsapp".equals(packageName)
                || "com.whatsapp.w4b".equals(packageName)
                || "org.telegram.messenger".equals(packageName)
                || "org.telegram.messenger.web".equals(packageName)
                || "com.instagram.android".equals(packageName)
                || "com.zhiliaoapp.musically".equals(packageName)
                || "com.ss.android.ugc.trill".equals(packageName);
    }

    public static boolean shouldKeepGuestResumed(String packageName) {
        if (!isTargetCameraPackage(packageName)) {
            return false;
        }
        long now = System.currentTimeMillis();
        synchronized (cameraKeepAliveUntil) {
            Long keepAliveUntil = cameraKeepAliveUntil.get(packageName);
            if (keepAliveUntil == null || keepAliveUntil <= now) {
                cameraKeepAliveUntil.remove(packageName);
                return false;
            }
            return true;
        }
    }

    private static void markCameraActive(String packageName, String processName, String reason) {
        markCameraActiveFor(packageName, CAMERA_KEEP_ALIVE_MS);
    }

    private static void markCameraDisconnectGrace(String packageName) {
        markCameraActiveFor(packageName, CAMERA_DISCONNECT_GRACE_MS);
    }

    private static void markCameraActiveFor(String packageName, long durationMs) {
        if (!isTargetCameraPackage(packageName)) {
            return;
        }
        long until = System.currentTimeMillis() + Math.max(1000L, durationMs);
        synchronized (cameraKeepAliveUntil) {
            cameraKeepAliveUntil.put(packageName, until);
        }
    }

    private static Object unwrapBaseService(Object service) {
        if (service == null) {
            return null;
        }
        if (Proxy.isProxyClass(service.getClass())) {
            InvocationHandler handler = Proxy.getInvocationHandler(service);
            if (handler instanceof CameraServiceHandler) {
                return ((CameraServiceHandler) handler).baseService;
            }
        }
        return service;
    }

    private static boolean isVirtualCameraProxy(Object service) {
        if (service == null || !Proxy.isProxyClass(service.getClass())) {
            return false;
        }
        try {
            return Proxy.getInvocationHandler(service) instanceof CameraServiceHandler;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static IBinder loadCameraServiceBinder() {
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method getService = serviceManager.getDeclaredMethod("getService", String.class);
            getService.setAccessible(true);
            IBinder binder = (IBinder) getService.invoke(null, CAMERA_SERVICE_NAME);
            if (binder instanceof CameraServiceBinder) {
                return ((CameraServiceBinder) binder).baseBinder;
            }
            return binder;
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to load camera service binder from ServiceManager", throwable);
            return null;
        }
    }

    private static Object loadCameraServiceFromServiceManager(IBinder binder) {
        try {
            if (binder == null) {
                binder = loadCameraServiceBinder();
            }
            if (binder == null) {
                return null;
            }
            Class<?> stub = Class.forName("android.hardware.ICameraService$Stub");
            Method asInterface = stub.getDeclaredMethod("asInterface", IBinder.class);
            asInterface.setAccessible(true);
            return asInterface.invoke(null, binder);
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to load ICameraService from ServiceManager", throwable);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static void replaceServiceManagerCache(IBinder baseBinder, IInterface proxyService) {
        if (baseBinder == null || proxyService == null) {
            return;
        }
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Field cacheField = findField(serviceManager, "sCache");
            Object cache = cacheField.get(null);
            if (cache instanceof Map) {
                ((Map<String, IBinder>) cache).put(CAMERA_SERVICE_NAME, new CameraServiceBinder(baseBinder, proxyService));
                Log.i(TAG, "Replaced ServiceManager cache for " + CAMERA_SERVICE_NAME);
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to replace ServiceManager camera cache", throwable);
        }
    }

    private static final class CameraServiceBinder implements IBinder {
        private final IBinder baseBinder;
        private final IInterface proxyService;

        CameraServiceBinder(IBinder baseBinder, IInterface proxyService) {
            this.baseBinder = baseBinder;
            this.proxyService = proxyService;
        }

        @Override
        public String getInterfaceDescriptor() throws RemoteException {
            return baseBinder.getInterfaceDescriptor();
        }

        @Override
        public boolean pingBinder() {
            return baseBinder.pingBinder();
        }

        @Override
        public boolean isBinderAlive() {
            return baseBinder.isBinderAlive();
        }

        @Override
        public IInterface queryLocalInterface(String descriptor) {
            return proxyService;
        }

        @Override
        public void dump(java.io.FileDescriptor fd, String[] args) throws RemoteException {
            baseBinder.dump(fd, args);
        }

        @Override
        public void dumpAsync(java.io.FileDescriptor fd, String[] args) throws RemoteException {
            baseBinder.dumpAsync(fd, args);
        }

        @Override
        public boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            return baseBinder.transact(code, data, reply, flags);
        }

        @Override
        public void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException {
            baseBinder.linkToDeath(recipient, flags);
        }

        @Override
        public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
            return baseBinder.unlinkToDeath(recipient, flags);
        }
    }

    private static final class CameraServiceHandler implements InvocationHandler {
        private final Object baseService;
        private final Context context;
        private final String packageName;
        private final String processName;

        CameraServiceHandler(Object baseService, Context context, String packageName, String processName) {
            this.baseService = baseService;
            this.context = context == null ? null : context.getApplicationContext();
            this.packageName = packageName;
            this.processName = processName;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();
            if (isObjectMethod(methodName, args)) {
                return handleObjectMethod(proxy, methodName, args);
            }
            Log.i(TAG, "Camera service method " + methodName + " package=" + packageName);
            if ("getNumberOfCameras".equals(methodName)) {
                logCallProbe(packageName, processName, 0, methodName, "syntheticCount=2 args=" + argsSummary(args));
                return numericValue(method.getReturnType(), 2L);
            }
            if ("supportsCameraApi".equals(methodName)) {
                logCallProbe(packageName, processName, 0, methodName, "syntheticSupported=true args=" + argsSummary(args));
                return booleanOrNumericValue(method.getReturnType(), true);
            }
            if ("isHiddenPhysicalCamera".equals(methodName)) {
                logCallProbe(packageName, processName, 0, methodName, "syntheticHidden=false args=" + argsSummary(args));
                return booleanOrNumericValue(method.getReturnType(), false);
            }
            if ("getCameraCharacteristics".equals(methodName)) {
                String cameraId = findCameraId(args);
                Object metadata = null;
                boolean syntheticFallback = false;
                try {
                    metadata = method.invoke(baseService, args);
                    logCallProbe(packageName, processName, 0, methodName,
                            "cameraId=" + cameraId
                                    + " source=base"
                                    + " return=" + objectSummary(metadata)
                                    + " args=" + argsSummary(args));
                } catch (Throwable throwable) {
                    Log.w(TAG, "Unable to read base camera metadata for " + packageName
                            + " cameraId=" + cameraId + "; using synthetic fallback", unwrapInvocation(throwable));
                    syntheticFallback = true;
                    metadata = createCameraInfoMetadata(
                            method.getReturnType(),
                            cameraId,
                            packageName,
                            processName,
                            0
                    );
                }
                logCallProbe(packageName, processName, 0, methodName,
                        "cameraId=" + cameraId
                                + " syntheticFallback=" + syntheticFallback
                                + " return=" + method.getReturnType().getName()
                                + " args=" + argsSummary(args));
                return metadata != null ? metadata : defaultValue(method.getReturnType());
            }
            if ("connectDevice".equals(methodName)) {
                String cameraId = findCameraId(args);
                boolean live = VirtualCameraBridge.hasLiveSource(VirtualCameraBridge.DEFAULT_SLOT);
                int sessionId = nextCameraSessionId();
                Log.i(TAG, "connectDevice package=" + packageName
                        + " process=" + processName
                        + " cameraId=" + cameraId
                        + " live=" + live
                        + " return=" + method.getReturnType().getName());
                logCallProbe(packageName, processName, sessionId, "connectDevice",
                        "cameraId=" + cameraId
                                + " live=" + live
                                + " return=" + method.getReturnType().getName()
                                + " args=" + argsSummary(args));
                markCameraActive(packageName, processName, "connectDevice");
                Log.i(TAG, "Serving virtual Camera2 device to " + packageName + " cameraId=" + cameraId);
                return VirtualCameraDeviceUser.create(method.getReturnType(), context, packageName, processName, cameraId, sessionId);
            }
            if ("connect".equals(methodName) || "connectLegacy".equals(methodName)) {
                Log.w(TAG, "Camera service used legacy path " + methodName
                        + " for " + packageName
                        + "; delegating until Camera1 virtual proxy is available");
                logCallProbe(packageName, processName, 0, methodName, "legacyPath=true args=" + argsSummary(args));
            }
            try {
                return method.invoke(baseService, args);
            } catch (Throwable throwable) {
                throw unwrapInvocation(throwable);
            }
        }

        private String findCameraId(Object[] args) {
            if (args == null) {
                return "0";
            }
            for (Object arg : args) {
                if (arg instanceof String) {
                    String value = (String) arg;
                    if (value.length() <= 8) {
                        return value;
                    }
                }
            }
            return "0";
        }
    }

    private static boolean shouldLogCameraServiceMethod(String methodName) {
        if (methodName == null) {
            return false;
        }
        return methodName.startsWith("connect")
                || methodName.contains("Camera")
                || methodName.contains("camera")
                || "getNumberOfCameras".equals(methodName)
                || "getCameraInfo".equals(methodName);
    }

    private static synchronized int nextCameraSessionId() {
        return ++nextCameraSessionId;
    }

    private static void logCallProbe(String packageName, String processName, int sessionId, String event, String details) {
        String stack = stackFingerprint();
        Log.i(TAG_CALL, "mode=" + modeHint(packageName, processName, stack)
                + " session=" + sessionId
                + " package=" + packageName
                + " process=" + processName
                + " event=" + event
                + " thread=" + Thread.currentThread().getName()
                + " stack=" + stack
                + " " + details);
    }

    private static String modeHint(String packageName, String processName, String stack) {
        if (!isWhatsAppPackage(packageName)) {
            return "OTHER_TARGET_CAMERA";
        }
        String value = ((processName == null ? "" : processName) + " " + (stack == null ? "" : stack))
                .toLowerCase(java.util.Locale.US);
        if (value.contains("voip")
                || value.contains("webrtc")
                || value.contains("peerconnection")
                || value.contains("cameracapturer")
                || value.contains("videocapture")
                || value.contains("videocall")
                || value.contains("callactivity")
                || value.contains("callfragment")
                || value.contains("call_screen")
                || value.contains("callscreen")) {
            return "WHATSAPP_VIDEO_CALL_CANDIDATE";
        }
        return "WHATSAPP_NORMAL_CAMERA_OR_UNKNOWN";
    }

    private static boolean isWhatsAppPackage(String packageName) {
        return "com.whatsapp".equals(packageName) || "com.whatsapp.w4b".equals(packageName);
    }

    private static String stackFingerprint() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (StackTraceElement frame : stack) {
            if (count >= 10) {
                break;
            }
            String className = frame.getClassName();
            if (className == null
                    || className.startsWith("java.lang.Thread")
                    || className.startsWith("java.lang.reflect")
                    || className.startsWith("sun.reflect")
                    || className.startsWith("android.util.Log")
                    || className.startsWith("top.niunaijun.blackboxa.vcam.VirtualCameraServiceHook")) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('>');
            }
            builder.append(className)
                    .append('.')
                    .append(frame.getMethodName())
                    .append(':')
                    .append(frame.getLineNumber());
            count++;
        }
        return builder.length() == 0 ? "unknown" : builder.toString();
    }

    private static String argsSummary(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(i).append('=').append(objectSummary(args[i]));
        }
        return builder.append(']').toString();
    }

    private static String objectSummary(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Surface) {
            return surfaceSummary((Surface) value);
        }
        Class<?> valueClass = value.getClass();
        if (valueClass.isArray()) {
            return valueClass.getComponentType().getName() + "[]";
        }
        return valueClass.getName()
                + "@"
                + Integer.toHexString(System.identityHashCode(value))
                + "("
                + safeToString(value)
                + ")";
    }

    private static String describeSurfaces(List<Surface> surfaces) {
        if (surfaces == null || surfaces.isEmpty()) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < surfaces.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(surfaceSummary(surfaces.get(i)));
        }
        return builder.append(']').toString();
    }

    private static String surfaceSummary(Surface surface) {
        if (surface == null) {
            return "null";
        }
        return "Surface@"
                + Integer.toHexString(System.identityHashCode(surface))
                + "{valid=" + surface.isValid()
                + ",format=" + surfaceFormat(surface)
                + ",size=" + surfaceSize(surface)
                + ",str=" + safeToString(surface)
                + "}";
    }

    private static String surfaceSize(Surface surface) {
        if (surface == null || !surface.isValid()) {
            return "unknown";
        }
        try {
            Class<?> surfaceUtils = Class.forName("android.hardware.camera2.utils.SurfaceUtils");
            Method method = findMethod(surfaceUtils, "getSurfaceSize", Surface.class);
            Object value = method.invoke(null, surface);
            return value == null ? "unknown" : value.toString();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String safeContextPackage(Context context) {
        if (context == null) {
            return "null";
        }
        try {
            return context.getPackageName();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String safeToString(Object value) {
        try {
            return String.valueOf(value);
        } catch (Throwable error) {
            return value.getClass().getName() + ":toString_failed";
        }
    }

    private static final class VirtualCameraDeviceUser implements InvocationHandler {
        private final IBinder binder;
        private final Map<Integer, List<Nv21SurfaceRenderer>> renderers = new HashMap<>();
        private final Context context;
        private final String packageName;
        private final String processName;
        private final String cameraId;
        private final int sessionId;
        private int nextStreamId = 1;
        private int nextRequestId = 1;
        private int submitRequestLogCount;

        static Object create(Class<?> returnType, Context context, String packageName, String processName, String cameraId, int sessionId) {
            Class<?> deviceUserInterface = returnType;
            if (deviceUserInterface == null || deviceUserInterface == Void.TYPE) {
                try {
                    deviceUserInterface = Class.forName("android.hardware.camera2.ICameraDeviceUser");
                } catch (Throwable ignored) {
                    return null;
                }
            }
            return Proxy.newProxyInstance(
                    deviceUserInterface.getClassLoader(),
                    new Class[]{deviceUserInterface},
                    new VirtualCameraDeviceUser(context, packageName, processName, cameraId, sessionId)
            );
        }

        VirtualCameraDeviceUser(Context context, String packageName, String processName, String cameraId, int sessionId) {
            this.context = context == null ? null : context.getApplicationContext();
            this.packageName = packageName;
            this.processName = processName;
            this.cameraId = cameraId;
            this.sessionId = sessionId;
            this.binder = new LocalCameraDeviceBinder(packageName, processName, cameraId, sessionId);
            markCameraActive(packageName, processName, "device_proxy_created");
            logCallProbe(packageName, processName, sessionId, "device_proxy_created", "cameraId=" + cameraId);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();
            if (isObjectMethod(methodName, args)) {
                return handleObjectMethod(proxy, methodName, args);
            }
            if ("asBinder".equals(methodName)) {
                logCallProbe(packageName, processName, sessionId, "asBinder", "localBinder=true");
                return binder;
            }
            if ("getCaptureResultMetadataQueue".equals(methodName)) {
                Object descriptor = createEmptyParcelableDescriptor(method.getReturnType());
                logCallProbe(packageName, processName, sessionId, methodName,
                        "descriptor=" + objectSummary(descriptor)
                                + " return=" + method.getReturnType().getName());
                return descriptor != null ? descriptor : defaultValue(method.getReturnType());
            }
            if ("disconnect".equals(methodName)) {
                int streamCount;
                synchronized (renderers) {
                    streamCount = renderers.size();
                }
                logCallProbe(packageName, processName, sessionId, "disconnect", "streams=" + streamCount);
                markCameraDisconnectGrace(packageName);
                stopAllRenderers();
                return defaultValue(method.getReturnType());
            }
            if ("beginConfigure".equals(methodName)
                    || "endConfigure".equals(methodName)
                    || "waitUntilIdle".equals(methodName)
                    || "prepare".equals(methodName)
                    || "prepare2".equals(methodName)
                    || "tearDown".equals(methodName)) {
                Log.i(TAG, "Virtual Camera2 " + methodName + " for " + packageName);
                logCallProbe(packageName, processName, sessionId, methodName, "args=" + argsSummary(args));
                return numericValue(method.getReturnType(), 0L);
            }
            if ("createStream".equals(methodName)) {
                return createStream(args);
            }
            if ("deleteStream".equals(methodName)) {
                stopStream(args);
                return defaultValue(method.getReturnType());
            }
            if ("updateOutputConfiguration".equals(methodName) || "finalizeOutputConfigurations".equals(methodName)) {
                logCallProbe(packageName, processName, sessionId, methodName, "args=" + argsSummary(args));
                updateStream(args);
                return defaultValue(method.getReturnType());
            }
            if ("createDefaultRequest".equals(methodName)) {
                Log.i(TAG, "Virtual Camera2 " + methodName + " for " + packageName);
                logCallProbe(packageName, processName, sessionId, methodName, "args=" + argsSummary(args));
                Object metadata = newInstance(method.getReturnType());
                return metadata != null ? metadata : defaultValue(method.getReturnType());
            }
            if ("getCameraInfo".equals(methodName)) {
                Log.i(TAG, "Virtual Camera2 " + methodName + " for " + packageName);
                logCallProbe(packageName, processName, sessionId, methodName, "args=" + argsSummary(args));
                Object metadata = createCameraInfo(method.getReturnType());
                return metadata != null ? metadata : defaultValue(method.getReturnType());
            }
            if ("submitRequest".equals(methodName) || "submitRequestList".equals(methodName)) {
                Log.i(TAG, "Virtual Camera2 " + methodName + " for " + packageName);
                markCameraActive(packageName, processName, methodName);
                submitRequestLogCount++;
                if (submitRequestLogCount <= 5 || submitRequestLogCount % 30 == 0) {
                    logCallProbe(packageName, processName, sessionId, methodName,
                            "count=" + submitRequestLogCount + " args=" + argsSummary(args));
                }
                return createSubmitInfo(method.getReturnType());
            }
            if ("cancelRequest".equals(methodName) || "flush".equals(methodName)) {
                Log.i(TAG, "Virtual Camera2 " + methodName + " for " + packageName);
                logCallProbe(packageName, processName, sessionId, methodName, "args=" + argsSummary(args));
                return numericValue(method.getReturnType(), 0L);
            }
            if ("isSessionConfigurationSupported".equals(methodName)) {
                logCallProbe(packageName, processName, sessionId, methodName, "args=" + argsSummary(args));
                return true;
            }
            if ("getGlobalAudioRestriction".equals(methodName)) {
                return numericValue(method.getReturnType(), 0L);
            }
            if ("setCameraAudioRestriction".equals(methodName)) {
                return numericValue(method.getReturnType(), 0L);
            }
            Log.d(TAG, "Virtual Camera2 method " + methodName + " for " + packageName);
            return defaultValue(method.getReturnType());
        }

        private int createStream(Object[] args) {
            int streamId = nextStreamId++;
            Object outputConfiguration = firstOutputConfiguration(args);
            List<Surface> surfaces = new ArrayList<>(surfacesFromOutputConfiguration(outputConfiguration));
            int[] size = sizeFromOutputConfiguration(outputConfiguration);
            String surfaceDescription = describeSurfaces(surfaces);
            String outputSummary = objectSummary(outputConfiguration);
            String rawArgs = argsSummary(args);
            markCameraActive(packageName, processName, "createStream");
            logCallProbe(packageName, processName, sessionId, "createStream",
                    "streamId=" + streamId
                            + " configuredSize=" + size[0] + "x" + size[1]
                            + " surfaces=" + surfaceDescription
                            + " outputConfiguration=" + outputSummary
                            + " rawArgs=" + rawArgs
                            + " async=true");
            VirtualCameraThread.post("createStream", () ->
                    createStreamOnCameraThread(streamId, surfaces, size, outputSummary, rawArgs));
            return streamId;
        }

        private void createStreamOnCameraThread(int streamId, List<Surface> surfaces, int[] size, String outputSummary, String rawArgs) {
            ArrayList<Nv21SurfaceRenderer> streamRenderers = new ArrayList<>();
            try {
                SharedFrameMemory.prepare(context, VirtualCameraBridge.DEFAULT_SLOT, size[0], size[1]);
                for (Surface surface : surfaces) {
                    int surfaceFormat = surfaceFormat(surface);
                    boolean directSurfaceRegistered = VirtualCameraSurfaceClient.registerSurface(
                            context,
                            packageName,
                            processName,
                            cameraId,
                            sessionId,
                            streamId,
                            surface,
                            size[0],
                            size[1],
                            surfaceFormat
                    );
                    Nv21SurfaceRenderer renderer = null;
                    if (!directSurfaceRegistered) {
                        renderer = Nv21SurfaceRenderer.start(
                                surface,
                                packageName + "-" + cameraId + "-" + streamId,
                                size[0],
                                size[1],
                                surfaceFormat
                        );
                    }
                    if (renderer != null) {
                        streamRenderers.add(renderer);
                    }
                    Log.i(TAG, "Virtual camera surface format=" + surfaceFormat
                        + " size=" + size[0] + "x" + size[1]
                        + " package=" + packageName
                        + " directSurface=" + directSurfaceRegistered);
                    logCallProbe(packageName, processName, sessionId, "stream_surface",
                            "streamId=" + streamId
                                    + " directSurface=" + directSurfaceRegistered
                                    + " rendererStarted=" + (renderer != null)
                                    + " surface=" + surfaceSummary(surface)
                                    + " renderSize=" + size[0] + "x" + size[1]);
                }
            } catch (Throwable ignored) {
            }
            List<Nv21SurfaceRenderer> previous;
            synchronized (renderers) {
                previous = renderers.put(streamId, streamRenderers);
            }
            safeStopRenderers(previous);
            markCameraActive(packageName, processName, "stream_renderer_started");
            logCallProbe(packageName, processName, sessionId, "createStream_async_complete",
                    "streamId=" + streamId
                            + " configuredSize=" + size[0] + "x" + size[1]
                            + " surfaces=" + surfaces.size()
                            + " renderers=" + streamRenderers.size()
                            + " outputConfiguration=" + outputSummary
                            + " rawArgs=" + rawArgs);
            Log.i(TAG, "Created virtual camera stream id=" + streamId
                    + " surfaces=" + surfaces.size()
                    + " renderers=" + streamRenderers.size()
                    + " size=" + size[0] + "x" + size[1]
                    + " package=" + packageName);
        }

        private void updateStream(Object[] args) {
            Integer streamId = firstInteger(args);
            if (streamId == null) {
                return;
            }
            Object outputConfiguration = firstOutputConfiguration(args);
            if (outputConfiguration == null) {
                stopStream(new Object[]{streamId});
                return;
            }
            List<Surface> surfaces = new ArrayList<>(surfacesFromOutputConfiguration(outputConfiguration));
            int[] size = sizeFromOutputConfiguration(outputConfiguration);
            String outputSummary = objectSummary(outputConfiguration);
            String rawArgs = argsSummary(args);
            markCameraActive(packageName, processName, "updateStream");
            VirtualCameraThread.post("updateStream", () -> {
                stopStreamOnCameraThread(streamId);
                createStreamOnCameraThread(streamId, surfaces, size, outputSummary, rawArgs);
            });
        }

        private void stopStream(Object[] args) {
            Integer streamId = firstInteger(args);
            if (streamId == null) {
                return;
            }
            if (VirtualCameraThread.isOnThread()) {
                stopStreamOnCameraThread(streamId);
            } else {
                VirtualCameraThread.post("deleteStream", () -> stopStreamOnCameraThread(streamId));
            }
        }

        private void stopStreamOnCameraThread(Integer streamId) {
            VirtualCameraSurfaceClient.unregisterStream(context, packageName, sessionId, streamId);
            List<Nv21SurfaceRenderer> streamRenderers;
            synchronized (renderers) {
                streamRenderers = renderers.remove(streamId);
            }
            if (streamRenderers == null) {
                return;
            }
            logCallProbe(packageName, processName, sessionId, "deleteStream",
                    "streamId=" + streamId + " rendererCount=" + streamRenderers.size());
            safeStopRenderers(streamRenderers);
            Log.i(TAG, "Stopped virtual camera stream id=" + streamId + " package=" + packageName);
        }

        private void stopAllRenderers() {
            if (VirtualCameraThread.isOnThread()) {
                stopAllRenderersOnCameraThread();
            } else {
                VirtualCameraThread.post("disconnect", this::stopAllRenderersOnCameraThread);
            }
        }

        private void stopAllRenderersOnCameraThread() {
            VirtualCameraSurfaceClient.unregisterSession(context, packageName, sessionId);
            ArrayList<List<Nv21SurfaceRenderer>> stopped = new ArrayList<>();
            synchronized (renderers) {
                stopped.addAll(renderers.values());
                renderers.clear();
            }
            for (List<Nv21SurfaceRenderer> streamRenderers : stopped) {
                safeStopRenderers(streamRenderers);
            }
            Log.i(TAG, "Disconnected virtual Camera2 device for " + packageName + "/" + processName);
            logCallProbe(packageName, processName, sessionId, "device_disconnected", "cameraId=" + cameraId);
        }

        private void safeStopRenderers(List<Nv21SurfaceRenderer> streamRenderers) {
            if (streamRenderers == null) {
                return;
            }
            for (Nv21SurfaceRenderer renderer : streamRenderers) {
                try {
                    if (renderer != null) {
                        renderer.stop();
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        private Object createCameraInfo(Class<?> returnType) {
            return createCameraInfoMetadata(returnType, cameraId, packageName, processName, sessionId);
        }

        private Object createSubmitInfo(Class<?> returnType) {
            if (returnType == null || returnType == Void.TYPE || returnType == Void.class) {
                return null;
            }
            int requestId = nextRequestId++;
            try {
                Constructor<?> constructor = returnType.getDeclaredConstructor(int.class, long.class);
                constructor.setAccessible(true);
                return constructor.newInstance(requestId, 0L);
            } catch (Throwable ignored) {
            }
            Object submitInfo = newInstance(returnType);
            return submitInfo != null ? submitInfo : defaultValue(returnType);
        }
    }

    private static final class LocalCameraDeviceBinder extends Binder {
        private final String packageName;
        private final String processName;
        private final String cameraId;
        private final int sessionId;

        LocalCameraDeviceBinder(String packageName, String processName, String cameraId, int sessionId) {
            this.packageName = packageName;
            this.processName = processName;
            this.cameraId = cameraId;
            this.sessionId = sessionId;
        }

        @Override
        public void linkToDeath(DeathRecipient recipient, int flags) {
            logCallProbe(packageName, processName, sessionId, "binder_linkToDeath",
                    "cameraId=" + cameraId
                            + " recipient=" + objectSummary(recipient)
                            + " flags=" + flags);
        }

        @Override
        public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
            logCallProbe(packageName, processName, sessionId, "binder_unlinkToDeath",
                    "cameraId=" + cameraId
                            + " recipient=" + objectSummary(recipient)
                            + " flags=" + flags);
            return true;
        }
    }

    private static Object createEmptyParcelableDescriptor(Class<?> returnType) {
        Object descriptor = newInstance(returnType);
        if (descriptor == null) {
            return null;
        }
        try {
            setFieldValueIfPresent(descriptor, "quantum", 1);
            setFieldValueIfPresent(descriptor, "flags", 1);
            setFieldValueIfPresent(descriptor, "grantors", emptyFieldValue(fieldType(descriptor, "grantors")));
            setFieldValueIfPresent(descriptor, "handle", createEmptyNativeHandle(fieldType(descriptor, "handle")));
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to fully initialize " + returnType.getName(), throwable);
        }
        return descriptor;
    }

    private static Object createEmptyNativeHandle(Class<?> handleType) {
        if (handleType == null || handleType == Void.TYPE || handleType.isPrimitive()) {
            return null;
        }
        Object handle = newInstance(handleType);
        if (handle == null) {
            return null;
        }
        try {
            setFieldValueIfPresent(handle, "fds", emptyFieldValue(fieldType(handle, "fds")));
            setFieldValueIfPresent(handle, "ints", emptyFieldValue(fieldType(handle, "ints")));
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to fully initialize " + handleType.getName(), throwable);
        }
        return handle;
    }

    private static Class<?> fieldType(Object target, String name) {
        if (target == null || name == null) {
            return null;
        }
        try {
            return findField(target.getClass(), name).getType();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object emptyFieldValue(Class<?> type) {
        if (type == null) {
            return null;
        }
        if (type.isArray()) {
            return Array.newInstance(type.getComponentType(), 0);
        }
        if (Collection.class.isAssignableFrom(type)) {
            return new ArrayList<>();
        }
        if (Map.class.isAssignableFrom(type)) {
            return new HashMap<>();
        }
        if (type == Integer.TYPE || type == Integer.class) {
            return 0;
        }
        if (type == Long.TYPE || type == Long.class) {
            return 0L;
        }
        if (type == Boolean.TYPE || type == Boolean.class) {
            return false;
        }
        return newInstance(type);
    }

    private static void setFieldValueIfPresent(Object target, String name, Object value) {
        if (target == null || name == null) {
            return;
        }
        try {
            Field field = findField(target.getClass(), name);
            if (Modifier.isStatic(field.getModifiers())) {
                return;
            }
            field.set(target, value);
        } catch (Throwable throwable) {
            Log.d(TAG, "Unable to set field " + name + " on " + target.getClass().getName(), throwable);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object createCameraInfoMetadata(
            Class<?> returnType,
            String cameraId,
            String packageName,
            String processName,
            int sessionId
    ) {
        Object metadata = newInstance(returnType);
        if (metadata == null) {
            return null;
        }

        boolean front = isFrontCameraId(cameraId);
        int lensFacing = front
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        int sensorOrientation = front ? 270 : 90;
        Range<Integer>[] fpsRanges = new Range[]{
                new Range<>(15, 30),
                new Range<>(30, 30)
        };
        StreamConfigurationMap streamConfigurationMap = null;

        setCameraMetadata(metadata, CameraCharacteristics.LENS_FACING, lensFacing);
        setCameraMetadata(metadata, CameraCharacteristics.SENSOR_ORIENTATION, sensorOrientation);
        setCameraMetadata(metadata, CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL,
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED);
        setCameraMetadata(metadata, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
                new int[]{CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE});
        setCameraMetadata(metadata, CameraCharacteristics.REQUEST_MAX_NUM_INPUT_STREAMS, 0);
        setCameraMetadata(metadata, CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_PROC, 3);
        setCameraMetadata(metadata, CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_PROC_STALLING, 1);
        setCameraMetadata(metadata, CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_RAW, 0);
        setCameraMetadata(metadata, CameraCharacteristics.CONTROL_AVAILABLE_MODES,
                new int[]{CameraCharacteristics.CONTROL_MODE_AUTO});
        setCameraMetadata(metadata, CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES,
                new int[]{CameraCharacteristics.CONTROL_AE_MODE_ON});
        setCameraMetadata(metadata, CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES,
                new int[]{
                        CameraCharacteristics.CONTROL_AF_MODE_OFF,
                        CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                        CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                });
        setCameraMetadata(metadata, CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES,
                new int[]{CameraCharacteristics.CONTROL_AWB_MODE_AUTO});
        setCameraMetadata(metadata, CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES, fpsRanges);
        setCameraMetadata(metadata, CameraCharacteristics.FLASH_INFO_AVAILABLE, false);
        setCameraMetadata(metadata, CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE, new Rect(0, 0, 720, 1280));
        setCameraMetadata(metadata, CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE,
                new Rect(0, 0, 720, 1280));
        setCameraMetadata(metadata, CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE, new Size(720, 1280));
        setCameraMetadata(metadata, CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE, new SizeF(5.0f, 7.0f));
        setCameraMetadata(metadata, CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE,
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME);
        setCameraMetadata(metadata, CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION, 33333333L);
        setCameraMetadata(metadata, CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE,
                new Range<>(100000L, 33333333L));
        setCameraMetadata(metadata, CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE,
                new Range<>(100, 1600));
        setCameraMetadata(metadata, CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM, 1.0f);
        setCameraMetadata(metadata, CameraCharacteristics.SCALER_CROPPING_TYPE,
                CameraCharacteristics.SCALER_CROPPING_TYPE_CENTER_ONLY);
        streamConfigurationMap = setSyntheticStreamConfigurationMetadata(metadata);
        setCameraMetadata(metadata, CameraCharacteristics.JPEG_AVAILABLE_THUMBNAIL_SIZES,
                new Size[]{new Size(0, 0), new Size(160, 120)});
        setCameraMetadata(metadata, CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES,
                new int[]{CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_OFF});
        setCameraMetadata(metadata, CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT, 0);
        setCameraMetadata(metadata, CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES,
                new int[]{CameraCharacteristics.TONEMAP_MODE_FAST});
        setCameraMetadata(metadata, CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS, 0);
        setCameraMetadata(metadata, CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES,
                new int[]{CameraCharacteristics.EDGE_MODE_FAST});
        setCameraMetadata(metadata, CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
                new int[]{CameraCharacteristics.NOISE_REDUCTION_MODE_FAST});
        setCameraMetadata(metadata, CameraCharacteristics.SYNC_MAX_LATENCY,
                CameraCharacteristics.SYNC_MAX_LATENCY_PER_FRAME_CONTROL);

        logCallProbe(packageName, processName, sessionId, "camera_info_metadata",
                "cameraId=" + cameraId
                        + " lensFacing=" + lensFacing
                        + " sensorOrientation=" + sensorOrientation
                        + " streamMap=" + (streamConfigurationMap != null)
                        + " streamMapSummary=" + streamMapSummary(streamConfigurationMap)
                        + " return=" + returnType.getName());
        return metadata;
    }

    private static boolean isFrontCameraId(String id) {
        String value = String.valueOf(id == null ? "" : id).toLowerCase(java.util.Locale.US);
        return "1".equals(value) || value.contains("front") || value.contains("user");
    }

    private static StreamConfigurationMap createStreamConfigurationMap() {
        try {
            Class<?> configClass = Class.forName("android.hardware.camera2.params.StreamConfiguration");
            Class<?> durationClass = Class.forName("android.hardware.camera2.params.StreamConfigurationDuration");
            Object configurations = createStreamConfigurations(configClass);
            Object durations = createStreamDurations(durationClass, false);
            Object stalls = createStreamDurations(durationClass, true);

            Constructor<?> selected = null;
            for (Constructor<?> constructor : StreamConfigurationMap.class.getDeclaredConstructors()) {
                if (constructor.getParameterTypes().length >= 5) {
                    selected = constructor;
                    break;
                }
            }
            if (selected == null) {
                Log.w(TAG, "No hidden StreamConfigurationMap constructor found");
                return null;
            }

            Class<?>[] parameterTypes = selected.getParameterTypes();
            Object[] args = new Object[parameterTypes.length];
            int configArrays = 0;
            int durationArrays = 0;
            for (int i = 0; i < parameterTypes.length; i++) {
                Class<?> parameterType = parameterTypes[i];
                if (parameterType.isArray()) {
                    Class<?> componentType = parameterType.getComponentType();
                    if (componentType == configClass) {
                        args[i] = configArrays++ == 0
                                ? configurations
                                : Array.newInstance(configClass, 0);
                    } else if (componentType == durationClass) {
                        if (durationArrays == 0) {
                            args[i] = durations;
                        } else if (durationArrays == 1) {
                            args[i] = stalls;
                        } else {
                            args[i] = Array.newInstance(durationClass, 0);
                        }
                        durationArrays++;
                    } else {
                        args[i] = Array.newInstance(componentType, 0);
                    }
                } else if (parameterType == Boolean.TYPE || parameterType == Boolean.class) {
                    args[i] = false;
                } else if (parameterType == Integer.TYPE || parameterType == Integer.class) {
                    args[i] = 0;
                } else {
                    args[i] = null;
                }
            }

            selected.setAccessible(true);
            Object streamMap = selected.newInstance(args);
            return streamMap instanceof StreamConfigurationMap
                    ? (StreamConfigurationMap) streamMap
                    : null;
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to create synthetic stream configuration map", throwable);
            return null;
        }
    }

    private static StreamConfigurationMap setSyntheticStreamConfigurationMetadata(Object metadata) {
        if (metadata == null) {
            return null;
        }
        try {
            Class<?> configClass = Class.forName("android.hardware.camera2.params.StreamConfiguration");
            Class<?> durationClass = Class.forName("android.hardware.camera2.params.StreamConfigurationDuration");
            Object configurations = createStreamConfigurations(configClass);
            Object durations = createStreamDurations(durationClass, false);
            Object stalls = createStreamDurations(durationClass, true);
            setCameraMetadataRaw(
                    metadata,
                    "android.scaler.availableStreamConfigurations",
                    configurations.getClass(),
                    configurations
            );
            setCameraMetadataRaw(
                    metadata,
                    "android.scaler.availableMinFrameDurations",
                    durations.getClass(),
                    durations
            );
            setCameraMetadataRaw(
                    metadata,
                    "android.scaler.availableStallDurations",
                    stalls.getClass(),
                    stalls
            );
            Object streamMap = getCameraMetadata(metadata, CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            return streamMap instanceof StreamConfigurationMap ? (StreamConfigurationMap) streamMap : null;
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to set synthetic stream configuration metadata", unwrapInvocation(throwable));
            return null;
        }
    }

    private static String streamMapSummary(StreamConfigurationMap streamMap) {
        if (streamMap == null) {
            return "null";
        }
        try {
            return "formats=" + java.util.Arrays.toString(streamMap.getOutputFormats())
                    + ",private=" + sizesSummary(streamMap.getOutputSizes(ImageFormat.PRIVATE))
                    + ",yuv=" + sizesSummary(streamMap.getOutputSizes(ImageFormat.YUV_420_888))
                    + ",jpeg=" + sizesSummary(streamMap.getOutputSizes(ImageFormat.JPEG))
                    + ",surfaceTexture=" + sizesSummary(streamMap.getOutputSizes(SurfaceTexture.class))
                    + ",surfaceHolder=" + sizesSummary(streamMap.getOutputSizes(SurfaceHolder.class))
                    + ",mediaRecorder=" + sizesSummary(streamMap.getOutputSizes(MediaRecorder.class));
        } catch (Throwable throwable) {
            return "summary_error=" + throwable.getClass().getName() + ":" + throwable.getMessage();
        }
    }

    private static String sizesSummary(Size[] sizes) {
        if (sizes == null) {
            return "null";
        }
        return java.util.Arrays.toString(sizes);
    }

    private static Object createStreamConfigurations(Class<?> configClass) throws Exception {
        int[][] entries = streamConfigurationEntries();
        Object array = Array.newInstance(configClass, entries.length);
        Constructor<?> constructor = configClass.getDeclaredConstructor(
                int.class,
                int.class,
                int.class,
                boolean.class
        );
        constructor.setAccessible(true);
        for (int i = 0; i < entries.length; i++) {
            int[] entry = entries[i];
            Array.set(array, i, constructor.newInstance(entry[0], entry[1], entry[2], false));
        }
        return array;
    }

    private static Object createStreamDurations(Class<?> durationClass, boolean stall) throws Exception {
        int[][] entries = streamConfigurationEntries();
        Object array = Array.newInstance(durationClass, entries.length);
        Constructor<?> constructor = durationClass.getDeclaredConstructor(
                int.class,
                int.class,
                int.class,
                long.class
        );
        constructor.setAccessible(true);
        for (int i = 0; i < entries.length; i++) {
            int[] entry = entries[i];
            long duration = stall && entry[0] == HAL_PIXEL_FORMAT_BLOB ? 100000000L : 0L;
            if (!stall) {
                duration = DEFAULT_FRAME_DURATION_NS;
            }
            Array.set(array, i, constructor.newInstance(entry[0], entry[1], entry[2], duration));
        }
        return array;
    }

    private static int[][] streamConfigurationEntries() {
        return new int[][]{
                {ImageFormat.PRIVATE, 1280, 720},
                {ImageFormat.PRIVATE, 720, 1280},
                {ImageFormat.PRIVATE, 640, 480},
                {ImageFormat.PRIVATE, 480, 640},
                {ImageFormat.PRIVATE, 320, 240},
                {ImageFormat.YUV_420_888, 1280, 720},
                {ImageFormat.YUV_420_888, 720, 1280},
                {ImageFormat.YUV_420_888, 640, 480},
                {ImageFormat.YUV_420_888, 480, 640},
                {HAL_PIXEL_FORMAT_BLOB, 1280, 720},
                {HAL_PIXEL_FORMAT_BLOB, 720, 1280},
                {HAL_PIXEL_FORMAT_BLOB, 640, 480}
        };
    }

    private static Object firstOutputConfiguration(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg != null && arg.getClass().getName().contains("OutputConfiguration")) {
                return arg;
            }
        }
        return null;
    }

    private static Integer firstInteger(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof Integer) {
                return (Integer) arg;
            }
        }
        return null;
    }

    private static List<Surface> surfacesFromOutputConfiguration(Object outputConfiguration) {
        ArrayList<Surface> surfaces = new ArrayList<>();
        if (outputConfiguration == null) {
            return surfaces;
        }
        Object list = callNoArg(outputConfiguration, "getSurfaces");
        if (list instanceof Iterable) {
            for (Object item : (Iterable<?>) list) {
                if (item instanceof Surface && ((Surface) item).isValid()) {
                    surfaces.add((Surface) item);
                }
            }
        }
        Object single = callNoArg(outputConfiguration, "getSurface");
        if (single instanceof Surface && ((Surface) single).isValid() && !surfaces.contains(single)) {
            surfaces.add((Surface) single);
        }
        return surfaces;
    }

    private static int surfaceFormat(Surface surface) {
        if (surface == null || !surface.isValid()) {
            return Integer.MIN_VALUE;
        }
        try {
            Class<?> surfaceUtils = Class.forName("android.hardware.camera2.utils.SurfaceUtils");
            Method method = findMethod(surfaceUtils, "getSurfaceFormat", Surface.class);
            Object value = method.invoke(null, surface);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
        } catch (Throwable throwable) {
            Log.d(TAG, "Unable to read surface format", throwable);
        }
        return Integer.MIN_VALUE;
    }

    private static int[] sizeFromOutputConfiguration(Object outputConfiguration) {
        int width = 720;
        int height = 1280;
        Object size = outputConfiguration == null ? null : callNoArg(outputConfiguration, "getSurfaceSize");
        if (size != null) {
            Object w = callNoArg(size, "getWidth");
            Object h = callNoArg(size, "getHeight");
            if (w instanceof Integer && h instanceof Integer && (Integer) w > 0 && (Integer) h > 0) {
                width = (Integer) w;
                height = (Integer) h;
            }
        }
        return new int[]{Math.max(2, width), Math.max(2, height)};
    }

    private static <T> void setCameraMetadata(Object metadata, CameraCharacteristics.Key<T> key, T value) {
        if (metadata == null || key == null) {
            return;
        }
        try {
            Method set = findMethod(metadata.getClass(), "set", CameraCharacteristics.Key.class, Object.class);
            set.invoke(metadata, key, value);
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to set camera metadata " + key.getName(), unwrapInvocation(throwable));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setCameraMetadataRaw(Object metadata, String keyName, Class<?> valueType, Object value) {
        try {
            Constructor<CameraCharacteristics.Key> constructor =
                    CameraCharacteristics.Key.class.getDeclaredConstructor(String.class, Class.class);
            constructor.setAccessible(true);
            CameraCharacteristics.Key key = constructor.newInstance(keyName, valueType);
            setCameraMetadata(metadata, key, value);
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to set raw camera metadata " + keyName, unwrapInvocation(throwable));
        }
    }

    private static Object getCameraMetadata(Object metadata, CameraCharacteristics.Key<?> key) {
        if (metadata == null || key == null) {
            return null;
        }
        try {
            Method get = findMethod(metadata.getClass(), "get", CameraCharacteristics.Key.class);
            return get.invoke(metadata, key);
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to read camera metadata " + key.getName(), unwrapInvocation(throwable));
            return null;
        }
    }

    private static Object callNoArg(Object target, String methodName) {
        try {
            Method method = findMethod(target.getClass(), methodName);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
            }
        }
        Method method = type.getMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }

    private static Object newInstance(Class<?> type) {
        if (type == null || type == Void.TYPE || type.isPrimitive()) {
            return null;
        }
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Throwable throwable) {
            Log.w(TAG, "Could not instantiate " + type.getName(), throwable);
            return null;
        }
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == null || returnType == Void.TYPE || returnType == Void.class) {
            return null;
        }
        if (returnType == Boolean.TYPE || returnType == Boolean.class) {
            return false;
        }
        if (returnType == Byte.TYPE || returnType == Byte.class) {
            return (byte) 0;
        }
        if (returnType == Short.TYPE || returnType == Short.class) {
            return (short) 0;
        }
        if (returnType == Integer.TYPE || returnType == Integer.class) {
            return 0;
        }
        if (returnType == Long.TYPE || returnType == Long.class) {
            return 0L;
        }
        if (returnType == Float.TYPE || returnType == Float.class) {
            return 0f;
        }
        if (returnType == Double.TYPE || returnType == Double.class) {
            return 0d;
        }
        if (returnType == Character.TYPE || returnType == Character.class) {
            return (char) 0;
        }
        if (returnType == String.class) {
            return "";
        }
        if (returnType == IBinder.class) {
            return new Binder();
        }
        if (returnType.isArray()) {
            return Array.newInstance(returnType.getComponentType(), 0);
        }
        return null;
    }

    private static Object booleanOrNumericValue(Class<?> returnType, boolean value) {
        if (returnType == Boolean.TYPE || returnType == Boolean.class) {
            return value;
        }
        return numericValue(returnType, value ? 1L : 0L);
    }

    private static Object numericValue(Class<?> returnType, long value) {
        if (returnType == Long.TYPE || returnType == Long.class) {
            return value;
        }
        if (returnType == Integer.TYPE || returnType == Integer.class) {
            return (int) value;
        }
        if (returnType == Short.TYPE || returnType == Short.class) {
            return (short) value;
        }
        if (returnType == Byte.TYPE || returnType == Byte.class) {
            return (byte) value;
        }
        return defaultValue(returnType);
    }

    private static boolean isObjectMethod(String methodName, Object[] args) {
        return ("toString".equals(methodName) && (args == null || args.length == 0))
                || ("hashCode".equals(methodName) && (args == null || args.length == 0))
                || ("equals".equals(methodName) && args != null && args.length == 1);
    }

    private static Object handleObjectMethod(Object proxy, String methodName, Object[] args) {
        if ("toString".equals(methodName)) {
            return "SmokescreenVirtualCameraProxy";
        }
        if ("hashCode".equals(methodName)) {
            return System.identityHashCode(proxy);
        }
        if ("equals".equals(methodName)) {
            return proxy == args[0];
        }
        return null;
    }

    private static Throwable unwrapInvocation(Throwable throwable) {
        if (throwable instanceof java.lang.reflect.InvocationTargetException
                && ((java.lang.reflect.InvocationTargetException) throwable).getTargetException() != null) {
            return ((java.lang.reflect.InvocationTargetException) throwable).getTargetException();
        }
        return throwable;
    }
}
