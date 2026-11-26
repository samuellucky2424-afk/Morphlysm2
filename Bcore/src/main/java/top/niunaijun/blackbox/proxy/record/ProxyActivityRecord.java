package top.niunaijun.blackbox.proxy.record;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.IBinder;
import android.os.Parcel;

import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.utils.compat.BundleCompat;


public class ProxyActivityRecord {
    public int mUserId;
    public ActivityInfo mActivityInfo;
    public Intent mTarget;
    public IBinder mActivityRecord;

    public ProxyActivityRecord(int userId, ActivityInfo activityInfo, Intent target, IBinder activityRecord) {
        mUserId = userId;
        mActivityInfo = activityInfo;
        mTarget = target;
        mActivityRecord = activityRecord;
    }

    public static void saveStub(Intent shadow, Intent target, ActivityInfo activityInfo, IBinder activityRecord, int userId) {
        shadow.putExtra("_B_|_user_id_", userId);
        shadow.putExtra("_B_|_activity_info_", activityInfo);
        // The target can carry guest classes such as AuthType. system_server
        // unparcels intent extras with the boot class loader and drops the
        // launch when it cannot find them. A byte array stays opaque there.
        if (target != null) {
            shadow.putExtra("_B_|_target_bytes_", marshall(target));
        }
        BundleCompat.putBinder(shadow, "_B_|_activity_record_v_", activityRecord);
    }

    public static ProxyActivityRecord create(Intent intent) {
        int userId = intent.getIntExtra("_B_|_user_id_", 0);
        ActivityInfo activityInfo = intent.getParcelableExtra("_B_|_activity_info_");
        Intent target = unmarshall(intent.getByteArrayExtra("_B_|_target_bytes_"));
        if (target == null) {
            target = intent.getParcelableExtra("_B_|_target_");
        }
        attachGuestClassLoader(target);
        IBinder activityRecord = BundleCompat.getBinder(intent, "_B_|_activity_record_v_");
        return new ProxyActivityRecord(userId, activityInfo, target, activityRecord);
    }

    private static byte[] marshall(Intent intent) {
        Parcel parcel = Parcel.obtain();
        try {
            intent.writeToParcel(parcel, 0);
            return parcel.marshall();
        } finally {
            parcel.recycle();
        }
    }

    private static Intent unmarshall(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        Parcel parcel = Parcel.obtain();
        try {
            parcel.unmarshall(bytes, 0, bytes.length);
            parcel.setDataPosition(0);
            return Intent.CREATOR.createFromParcel(parcel);
        } catch (Throwable throwable) {
            return null;
        } finally {
            parcel.recycle();
        }
    }

    private static void attachGuestClassLoader(Intent target) {
        if (target == null) {
            return;
        }
        try {
            if (BActivityThread.getApplication() != null) {
                target.setExtrasClassLoader(BActivityThread.getApplication().getClassLoader());
            }
        } catch (Throwable ignored) {
        }
    }
}
