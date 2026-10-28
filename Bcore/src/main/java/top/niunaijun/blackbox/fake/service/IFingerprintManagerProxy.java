package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.os.IBinder;

import black.android.os.BRServiceManager;
import black.android.view.BRIGraphicsStatsStub;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.service.base.PkgMethodProxy;


public class IFingerprintManagerProxy extends BinderInvocationStub {
    public IFingerprintManagerProxy() {
        super(BRServiceManager.get().getService("fingerprint" /* Context.FINGERPRINT_SERVICE, removed in API 37 */));
    }

    @Override
    protected Object getWho() {
        return BRIGraphicsStatsStub.get().asInterface(BRServiceManager.get().getService("fingerprint" /* Context.FINGERPRINT_SERVICE, removed in API 37 */));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("fingerprint" /* Context.FINGERPRINT_SERVICE, removed in API 37 */);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new PkgMethodProxy("isHardwareDetected"));
        addMethodHook(new PkgMethodProxy("hasEnrolledFingerprints"));
        addMethodHook(new PkgMethodProxy("authenticate"));
        addMethodHook(new PkgMethodProxy("cancelAuthentication"));
        addMethodHook(new PkgMethodProxy("getEnrolledFingerprints"));
        addMethodHook(new PkgMethodProxy("getAuthenticatorId"));
    }
}
