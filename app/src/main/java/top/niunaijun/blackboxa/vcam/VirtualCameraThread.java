package top.niunaijun.blackboxa.vcam;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;

public final class VirtualCameraThread {
    private static final String THREAD_NAME = "VirtualCameraThread";
    private static final Object LOCK = new Object();

    private static HandlerThread thread;
    private static Handler handler;

    private VirtualCameraThread() {
    }

    public static void ensureStarted() {
        synchronized (LOCK) {
            if (thread != null && thread.isAlive() && handler != null) {
                return;
            }
            thread = new HandlerThread(THREAD_NAME, Process.THREAD_PRIORITY_DISPLAY);
            thread.start();
            handler = new Handler(thread.getLooper());
        }
    }

    public static Handler handler() {
        ensureStarted();
        synchronized (LOCK) {
            return handler;
        }
    }

    public static boolean isOnThread() {
        Handler current = handler;
        return current != null && Looper.myLooper() == current.getLooper();
    }

    public static void post(String operation, Runnable runnable) {
        if (runnable == null) {
            return;
        }
        handler().post(() -> runSilently(runnable));
    }

    public static void postDelayed(String operation, Runnable runnable, long delayMs) {
        if (runnable == null) {
            return;
        }
        handler().postDelayed(() -> runSilently(runnable), Math.max(0L, delayMs));
    }

    private static void runSilently(Runnable runnable) {
        try {
            runnable.run();
        } catch (Throwable ignored) {
        }
    }
}
