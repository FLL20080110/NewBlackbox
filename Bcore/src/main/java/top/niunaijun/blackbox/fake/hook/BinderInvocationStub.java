package top.niunaijun.blackbox.fake.hook;

import android.content.pm.VersionedPackage;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileDescriptor;
import java.lang.reflect.Method;
import java.util.Map;

import black.android.os.BRServiceManager;


public abstract class BinderInvocationStub extends ClassInvocationStub implements IBinder {
    private static final String TAG_PM = "PackageManagerStub";
    private static final String PM_DESCRIPTOR = "android.content.pm.IPackageManager";
    private static final int API36_PM_EXTRA_TAIL_BYTES = 32;

    private IBinder mBaseBinder;

    public BinderInvocationStub(IBinder baseBinder) {
        mBaseBinder = baseBinder;
    }

    @Override
    protected void onBindMethod() {
    }

    @Nullable
    @Override
    public String getInterfaceDescriptor() throws RemoteException {
        return mBaseBinder.getInterfaceDescriptor();
    }

    @Override
    public boolean pingBinder() {
        return mBaseBinder.pingBinder();
    }

    @Override
    public boolean isBinderAlive() {
        return mBaseBinder.isBinderAlive();
    }

    @Nullable
    @Override
    public IInterface queryLocalInterface(@NonNull String descriptor) {
        return (IInterface) getProxyInvocation();
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @Nullable String[] args) throws RemoteException {
        mBaseBinder.dump(fd, args);
    }

    @Override
    public void dumpAsync(@NonNull FileDescriptor fd, @Nullable String[] args) throws RemoteException {
        mBaseBinder.dumpAsync(fd, args);
    }

    /**
     * Resolve an Android 16 package-manager transaction code to the framework
     * method name when the hidden generated Stub exposes getDefaultTransactionName.
     * This is diagnostic only and deliberately fails closed.
     */
    @Nullable
    private static String getPackageManagerTransactionName(int code) {
        try {
            Class<?> stub = Class.forName("android.content.pm.IPackageManager$Stub");
            Method method = stub.getDeclaredMethod("getDefaultTransactionName", int.class);
            method.setAccessible(true);
            Object value = method.invoke(null, code);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * A few protected/legacy guests issue a raw IPackageManager transaction with
     * the current API-36 argument prefix followed by an additional 32-byte tail.
     * Android 16's generated Stub calls enforceNoDataAvail(), so forwarding that
     * Parcel unchanged throws BadParcelableException before PackageManager can
     * answer the request.
     *
     * Parse only the two transactions observed in the crash log using the API-36
     * schema. If and only if exactly 32 bytes remain after the expected arguments,
     * return a trimmed copy. Any other shape fails closed and is forwarded exactly
     * as received.
     */
    @Nullable
    private static Parcel sanitizePackageManagerParcelForApi36(
            @NonNull String transactionName, @NonNull Parcel source) {
        if (!"getPackageInfo".equals(transactionName)
                && !"getPackageInfoVersioned".equals(transactionName)) {
            return null;
        }

        Parcel copy = Parcel.obtain();
        try {
            copy.appendFrom(source, 0, source.dataSize());
            copy.setDataPosition(0);
            copy.enforceInterface(PM_DESCRIPTOR);

            if ("getPackageInfo".equals(transactionName)) {
                copy.readString();      // packageName
            } else {
                copy.readTypedObject(VersionedPackage.CREATOR);
            }
            copy.readLong();            // flags on Android 13+
            copy.readInt();             // userId

            int expectedEnd = copy.dataPosition();
            int remaining = copy.dataAvail();
            Log.d(TAG_PM, "API36 parsed " + transactionName
                    + ", expectedEnd=" + expectedEnd
                    + ", remaining=" + remaining
                    + ", originalSize=" + source.dataSize());

            if (remaining != API36_PM_EXTRA_TAIL_BYTES) {
                copy.recycle();
                return null;
            }

            copy.setDataSize(expectedEnd);
            copy.setDataPosition(expectedEnd);
            Log.w(TAG_PM, "API36 trimmed malformed " + transactionName
                    + " parcel by " + API36_PM_EXTRA_TAIL_BYTES + " bytes");
            return copy;
        } catch (Throwable parseError) {
            Log.w(TAG_PM, "API36 failed to parse raw " + transactionName
                    + " parcel; forwarding unchanged", parseError);
            copy.recycle();
            return null;
        }
    }

    @Override
    public boolean transact(int code, @NonNull Parcel data, @Nullable Parcel reply, int flags) throws RemoteException {
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                String descriptor = mBaseBinder.getInterfaceDescriptor();
                if (PM_DESCRIPTOR.equals(descriptor)) {
                    String transactionName = getPackageManagerTransactionName(code);
                    Log.d(TAG_PM, "API36 raw transact code=" + code
                            + ", name=" + (transactionName == null ? "unknown" : transactionName)
                            + ", dataSize=" + data.dataSize()
                            + ", dataPosition=" + data.dataPosition()
                            + ", flags=" + flags);

                    if (transactionName != null) {
                        Parcel sanitized = sanitizePackageManagerParcelForApi36(transactionName, data);
                        if (sanitized != null) {
                            try {
                                return mBaseBinder.transact(code, sanitized, reply, flags);
                            } finally {
                                sanitized.recycle();
                            }
                        }
                    }
                }
            } catch (Throwable diagnosticError) {
                Log.w(TAG_PM, "API36 package transact compatibility failed; forwarding unchanged",
                        diagnosticError);
            }
        }
        return mBaseBinder.transact(code, data, reply, flags);
    }

    @Override
    public void linkToDeath(@NonNull DeathRecipient recipient, int flags) throws RemoteException {
        mBaseBinder.linkToDeath(recipient, flags);
    }

    @Override
    public boolean unlinkToDeath(@NonNull DeathRecipient recipient, int flags) {
        return mBaseBinder.unlinkToDeath(recipient, flags);
    }


    protected void replaceSystemService(String name) {
        Map<String, IBinder> services = BRServiceManager.get().sCache();
        services.put(name, this);
    }
}
