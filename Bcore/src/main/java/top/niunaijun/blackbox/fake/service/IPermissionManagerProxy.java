package top.niunaijun.blackbox.fake.service;

import java.lang.reflect.Method;

import black.android.app.BRActivityThread;
import black.android.os.BRServiceManager;
import black.android.permission.BRIPermissionManagerStub;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.frameworks.VirtualPermissionManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.service.base.PkgMethodProxy;
import top.niunaijun.blackbox.fake.service.base.ValueMethodProxy;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BuildCompat;

/**
 * PermissionManager facade for virtual guests.
 *
 * Runtime permission checks for a guest must never fall through to Android's host-package
 * permission record. The host UID and the virtual guest have different permission state, so all
 * permissions managed by VirtualPermissionManager are answered from the container service.
 */
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
        replaceSystemService(P);
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
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String permission = findManagedPermission(args);
        if (permission != null && isPermissionCheck(method.getName())) {
            String packageName = BActivityThread.getAppPackageName();
            if (packageName != null) {
                int result = VirtualPermissionManager.checkPermission(
                        packageName, BActivityThread.getUserId(), permission);
                Slog.d(TAG, "Virtual permission " + permission + " for " + packageName + " => " + result);
                return result;
            }
        }
        return super.invoke(proxy, method, args);
    }

    private static boolean isPermissionCheck(String methodName) {
        return "checkPermission".equals(methodName)
                || "checkSelfPermission".equals(methodName)
                || "checkUidPermission".equals(methodName)
                || "checkPermissionUncached".equals(methodName);
    }

    private static String findManagedPermission(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof String && VirtualPermissionManager.isManagedRuntimePermission((String) arg)) {
                return (String) arg;
            }
        }
        return null;
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}
