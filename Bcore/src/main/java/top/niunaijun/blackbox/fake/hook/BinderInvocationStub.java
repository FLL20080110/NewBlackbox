package top.niunaijun.blackbox.fake.hook;

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

    @Override
    public boolean transact(int code, @NonNull Parcel data, @Nullable Parcel reply, int flags) throws RemoteException {
        // On Android 16, log raw package-manager Binder traffic that bypasses
        // the Java method proxy. A protected/legacy guest may ship an older
        // generated IPackageManager proxy; forwarding that Parcel unchanged to
        // the API-36 system_server can produce enforceNoDataAvail() failures.
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
                }
            } catch (Throwable diagnosticError) {
                Log.w(TAG_PM, "API36 package transact diagnostic failed", diagnosticError);
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
