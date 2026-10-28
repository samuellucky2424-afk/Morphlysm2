package top.niunaijun.blackbox.utils;

import android.os.Build;

import java.lang.reflect.Method;

/**
 * Detects hosts where the native BlackBox/Dobby engine cannot run: x86/x86_64 devices that
 * execute our ARM-only libblackbox.so through a binary translator (Berberis/houdini), and
 * Android emulators. Dobby's inline code patching segfaults there, so the engine is skipped.
 * On real ARM devices this always returns false, so behaviour is unchanged.
 */
public final class HostCompat {
    private static volatile Boolean sUnsupported;

    private HostCompat() {}

    public static boolean isNativeEngineUnsupported() {
        Boolean cached = sUnsupported;
        if (cached != null) return cached;
        boolean result = false;
        try {
            String[] abis = Build.SUPPORTED_ABIS;
            if (abis != null && abis.length > 0 && abis[0] != null && abis[0].startsWith("x86")) {
                result = true;
            }
            String hw = Build.HARDWARE == null ? "" : Build.HARDWARE.toLowerCase();
            if (hw.contains("ranchu") || hw.contains("goldfish")) result = true;
            if ("1".equals(prop("ro.kernel.qemu")) || "1".equals(prop("ro.boot.qemu"))) result = true;
        } catch (Throwable ignored) {
        }
        sUnsupported = result;
        return result;
    }

    private static String prop(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Method m = c.getMethod("get", String.class, String.class);
            return (String) m.invoke(null, key, "");
        } catch (Throwable t) {
            return "";
        }
    }
}