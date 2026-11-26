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
        byte[] replaced = replacePackageStrings(raw, guest, host);
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

    /**
     * Play services writes the package with a length prefix and a size field in
     * front of it. Replacing com.tinder with the longer host name without
     * updating those sizes makes the parser keep reading com.tinder.
     */
    private static byte[] replacePackageStrings(byte[] raw, String guest, String host) {
        byte[] guest16 = guest.getBytes(StandardCharsets.UTF_16LE);
        byte[] host16 = host.getBytes(StandardCharsets.UTF_16LE);
        byte[] guest8 = guest.getBytes(StandardCharsets.UTF_8);
        byte[] host8 = host.getBytes(StandardCharsets.UTF_8);
        int span16 = padded(4 + guest16.length);
        int span8 = 4 + padded(guest8.length + 1);
        java.util.List<int[]> matches = new java.util.ArrayList<>();
        for (int index = 0; index + 4 <= raw.length; index += 4) {
            if (matches16(raw, index, guest.length(), guest16, span16)) {
                matches.add(new int[]{index, 16});
            } else if (matches8(raw, index, guest8, span8)) {
                matches.add(new int[]{index, 8});
            }
        }
        if (matches.isEmpty()) {
            return null;
        }
        byte[] buf = raw;
        for (int i = matches.size() - 1; i >= 0; i--) {
            int index = matches.get(i)[0];
            if (matches.get(i)[1] == 16) {
                buf = splice(buf, index, span16, string16(host, host16));
            } else {
                buf = splice(buf, index, span8, string8(host8));
            }
        }
        return buf;
    }

    private static byte[] splice(byte[] buf, int index, int oldSpan, byte[] replacement) {
        int delta = replacement.length - oldSpan;
        for (int position = index - 4; position >= 0; position -= 4) {
            int size = readInt(buf, position);
            int coverEnd = position + 4 + size;
            if (size > 0 && size < 65536 && (size % 4) == 0
                    && position + 4 <= index && coverEnd >= index + oldSpan) {
                writeIntInPlace(buf, position, size + delta);
            }
        }
        byte[] out = new byte[buf.length + delta];
        System.arraycopy(buf, 0, out, 0, index);
        System.arraycopy(replacement, 0, out, index, replacement.length);
        System.arraycopy(buf, index + oldSpan, out, index + replacement.length, buf.length - index - oldSpan);
        return out;
    }

    private static byte[] string16(String host, byte[] hostChars) {
        byte[] out = new byte[padded(4 + hostChars.length)];
        writeIntInPlace(out, 0, host.length());
        System.arraycopy(hostChars, 0, out, 4, hostChars.length);
        return out;
    }

    private static byte[] string8(byte[] hostChars) {
        int payload = padded(hostChars.length + 1);
        byte[] out = new byte[4 + payload];
        writeIntInPlace(out, 0, hostChars.length);
        System.arraycopy(hostChars, 0, out, 4, hostChars.length);
        return out;
    }

    private static boolean matches16(byte[] raw, int offset, int charCount, byte[] chars, int span) {
        if (offset < 0 || offset + span > raw.length || readInt(raw, offset) != charCount) {
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

    private static boolean matches8(byte[] raw, int offset, byte[] chars, int span) {
        if (offset < 0 || offset + span > raw.length || readInt(raw, offset) != chars.length) {
            return false;
        }
        for (int i = 0; i < chars.length; i++) {
            if (raw[offset + 4 + i] != chars[i]) {
                return false;
            }
        }
        if (raw[offset + 4 + chars.length] != 0) {
            return false;
        }
        for (int i = 4 + chars.length + 1; i < span; i++) {
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

    private static void writeIntInPlace(byte[] raw, int offset, int value) {
        raw[offset] = (byte) (value & 0xff);
        raw[offset + 1] = (byte) ((value >> 8) & 0xff);
        raw[offset + 2] = (byte) ((value >> 16) & 0xff);
        raw[offset + 3] = (byte) ((value >> 24) & 0xff);
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
