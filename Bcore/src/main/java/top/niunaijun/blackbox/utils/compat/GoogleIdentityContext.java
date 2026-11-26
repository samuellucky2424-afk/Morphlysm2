package top.niunaijun.blackbox.utils.compat;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.lang.reflect.Field;

import top.niunaijun.blackbox.BlackBoxCore;

/**
 * Android 16 reports {@link Context#getPackageName()} from the loaded APK, so a
 * clone tells Google Play services it is com.tinder while the process uid belongs
 * to Morphly. Play services then throws "Unknown calling package name".
 * Calls that originate in Play services, Play Billing, or the Play Store are
 * answered with the host package, which owns this process uid.
 */
public final class GoogleIdentityContext extends ContextWrapper {
    private static final String TAG = "GoogleIdentity";
    private static volatile boolean logged;

    private GoogleIdentityContext(Context base) {
        super(base);
    }

    public static void install(Context context) {
        if (!(context instanceof ContextWrapper) || context instanceof GoogleIdentityContext) {
            return;
        }
        try {
            Field baseField = ContextWrapper.class.getDeclaredField("mBase");
            baseField.setAccessible(true);
            Object base = baseField.get(context);
            if (!(base instanceof Context) || base instanceof GoogleIdentityContext) {
                return;
            }
            baseField.set(context, new GoogleIdentityContext((Context) base));
        } catch (Throwable throwable) {
            Log.w(TAG, "Could not install Google identity context", throwable);
        }
    }

    @Override
    public String getPackageName() {
        if (callerNeedsHostIdentity()) {
            return hostPackage();
        }
        return super.getPackageName();
    }

    @Override
    public String getOpPackageName() {
        if (callerNeedsHostIdentity()) {
            return hostPackage();
        }
        return super.getOpPackageName();
    }

    @Override
    public ApplicationInfo getApplicationInfo() {
        ApplicationInfo info = super.getApplicationInfo();
        if (info == null || !callerNeedsHostIdentity()) {
            return info;
        }
        ApplicationInfo copy = new ApplicationInfo(info);
        copy.packageName = hostPackage();
        return copy;
    }

    private static String hostPackage() {
        String host = BlackBoxCore.getHostPkg();
        if (!logged) {
            logged = true;
            Log.i(TAG, "Reporting host package " + host + " to Google Play services");
        }
        return host;
    }

    static boolean callerNeedsHostIdentity() {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            try {
                if (GoogleIdentityStack.needsHost()) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        int limit = Math.min(stack.length, 40);
        for (int i = 0; i < limit; i++) {
            String name = stack[i].getClassName();
            if (isGoogleCallerName(name)) {
                return true;
            }
        }
        return false;
    }

    static boolean isGoogleCallerName(String name) {
        if (name == null) {
            return false;
        }
        return name.startsWith("com.google.android.gms")
                || name.startsWith("com.google.android.finsky")
                || name.startsWith("com.google.android.play")
                || name.startsWith("com.google.firebase")
                || name.startsWith("com.android.billingclient")
                || name.startsWith("com.android.vending")
                || name.contains("dynamite")
                || name.contains("measurement");
    }

    static boolean isGoogleCallerLoader(String loaderName) {
        if (loaderName == null) {
            return false;
        }
        return loaderName.contains("com.google.android.gms")
                || loaderName.contains("dynamite")
                || loaderName.contains("com.google.firebase")
                || loaderName.contains("com.google.android.play")
                || loaderName.contains("com.android.billingclient")
                || loaderName.contains("com.android.vending")
                || loaderName.contains("com.google.android.finsky")
                || loaderName.contains("measurementdynamite");
    }
}
