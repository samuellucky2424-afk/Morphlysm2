package top.niunaijun.blackbox.fake.service;

import android.content.pm.PackageManager;

import java.lang.reflect.Method;

import black.android.app.BRActivityThread;
import black.android.os.BRServiceManager;
import black.android.permission.BRIPermissionManagerStub;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.fake.hook.ProxyMethods;
import top.niunaijun.blackbox.fake.service.base.PkgMethodProxy;
import top.niunaijun.blackbox.fake.service.base.ValueMethodProxy;
import top.niunaijun.blackbox.utils.compat.BuildCompat;


public class IPermissionManagerProxy extends BinderInvocationStub {
    public static final String TAG = "IPermissionManagerProxy";

    private static final String P = "permissionmgr";

    public IPermissionManagerProxy() {
        super(BRServiceManager.get().getService(P));
    }

    @Override
    protected Object getWho() {
        return BRIPermissionManagerStub.get().asInterface(BRServiceManager.get().getService(P));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("permissionmgr");
        BRActivityThread.getWithException()._set_sPermissionManager(proxyInvocation);
        
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new ValueMethodProxy("addPermissionAsync", true));
        addMethodHook(new ValueMethodProxy("addPermission", true));
        addMethodHook(new ValueMethodProxy("performDexOpt", true));
        addMethodHook(new ValueMethodProxy("performDexOptIfNeeded", false));
        addMethodHook(new ValueMethodProxy("performDexOptSecondary", true));
        addMethodHook(new ValueMethodProxy("addOnPermissionsChangeListener", 0));
        addMethodHook(new ValueMethodProxy("removeOnPermissionsChangeListener", 0));
        addMethodHook(new ValueMethodProxy("checkDeviceIdentifierAccess", false));
        addMethodHook(new PkgMethodProxy("shouldShowRequestPermissionRationale"));
        if (BuildCompat.isOreo()) {
            addMethodHook(new ValueMethodProxy("notifyDexLoad", 0));
            addMethodHook(new ValueMethodProxy("notifyPackageUse", 0));
            addMethodHook(new ValueMethodProxy("setInstantAppCookie", false));
            addMethodHook(new ValueMethodProxy("isInstantApp", false));
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("checkPermission")
    @ProxyMethods({
            "checkPermissionForDevice",
            "checkPermissionForPreflight",
            "checkPermissionForDataDelivery",
            "checkPermissionForStartDataDelivery"
    })
    public static class CheckPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return GuestPermissionPolicy.onCheck("PermissionManager." + method.getName(), who, method, args);
        }
    }

    @ProxyMethod("checkUidPermission")
    public static class CheckUidPermission extends CheckPermission {
    }

    @ProxyMethod("shouldShowRequestPermissionRationale")
    public static class ShouldShowRationale extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String permission = GuestPermissionPolicy.findPermission(args);
            if (GuestPermissionPolicy.grantsToGuest(permission)) {
                android.util.Log.i(GuestPermissionPolicy.TAG,
                        "PermissionManager.shouldShowRequestPermissionRationale permission="
                                + permission + " -> false");
                return false;
            }
            return method.invoke(who, args);
        }
    }
}
