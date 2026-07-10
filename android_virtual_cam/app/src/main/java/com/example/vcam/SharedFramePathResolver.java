package com.example.vcam;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;

public final class SharedFramePathResolver {
    private static final String TAG = "VCAM_FRAME_PATH";
    private static final String HOST_PACKAGE = "top.niunaijun.blackbox";
    private static final String FRAME_DIR_NAME = "virtual_camera_frames";
    private static volatile Context hostContext;

    private SharedFramePathResolver() {
    }

    public static File getSlotFile(Context context, int slot) {
        return new File(getFrameDirectory(context), "smokescreen_slot_" + safeSlot(slot) + ".frame");
    }

    static File getTempSlotFile(Context context, int slot) {
        return new File(getFrameDirectory(context), "smokescreen_slot_" + safeSlot(slot) + ".frame.tmp");
    }

    static File getSourceFile(Context context, int slot) {
        return new File(getFrameDirectory(context), "smokescreen_slot_" + safeSlot(slot) + ".source");
    }

    static File getTempSourceFile(Context context, int slot) {
        return new File(getFrameDirectory(context), "smokescreen_slot_" + safeSlot(slot) + ".source.tmp");
    }

    public static File getFrameDirectory(Context context) {
        File filesDir = resolveFilesDir(context);
        return new File(filesDir, FRAME_DIR_NAME);
    }

    public static Context resolveHostContext(Context fallback) {
        Context cached = hostContext;
        if (cached != null) {
            return cached;
        }
        Context reflected = reflectBlackBoxHostContext();
        if (reflected != null) {
            Context appContext = reflected.getApplicationContext();
            hostContext = appContext == null ? reflected : appContext;
            return hostContext;
        }
        if (fallback == null) {
            return null;
        }
        Context appContext = fallback.getApplicationContext();
        Context resolved = appContext == null ? fallback : appContext;
        if (HOST_PACKAGE.equals(safePackageName(resolved))) {
            hostContext = resolved;
        }
        return resolved;
    }

    public static void logFileState(String event, Context context, File file, Throwable error) {
        Context host = resolveHostContext(context);
        StringBuilder builder = new StringBuilder(event)
                .append(" process=").append(currentProcessName())
                .append(" pid=").append(Process.myPid())
                .append(" uid=").append(Process.myUid())
                .append(" contextPackage=").append(safePackageName(context))
                .append(" hostPackage=").append(safePackageName(host));
        if (file != null) {
            builder.append(" path=").append(file.getAbsolutePath())
                    .append(" exists=").append(file.exists())
                    .append(" length=").append(safeLength(file))
                    .append(" lastModified=").append(safeLastModified(file))
                    .append(" canRead=").append(safeCanRead(file))
                    .append(" canWrite=").append(safeCanWrite(file));
            File parent = file.getParentFile();
            if (parent != null) {
                builder.append(" parent=").append(parent.getAbsolutePath())
                        .append(" parentExists=").append(parent.exists())
                        .append(" parentCanRead=").append(safeCanRead(parent))
                        .append(" parentCanWrite=").append(safeCanWrite(parent));
            }
        } else {
            builder.append(" path=null");
        }
        if (error != null) {
            builder.append(" exception=").append(error.getClass().getSimpleName())
                    .append(":").append(error.getMessage() == null ? "" : error.getMessage());
        }
        Log.i(TAG, builder.toString());
    }

    private static File resolveFilesDir(Context context) {
        Context host = resolveHostContext(context);
        if (host != null) {
            try {
                File filesDir = host.getFilesDir();
                if (filesDir != null) {
                    return filesDir;
                }
            } catch (Throwable ignored) {
            }
        }
        if (context != null) {
            try {
                File filesDir = context.getFilesDir();
                if (filesDir != null) {
                    return filesDir;
                }
            } catch (Throwable ignored) {
            }
        }
        return new File("/data/user/0/" + HOST_PACKAGE + "/files");
    }

    private static Context reflectBlackBoxHostContext() {
        try {
            Class<?> blackBoxCore = Class.forName("top.niunaijun.blackbox.BlackBoxCore");
            Method getContext = blackBoxCore.getDeclaredMethod("getContext");
            getContext.setAccessible(true);
            Object value = getContext.invoke(null);
            return value instanceof Context ? (Context) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String currentProcessName() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                String name = Application.getProcessName();
                if (name != null && !name.trim().isEmpty()) {
                    return name;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentProcessName = activityThread.getDeclaredMethod("currentProcessName");
            currentProcessName.setAccessible(true);
            Object name = currentProcessName.invoke(null);
            if (name instanceof String && !((String) name).trim().isEmpty()) {
                return (String) name;
            }
        } catch (Throwable ignored) {
        }
        return "pid-" + Process.myPid();
    }

    private static String safePackageName(Context context) {
        if (context == null) {
            return "null";
        }
        try {
            String packageName = context.getPackageName();
            return packageName == null ? "null" : packageName;
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static long safeLength(File file) {
        try {
            return file.length();
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static long safeLastModified(File file) {
        try {
            return file.lastModified();
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static boolean safeCanRead(File file) {
        try {
            return file.canRead();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean safeCanWrite(File file) {
        try {
            return file.canWrite();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int safeSlot(int slot) {
        return Math.max(0, slot);
    }
}
