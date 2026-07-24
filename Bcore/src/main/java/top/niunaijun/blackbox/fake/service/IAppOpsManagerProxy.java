package top.niunaijun.blackbox.fake.service;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.IBinder;

import java.lang.reflect.Method;

import black.android.app.BRAppOpsManager;
import black.android.os.BRServiceManager;
import black.com.android.internal.app.BRIAppOpsServiceStub;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import top.niunaijun.blackbox.utils.Slog;


public class IAppOpsManagerProxy extends BinderInvocationStub {
    public IAppOpsManagerProxy() {
        super(BRServiceManager.get().getService(Context.APP_OPS_SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder call = BRServiceManager.get().getService(Context.APP_OPS_SERVICE);
        return BRIAppOpsServiceStub.get().asInterface(call);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        if (BRAppOpsManager.get(null)._check_mService() != null) {
            AppOpsManager appOpsManager = (AppOpsManager) BlackBoxCore.getContext().getSystemService(Context.APP_OPS_SERVICE);
            try {
                BRAppOpsManager.get(appOpsManager)._set_mService(getProxyInvocation());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        replaceSystemService(Context.APP_OPS_SERVICE);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();
        
        
        
        if (methodName.startsWith("check") ||
            methodName.startsWith("note") ||
            methodName.startsWith("start")) {
            Slog.d(TAG, "AppOps invoke: Bypassing system for " + methodName + ", allowing operation");
            return allowedResult(method);
        }
        
        
        if (methodName.startsWith("finish")) {
            Slog.d(TAG, "AppOps invoke: Bypassing system for " + methodName);
            return null;
        }
        
        
        try {
            MethodParameterUtils.replaceFirstAppPkg(args);
            MethodParameterUtils.replaceLastUid(args);
            return super.invoke(proxy, method, args);
        } catch (SecurityException e) {
            
            Slog.w(TAG, "AppOps invoke: SecurityException caught for " + methodName + ", allowing operation", e);
            return allowedResult(method);
        } catch (Exception e) {
            Slog.e(TAG, "AppOps invoke: Error in method " + methodName, e);
            
            return allowedResult(method);
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("noteProxyOperation")
    public static class NoteProxyOperation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return allowedResult(method);
        }
    }

    @ProxyMethod("checkPackage")
    public static class CheckPackage extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            return allowedResult(method);
        }
    }

    @ProxyMethod("checkOperation")
    public static class CheckOperation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            
            Slog.d(TAG, "AppOps CheckOperation: Bypassing system check, allowing operation");
            return allowedResult(method);
        }
    }

    
    @ProxyMethod("checkOperationForDevice")
    public static class CheckOperationForDevice extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            Slog.d(TAG, "AppOps CheckOperationForDevice: Bypassing system check, allowing operation");
            return allowedResult(method);
        }
    }

    @ProxyMethod("noteOperation")
    public static class NoteOperation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            Slog.d(TAG, "AppOps NoteOperation: Bypassing system check, allowing operation");
            return allowedResult(method);
        }
    }

    @ProxyMethod("checkOpNoThrow")
    public static class CheckOpNoThrow extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            Slog.d(TAG, "AppOps CheckOpNoThrow: Bypassing system check, allowing operation");
            return allowedResult(method);
        }
    }

    
    @ProxyMethod("startOp")
    public static class StartOp extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            Slog.d(TAG, "AppOps StartOp: Bypassing system check, allowing operation");
            return allowedResult(method);
        }
    }

    @ProxyMethod("startOpNoThrow")
    public static class StartOpNoThrow extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            Slog.d(TAG, "AppOps StartOpNoThrow: Bypassing system check, allowing operation");
            return allowedResult(method);
        }
    }

    
    @ProxyMethod("finishOp")
    public static class FinishOp extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                int op = (int) args[0];
                String name = getOpPublicName(op);
                if (name != null && isMediaStorageOrAudioOp(name)) {
                    Slog.d(TAG, "AppOps FinishOp: Finishing operation: " + name);
                }
            } catch (Throwable ignored) {
            }
            return null;
        }
    }

    
    @ProxyMethod("noteOp")
    public static class NoteOp extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                int op = (int) args[0];
                String name = getOpPublicName(op);
                if (name != null && (name.contains("RECORD_AUDIO") || name.contains("AUDIO") || name.contains("MICROPHONE"))) {
                    Slog.d(TAG, "AppOps NoteOp: Allowing RECORD_AUDIO operation: " + name);
                    return allowedResult(method);
                }
            } catch (Throwable ignored) {
            }
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("noteOpNoThrow")
    public static class NoteOpNoThrow extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                int op = (int) args[0];
                String name = getOpPublicName(op);
                if (name != null && (name.contains("RECORD_AUDIO") || name.contains("AUDIO") || name.contains("MICROPHONE"))) {
                    Slog.d(TAG, "AppOps NoteOpNoThrow: Allowing RECORD_AUDIO operation: " + name);
                    return allowedResult(method);
                }
            } catch (Throwable ignored) {
            }
            return method.invoke(who, args);
        }
    }

    private static boolean isMediaStorageOrAudioOp(String opPublicNameOrStr) {
        if (opPublicNameOrStr == null) return false;
        
        String n = opPublicNameOrStr.toUpperCase();
        return n.contains("READ_MEDIA")
                || n.contains("READ_EXTERNAL_STORAGE")
                || n.contains("RECORD_AUDIO")
                || n.contains("CAPTURE_AUDIO_OUTPUT")
                || n.contains("MODIFY_AUDIO_SETTINGS")
                || n.contains("AUDIO")
                || n.contains("MICROPHONE")
                || n.contains("FOREGROUND_SERVICE")
                || n.contains("SYSTEM_ALERT_WINDOW")
                || n.contains("WRITE_SETTINGS")
                || n.contains("ACCESS_FINE_LOCATION")
                || n.contains("ACCESS_COARSE_LOCATION")
                || n.contains("CAMERA")
                || n.contains("BODY_SENSORS")
                || n.contains("BLUETOOTH_SCAN")
                || n.contains("BLUETOOTH_CONNECT")
                || n.contains("BLUETOOTH_ADVERTISE")
                || n.contains("NEARBY_WIFI_DEVICES")
                || n.contains("POST_NOTIFICATIONS");
    }

    private static String getOpPublicName(int op) {
        try {
            
            java.lang.reflect.Method m = AppOpsManager.class.getMethod("opToPublicName", int.class);
            Object name = m.invoke(null, op);
            return name != null ? name.toString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object allowedResult(Method method) {
        Class<?> returnType = method.getReturnType();
        if (returnType == Void.TYPE) {
            return null;
        }
        if (returnType == Integer.TYPE || returnType == Integer.class) {
            return AppOpsManager.MODE_ALLOWED;
        }
        if (returnType == Boolean.TYPE || returnType == Boolean.class) {
            return true;
        }
        if ("android.app.SyncNotedAppOp".equals(returnType.getName())) {
            try {
                return returnType.getConstructor(int.class, String.class)
                        .newInstance(AppOpsManager.MODE_ALLOWED, null);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
