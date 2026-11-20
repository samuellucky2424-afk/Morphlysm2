package top.niunaijun.blackbox.utils.compat;

import java.lang.reflect.Method;

/**
 * Java fallback for the hidden-API exemption when the native libart symbol
 * lookup misses. Android 16 still enforces the greylist, and a failed exemption
 * makes the virtualization hooks throw on the first hidden framework call.
 */
public final class HiddenApiCompat {
    private HiddenApiCompat() {
    }

    public static boolean exemptAll() {
        try {
            Method getDeclaredMethod = Class.class.getDeclaredMethod(
                    "getDeclaredMethod", String.class, Class[].class);
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclaredMethod.invoke(vmRuntime, "getRuntime", null);
            Method setHiddenApiExemptions = (Method) getDeclaredMethod.invoke(
                    vmRuntime,
                    "setHiddenApiExemptions",
                    new Class[]{String[].class});
            Object runtime = getRuntime.invoke(null);
            if (runtime == null || setHiddenApiExemptions == null) {
                return false;
            }
            setHiddenApiExemptions.invoke(runtime, (Object) new String[]{"L"});
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
