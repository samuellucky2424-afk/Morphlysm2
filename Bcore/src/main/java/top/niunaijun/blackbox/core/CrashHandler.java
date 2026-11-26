package top.niunaijun.blackbox.core;

import android.os.Looper;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.Slog;


public class CrashHandler implements Thread.UncaughtExceptionHandler {
    private static final String TAG = "CrashHandler";
    private Thread.UncaughtExceptionHandler mDefaultHandler;
    private int measurementFailures;

    public static void create() {
        new CrashHandler();
    }

    public CrashHandler() {
        mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(this);
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        if (isMeasurementPackageRejection(e)
                && t == Looper.getMainLooper().getThread()
                && measurementFailures < 3) {
            measurementFailures++;
            Slog.w(TAG, "Play services rejected the clone package. Continuing without that analytics call: "
                    + e.getMessage());
            if (Looper.myLooper() != null) {
                Looper.loop();
            }
            return;
        }
        if (BlackBoxCore.get().getExceptionHandler() != null) {
            BlackBoxCore.get().getExceptionHandler().uncaughtException(t, e);
        }
        if (mDefaultHandler != null) {
            mDefaultHandler.uncaughtException(t, e);
        }
    }

    private static boolean isMeasurementPackageRejection(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (current instanceof SecurityException
                    && message != null
                    && message.contains("Unknown calling package name")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
