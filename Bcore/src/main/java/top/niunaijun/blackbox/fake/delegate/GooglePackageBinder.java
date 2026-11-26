package top.niunaijun.blackbox.fake.delegate;

import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

import java.io.FileDescriptor;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.WeakHashMap;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Play services reads the calling package from the binder payload
 * (GetServiceRequest.callingPackage and AttributionSource), which was copied
 * before our context wrapper could see a Google caller. Rewrite that string
 * on the way out so the process uid and the package name agree.
 */
public final class GooglePackageBinder implements IBinder {
    private static final String TAG = "GooglePackageBinder";
    private final IBinder delegate;

    private GooglePackageBinder(IBinder delegate) {
        this.delegate = delegate;
    }

    private static final Map<IBinder, String> DESCRIPTORS = new WeakHashMap<>();

    /**
     * Called from the BinderProxy.transact hook so every Play services call is
     * rewritten, including ones that never pass through our service-connection wrapper.
     */
    public static Parcel rewriteIfGoogle(IBinder binder, Parcel data) {
        if (binder == null || data == null || !isGoogleBinder(binder)) {
            return null;
        }
        return rewriteGuestPackage(data);
    }

    private static boolean isGoogleBinder(IBinder binder) {
        String descriptor;
        synchronized (DESCRIPTORS) {
            descriptor = DESCRIPTORS.get(binder);
        }
        if (descriptor == null) {
            try {
                descriptor = binder.getInterfaceDescriptor();
            } catch (Throwable throwable) {
                descriptor = "";
            }
            if (descriptor == null) {
                descriptor = "";
            }
            synchronized (DESCRIPTORS) {
                DESCRIPTORS.put(binder, descriptor);
            }
        }
        return descriptor.contains("com.google.android.gms")
                || descriptor.contains("IGmsServiceBroker")
                || descriptor.contains("com.google.android.finsky")
                || descriptor.contains("com.android.vending")
                || descriptor.contains("com.google.android.play");
    }

    public static IBinder wrap(IBinder binder) {
        if (binder == null || binder instanceof GooglePackageBinder) {
            return binder;
        }
        return new GooglePackageBinder(binder);
    }

    @Override
    public boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        Parcel rewritten = rewriteGuestPackage(data);
        try {
            return delegate.transact(code, rewritten != null ? rewritten : data, reply, flags);
        } finally {
            if (rewritten != null) {
                rewritten.recycle();
            }
        }
    }

    private static Parcel rewriteGuestPackage(Parcel source) {
        if (source == null) {
            return null;
        }
        String guest = BActivityThread.getAppPackageName();
        String host = BlackBoxCore.getHostPkg();
        if (guest == null || host == null || guest.equals(host) || guest.isEmpty()) {
            return null;
        }
        byte[] raw;
        try {
            raw = source.marshall();
        } catch (Throwable throwable) {
            return null;
        }
        byte[] replaced = replaceString16(raw, guest, host);
        if (replaced == null) {
            return null;
        }
        Slog.i(TAG, "calling package " + guest + " -> " + host);
        Parcel out = Parcel.obtain();
        out.unmarshall(replaced, 0, replaced.length);
        out.setDataPosition(0);
        out.setDataSize(replaced.length);
        return out;
    }

    private static byte[] replaceString16(byte[] raw, String guest, String host) {
        byte[] guestChars = guest.getBytes(StandardCharsets.UTF_16LE);
        byte[] hostChars = host.getBytes(StandardCharsets.UTF_16LE);
        int guestSpan = padded(4 + guestChars.length);
        boolean found = false;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(raw.length + 64);
        int index = 0;
        while (index < raw.length) {
            if ((index % 4) == 0 && matches(raw, index, guest.length(), guestChars)) {
                writeInt(out, host.length());
                out.write(hostChars, 0, hostChars.length);
                int pad = padded(4 + hostChars.length) - 4 - hostChars.length;
                for (int i = 0; i < pad; i++) {
                    out.write(0);
                }
                index += guestSpan;
                found = true;
            } else {
                out.write(raw[index]);
                index++;
            }
        }
        return found ? out.toByteArray() : null;
    }

    private static boolean matches(byte[] raw, int offset, int charCount, byte[] chars) {
        int span = padded(4 + chars.length);
        if (offset < 0 || offset + span > raw.length) {
            return false;
        }
        int length = readInt(raw, offset);
        if (length != charCount) {
            return false;
        }
        for (int i = 0; i < chars.length; i++) {
            if (raw[offset + 4 + i] != chars[i]) {
                return false;
            }
        }
        for (int i = 4 + chars.length; i < span; i++) {
            if (raw[offset + i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static int padded(int size) {
        return (size + 3) & ~3;
    }

    private static int readInt(byte[] raw, int offset) {
        return (raw[offset] & 0xff)
                | ((raw[offset + 1] & 0xff) << 8)
                | ((raw[offset + 2] & 0xff) << 16)
                | ((raw[offset + 3] & 0xff) << 24);
    }

    private static void writeInt(java.io.ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
        out.write((value >> 16) & 0xff);
        out.write((value >> 24) & 0xff);
    }

    @Override
    public String getInterfaceDescriptor() throws RemoteException {
        return delegate.getInterfaceDescriptor();
    }

    @Override
    public boolean pingBinder() {
        return delegate.pingBinder();
    }

    @Override
    public boolean isBinderAlive() {
        return delegate.isBinderAlive();
    }

    @Override
    public IInterface queryLocalInterface(String descriptor) {
        return null;
    }

    @Override
    public void dump(FileDescriptor fd, String[] args) throws RemoteException {
        delegate.dump(fd, args);
    }

    @Override
    public void dumpAsync(FileDescriptor fd, String[] args) throws RemoteException {
        delegate.dumpAsync(fd, args);
    }

    @Override
    public void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException {
        delegate.linkToDeath(recipient, flags);
    }

    @Override
    public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
        return delegate.unlinkToDeath(recipient, flags);
    }
}
