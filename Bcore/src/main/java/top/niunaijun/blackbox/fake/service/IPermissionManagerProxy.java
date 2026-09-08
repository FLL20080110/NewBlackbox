package top.niunaijun.blackbox.fake.service;

import android.Manifest;
import android.content.pm.PackageManager;

import java.lang.reflect.Method;

import black.android.app.BRActivityThread;
import black.android.app.BRContextImpl;
import black.android.os.BRServiceManager;
import black.android.permission.BRIPermissionManagerStub;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.frameworks.BLocationManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.service.base.PkgMethodProxy;
import top.niunaijun.blackbox.fake.service.base.ValueMethodProxy;
import top.niunaijun.blackbox.utils.Reflector;
import top.niunaijun.blackbox.utils.Slog;
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

    /**
     * Android 11+ routes a large part of runtime-permission checks through permissionmgr
     * instead of PackageManager. A virtual guest has no real Android UID/package entry, so
     * forwarding ACCESS_FINE_LOCATION/ACCESS_COARSE_LOCATION to system_server makes the
     * guest appear to have only approximate/no location even though BlackBox is already
     * supplying a synthetic location.
     *
     * Only report location permission as granted while the container-wide virtual location
     * is active. This does not expose the host's physical GPS; the guest still receives the
     * BLocation supplied by the virtual location service.
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();
        if (isPermissionCheck(methodName) && hasVirtualLocationPermissionArg(args)
                && BLocationManager.get().isContainerLocationEnabled()) {
            Slog.d(TAG, "Granting precise virtual location permission for " + methodName);
            return PackageManager.PERMISSION_GRANTED;
        }
        return super.invoke(proxy, method, args);
    }

    private static boolean isPermissionCheck(String methodName) {
        return "checkPermission".equals(methodName)
                || "checkUidPermission".equals(methodName)
                || "checkPermissionUncached".equals(methodName);
    }

    private static boolean hasVirtualLocationPermissionArg(Object[] args) {
        if (args == null) return false;
        for (Object arg : args) {
            if (!(arg instanceof String)) continue;
            String permission = (String) arg;
            if (Manifest.permission.ACCESS_FINE_LOCATION.equals(permission)
                    || Manifest.permission.ACCESS_COARSE_LOCATION.equals(permission)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

}
