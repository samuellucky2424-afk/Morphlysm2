package top.niunaijun.blackbox.fake.delegate;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import black.android.app.BRActivity;
import black.android.app.BRActivityThread;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.entity.AppConfig;
import top.niunaijun.blackbox.fake.hook.HookManager;
import top.niunaijun.blackbox.fake.hook.IInjectHook;
import top.niunaijun.blackbox.fake.service.HCallbackProxy;
import top.niunaijun.blackbox.fake.service.IActivityClientProxy;
import top.niunaijun.blackbox.utils.HackAppUtils;
import top.niunaijun.blackbox.utils.compat.ActivityCompat;
import top.niunaijun.blackbox.utils.compat.ActivityManagerCompat;
import top.niunaijun.blackbox.utils.compat.ContextCompat;

public final class AppInstrumentation extends BaseInstrumentationDelegate implements IInjectHook {

    private static final String TAG = AppInstrumentation.class.getSimpleName();
    private static final long VIRTUAL_CAMERA_HOOK_INTERVAL_MS = 1000L;

    private static AppInstrumentation sAppInstrumentation;
    private String mLastVirtualCameraHookKey;
    private long mLastVirtualCameraHookAt;

    public static AppInstrumentation get() {
        if (sAppInstrumentation == null) {
            synchronized (AppInstrumentation.class) {
                if (sAppInstrumentation == null) {
                    sAppInstrumentation = new AppInstrumentation();
                }
            }
        }
        return sAppInstrumentation;
    }

    public AppInstrumentation() {
    }

    @Override
    public void injectHook() {
        try {
            Instrumentation mInstrumentation = getCurrInstrumentation();
            if (mInstrumentation == this || checkInstrumentation(mInstrumentation))
                return;
            mBaseInstrumentation = (Instrumentation) mInstrumentation;
            BRActivityThread.get(BlackBoxCore.mainThread())._set_mInstrumentation(this);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Instrumentation getCurrInstrumentation() {
        Object currentActivityThread = BlackBoxCore.mainThread();
        return BRActivityThread.get(currentActivityThread).mInstrumentation();
    }

    @Override
    public boolean isBadEnv() {
        return !checkInstrumentation(getCurrInstrumentation());
    }

    private boolean checkInstrumentation(Instrumentation instrumentation) {
        if (instrumentation instanceof AppInstrumentation) {
            return true;
        }
        Class<?> clazz = instrumentation.getClass();
        if (Instrumentation.class.equals(clazz)) {
            return false;
        }
        do {
            assert clazz != null;
            Field[] fields = clazz.getDeclaredFields();
            for (Field field : fields) {
                if (Instrumentation.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    try {
                        Object obj = field.get(instrumentation);
                        if ((obj instanceof AppInstrumentation)) {
                            return true;
                        }
                    } catch (Exception e) {
                        return false;
                    }
                }
            }
            clazz = clazz.getSuperclass();
        } while (!Instrumentation.class.equals(clazz));
        return false;
    }

    private void checkHCallback() {
        HookManager.get().checkEnv(HCallbackProxy.class);
    }

    private void checkActivity(Activity activity) {
        Log.d(TAG, "callActivityOnCreate: " + activity.getClass().getName());
        ensureMorphlyVirtualCameraHook(activity);
        HackAppUtils.enableQQLogOutput(activity.getPackageName(), activity.getClassLoader());
        checkHCallback();
        HookManager.get().checkEnv(IActivityClientProxy.class);
        ActivityInfo info = BRActivity.get(activity).mActivityInfo();
        ContextCompat.fix(activity);
        ActivityCompat.fix(activity);
        if (info.theme != 0) {
            activity.getTheme().applyStyle(info.theme, true);
        }
        ActivityManagerCompat.setActivityOrientation(activity, info.screenOrientation);
    }

    private void ensureMorphlyVirtualCameraHook(Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            ActivityInfo info = null;
            try {
                info = BRActivity.get(activity).mActivityInfo();
            } catch (Throwable ignored) {
            }
            String packageName = info != null && info.packageName != null
                    ? info.packageName
                    : activity.getPackageName();
            if (!isTargetCameraPackage(packageName)) {
                return;
            }

            AppConfig config = BActivityThread.getAppConfig();
            String processName = config != null && config.processName != null
                    ? config.processName
                    : packageName;
            String key = packageName + "/" + processName;
            long now = System.currentTimeMillis();
            if (key.equals(mLastVirtualCameraHookKey)
                    && now - mLastVirtualCameraHookAt < VIRTUAL_CAMERA_HOOK_INTERVAL_MS) {
                return;
            }
            mLastVirtualCameraHookKey = key;
            mLastVirtualCameraHookAt = now;

            Context context = activity.getApplicationContext() != null
                    ? activity.getApplicationContext()
                    : activity;
            Class<?> hookClass = Class.forName("top.niunaijun.blackboxa.vcam.VirtualCameraServiceHook");
            Method install = hookClass.getDeclaredMethod("install", Context.class, String.class, String.class);
            install.setAccessible(true);
            install.invoke(null, context, packageName, processName);
        } catch (Throwable throwable) {
            Log.w(TAG, "Morphly virtual camera hook refresh failed", throwable);
        }
    }

    private boolean isTargetCameraPackage(String packageName) {
        return "com.whatsapp".equals(packageName)
                || "com.whatsapp.w4b".equals(packageName)
                || "org.telegram.messenger".equals(packageName)
                || "org.telegram.messenger.web".equals(packageName)
                || "com.instagram.android".equals(packageName)
                || "com.zhiliaoapp.musically".equals(packageName)
                || "com.ss.android.ugc.trill".equals(packageName);
    }

    private String resolveActivityPackageName(Activity activity) {
        if (activity == null) {
            return null;
        }
        try {
            ActivityInfo info = BRActivity.get(activity).mActivityInfo();
            if (info != null && info.packageName != null) {
                return info.packageName;
            }
        } catch (Throwable ignored) {
        }
        return activity.getPackageName();
    }

    private boolean shouldKeepVirtualCameraActivityResumed(Activity activity, String event) {
        String packageName = resolveActivityPackageName(activity);
        if (!isTargetCameraPackage(packageName)) {
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null && activity.isInPictureInPictureMode()) {
                ensureMorphlyVirtualCameraHook(activity);
                Log.i(TAG, "Keeping virtual camera guest resumed during PiP " + event + " package=" + packageName);
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> hookClass = Class.forName("top.niunaijun.blackboxa.vcam.VirtualCameraServiceHook");
            Method shouldKeep = hookClass.getDeclaredMethod("shouldKeepGuestResumed", String.class);
            shouldKeep.setAccessible(true);
            Object value = shouldKeep.invoke(null, packageName);
            if (Boolean.TRUE.equals(value)) {
                ensureMorphlyVirtualCameraHook(activity);
                Log.i(TAG, "Keeping virtual camera guest resumed during " + event + " package=" + packageName);
                return true;
            }
        } catch (InvocationTargetException invocationTargetException) {
            Throwable target = invocationTargetException.getTargetException();
            Log.w(TAG, "Virtual camera lifecycle keepalive check failed: "
                    + (target == null ? invocationTargetException.getClass().getSimpleName() : target.getClass().getSimpleName()));
        } catch (Throwable throwable) {
            Log.w(TAG, "Virtual camera lifecycle keepalive unavailable", throwable);
        }
        return false;
    }

    @Override
    public Application newApplication(ClassLoader cl, String className, Context context) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        ContextCompat.fix(context);

        return super.newApplication(cl, className, context);
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle, PersistableBundle persistentState) {
        checkActivity(activity);
        super.callActivityOnCreate(activity, icicle, persistentState);
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle) {
        checkActivity(activity);
        super.callActivityOnCreate(activity, icicle);
    }

    @Override
    public void callActivityOnResume(Activity activity) {
        ensureMorphlyVirtualCameraHook(activity);
        super.callActivityOnResume(activity);
    }

    @Override
    public void callActivityOnPause(Activity activity) {
        if (shouldKeepVirtualCameraActivityResumed(activity, "onPause")) {
            return;
        }
        super.callActivityOnPause(activity);
    }

    @Override
    public void callActivityOnStop(Activity activity) {
        if (shouldKeepVirtualCameraActivityResumed(activity, "onStop")) {
            return;
        }
        super.callActivityOnStop(activity);
    }

    @Override
    public void callApplicationOnCreate(Application app) {
        checkHCallback();
        super.callApplicationOnCreate(app);
    }

    public Activity newActivity(ClassLoader cl, String className, Intent intent) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        try {
            return super.newActivity(cl, className, intent);
        } catch (ClassNotFoundException e) {
            return mBaseInstrumentation.newActivity(cl, className, intent);
        }
    }
}
