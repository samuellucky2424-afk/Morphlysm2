package com.example.vcam;


import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.InputConfiguration;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Process;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.widget.Toast;
import android.util.Log;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookMain implements IXposedHookLoadPackage {
    public static Surface mSurface;
    public static SurfaceTexture mSurfacetexture;
    public static MediaPlayer mMediaPlayer;
    public static SurfaceTexture fake_SurfaceTexture;
    public static Camera origin_preview_camera;

    public static Camera camera_onPreviewFrame;
    public static Camera start_preview_camera;
    public static volatile byte[] data_buffer = {0};
    public static byte[] input;
    public static int mhight;
    public static int mwidth;
    public static boolean is_someone_playing;
    public static boolean is_hooked;
    public static VideoToFrames hw_decode_obj;
    public static VideoToFrames c2_hw_decode_obj;
    public static VideoToFrames c2_hw_decode_obj_1;
    public static SurfaceTexture c1_fake_texture;
    public static Surface c1_fake_surface;
    public static SurfaceHolder ori_holder;
    public static MediaPlayer mplayer1;
    public static Camera mcamera1;
    public int imageReaderFormat = 0;
    public static boolean is_first_hook_build = true;

    public static int onemhight;
    public static int onemwidth;
    public static Class camera_callback_calss;

    public static String video_path = "/storage/emulated/0/DCIM/Camera1/";

    public static Surface c2_preview_Surfcae;
    public static Surface c2_preview_Surfcae_1;
    public static Surface c2_reader_Surfcae;
    public static Surface c2_reader_Surfcae_1;
    public static MediaPlayer c2_player;
    public static MediaPlayer c2_player_1;
    public static Surface c2_virtual_surface;
    public static SurfaceTexture c2_virtual_surfaceTexture;
    public boolean need_recreate;
    public static CameraDevice.StateCallback c2_state_cb;
    public static CaptureRequest.Builder c2_builder;
    public static SessionConfiguration fake_sessionConfiguration;
    public static SessionConfiguration sessionConfiguration;
    public static OutputConfiguration outputConfiguration;
    public boolean need_to_show_toast = true;

    public int c2_ori_width = 1280;
    public int c2_ori_height = 720;

    public static Class c2_state_callback;
    public static Context toast_content;

    // Live camera bridge fields
    private static final SlotFrameSink DEFAULT_SLOT_SINK = new SlotFrameSink(VirtualCameraBridge.DEFAULT_SLOT, null);
    private static FrameSource activeFrameSource;
    private static String activeFrameSourceKey;
    private static final String TAG_HOOK = "VCAM_HOOK";
    private static final String TAG_SURFACE = "VCAM_SURFACE";
    private static volatile long lastFrameCopyLogMs;

    public static Nv21SurfaceRenderer c1_holder_renderer;
    public static Nv21SurfaceRenderer c1_texture_renderer;
    public static Nv21SurfaceRenderer c2_preview_renderer;
    public static Nv21SurfaceRenderer c2_preview_renderer_1;
    public static Nv21SurfaceRenderer c2_reader_renderer;
    public static Nv21SurfaceRenderer c2_reader_renderer_1;

    private static final String TAG_DIAG = "VCAM_DIAG";
    private static final String TAG_PERM_DIAG = "PERM_DIAG";
    private static volatile int sessionCounter;
    private static volatile String loadedPackageName = "";
    private static volatile String loadedProcessName = "";
    private static volatile int loadedTargetSdk = -1;

    private static final String[] CALL_PERMISSION_NAMES = new String[] {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.BLUETOOTH_CONNECT",
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_CAMERA",
            "android.permission.FOREGROUND_SERVICE_MICROPHONE"
    };

    private static void logDiag(String event, String details) {
        int session = sessionCounter;
        String thread = Thread.currentThread().getName();
        String caller = callerFingerprint();
        String msg = "mode=" + modeHint(caller) + " package=" + loadedPackageName
                + " session=" + session + " event=" + event + " thread=" + thread
                + " caller=" + caller + " " + details;
        Log.i(TAG_DIAG, msg);
        de.robv.android.xposed.XposedBridge.log("[VCAM_DIAG] " + msg);
    }

    private static void logPermDiag(String source, String permission, Object returnedResult,
                                    Object originalResult, Object appOpsMode, Object requestResult,
                                    String extra) {
        if (!shouldTrackPermission(permission)) {
            return;
        }
        String caller = callerFingerprint();
        String state = virtualPermissionState(permission);
        boolean virtualGranted = virtualPermissionManagerGranted(permission);
        String msg = "api=" + source
                + " device=" + Build.MANUFACTURER + "/" + Build.MODEL + "/" + Build.DEVICE
                + " android=" + Build.VERSION.RELEASE + "(sdk=" + Build.VERSION.SDK_INT + ")"
                + " targetSdk=" + loadedTargetSdk
                + " hostPackage=" + BuildConfig.APPLICATION_ID
                + " clonedPackage=" + loadedPackageName
                + " process=" + loadedProcessName
                + " realUid=" + Process.myUid()
                + " virtualUid=" + safeVirtualUid()
                + " permission=" + permission
                + " returnedResult=" + nullable(returnedResult)
                + " originalResult=" + nullable(originalResult)
                + " appOpsMode=" + nullable(appOpsMode)
                + " requestPermissionsResult=" + nullable(requestResult)
                + " virtualPermissionManagerGranted=" + virtualGranted
                + " virtualPermissionState=" + state
                + " caller=" + caller
                + (extra == null || extra.length() == 0 ? "" : " " + extra);
        Log.i(TAG_PERM_DIAG, msg);
        XposedBridge.log("[PERM_DIAG] " + msg);
    }

    private static void installPermissionDiagnostics(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!isWhatsAppPackage(lpparam.packageName)) {
            return;
        }
        logPermDiag("install", Manifest.permission.CAMERA, "installed", "installed",
                AppOpsManager.MODE_ALLOWED, null, "classLoader=" + lpparam.classLoader);

        hookPermissionResultMethod(lpparam.classLoader, "android.app.ContextImpl",
                "checkSelfPermission", "Context.checkSelfPermission");
        hookPermissionResultMethod(lpparam.classLoader, "android.content.ContextWrapper",
                "checkSelfPermission", "Context.checkSelfPermission");
        hookPermissionResultMethod(lpparam.classLoader, "androidx.core.content.ContextCompat",
                "checkSelfPermission", "ContextCompat.checkSelfPermission");
        hookPermissionResultMethod(lpparam.classLoader, "android.support.v4.content.ContextCompat",
                "checkSelfPermission", "ContextCompat.checkSelfPermission");
        hookPermissionResultMethod(lpparam.classLoader, "android.app.ApplicationPackageManager",
                "checkPermission", "PackageManager.checkPermission");
        hookPermissionResultMethod(lpparam.classLoader, "androidx.core.content.PermissionChecker",
                "checkSelfPermission", "PermissionChecker.checkSelfPermission");
        hookPermissionResultMethod(lpparam.classLoader, "android.support.v4.content.PermissionChecker",
                "checkSelfPermission", "PermissionChecker.checkSelfPermission");

        hookActivityRequestPermissions(lpparam.classLoader);
        hookActivityPermissionResult(lpparam.classLoader);

        hookAppOpsMethod(lpparam.classLoader, "checkOpNoThrow", "AppOpsManager.checkOpNoThrow");
        hookAppOpsMethod(lpparam.classLoader, "noteOp", "AppOpsManager.noteOp");
        hookAppOpsMethod(lpparam.classLoader, "noteProxyOp", "AppOpsManager.noteProxyOp");
        hookAppOpsMethod(lpparam.classLoader, "unsafeCheckOpNoThrow", "AppOpsManager.unsafeCheckOpNoThrow");
    }

    private static void hookPermissionResultMethod(ClassLoader loader, String className,
                                                   String methodName, final String source) {
        Class<?> clazz = findHookClass(loader, className);
        if (clazz == null) {
            logPermDiag(source + ".hookMissing", Manifest.permission.CAMERA, "class_missing",
                    "class_missing", AppOpsManager.MODE_ALLOWED, null, "class=" + className);
            return;
        }
        try {
            XposedBridge.hookAllMethods(clazz, methodName, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String permission = findPermissionArg(param.args);
                    if (!shouldTrackPermission(permission)) {
                        return;
                    }
                    Object original = param.getResult();
                    Object result = forcePermissionGrantedResult(param.method, original);
                    param.setResult(result);
                    logPermDiag(source, permission, result, original, AppOpsManager.MODE_ALLOWED,
                            null, "method=" + methodSignature(param.method));
                }
            });
            logPermDiag(source + ".hookInstalled", Manifest.permission.CAMERA, "installed",
                    "installed", AppOpsManager.MODE_ALLOWED, null, "class=" + className);
        } catch (Throwable throwable) {
            logPermDiag(source + ".hookFailed", Manifest.permission.CAMERA,
                    throwable.getClass().getSimpleName(), throwable.getMessage(),
                    AppOpsManager.MODE_ALLOWED, null, "class=" + className);
        }
    }

    private static void hookActivityRequestPermissions(ClassLoader loader) {
        Class<?> clazz = findHookClass(loader, "android.app.Activity");
        if (clazz == null) {
            return;
        }
        try {
            XposedBridge.hookAllMethods(clazz, "requestPermissions", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String[] permissions = findPermissionArray(param.args);
                    if (permissions == null || permissions.length == 0) {
                        return;
                    }
                    int requestCode = findRequestCode(param.args);
                    int[] grantResults = grantedResults(permissions);
                    for (String permission : permissions) {
                        logPermDiag("Activity.requestPermissions", permission,
                                PackageManager.PERMISSION_GRANTED, "requested",
                                AppOpsManager.MODE_ALLOWED, Arrays.toString(grantResults),
                                "requestCode=" + requestCode + " permissions=" + Arrays.toString(permissions));
                    }
                    if (allTrackedPermissions(permissions) && param.thisObject instanceof Activity) {
                        ((Activity) param.thisObject).onRequestPermissionsResult(requestCode, permissions, grantResults);
                        param.setResult(null);
                    }
                }
            });
            logPermDiag("Activity.requestPermissions.hookInstalled", Manifest.permission.CAMERA,
                    "installed", "installed", AppOpsManager.MODE_ALLOWED, null, null);
        } catch (Throwable throwable) {
            logPermDiag("Activity.requestPermissions.hookFailed", Manifest.permission.CAMERA,
                    throwable.getClass().getSimpleName(), throwable.getMessage(),
                    AppOpsManager.MODE_ALLOWED, null, null);
        }
    }

    private static void hookActivityPermissionResult(ClassLoader loader) {
        Class<?> clazz = findHookClass(loader, "android.app.Activity");
        if (clazz == null) {
            return;
        }
        try {
            XposedBridge.hookAllMethods(clazz, "onRequestPermissionsResult", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String[] permissions = findPermissionArray(param.args);
                    int[] grantResults = findGrantResults(param.args);
                    if (permissions == null || grantResults == null) {
                        return;
                    }
                    for (int i = 0; i < permissions.length && i < grantResults.length; i++) {
                        if (shouldTrackPermission(permissions[i])) {
                            int original = grantResults[i];
                            grantResults[i] = PackageManager.PERMISSION_GRANTED;
                            logPermDiag("onRequestPermissionsResult", permissions[i],
                                    PackageManager.PERMISSION_GRANTED, original,
                                    AppOpsManager.MODE_ALLOWED, Arrays.toString(grantResults),
                                    "permissions=" + Arrays.toString(permissions));
                        }
                    }
                }
            });
            logPermDiag("onRequestPermissionsResult.hookInstalled", Manifest.permission.CAMERA,
                    "installed", "installed", AppOpsManager.MODE_ALLOWED, null, null);
        } catch (Throwable throwable) {
            logPermDiag("onRequestPermissionsResult.hookFailed", Manifest.permission.CAMERA,
                    throwable.getClass().getSimpleName(), throwable.getMessage(),
                    AppOpsManager.MODE_ALLOWED, null, null);
        }
    }

    private static void hookAppOpsMethod(ClassLoader loader, String methodName, final String source) {
        Class<?> clazz = findHookClass(loader, "android.app.AppOpsManager");
        if (clazz == null) {
            return;
        }
        try {
            XposedBridge.hookAllMethods(clazz, methodName, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String permission = findPermissionOrAppOpPermission(param.args);
                    if (!shouldTrackPermission(permission)) {
                        return;
                    }
                    Object original = param.getResult();
                    Object result = forceAppOpsAllowedResult(param.method, original);
                    param.setResult(result);
                    logPermDiag(source, permission, PackageManager.PERMISSION_GRANTED,
                            original, result, null, "method=" + methodSignature(param.method)
                                    + " args=" + describeArgs(param.args));
                }
            });
            logPermDiag(source + ".hookInstalled", Manifest.permission.CAMERA,
                    "installed", "installed", AppOpsManager.MODE_ALLOWED, null, null);
        } catch (Throwable throwable) {
            logPermDiag(source + ".hookFailed", Manifest.permission.CAMERA,
                    throwable.getClass().getSimpleName(), throwable.getMessage(),
                    AppOpsManager.MODE_ALLOWED, null, "method=" + methodName);
        }
    }

    private static Class<?> findHookClass(ClassLoader loader, String className) {
        Class<?> clazz = null;
        try {
            clazz = XposedHelpers.findClassIfExists(className, loader);
        } catch (Throwable ignored) {
        }
        if (clazz != null) {
            return clazz;
        }
        try {
            return XposedHelpers.findClassIfExists(className, null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String findPermissionArg(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof String && shouldTrackPermission((String) arg)) {
                return (String) arg;
            }
        }
        for (Object arg : args) {
            if (arg instanceof String && ((String) arg).startsWith("android.permission.")) {
                return (String) arg;
            }
        }
        return null;
    }

    private static String findPermissionOrAppOpPermission(Object[] args) {
        String permission = findPermissionArg(args);
        if (permission != null) {
            return permission;
        }
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof String) {
                permission = appOpNameToPermission((String) arg);
                if (permission != null) {
                    return permission;
                }
            } else if (arg instanceof Integer) {
                permission = appOpToPermission((Integer) arg);
                if (permission != null) {
                    return permission;
                }
            }
        }
        return null;
    }

    private static String[] findPermissionArray(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof String[]) {
                return (String[]) arg;
            }
        }
        return null;
    }

    private static int[] findGrantResults(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof int[]) {
                return (int[]) arg;
            }
        }
        return null;
    }

    private static int findRequestCode(Object[] args) {
        if (args == null) {
            return -1;
        }
        for (Object arg : args) {
            if (arg instanceof Integer) {
                return (Integer) arg;
            }
        }
        return -1;
    }

    private static int[] grantedResults(String[] permissions) {
        int[] results = new int[permissions == null ? 0 : permissions.length];
        Arrays.fill(results, PackageManager.PERMISSION_GRANTED);
        return results;
    }

    private static boolean allTrackedPermissions(String[] permissions) {
        if (permissions == null || permissions.length == 0) {
            return false;
        }
        for (String permission : permissions) {
            if (!shouldTrackPermission(permission)) {
                return false;
            }
        }
        return true;
    }

    private static Object forcePermissionGrantedResult(Member member, Object original) {
        Class<?> returnType = member instanceof Method ? ((Method) member).getReturnType() : null;
        if (returnType == Integer.TYPE || returnType == Integer.class || original instanceof Integer) {
            return PackageManager.PERMISSION_GRANTED;
        }
        if (returnType == Boolean.TYPE || returnType == Boolean.class || original instanceof Boolean) {
            return true;
        }
        return original;
    }

    private static Object forceAppOpsAllowedResult(Member member, Object original) {
        Class<?> returnType = member instanceof Method ? ((Method) member).getReturnType() : null;
        if (returnType == Integer.TYPE || returnType == Integer.class || original instanceof Integer) {
            return AppOpsManager.MODE_ALLOWED;
        }
        if (returnType == Boolean.TYPE || returnType == Boolean.class || original instanceof Boolean) {
            return true;
        }
        if (returnType != null && "android.app.SyncNotedAppOp".equals(returnType.getName())) {
            try {
                return returnType.getConstructor(int.class, String.class)
                        .newInstance(AppOpsManager.MODE_ALLOWED, null);
            } catch (Throwable ignored) {
            }
        }
        return original;
    }

    private static boolean shouldTrackPermission(String permission) {
        if (permission == null) {
            return false;
        }
        for (String tracked : CALL_PERMISSION_NAMES) {
            if (tracked.equals(permission)) {
                return true;
            }
        }
        return false;
    }

    private static boolean virtualPermissionManagerGranted(String permission) {
        return shouldTrackPermission(permission);
    }

    private static String virtualPermissionState(String permission) {
        StringBuilder state = new StringBuilder();
        state.append("forcedGrant=").append(shouldTrackPermission(permission));
        try {
            Context context = toast_content;
            if (context == null) {
                state.append(",context=false");
                return state.toString();
            }
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(loadedPackageName, PackageManager.GET_PERMISSIONS);
            boolean requested = false;
            boolean flagGranted = false;
            if (info != null && info.requestedPermissions != null) {
                for (int i = 0; i < info.requestedPermissions.length; i++) {
                    if (!permission.equals(info.requestedPermissions[i])) {
                        continue;
                    }
                    requested = true;
                    flagGranted = info.requestedPermissionsFlags != null
                            && i < info.requestedPermissionsFlags.length
                            && (info.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
                    break;
                }
            }
            state.append(",manifestRequested=").append(requested)
                    .append(",requestedPermissionFlagGranted=").append(flagGranted);
        } catch (Throwable throwable) {
            state.append(",error=").append(throwable.getClass().getSimpleName());
        }
        return state.toString();
    }

    private static String appOpToPermission(int op) {
        try {
            Method method = AppOpsManager.class.getMethod("opToPermission", int.class);
            Object result = method.invoke(null, op);
            if (result instanceof String) {
                return (String) result;
            }
        } catch (Throwable ignored) {
        }
        try {
            Method method = AppOpsManager.class.getMethod("opToPublicName", int.class);
            Object result = method.invoke(null, op);
            return appOpNameToPermission(result == null ? null : String.valueOf(result));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String appOpNameToPermission(String opName) {
        if (opName == null) {
            return null;
        }
        String normalized = opName.toLowerCase(java.util.Locale.US);
        if (normalized.contains("camera")) return Manifest.permission.CAMERA;
        if (normalized.contains("record_audio") || normalized.contains("microphone")) return Manifest.permission.RECORD_AUDIO;
        if (normalized.contains("read_contacts")) return Manifest.permission.READ_CONTACTS;
        if (normalized.contains("write_contacts")) return Manifest.permission.WRITE_CONTACTS;
        if (normalized.contains("read_phone_state")) return Manifest.permission.READ_PHONE_STATE;
        if (normalized.contains("call_phone") || normalized.contains("phone_call")) return Manifest.permission.CALL_PHONE;
        if (normalized.contains("post_notification")) return "android.permission.POST_NOTIFICATIONS";
        if (normalized.contains("bluetooth_connect")) return "android.permission.BLUETOOTH_CONNECT";
        if (normalized.contains("modify_audio")) return Manifest.permission.MODIFY_AUDIO_SETTINGS;
        if (normalized.contains("foreground_service_camera")) return "android.permission.FOREGROUND_SERVICE_CAMERA";
        if (normalized.contains("foreground_service_microphone")) return "android.permission.FOREGROUND_SERVICE_MICROPHONE";
        if (normalized.contains("foreground_service")) return "android.permission.FOREGROUND_SERVICE";
        return null;
    }

    private static int safeVirtualUid() {
        try {
            Class<?> clazz = Class.forName("top.niunaijun.blackbox.app.BActivityThread");
            Object value = clazz.getMethod("getBUid").invoke(null);
            if (value instanceof Integer) {
                return (Integer) value;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static boolean isWhatsAppPackage(String packageName) {
        return "com.whatsapp".equals(packageName) || "com.whatsapp.w4b".equals(packageName);
    }

    private static String describeArgs(Object[] args) {
        if (args == null) {
            return "null";
        }
        try {
            return Arrays.deepToString(args);
        } catch (Throwable throwable) {
            return "error=" + throwable.getClass().getSimpleName();
        }
    }

    private static String methodSignature(Member member) {
        return member == null ? "unknown" : member.toString();
    }

    private static String nullable(Object value) {
        return value == null ? "null" : String.valueOf(value);
    }

    private static String callerFingerprint() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = 0; i < stack.length && count < 6; i++) {
            String cls = stack[i].getClassName();
            if (cls.startsWith("com.example.vcam") || cls.startsWith("dalvik") || cls.startsWith("java.lang.Thread")) {
                continue;
            }
            sb.append(stack[i].getClassName()).append('.').append(stack[i].getMethodName())
              .append(':').append(stack[i].getLineNumber()).append('>');
            count++;
        }
        return sb.length() > 0 ? sb.toString() : "unknown";
    }

    private static String surfaceId(Surface s) {
        if (s == null) return "null";
        return "Surface@" + Integer.toHexString(System.identityHashCode(s))
                + "(valid=" + s.isValid()
                + ",format=" + surfaceFormat(s)
                + ",size=" + surfaceSize(s)
                + ",str=" + s.toString() + ")";
    }

    private static String modeHint(String caller) {
        if (!"com.whatsapp".equals(loadedPackageName) && !"com.whatsapp.w4b".equals(loadedPackageName)) {
            return "OTHER_TARGET_CAMERA";
        }
        String value = caller == null ? "" : caller.toLowerCase(java.util.Locale.US);
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

    private static String describeSurfaceList(Object value) {
        if (!(value instanceof Iterable)) {
            return String.valueOf(value);
        }
        StringBuilder sb = new StringBuilder("[");
        int index = 0;
        for (Object item : (Iterable<?>) value) {
            if (index > 0) {
                sb.append(", ");
            }
            if (item instanceof Surface) {
                sb.append(surfaceId((Surface) item));
            } else {
                sb.append(objectLabel(item));
            }
            index++;
        }
        return sb.append(']').toString();
    }

    private static String describeOutputConfigurationList(Object value) {
        if (!(value instanceof Iterable)) {
            return String.valueOf(value);
        }
        StringBuilder sb = new StringBuilder("[");
        int index = 0;
        for (Object item : (Iterable<?>) value) {
            if (index > 0) {
                sb.append(", ");
            }
            sb.append(objectLabel(item)).append("{surfaces=");
            Object surfaces = callNoArg(item, "getSurfaces");
            sb.append(describeSurfaceList(surfaces));
            Object size = callNoArg(item, "getSurfaceSize");
            if (size != null) {
                sb.append(",size=").append(size);
            }
            sb.append('}');
            index++;
        }
        return sb.append(']').toString();
    }

    private static String builderDiagnostics(CaptureRequest.Builder builder) {
        if (builder == null) {
            return "builder=null";
        }
        return "builder@" + Integer.toHexString(System.identityHashCode(builder))
                + " fps=" + safeBuilderGet(builder, CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)
                + " controlMode=" + safeBuilderGet(builder, CaptureRequest.CONTROL_MODE)
                + " aeMode=" + safeBuilderGet(builder, CaptureRequest.CONTROL_AE_MODE)
                + " afMode=" + safeBuilderGet(builder, CaptureRequest.CONTROL_AF_MODE);
    }

    private static Object safeBuilderGet(CaptureRequest.Builder builder, CaptureRequest.Key<?> key) {
        try {
            return builder.get(key);
        } catch (Throwable ignored) {
            return "unavailable";
        }
    }

    private static int surfaceFormat(Surface surface) {
        if (surface == null || !surface.isValid()) {
            return Integer.MIN_VALUE;
        }
        try {
            Class<?> surfaceUtils = Class.forName("android.hardware.camera2.utils.SurfaceUtils");
            java.lang.reflect.Method method = surfaceUtils.getDeclaredMethod("getSurfaceFormat", Surface.class);
            method.setAccessible(true);
            Object value = method.invoke(null, surface);
            return value instanceof Number ? ((Number) value).intValue() : Integer.MIN_VALUE;
        } catch (Throwable ignored) {
            return Integer.MIN_VALUE;
        }
    }

    private static String surfaceSize(Surface surface) {
        if (surface == null || !surface.isValid()) {
            return "unknown";
        }
        try {
            Class<?> surfaceUtils = Class.forName("android.hardware.camera2.utils.SurfaceUtils");
            java.lang.reflect.Method method = surfaceUtils.getDeclaredMethod("getSurfaceSize", Surface.class);
            method.setAccessible(true);
            Object value = method.invoke(null, surface);
            return value == null ? "unknown" : value.toString();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static Object callNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            java.lang.reflect.Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String objectLabel(Object value) {
        if (value == null) {
            return "null";
        }
        return value.getClass().getName()
                + "@"
                + Integer.toHexString(System.identityHashCode(value))
                + "("
                + String.valueOf(value)
                + ")";
    }

    private static synchronized void startDefaultFrameSource(int width, int height) throws Exception {
        VirtualCameraBridge.ensureLocalSession(
                VirtualCameraBridge.DEFAULT_SLOT,
                width,
                height,
                VirtualCameraPreset.DEFAULT
        );
        File videoFile = VirtualMediaResolver.findVideoFile(video_path);
        File imageFile = VirtualMediaResolver.findImageFile(video_path);
        if (!videoFile.exists() && imageFile == null) {
            return;
        }
        String sourceKey = (videoFile.exists() ? videoFile.getAbsolutePath() : imageFile.getAbsolutePath())
                + ":" + width + "x" + height;
        if (sourceKey.equals(activeFrameSourceKey) && activeFrameSource != null && activeFrameSource.isRunning()) {
            return;
        }
        stopDefaultFrameSource();
        if (videoFile.exists()) {
            activeFrameSource = new MediaFileFrameSource(videoFile);
        } else {
            activeFrameSource = new StaticImageFrameSource(imageFile, width, height, VirtualCameraPreset.DEFAULT.fps);
        }
        activeFrameSourceKey = sourceKey;
        activeFrameSource.start(DEFAULT_SLOT_SINK);
    }

    private static synchronized void stopDefaultFrameSource() {
        if (activeFrameSource != null) {
            activeFrameSource.stop();
        }
        activeFrameSource = null;
        activeFrameSourceKey = null;
    }

    private static boolean hasFileSource() {
        return VirtualMediaResolver.hasAnySource(video_path);
    }

    private static boolean hasLiveSource() {
        return VirtualCameraBridge.hasLiveSource(VirtualCameraBridge.DEFAULT_SLOT);
    }

    private static boolean hasVirtualSource() {
        return hasFileSource() || hasLiveSource();
    }

    private static boolean isDisableControlActive(boolean liveSource) {
        return !liveSource && new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/disable.jpg").exists();
    }

    private static void logHook(String message) {
        Log.d(TAG_HOOK, message);
        de.robv.android.xposed.XposedBridge.log("[VCAM] " + message);
    }

    private static void logSurface(String message) {
        Log.d(TAG_SURFACE, message);
        de.robv.android.xposed.XposedBridge.log("[VCAM] " + message);
    }

    private static void logFrameCopy(String message) {
        long now = System.currentTimeMillis();
        if (now - lastFrameCopyLogMs < 2000) {
            return;
        }
        lastFrameCopyLogMs = now;
        Log.d(TAG_HOOK, message);
    }

    private static Nv21SurfaceRenderer restartRenderer(
            Nv21SurfaceRenderer renderer,
            Surface surface,
            String label,
            int width,
            int height
    ) {
        if (renderer != null && renderer.matches(surface, label, width, height)) {
            return renderer;
        }
        if (renderer != null) {
            renderer.stop();
        }
        return Nv21SurfaceRenderer.start(surface, label, width, height);
    }

    private static void stopSurfaceRenderers() {
        if (c1_holder_renderer != null) {
            c1_holder_renderer.stop();
            c1_holder_renderer = null;
        }
        if (c1_texture_renderer != null) {
            c1_texture_renderer.stop();
            c1_texture_renderer = null;
        }
        if (c2_preview_renderer != null) {
            c2_preview_renderer.stop();
            c2_preview_renderer = null;
        }
        if (c2_preview_renderer_1 != null) {
            c2_preview_renderer_1.stop();
            c2_preview_renderer_1 = null;
        }
        if (c2_reader_renderer != null) {
            c2_reader_renderer.stop();
            c2_reader_renderer = null;
        }
        if (c2_reader_renderer_1 != null) {
            c2_reader_renderer_1.stop();
            c2_reader_renderer_1 = null;
        }
    }

    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Exception {
        loadedPackageName = lpparam.packageName;
        loadedProcessName = lpparam.processName;
        loadedTargetSdk = lpparam.appInfo != null ? lpparam.appInfo.targetSdkVersion : -1;
        logDiag("loadPackage", "process=" + lpparam.processName + " classLoader=" + lpparam.classLoader);
        installPermissionDiagnostics(lpparam);
        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewTexture", SurfaceTexture.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                File file = VirtualMediaResolver.findVideoFile(video_path);
                boolean liveSource = hasLiveSource();
                if (file.exists() || liveSource) {
                    if (isDisableControlActive(liveSource)){
                        return;
                    }
                    if (is_hooked) {
                        is_hooked = false;
                        return;
                    }
                    if (param.args[0] == null) {
                        return;
                    }
                    if (param.args[0].equals(c1_fake_texture)) {
                        return;
                    }
                    if (origin_preview_camera != null && origin_preview_camera.equals(param.thisObject)) {
                        param.args[0] = fake_SurfaceTexture;
                        XposedBridge.log("[VCAM] Duplicate preview detected: " + origin_preview_camera.toString());
                        return;
                    } else {
                        XposedBridge.log("[VCAM] Creating preview");
                    }

                    origin_preview_camera = (Camera) param.thisObject;
                    logHook("Camera1 setPreviewTexture hooked package=" + lpparam.packageName + " live=" + liveSource);
                    logDiag("camera1_setPreviewTexture",
                            "live=" + liveSource
                                    + " originalTexture=" + objectLabel(param.args[0])
                                    + " camera=" + objectLabel(param.thisObject));
                    mSurfacetexture = (SurfaceTexture) param.args[0];
                    if (fake_SurfaceTexture == null) {
                        fake_SurfaceTexture = new SurfaceTexture(10);
                    } else {
                        fake_SurfaceTexture.release();
                        fake_SurfaceTexture = new SurfaceTexture(10);
                    }
                    param.args[0] = fake_SurfaceTexture;
                } else {
                    File toast_control = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no_toast.jpg");
                    need_to_show_toast = !toast_control.exists();
                    if (toast_content != null && need_to_show_toast) {
                        try {
                            Toast.makeText(toast_content, "No replacement video found\n" + lpparam.packageName + " path: " + video_path, Toast.LENGTH_SHORT).show();
                        } catch (Exception ee) {
                            XposedBridge.log("[VCAM] [toast]" + ee.toString());
                        }
                    }
                }
            }
        });

        XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraManager", lpparam.classLoader, "openCamera", String.class, CameraDevice.StateCallback.class, Handler.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (param.args[1] == null) {
                    return;
                }
                if (param.args[1].equals(c2_state_cb)) {
                    return;
                }
                c2_state_cb = (CameraDevice.StateCallback) param.args[1];
                c2_state_callback = param.args[1].getClass();
                boolean liveSource = hasLiveSource();
                sessionCounter++;
                logDiag("openCamera_handler", "cameraId=" + param.args[0] + " callbackClass=" + c2_state_callback.getName() + " live=" + liveSource);
                File control_file = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "disable.jpg");
                if (control_file.exists()) {
                    return;
                }
                File file = VirtualMediaResolver.findVideoFile(video_path);
                File toast_control = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no_toast.jpg");
                need_to_show_toast = !toast_control.exists();
                if (!file.exists() && !liveSource) {
                    if (toast_content != null && need_to_show_toast) {
                        try {
                            Toast.makeText(toast_content, "No replacement video found\n" + lpparam.packageName + " path: " + video_path, Toast.LENGTH_SHORT).show();
                        } catch (Exception ee) {
                            XposedBridge.log("[VCAM] [toast]" + ee.toString());
                        }
                    }
                    return;
                }
                XposedBridge.log("[VCAM] Camera2 openCamera (handler), callback: " + c2_state_callback.toString());
                is_first_hook_build = true;
                process_camera2_init(c2_state_callback);
            }
        });


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraManager", lpparam.classLoader, "openCamera", String.class, Executor.class, CameraDevice.StateCallback.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args[2] == null) {
                        return;
                    }
                    if (param.args[2].equals(c2_state_cb)) {
                        return;
                    }
                    c2_state_cb = (CameraDevice.StateCallback) param.args[2];
                    boolean liveSource = hasLiveSource();
                    sessionCounter++;
                    logDiag("openCamera_executor", "cameraId=" + param.args[0] + " callbackClass=" + param.args[2].getClass().getName() + " live=" + liveSource);
                    File control_file = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "disable.jpg");
                    if (control_file.exists()) {
                        return;
                    }
                    File file = VirtualMediaResolver.findVideoFile(video_path);
                    File toast_control = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no_toast.jpg");
                    need_to_show_toast = !toast_control.exists();
                    if (!file.exists() && !liveSource) {
                        if (toast_content != null && need_to_show_toast) {
                            try {
                                Toast.makeText(toast_content, "No replacement video found\n" + lpparam.packageName + " path: " + video_path, Toast.LENGTH_SHORT).show();
                            } catch (Exception ee) {
                                XposedBridge.log("[VCAM] [toast]" + ee.toString());
                            }
                        }
                        return;
                    }
                    c2_state_callback = param.args[2].getClass();
                    XposedBridge.log("[VCAM] Camera2 openCamera (executor), callback: " + c2_state_callback.toString());
                    is_first_hook_build = true;
                    process_camera2_init(c2_state_callback);
                }
            });
        }


        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewCallbackWithBuffer", Camera.PreviewCallback.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args[0] != null) {
                    process_callback(param);
                }
            }
        });

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "addCallbackBuffer", byte[].class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args[0] != null) {
                    param.args[0] = new byte[((byte[]) param.args[0]).length];
                }
            }
        });

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewCallback", Camera.PreviewCallback.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args[0] != null) {
                    process_callback(param);
                }
            }
        });

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setOneShotPreviewCallback", Camera.PreviewCallback.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args[0] != null) {
                    process_callback(param);
                }
            }
        });

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "takePicture", Camera.ShutterCallback.class, Camera.PictureCallback.class, Camera.PictureCallback.class, Camera.PictureCallback.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                XposedBridge.log("[VCAM] 4-parameter takePicture");
                if (param.args[1] != null) {
                    process_a_shot_YUV(param);
                }

                if (param.args[3] != null) {
                    process_a_shot_jpeg(param, 3);
                }
            }
        });

        XposedHelpers.findAndHookMethod("android.media.MediaRecorder", lpparam.classLoader, "setCamera", Camera.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                File toast_control = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no_toast.jpg");
                need_to_show_toast = !toast_control.exists();
                XposedBridge.log("[VCAM] [record]" + lpparam.packageName);
                if (toast_content != null && need_to_show_toast) {
                    try {
                        Toast.makeText(toast_content, "App: " + lpparam.appInfo.name + "(" + lpparam.packageName + ") triggered recording, but intercept is not supported", Toast.LENGTH_SHORT).show();
                    }catch (Exception ee){
                        XposedBridge.log("[VCAM] [toast]" + Arrays.toString(ee.getStackTrace()));
                    }
                }
            }
        });

        XposedHelpers.findAndHookMethod("android.app.Instrumentation", lpparam.classLoader, "callApplicationOnCreate", Application.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
                if (param.args[0] instanceof Application) {
                    try {
                        toast_content = ((Application) param.args[0]).getApplicationContext();
                    } catch (Exception ee) {
                        XposedBridge.log("[VCAM] " + ee.toString());
                    }
                    File force_private = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1/private_dir.jpg");
                    if (toast_content != null) {// Forced private directory check
                        int auth_statue = 0;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            try {
                                auth_statue += (toast_content.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) + 1);
                            } catch (Exception ee) {
                                XposedBridge.log("[VCAM] [permission-check]" + ee.toString());
                            }
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    auth_statue += (toast_content.checkSelfPermission(Manifest.permission.MANAGE_EXTERNAL_STORAGE) + 1);
                                }
                            } catch (Exception ee) {
                                XposedBridge.log("[VCAM] [permission-check]" + ee.toString());
                            }
                        }else {
                            if (toast_content.checkCallingPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ){
                                auth_statue = 2;
                            }
                        }
                        // Permission check completed
                        if (auth_statue < 1 || force_private.exists()) {
                            File shown_file = new File(toast_content.getExternalFilesDir(null).getAbsolutePath() + "/Camera1/");
                            if ((!shown_file.isDirectory()) && shown_file.exists()) {
                                shown_file.delete();
                            }
                            if (!shown_file.exists()) {
                                shown_file.mkdir();
                            }
                            shown_file = new File(toast_content.getExternalFilesDir(null).getAbsolutePath() + "/Camera1/" + "has_shown");
                            File toast_force_file = new File(Environment.getExternalStorageDirectory().getPath()+ "/DCIM/Camera1/force_show.jpg");
                            if ((!lpparam.packageName.equals(BuildConfig.APPLICATION_ID)) && ((!shown_file.exists()) || toast_force_file.exists())) {
                                try {
                                    Toast.makeText(toast_content, lpparam.packageName + " local directory permission not granted, please check permissions\nCamera1 redirected to " + toast_content.getExternalFilesDir(null).getAbsolutePath() + "/Camera1/", Toast.LENGTH_SHORT).show();
                                    FileOutputStream fos = new FileOutputStream(toast_content.getExternalFilesDir(null).getAbsolutePath() + "/Camera1/" + "has_shown");
                                    String info = "shown";
                                    fos.write(info.getBytes());
                                    fos.flush();
                                    fos.close();
                                } catch (Exception e) {
                                    XposedBridge.log("[VCAM] [switch-dir]" + e.toString());
                                }
                            }
                            video_path = toast_content.getExternalFilesDir(null).getAbsolutePath() + "/Camera1/";
                        }else {
                            video_path = Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/";
                        }
                    } else {
                        video_path = Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/";
                        File uni_DCIM_path = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/");
                        if (uni_DCIM_path.canWrite()) {
                            File uni_Camera1_path = new File(video_path);
                            if (!uni_Camera1_path.exists()) {
                                uni_Camera1_path.mkdir();
                            }
                        }
                    }
                }
            }
        });

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "startPreview", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                File file = VirtualMediaResolver.findVideoFile(video_path);
                boolean liveSource = hasLiveSource();
                File toast_control = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no_toast.jpg");
                need_to_show_toast = !toast_control.exists();
                if (!file.exists() && !liveSource) {
                    if (toast_content != null && need_to_show_toast) {
                        try {
                            Toast.makeText(toast_content, "No replacement video found\n" + lpparam.packageName + " path: " + video_path, Toast.LENGTH_SHORT).show();
                        } catch (Exception ee) {
                            XposedBridge.log("[VCAM] [toast]" + ee.toString());
                        }
                    }
                    return;
                }
                if (isDisableControlActive(liveSource)) {
                    return;
                }
                is_someone_playing = false;
                XposedBridge.log("[VCAM] startPreview callback, live=" + liveSource);
                start_preview_camera = (Camera) param.thisObject;

                if (liveSource) {
                    try {
                        startDefaultFrameSource(onemwidth > 0 ? onemwidth : 1280, onemhight > 0 ? onemhight : 720);
                    } catch (Exception e) {
                        XposedBridge.log("[VCAM] failed starting default frame source: " + e);
                    }
                }

                if (ori_holder != null) {
                    if (liveSource) {
                        c1_holder_renderer = restartRenderer(c1_holder_renderer, ori_holder.getSurface(), "c1_holder", onemwidth > 0 ? onemwidth : 1280, onemhight > 0 ? onemhight : 720);
                    } else {
                        if (mplayer1 == null) {
                            mplayer1 = new MediaPlayer();
                        } else {
                            mplayer1.release();
                            mplayer1 = null;
                            mplayer1 = new MediaPlayer();
                        }
                        if (!ori_holder.getSurface().isValid() || ori_holder == null) {
                            return;
                        }
                        mplayer1.setSurface(ori_holder.getSurface());
                        File sfile = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no-silent.jpg");
                        if (!(sfile.exists() && (!is_someone_playing))) {
                            mplayer1.setVolume(0, 0);
                            is_someone_playing = false;
                        } else {
                            is_someone_playing = true;
                        }
                        mplayer1.setLooping(true);

                        mplayer1.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                            @Override
                            public void onPrepared(MediaPlayer mp) {
                                mplayer1.start();
                            }
                        });

                        try {
                            mplayer1.setDataSource(video_path + "virtual.mp4");
                            mplayer1.prepare();
                        } catch (IOException e) {
                            XposedBridge.log("[VCAM] " + e.toString());
                        }
                    }
                }


                if (mSurfacetexture != null) {
                    if (liveSource) {
                        if (mSurface == null) {
                            mSurface = new Surface(mSurfacetexture);
                        } else {
                            mSurface.release();
                            mSurface = new Surface(mSurfacetexture);
                        }
                        c1_texture_renderer = restartRenderer(c1_texture_renderer, mSurface, "c1_texture", onemwidth > 0 ? onemwidth : 1280, onemhight > 0 ? onemhight : 720);
                    } else {
                        if (mSurface == null) {
                            mSurface = new Surface(mSurfacetexture);
                        } else {
                            mSurface.release();
                            mSurface = new Surface(mSurfacetexture);
                        }

                        if (mMediaPlayer == null) {
                            mMediaPlayer = new MediaPlayer();
                        } else {
                            mMediaPlayer.release();
                            mMediaPlayer = new MediaPlayer();
                        }

                        mMediaPlayer.setSurface(mSurface);

                        File sfile = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no-silent.jpg");
                        if (!(sfile.exists() && (!is_someone_playing))) {
                            mMediaPlayer.setVolume(0, 0);
                            is_someone_playing = false;
                        } else {
                            is_someone_playing = true;
                        }
                        mMediaPlayer.setLooping(true);

                        mMediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                            @Override
                            public void onPrepared(MediaPlayer mp) {
                                mMediaPlayer.start();
                            }
                        });

                        try {
                            mMediaPlayer.setDataSource(video_path + "virtual.mp4");
                            mMediaPlayer.prepare();
                        } catch (IOException e) {
                            XposedBridge.log("[VCAM] " + e.toString());
                        }
                    }
                }
            }
        });

        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "setPreviewDisplay", SurfaceHolder.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                XposedBridge.log("[VCAM] Camera1 setPreviewDisplay hook");
                File file = VirtualMediaResolver.findVideoFile(video_path);
                boolean liveSource = hasLiveSource();
                File toast_control = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no_toast.jpg");
                need_to_show_toast = !toast_control.exists();
                if (!file.exists() && !liveSource) {
                    if (toast_content != null && need_to_show_toast) {
                        try {
                            Toast.makeText(toast_content, "No replacement video found\n" + lpparam.packageName + " path: " + video_path, Toast.LENGTH_SHORT).show();
                        } catch (Exception ee) {
                            XposedBridge.log("[VCAM] [toast]" + ee.toString());
                        }
                    }
                    return;
                }
                if (isDisableControlActive(liveSource)) {
                    return;
                }
                mcamera1 = (Camera) param.thisObject;
                ori_holder = (SurfaceHolder) param.args[0];
                if (c1_fake_texture == null) {
                    c1_fake_texture = new SurfaceTexture(11);
                } else {
                    c1_fake_texture.release();
                    c1_fake_texture = null;
                    c1_fake_texture = new SurfaceTexture(11);
                }

                if (c1_fake_surface == null) {
                    c1_fake_surface = new Surface(c1_fake_texture);
                } else {
                    c1_fake_surface.release();
                    c1_fake_surface = null;
                    c1_fake_surface = new Surface(c1_fake_texture);
                }
                is_hooked = true;
                mcamera1.setPreviewTexture(c1_fake_texture);
                param.setResult(null);
            }
        });

        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder", lpparam.classLoader, "addTarget", Surface.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args[0] == null) {
                    return;
                }
                if (param.thisObject == null) {
                    return;
                }
                File file = VirtualMediaResolver.findVideoFile(video_path);
                boolean liveSource = hasLiveSource();
                File toast_control = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no_toast.jpg");
                need_to_show_toast = !toast_control.exists();
                if (!file.exists() && !liveSource) {
                    if (toast_content != null && need_to_show_toast) {
                        try {
                            Toast.makeText(toast_content, "No replacement video found\n" + lpparam.packageName + " path: " + video_path, Toast.LENGTH_SHORT).show();
                        } catch (Exception ee) {
                            XposedBridge.log("[VCAM] [toast]" + ee.toString());
                        }
                    }
                    return;
                }
                if (param.args[0].equals(c2_virtual_surface)) {
                    return;
                }
                if (isDisableControlActive(liveSource)) {
                    return;
                }
                String surfaceInfo = param.args[0].toString();
                if (surfaceInfo.contains("Surface(name=null)")) {
                    if (c2_reader_Surfcae == null) {
                        c2_reader_Surfcae = (Surface) param.args[0];
                    } else {
                        if ((!c2_reader_Surfcae.equals(param.args[0])) && c2_reader_Surfcae_1 == null) {
                            c2_reader_Surfcae_1 = (Surface) param.args[0];
                        }
                    }
                } else {
                    if (c2_preview_Surfcae == null) {
                        c2_preview_Surfcae = (Surface) param.args[0];
                    } else {
                        if ((!c2_preview_Surfcae.equals(param.args[0])) && c2_preview_Surfcae_1 == null) {
                            c2_preview_Surfcae_1 = (Surface) param.args[0];
                        }
                    }
                }
                logDiag("request_addTarget",
                        "live=" + liveSource
                                + " builder=" + builderDiagnostics((CaptureRequest.Builder) param.thisObject)
                                + " original=" + surfaceId((Surface) param.args[0])
                                + " virtual=" + surfaceId(c2_virtual_surface)
                                + " readerPrimary=" + surfaceId(c2_reader_Surfcae)
                                + " previewPrimary=" + surfaceId(c2_preview_Surfcae));
                XposedBridge.log("[VCAM] addTarget: " + surfaceId((Surface) param.args[0]) + " -> " + surfaceId(c2_virtual_surface));
                param.args[0] = c2_virtual_surface;
            }
        });

        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder", lpparam.classLoader, "removeTarget", Surface.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args[0] == null) {
                    return;
                }
                if (param.thisObject == null) {
                    return;
                }
                File file = VirtualMediaResolver.findVideoFile(video_path);
                boolean liveSource = hasLiveSource();
                File toast_control = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no_toast.jpg");
                need_to_show_toast = !toast_control.exists();
                if (!file.exists() && !liveSource) {
                    if (toast_content != null && need_to_show_toast) {
                        try {
                            Toast.makeText(toast_content, "No replacement video found\n" + lpparam.packageName + " path: " + video_path, Toast.LENGTH_SHORT).show();
                        } catch (Exception ee) {
                            XposedBridge.log("[VCAM] [toast]" + ee.toString());
                        }
                    }
                    return;
                }
                if (isDisableControlActive(liveSource)) {
                    return;
                }
                Surface rm_surf = (Surface) param.args[0];
                if (rm_surf.equals(c2_preview_Surfcae)) {
                    c2_preview_Surfcae = null;
                }
                if (rm_surf.equals(c2_preview_Surfcae_1)) {
                    c2_preview_Surfcae_1 = null;
                }
                if (rm_surf.equals(c2_reader_Surfcae_1)) {
                    c2_reader_Surfcae_1 = null;
                }
                if (rm_surf.equals(c2_reader_Surfcae)) {
                    c2_reader_Surfcae = null;
                }

                logDiag("request_removeTarget",
                        "live=" + liveSource
                                + " builder=" + builderDiagnostics((CaptureRequest.Builder) param.thisObject)
                                + " removed=" + surfaceId(rm_surf));
                XposedBridge.log("[VCAM] removeTarget: " + surfaceId(rm_surf));
            }
        });

        XposedHelpers.findAndHookMethod("android.hardware.camera2.CaptureRequest.Builder", lpparam.classLoader, "build", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (param.thisObject == null) {
                    return;
                }
                if (param.thisObject.equals(c2_builder)) {
                    return;
                }
                c2_builder = (CaptureRequest.Builder) param.thisObject;
                File file = VirtualMediaResolver.findVideoFile(video_path);
                boolean liveSource = hasLiveSource();
                File toast_control = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no_toast.jpg");
                need_to_show_toast = !toast_control.exists();
                if (!file.exists() && !liveSource && need_to_show_toast) {
                    if (toast_content != null) {
                        try {
                            Toast.makeText(toast_content, "No replacement video found\n" + lpparam.packageName + " path: " + video_path, Toast.LENGTH_SHORT).show();
                        } catch (Exception ee) {
                            XposedBridge.log("[VCAM] [toast]" + ee.toString());
                        }
                    }
                    return;
                }

                if (isDisableControlActive(liveSource)) {
                    return;
                }
                logDiag("request_build",
                        "live=" + liveSource
                                + " " + builderDiagnostics((CaptureRequest.Builder) param.thisObject)
                                + " virtual=" + surfaceId(c2_virtual_surface)
                                + " reader0=" + surfaceId(c2_reader_Surfcae)
                                + " reader1=" + surfaceId(c2_reader_Surfcae_1)
                                + " preview0=" + surfaceId(c2_preview_Surfcae)
                                + " preview1=" + surfaceId(c2_preview_Surfcae_1));
                XposedBridge.log("[VCAM] CaptureRequest.Builder build, live=" + liveSource);
                process_camera2_play();
            }
        });

/*        XposedHelpers.findAndHookMethod("android.hardware.Camera", lpparam.classLoader, "stopPreview", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.thisObject.equals(HookMain.origin_preview_camera) || param.thisObject.equals(HookMain.camera_onPreviewFrame) || param.thisObject.equals(HookMain.mcamera1)) {
                    if (hw_decode_obj != null) {
                        hw_decode_obj.stopDecode();
                    }
                    if (mplayer1 != null) {
                        mplayer1.release();
                        mplayer1 = null;
                    }
                    if (mMediaPlayer != null) {
                        mMediaPlayer.release();
                        mMediaPlayer = null;
                    }
                    is_someone_playing = false;

                    XposedBridge.log("[VCAM] stopPreview");
                }
            }
        });*/

        XposedHelpers.findAndHookMethod("android.media.ImageReader", lpparam.classLoader, "newInstance", int.class, int.class, int.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                XposedBridge.log("[VCAM] ImageReader.newInstance: width=" + param.args[0] + " height=" + param.args[1] + " format=" + param.args[2]);
                logDiag("imageReader_newInstance",
                        "width=" + param.args[0]
                                + " height=" + param.args[1]
                                + " format=" + param.args[2]
                                + " maxImages=" + param.args[3]);
                c2_ori_width = (int) param.args[0];
                c2_ori_height = (int) param.args[1];
                imageReaderFormat = (int) param.args[2];
                File toast_control = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no_toast.jpg");
                need_to_show_toast = !toast_control.exists();
                if (toast_content != null && need_to_show_toast) {
                    try {
                        Toast.makeText(toast_content, "Renderer created:\nwidth: " + param.args[0] + "\nheight: " + param.args[1] + "\nAspect ratio should match target", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        XposedBridge.log("[VCAM] [toast]" + e.toString());
                    }
                }
            }
        });


        XposedHelpers.findAndHookMethod("android.hardware.camera2.CameraCaptureSession.CaptureCallback", lpparam.classLoader, "onCaptureFailed", CameraCaptureSession.class, CaptureRequest.class, CaptureFailure.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        XposedBridge.log("[VCAM] onCaptureFailed reason: " + ((CaptureFailure) param.args[2]).getReason());
                        logDiag("capture_failed",
                                "reason=" + ((CaptureFailure) param.args[2]).getReason()
                                        + " request=" + objectLabel(param.args[1])
                                        + " session=" + objectLabel(param.args[0]));

                    }
                });
    }

    private void process_camera2_play() {
        boolean liveSource = hasLiveSource();
        int width = c2_ori_width > 0 ? c2_ori_width : 1280;
        int height = c2_ori_height > 0 ? c2_ori_height : 720;

        if (liveSource) {
            try {
                startDefaultFrameSource(width, height);
            } catch (Exception e) {
                XposedBridge.log("[VCAM] failed starting default frame source on C2 play: " + e);
            }
        }

        if (c2_reader_Surfcae != null) {
            if (liveSource) {
                c2_reader_renderer = restartRenderer(c2_reader_renderer, c2_reader_Surfcae, "c2_reader", width, height);
            } else {
                if (c2_hw_decode_obj != null) {
                    c2_hw_decode_obj.stopDecode();
                    c2_hw_decode_obj = null;
                }

                c2_hw_decode_obj = new VideoToFrames();
                try {
                    if (imageReaderFormat == 256) {
                        c2_hw_decode_obj.setSaveFrames("null", OutputImageFormat.JPEG);
                    } else {
                        c2_hw_decode_obj.setSaveFrames("null", OutputImageFormat.NV21);
                    }
                    c2_hw_decode_obj.set_surfcae(c2_reader_Surfcae);
                    c2_hw_decode_obj.decode(video_path + "virtual.mp4");
                } catch (Throwable throwable) {
                    XposedBridge.log("[VCAM] " + throwable);
                }
            }
        }

        if (c2_reader_Surfcae_1 != null) {
            if (liveSource) {
                c2_reader_renderer_1 = restartRenderer(c2_reader_renderer_1, c2_reader_Surfcae_1, "c2_reader_1", width, height);
            } else {
                if (c2_hw_decode_obj_1 != null) {
                    c2_hw_decode_obj_1.stopDecode();
                    c2_hw_decode_obj_1 = null;
                }

                c2_hw_decode_obj_1 = new VideoToFrames();
                try {
                    if (imageReaderFormat == 256) {
                        c2_hw_decode_obj_1.setSaveFrames("null", OutputImageFormat.JPEG);
                    } else {
                        c2_hw_decode_obj_1.setSaveFrames("null", OutputImageFormat.NV21);
                    }
                    c2_hw_decode_obj_1.set_surfcae(c2_reader_Surfcae_1);
                    c2_hw_decode_obj_1.decode(video_path + "virtual.mp4");
                } catch (Throwable throwable) {
                    XposedBridge.log("[VCAM] " + throwable);
                }
            }
        }


        if (c2_preview_Surfcae != null) {
            if (liveSource) {
                c2_preview_renderer = restartRenderer(c2_preview_renderer, c2_preview_Surfcae, "c2_preview", width, height);
            } else {
                if (c2_player == null) {
                    c2_player = new MediaPlayer();
                } else {
                    c2_player.release();
                    c2_player = new MediaPlayer();
                }
                c2_player.setSurface(c2_preview_Surfcae);
                File sfile = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no-silent.jpg");
                if (!sfile.exists()) {
                    c2_player.setVolume(0, 0);
                }
                c2_player.setLooping(true);

                try {
                    c2_player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                        public void onPrepared(MediaPlayer mp) {
                            c2_player.start();
                        }
                    });
                    c2_player.setDataSource(video_path + "virtual.mp4");
                    c2_player.prepare();
                } catch (Exception e) {
                    XposedBridge.log("[VCAM] [c2player][" + c2_preview_Surfcae.toString() + "]" + e);
                }
            }
        }

        if (c2_preview_Surfcae_1 != null) {
            if (liveSource) {
                c2_preview_renderer_1 = restartRenderer(c2_preview_renderer_1, c2_preview_Surfcae_1, "c2_preview_1", width, height);
            } else {
                if (c2_player_1 == null) {
                    c2_player_1 = new MediaPlayer();
                } else {
                    c2_player_1.release();
                    c2_player_1 = new MediaPlayer();
                }
                c2_player_1.setSurface(c2_preview_Surfcae_1);
                File sfile = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no-silent.jpg");
                if (!sfile.exists()) {
                    c2_player_1.setVolume(0, 0);
                }
                c2_player_1.setLooping(true);

                try {
                    c2_player_1.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                        public void onPrepared(MediaPlayer mp) {
                            c2_player_1.start();
                        }
                    });
                    c2_player_1.setDataSource(video_path + "virtual.mp4");
                    c2_player_1.prepare();
                } catch (Exception e) {
                    XposedBridge.log("[VCAM] [c2player1]" + "[ " + c2_preview_Surfcae_1.toString() + "]" + e);
                }
            }
        }
        XposedBridge.log("[VCAM] Camera2 processing completed, live=" + liveSource);
    }

    private Surface create_virtual_surface() {
        if (need_recreate) {
            if (c2_virtual_surfaceTexture != null) {
                c2_virtual_surfaceTexture.release();
                c2_virtual_surfaceTexture = null;
            }
            if (c2_virtual_surface != null) {
                c2_virtual_surface.release();
                c2_virtual_surface = null;
            }
            c2_virtual_surfaceTexture = new SurfaceTexture(15);
            c2_virtual_surface = new Surface(c2_virtual_surfaceTexture);
            need_recreate = false;
        } else {
            if (c2_virtual_surface == null) {
                need_recreate = true;
                c2_virtual_surface = create_virtual_surface();
            }
        }
        XposedBridge.log("[VCAM] [recreate virtual surface] " + c2_virtual_surface.toString());
        return c2_virtual_surface;
    }

    private void process_camera2_init(Class hooked_class) {

        XposedHelpers.findAndHookMethod(hooked_class, "onOpened", CameraDevice.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                need_recreate = true;
                create_virtual_surface();
                if (c2_player != null) {
                    c2_player.stop();
                    c2_player.reset();
                    c2_player.release();
                    c2_player = null;
                }
                if (c2_hw_decode_obj_1 != null) {
                    c2_hw_decode_obj_1.stopDecode();
                    c2_hw_decode_obj_1 = null;
                }
                if (c2_hw_decode_obj != null) {
                    c2_hw_decode_obj.stopDecode();
                    c2_hw_decode_obj = null;
                }
                if (c2_player_1 != null) {
                    c2_player_1.stop();
                    c2_player_1.reset();
                    c2_player_1.release();
                    c2_player_1 = null;
                }
                c2_preview_Surfcae_1 = null;
                c2_reader_Surfcae_1 = null;
                c2_reader_Surfcae = null;
                c2_preview_Surfcae = null;
                is_first_hook_build = true;
                XposedBridge.log("[VCAM] Camera2 onOpened, device=" + param.args[0].toString());
                logDiag("camera2_onOpened",
                        "device=" + objectLabel(param.args[0])
                                + " callbackClass=" + hooked_class.getName()
                                + " virtual=" + surfaceId(c2_virtual_surface));

                File file = VirtualMediaResolver.findVideoFile(video_path);
                boolean liveSource = hasLiveSource();
                File toast_control = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no_toast.jpg");
                need_to_show_toast = !toast_control.exists();
                if (!file.exists() && !liveSource) {
                    if (toast_content != null && need_to_show_toast) {
                        try {
                            Toast.makeText(toast_content, "No replacement video found\n" + toast_content.getPackageName() + " path: " + video_path, Toast.LENGTH_SHORT).show();
                        } catch (Exception ee) {
                            XposedBridge.log("[VCAM] [toast]" + ee.toString());
                        }
                    }
                    return;
                }
                XposedHelpers.findAndHookMethod(param.args[0].getClass(), "createCaptureSession", List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam paramd) throws Throwable {
                        if (paramd.args[0] != null) {
                            XposedBridge.log("[VCAM] createCaptureSession, original=" + paramd.args[0].toString() + " virtual=" + c2_virtual_surface.toString());
                            logDiag("session_create_list",
                                    "originalSurfaces=" + describeSurfaceList(paramd.args[0])
                                            + " virtual=" + surfaceId(c2_virtual_surface)
                                            + " callback=" + objectLabel(paramd.args[1])
                                            + " handler=" + objectLabel(paramd.args[2]));
                            paramd.args[0] = Arrays.asList(c2_virtual_surface);
                            if (paramd.args[1] != null) {
                                process_camera2Session_callback((CameraCaptureSession.StateCallback) paramd.args[1]);
                            }
                        }
                    }
                });

/*                XposedHelpers.findAndHookMethod(param.args[0].getClass(), "close", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam paramd) throws Throwable {
                        XposedBridge.log("[VCAM] C2 stop preview");
                        if (c2_hw_decode_obj != null) {
                            c2_hw_decode_obj.stopDecode();
                            c2_hw_decode_obj = null;
                        }
                        if (c2_hw_decode_obj_1 != null) {
                            c2_hw_decode_obj_1.stopDecode();
                            c2_hw_decode_obj_1 = null;
                        }
                        if (c2_player != null) {
                            c2_player.release();
                            c2_player = null;
                        }
                        if (c2_player_1 != null){
                            c2_player_1.release();
                            c2_player_1 = null;
                        }
                        c2_preview_Surfcae_1 = null;
                        c2_reader_Surfcae_1 = null;
                        c2_reader_Surfcae = null;
                        c2_preview_Surfcae = null;
                        need_recreate = true;
                        is_first_hook_build= true;
                    }
                });*/

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    XposedHelpers.findAndHookMethod(param.args[0].getClass(), "createCaptureSessionByOutputConfigurations", List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            super.beforeHookedMethod(param);
                            if (param.args[0] != null) {
                                outputConfiguration = new OutputConfiguration(c2_virtual_surface);
                                logDiag("session_create_outputConfigurations",
                                        "originalConfigurations=" + describeOutputConfigurationList(param.args[0])
                                                + " virtual=" + surfaceId(c2_virtual_surface)
                                                + " callback=" + objectLabel(param.args[1])
                                                + " handler=" + objectLabel(param.args[2]));
                                param.args[0] = Arrays.asList(outputConfiguration);

                                XposedBridge.log("[VCAM] createCaptureSessionByOutputConfigurations");
                                if (param.args[1] != null) {
                                    process_camera2Session_callback((CameraCaptureSession.StateCallback) param.args[1]);
                                }
                            }
                        }
                    });
                }


                    XposedHelpers.findAndHookMethod(param.args[0].getClass(), "createConstrainedHighSpeedCaptureSession", List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            super.beforeHookedMethod(param);
                            if (param.args[0] != null) {
                                logDiag("session_create_highSpeed",
                                        "originalSurfaces=" + describeSurfaceList(param.args[0])
                                                + " virtual=" + surfaceId(c2_virtual_surface)
                                                + " callback=" + objectLabel(param.args[1])
                                                + " handler=" + objectLabel(param.args[2]));
                                param.args[0] = Arrays.asList(c2_virtual_surface);
                                XposedBridge.log("[VCAM] createConstrainedHighSpeedCaptureSession");
                                if (param.args[1] != null) {
                                    process_camera2Session_callback((CameraCaptureSession.StateCallback) param.args[1]);
                                }
                            }
                        }
                    });


                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    XposedHelpers.findAndHookMethod(param.args[0].getClass(), "createReprocessableCaptureSession", InputConfiguration.class, List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            super.beforeHookedMethod(param);
                            if (param.args[1] != null) {
                                logDiag("session_create_reprocessable",
                                        "inputConfiguration=" + objectLabel(param.args[0])
                                                + " originalSurfaces=" + describeSurfaceList(param.args[1])
                                                + " virtual=" + surfaceId(c2_virtual_surface)
                                                + " callback=" + objectLabel(param.args[2])
                                                + " handler=" + objectLabel(param.args[3]));
                                param.args[1] = Arrays.asList(c2_virtual_surface);
                                XposedBridge.log("[VCAM] createReprocessableCaptureSession");
                                if (param.args[2] != null) {
                                    process_camera2Session_callback((CameraCaptureSession.StateCallback) param.args[2]);
                                }
                            }
                        }
                    });
                }


                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    XposedHelpers.findAndHookMethod(param.args[0].getClass(), "createReprocessableCaptureSessionByConfigurations", InputConfiguration.class, List.class, CameraCaptureSession.StateCallback.class, Handler.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            super.beforeHookedMethod(param);
                            if (param.args[1] != null) {
                                outputConfiguration = new OutputConfiguration(c2_virtual_surface);
                                logDiag("session_create_reprocessableConfigurations",
                                        "inputConfiguration=" + objectLabel(param.args[0])
                                                + " originalConfigurations=" + describeOutputConfigurationList(param.args[1])
                                                + " virtual=" + surfaceId(c2_virtual_surface)
                                                + " callback=" + objectLabel(param.args[2])
                                                + " handler=" + objectLabel(param.args[3]));
                                param.args[0] = Arrays.asList(outputConfiguration);
                                XposedBridge.log("[VCAM] createReprocessableCaptureSessionByConfigurations");
                                if (param.args[2] != null) {
                                    process_camera2Session_callback((CameraCaptureSession.StateCallback) param.args[2]);
                                }
                            }
                        }
                    });
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    XposedHelpers.findAndHookMethod(param.args[0].getClass(), "createCaptureSession", SessionConfiguration.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            super.beforeHookedMethod(param);
                            if (param.args[0] != null) {
                                XposedBridge.log("[VCAM] createCaptureSession SessionConfiguration");
                                sessionConfiguration = (SessionConfiguration) param.args[0];
                                logDiag("session_create_sessionConfiguration",
                                        "sessionType=" + sessionConfiguration.getSessionType()
                                                + " outputs=" + describeOutputConfigurationList(sessionConfiguration.getOutputConfigurations())
                                                + " executor=" + objectLabel(sessionConfiguration.getExecutor())
                                                + " callback=" + objectLabel(sessionConfiguration.getStateCallback())
                                                + " virtual=" + surfaceId(c2_virtual_surface));
                                outputConfiguration = new OutputConfiguration(c2_virtual_surface);
                                fake_sessionConfiguration = new SessionConfiguration(sessionConfiguration.getSessionType(),
                                        Arrays.asList(outputConfiguration),
                                        sessionConfiguration.getExecutor(),
                                        sessionConfiguration.getStateCallback());
                                param.args[0] = fake_sessionConfiguration;
                                process_camera2Session_callback(sessionConfiguration.getStateCallback());
                            }
                        }
                    });
                }
            }
        });


        XposedHelpers.findAndHookMethod(hooked_class, "onError", CameraDevice.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                XposedBridge.log("[VCAM] Camera onError: " + (int) param.args[1]);
                logDiag("camera2_onError", "device=" + objectLabel(param.args[0]) + " error=" + param.args[1]);
            }

        });


        XposedHelpers.findAndHookMethod(hooked_class, "onDisconnected", CameraDevice.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                XposedBridge.log("[VCAM] Camera onDisconnected");
                logDiag("camera2_onDisconnected", "device=" + objectLabel(param.args[0]));
            }

        });


    }

    private void process_a_shot_jpeg(XC_MethodHook.MethodHookParam param, int index) {
        try {
            XposedBridge.log("[VCAM] JPEG callback: " + param.args[index].toString());
        } catch (Exception eee) {
            XposedBridge.log("[VCAM] " + eee);

        }
        Class callback = param.args[index].getClass();

        XposedHelpers.findAndHookMethod(callback, "onPictureTaken", byte[].class, android.hardware.Camera.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam paramd) throws Throwable {
                try {
                    Camera loaclcam = (Camera) paramd.args[1];
                    onemwidth = loaclcam.getParameters().getPreviewSize().width;
                    onemhight = loaclcam.getParameters().getPreviewSize().height;
                    XposedBridge.log("[VCAM] JPEG picture taken callback initialized: " + onemwidth + "x" + onemhight + " camera=" + loaclcam.toString());
                    File toast_control = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no_toast.jpg");
                    need_to_show_toast = !toast_control.exists();
                    if (toast_content != null && need_to_show_toast) {
                        try {
                            Toast.makeText(toast_content, "Picture taken\nwidth: " + onemwidth + "\nheight: " + onemhight + "\nFormat: JPEG", Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            XposedBridge.log("[VCAM] [toast]" + e.toString());
                        }
                    }
                    File control_file = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "disable.jpg");
                    if (control_file.exists()) {
                        return;
                    }

                    Bitmap pict = getBMP(video_path + "1000.bmp");
                    ByteArrayOutputStream temp_array = new ByteArrayOutputStream();
                    pict.compress(Bitmap.CompressFormat.JPEG, 100, temp_array);
                    byte[] jpeg_data = temp_array.toByteArray();
                    paramd.args[0] = jpeg_data;
                } catch (Exception ee) {
                    XposedBridge.log("[VCAM] " + ee.toString());
                }
            }
        });
    }

    private void process_a_shot_YUV(XC_MethodHook.MethodHookParam param) {
        try {
            XposedBridge.log("[VCAM] picture taken YUV callback: " + param.args[1].toString());
        } catch (Exception eee) {
            XposedBridge.log("[VCAM] " + eee);
        }
        Class callback = param.args[1].getClass();
        XposedHelpers.findAndHookMethod(callback, "onPictureTaken", byte[].class, android.hardware.Camera.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam paramd) throws Throwable {
                try {
                    Camera loaclcam = (Camera) paramd.args[1];
                    onemwidth = loaclcam.getParameters().getPreviewSize().width;
                    onemhight = loaclcam.getParameters().getPreviewSize().height;
                    XposedBridge.log("[VCAM] YUV picture taken callback initialized: " + onemwidth + "x" + onemhight + " camera=" + loaclcam.toString());
                    File toast_control = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no_toast.jpg");
                    need_to_show_toast = !toast_control.exists();
                    if (toast_content != null && need_to_show_toast) {
                        try {
                            Toast.makeText(toast_content, "Picture taken\nwidth: " + onemwidth + "\nheight: " + onemhight + "\nFormat: YUV_420_888", Toast.LENGTH_SHORT).show();
                        } catch (Exception ee) {
                            XposedBridge.log("[VCAM] [toast]" + ee.toString());
                        }
                    }
                    File control_file = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "disable.jpg");
                    if (control_file.exists()) {
                        return;
                    }
                    input = getYUVByBitmap(getBMP(video_path + "1000.bmp"));
                    paramd.args[0] = input;
                } catch (Exception ee) {
                    XposedBridge.log("[VCAM] " + ee.toString());
                }
            }
        });
    }

    private void process_callback(XC_MethodHook.MethodHookParam param) {
        Class preview_cb_class = param.args[0].getClass();
        boolean liveSource = hasLiveSource();
        int need_stop = 0;
        File control_file = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "disable.jpg");
        if (control_file.exists()) {
            need_stop = 1;
        }
        File file = VirtualMediaResolver.findVideoFile(video_path);
        File toast_control = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no_toast.jpg");
        need_to_show_toast = !toast_control.exists();
        if (!file.exists() && !liveSource) {
            if (toast_content != null && need_to_show_toast) {
                try {
                    Toast.makeText(toast_content, "No replacement video found\n" + toast_content.getPackageName() + " path: " + video_path, Toast.LENGTH_SHORT).show();
                } catch (Exception ee) {
                    XposedBridge.log("[VCAM] [toast]" + ee);
                }
            }
            need_stop = 1;
        }
        int finalNeed_stop = need_stop;
        XposedHelpers.findAndHookMethod(preview_cb_class, "onPreviewFrame", byte[].class, android.hardware.Camera.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam paramd) throws Throwable {
                Camera localcam = (android.hardware.Camera) paramd.args[1];
                if (localcam.equals(camera_onPreviewFrame)) {
                    if (liveSource) {
                        VirtualCameraBridge.copyLatestNv21To(VirtualCameraBridge.DEFAULT_SLOT, (byte[]) paramd.args[0], mwidth, mhight);
                        logFrameCopy("Camera1 onPreviewFrame copied live frame, len=" + ((byte[]) paramd.args[0]).length);
                    } else {
                        while (data_buffer == null) {
                        }
                        System.arraycopy(data_buffer, 0, paramd.args[0], 0, Math.min(data_buffer.length, ((byte[]) paramd.args[0]).length));
                    }
                } else {
                    camera_callback_calss = preview_cb_class;
                    camera_onPreviewFrame = (android.hardware.Camera) paramd.args[1];
                    mwidth = camera_onPreviewFrame.getParameters().getPreviewSize().width;
                    mhight = camera_onPreviewFrame.getParameters().getPreviewSize().height;
                    int frame_Rate = camera_onPreviewFrame.getParameters().getPreviewFrameRate();
                    XposedBridge.log("[VCAM] preview callback initialized: " + mwidth + "x" + mhight + " fps=" + frame_Rate + " live=" + liveSource);
                    logDiag("camera1_preview_callback_initialized",
                            "size=" + mwidth + "x" + mhight
                                    + " fps=" + frame_Rate
                                    + " live=" + liveSource
                                    + " camera=" + objectLabel(camera_onPreviewFrame)
                                    + " callbackClass=" + preview_cb_class.getName());
                    File toast_control = new File(Environment.getExternalStorageDirectory().getPath() + "/DCIM/Camera1/" + "no_toast.jpg");
                    need_to_show_toast = !toast_control.exists();
                    if (toast_content != null && need_to_show_toast) {
                        try {
                            Toast.makeText(toast_content, "Preview initialized\n" + mwidth + "x" + mhight + "\n" + "Resolution match required", Toast.LENGTH_SHORT).show();
                        } catch (Exception ee) {
                            XposedBridge.log("[VCAM] [toast]" + ee.toString());
                        }
                    }
                    if (finalNeed_stop == 1) {
                        return;
                    }
                    if (liveSource) {
                        try {
                            startDefaultFrameSource(mwidth, mhight);
                        } catch (Exception e) {
                            XposedBridge.log("[VCAM] failed starting default frame source on preview init: " + e);
                        }
                    } else {
                        if (hw_decode_obj != null) {
                            hw_decode_obj.stopDecode();
                        }
                        hw_decode_obj = new VideoToFrames();
                        hw_decode_obj.setSaveFrames("", OutputImageFormat.NV21);
                        hw_decode_obj.decode(video_path + "virtual.mp4");
                    }
                    if (liveSource) {
                        VirtualCameraBridge.copyLatestNv21To(VirtualCameraBridge.DEFAULT_SLOT, (byte[]) paramd.args[0], mwidth, mhight);
                    } else {
                        while (data_buffer == null) {
                        }
                        System.arraycopy(data_buffer, 0, paramd.args[0], 0, Math.min(data_buffer.length, ((byte[]) paramd.args[0]).length));
                    }
                }
            }
        });
    }

    private void process_camera2Session_callback(CameraCaptureSession.StateCallback callback_calss){
        if (callback_calss == null){
            return;
        }
        XposedHelpers.findAndHookMethod(callback_calss.getClass(), "onConfigureFailed", CameraCaptureSession.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                XposedBridge.log("[VCAM] onConfigureFailed: " + param.args[0].toString());
                logDiag("session_onConfigureFailed",
                        "callbackClass=" + callback_calss.getClass().getName()
                                + " sessionObject=" + objectLabel(param.args[0])
                                + " virtual=" + surfaceId(c2_virtual_surface));
            }

        });

        XposedHelpers.findAndHookMethod(callback_calss.getClass(), "onConfigured", CameraCaptureSession.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                XposedBridge.log("[VCAM] onConfigured: " + param.args[0].toString());
                logDiag("session_onConfigured",
                        "callbackClass=" + callback_calss.getClass().getName()
                                + " sessionObject=" + objectLabel(param.args[0])
                                + " virtual=" + surfaceId(c2_virtual_surface)
                                + " imageReader=" + c2_ori_width + "x" + c2_ori_height + "/" + imageReaderFormat
                                + " reader0=" + surfaceId(c2_reader_Surfcae)
                                + " reader1=" + surfaceId(c2_reader_Surfcae_1)
                                + " preview0=" + surfaceId(c2_preview_Surfcae)
                                + " preview1=" + surfaceId(c2_preview_Surfcae_1));
            }
        });

        XposedHelpers.findAndHookMethod( callback_calss.getClass(), "onClosed", CameraCaptureSession.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                XposedBridge.log("[VCAM] onClosed: " + param.args[0].toString());
                logDiag("session_onClosed",
                        "callbackClass=" + callback_calss.getClass().getName()
                                + " sessionObject=" + objectLabel(param.args[0]));
            }
        });
    }



    // Source: https://blog.csdn.net/jacke121/article/details/73888732
    private Bitmap getBMP(String file) throws Throwable {
        return BitmapFactory.decodeFile(file);
    }

    private static byte[] rgb2YCbCr420(int[] pixels, int width, int height) {
        int len = width * height;
        // YUV format size
        byte[] yuv = new byte[len * 3 / 2];
        int y, u, v;
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                int rgb = (pixels[i * width + j]) & 0x00FFFFFF;
                int r = rgb & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = (rgb >> 16) & 0xFF;
                // Apply formula
                y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                y = y < 16 ? 16 : (Math.min(y, 255));
                u = u < 0 ? 0 : (Math.min(u, 255));
                v = v < 0 ? 0 : (Math.min(v, 255));
                // Assignment
                yuv[i * width + j] = (byte) y;
                yuv[len + (i >> 1) * width + (j & ~1)] = (byte) u;
                yuv[len + +(i >> 1) * width + (j & ~1) + 1] = (byte) v;
            }
        }
        return yuv;
    }

    private static byte[] getYUVByBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int size = width * height;
        int[] pixels = new int[size];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        return rgb2YCbCr420(pixels, width, height);
    }
}

