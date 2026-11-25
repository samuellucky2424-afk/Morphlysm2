package top.niunaijun.blackbox.fake.service;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.lang.reflect.Method;
import java.util.Arrays;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Decides guest permission checks on Android 13+ (including Android 16).
 * Context.checkSelfPermission goes through ActivityManager.checkPermissionForDevice
 * and PermissionManager, not only PackageManager.checkSelfPermission.
 */
public final class GuestPermissionPolicy {
    public static final String TAG = "GuestPerm";

    private static final String[] HOST_BACKED = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
            "android.permission.READ_MEDIA_VISUAL",
            "android.permission.READ_MEDIA_AURAL",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            "android.permission.READ_MEDIA_IMAGES_USER_SELECTED",
            "android.permission.READ_MEDIA_VIDEO_USER_SELECTED",
            "android.permission.READ_MEDIA_AUDIO_USER_SELECTED",
            "android.permission.READ_MEDIA_AURAL_USER_SELECTED",
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.GET_ACCOUNTS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            "android.permission.MANAGE_OWN_CALLS",
            "android.permission.USE_FULL_SCREEN_INTENT",
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
    };

    private GuestPermissionPolicy() {
    }

    public static Object onCheck(String source, Object who, Method method, Object[] args) throws Throwable {
        String permission = findPermission(args);
        if (grantsToGuest(permission)) {
            Slog.i(TAG, source + " permission=" + permission + " -> GRANTED");
            return PackageManager.PERMISSION_GRANTED;
        }
        normalizeUserIds(args);
        Object system = method.invoke(who, args);
        Slog.i(TAG, source + " permission=" + permission + " -> SYSTEM " + system
                + " args=" + Arrays.toString(args));
        return system;
    }

    public static boolean grantsToGuest(String permission) {
        if (permission == null) {
            return false;
        }
        if (isLegacyStorage(permission)) {
            // Guests that still target Android 12 request the old storage permission.
            // On API 33+ the host grant is photos, video, audio, or the partial photo picker.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return hostHolds(Manifest.permission.READ_MEDIA_IMAGES)
                        || hostHolds(Manifest.permission.READ_MEDIA_VIDEO)
                        || hostHolds(Manifest.permission.READ_MEDIA_AUDIO)
                        || hostHolds("android.permission.READ_MEDIA_VISUAL_USER_SELECTED");
            }
            return hostHolds(Manifest.permission.READ_EXTERNAL_STORAGE)
                    || hostHolds(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!isHostBacked(permission)) {
            return false;
        }
        if (isFullVisualPermission(permission)) {
            // "Selected photos" is a separate grant. Do not tell the guest it has the whole library.
            return hostHolds(permission);
        }
        if (isSelectedVisualPermission(permission)) {
            return hostHolds(permission)
                    || hostHolds(Manifest.permission.READ_MEDIA_IMAGES)
                    || hostHolds(Manifest.permission.READ_MEDIA_VIDEO);
        }
        if ("android.permission.READ_MEDIA_AUDIO_USER_SELECTED".equals(permission)
                || "android.permission.READ_MEDIA_AURAL_USER_SELECTED".equals(permission)) {
            return hostHolds(permission) || hostHolds(Manifest.permission.READ_MEDIA_AUDIO);
        }
        return hostHolds(permission);
    }

    /**
     * Activity.requestPermissions starts the system permission screen. When the host already
     * holds the grant, finish that request immediately so the guest receives GRANTED.
     */
    public static Instrumentation.ActivityResult grantPermissionRequest(Intent intent) {
        if (intent == null || !"android.content.pm.action.REQUEST_PERMISSIONS".equals(intent.getAction())) {
            return null;
        }
        String[] permissions = intent.getStringArrayExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES");
        if (permissions == null || permissions.length == 0) {
            return null;
        }
        int[] results = new int[permissions.length];
        boolean anyGranted = false;
        for (int i = 0; i < permissions.length; i++) {
            boolean grant = grantsToGuest(permissions[i]);
            results[i] = grant ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
            anyGranted |= grant;
            Slog.i(TAG, "requestPermissions permission=" + permissions[i] + " -> "
                    + (grant ? "GRANTED" : "DENIED"));
        }
        if (!anyGranted) {
            return null;
        }
        Intent data = new Intent();
        data.putExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES", permissions);
        data.putExtra("android.content.pm.extra.REQUEST_PERMISSIONS_RESULTS", results);
        return new Instrumentation.ActivityResult(Activity.RESULT_OK, data);
    }

    public static boolean isLegacyStorage(String permission) {
        return Manifest.permission.READ_EXTERNAL_STORAGE.equals(permission)
                || Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(permission);
    }

    public static String findPermission(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof String) {
                String value = (String) arg;
                if (value.startsWith("android.permission.") || value.startsWith("com.android.voicemail.permission.")) {
                    return value;
                }
            }
        }
        return null;
    }

    private static boolean isHostBacked(String permission) {
        for (String known : HOST_BACKED) {
            if (known.equals(permission)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFullVisualPermission(String permission) {
        return Manifest.permission.READ_MEDIA_IMAGES.equals(permission)
                || Manifest.permission.READ_MEDIA_VIDEO.equals(permission)
                || "android.permission.READ_MEDIA_VISUAL".equals(permission);
    }

    private static boolean isSelectedVisualPermission(String permission) {
        return "android.permission.READ_MEDIA_VISUAL_USER_SELECTED".equals(permission)
                || "android.permission.READ_MEDIA_IMAGES_USER_SELECTED".equals(permission)
                || "android.permission.READ_MEDIA_VIDEO_USER_SELECTED".equals(permission);
    }

    private static boolean sHostCheckBroken;

    private static boolean hostHolds(String permission) {
        Object raw = IPackageManagerProxy.rawPackageManager();
        if (raw == null || permission == null) {
            return true;
        }
        String hostPkg = BlackBoxCore.getHostPkg();
        for (Method method : raw.getClass().getMethods()) {
            if (!"checkPermission".equals(method.getName())) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length < 2 || parameters[0] != String.class || parameters[1] != String.class) {
                continue;
            }
            Object[] call = new Object[parameters.length];
            call[0] = permission;
            call[1] = hostPkg;
            boolean filled = true;
            for (int i = 2; i < parameters.length; i++) {
                if (parameters[i] == int.class) {
                    call[i] = 0;
                } else if (parameters[i] == long.class) {
                    call[i] = 0L;
                } else if (parameters[i] == boolean.class) {
                    call[i] = false;
                } else if (parameters[i] == String.class) {
                    call[i] = null;
                } else {
                    filled = false;
                    break;
                }
            }
            if (!filled) {
                continue;
            }
            try {
                Object result = method.invoke(raw, call);
                if (result instanceof Integer) {
                    return (Integer) result == PackageManager.PERMISSION_GRANTED;
                }
            } catch (Throwable ignored) {
            }
        }
        if (!sHostCheckBroken) {
            sHostCheckBroken = true;
            Slog.w(TAG, "host permission check unavailable; treating listed runtime permissions as granted");
        }
        return true;
    }

    public static void normalizeUserIds(Object[] args) {
        if (args == null) {
            return;
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Integer && (Integer) args[i] < 0) {
                args[i] = 0;
            }
        }
    }
}
