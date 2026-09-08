package top.niunaijun.blackbox.fake.hook;

import android.content.pm.PackageInfo;
import android.text.TextUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.frameworks.VirtualPermissionManager;
import top.niunaijun.blackbox.utils.MethodParameterUtils;

public abstract class ClassInvocationStub implements InvocationHandler, IInjectHook {
    public static final String TAG = ClassInvocationStub.class.getSimpleName();

    private final Map<String, MethodHook> mMethodHookMap = new HashMap<>();
    private Object mBase;
    private Object mProxyInvocation;
    private boolean onlyProxy;

    protected abstract Object getWho();

    protected abstract void inject(Object baseInvocation, Object proxyInvocation);

    protected void onBindMethod() {
    }

    protected Object getProxyInvocation() {
        return mProxyInvocation;
    }

    protected Object getBase() {
        return mBase;
    }

    protected void onlyProxy(boolean o) {
        onlyProxy = o;
    }

    @Override
    public void injectHook() {
        mBase = getWho();
        if (mBase == null) return;
        mProxyInvocation = Proxy.newProxyInstance(mBase.getClass().getClassLoader(),
                MethodParameterUtils.getAllInterface(mBase.getClass()), this);
        if (!onlyProxy) inject(mBase, mProxyInvocation);

        onBindMethod();
        Class<?>[] declaredClasses = this.getClass().getDeclaredClasses();
        for (Class<?> declaredClass : declaredClasses) initAnnotation(declaredClass);
        ScanClass scanClass = this.getClass().getAnnotation(ScanClass.class);
        if (scanClass != null) {
            for (Class<?> aClass : scanClass.value()) {
                for (Class<?> declaredClass : aClass.getDeclaredClasses()) initAnnotation(declaredClass);
            }
        }
    }

    protected void initAnnotation(Class<?> clazz) {
        ProxyMethod proxyMethod = clazz.getAnnotation(ProxyMethod.class);
        if (proxyMethod != null) {
            final String name = proxyMethod.value();
            if (!TextUtils.isEmpty(name)) {
                try {
                    addMethodHook(name, (MethodHook) clazz.newInstance());
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }
        ProxyMethods proxyMethods = clazz.getAnnotation(ProxyMethods.class);
        if (proxyMethods != null) {
            for (String name : proxyMethods.value()) {
                try {
                    addMethodHook(name, (MethodHook) clazz.newInstance());
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }
    }

    protected void addMethodHook(MethodHook methodHook) {
        mMethodHookMap.put(methodHook.getMethodName(), methodHook);
    }

    protected void addMethodHook(String name, MethodHook methodHook) {
        mMethodHookMap.put(name, methodHook);
    }

    private boolean isPermissionProxy() {
        String proxyName = getClass().getSimpleName();
        return "IPackageManagerProxy".equals(proxyName)
                || "IActivityManagerProxy".equals(proxyName)
                || "IPermissionManagerProxy".equals(proxyName);
    }

    private boolean isPackageManagerProxy() {
        return "IPackageManagerProxy".equals(getClass().getSimpleName());
    }

    private String findManagedPermission(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof String && VirtualPermissionManager.isManagedRuntimePermission((String) arg)) {
                return (String) arg;
            }
        }
        return null;
    }

    private boolean isLegacyPermissionMethod(Method method) {
        if (!isPackageManagerProxy()) return false;
        String name = method.getName();
        return "checkPermission".equals(name)
                || "checkSelfPermission".equals(name)
                || "shouldShowRequestPermissionRationale".equals(name)
                || "requestPermissions".equals(name);
    }

    /** Keep PackageManager, ActivityManager and PermissionManager permission checks consistent. */
    private Integer getVirtualPermissionResult(Method method, Object[] args) {
        String methodName = method.getName();
        if (!"checkPermission".equals(methodName)
                && !"checkSelfPermission".equals(methodName)
                && !"checkUidPermission".equals(methodName)
                && !"checkPermissionUncached".equals(methodName)) {
            return null;
        }
        if (!isPermissionProxy()) return null;

        String permission = findManagedPermission(args);
        String packageName = BActivityThread.getAppPackageName();
        if (permission == null || packageName == null) return null;
        return VirtualPermissionManager.checkPermission(packageName,
                BActivityThread.getUserId(), permission);
    }

    private Boolean getVirtualRationaleResult(Method method, Object[] args) {
        if (!"shouldShowRequestPermissionRationale".equals(method.getName()) || !isPermissionProxy()) {
            return null;
        }
        String permission = findManagedPermission(args);
        String packageName = BActivityThread.getAppPackageName();
        if (permission == null || packageName == null) return null;
        int state = VirtualPermissionManager.getPermissionState(packageName,
                BActivityThread.getUserId(), permission);
        return state == VirtualPermissionManager.STATE_DENIED;
    }

    /** Mirror virtual grants into PackageInfo for SDKs that inspect requestedPermissionsFlags. */
    private Object applyVirtualPermissionFlags(Object result) {
        if (!(result instanceof PackageInfo)) return result;
        PackageInfo packageInfo = (PackageInfo) result;
        if (packageInfo.requestedPermissions == null || packageInfo.requestedPermissionsFlags == null) {
            return result;
        }

        String packageName = packageInfo.packageName;
        if (packageName == null || packageName.length() == 0) packageName = BActivityThread.getAppPackageName();
        if (packageName == null) return result;

        int count = Math.min(packageInfo.requestedPermissions.length,
                packageInfo.requestedPermissionsFlags.length);
        for (int i = 0; i < count; i++) {
            String permission = packageInfo.requestedPermissions[i];
            if (VirtualPermissionManager.isManagedRuntimePermission(permission)) {
                if (VirtualPermissionManager.isPermissionGranted(packageName,
                        BActivityThread.getUserId(), permission)) {
                    packageInfo.requestedPermissionsFlags[i] |= PackageInfo.REQUESTED_PERMISSION_GRANTED;
                } else {
                    packageInfo.requestedPermissionsFlags[i] &= ~PackageInfo.REQUESTED_PERMISSION_GRANTED;
                }
            } else if (isLegacyForcedPermission(permission)) {
                // Old package-manager hooks used to mark these special/non-runtime permissions as
                // granted. They are not guest runtime grants, so never manufacture this flag here.
                packageInfo.requestedPermissionsFlags[i] &= ~PackageInfo.REQUESTED_PERMISSION_GRANTED;
            }
        }
        return packageInfo;
    }

    private boolean isLegacyForcedPermission(String permission) {
        if (permission == null) return false;
        return "android.permission.FOREGROUND_SERVICE_MICROPHONE".equals(permission)
                || "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION".equals(permission)
                || "android.permission.FOREGROUND_SERVICE_CAMERA".equals(permission)
                || "android.permission.FOREGROUND_SERVICE_LOCATION".equals(permission)
                || "android.permission.FOREGROUND_SERVICE_HEALTH".equals(permission)
                || "android.permission.FOREGROUND_SERVICE_DATA_SYNC".equals(permission)
                || "android.permission.FOREGROUND_SERVICE_SPECIAL_USE".equals(permission)
                || "android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED".equals(permission)
                || "android.permission.FOREGROUND_SERVICE_PHONE_CALL".equals(permission)
                || "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE".equals(permission)
                || "android.permission.MODIFY_AUDIO_SETTINGS".equals(permission)
                || "android.permission.CAPTURE_AUDIO_OUTPUT".equals(permission);
    }

    private Object invokeBase(Method method, Object[] args) throws Throwable {
        try {
            return applyVirtualPermissionFlags(method.invoke(mBase, args));
        } catch (Throwable e) {
            Throwable cause = e.getCause();
            throw cause != null ? cause : e;
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Integer virtualPermissionResult = getVirtualPermissionResult(method, args);
        if (virtualPermissionResult != null) return virtualPermissionResult;

        Boolean rationaleResult = getVirtualRationaleResult(method, args);
        if (rationaleResult != null) return rationaleResult;

        // Legacy IPackageManagerProxy permission hooks contained broad hard-grant compatibility
        // rules. Managed runtime permissions are handled above. For non-managed permissions, use
        // the underlying package manager instead of those old hard-grant hooks.
        if (isLegacyPermissionMethod(method) && findManagedPermission(args) == null) {
            return invokeBase(method, args);
        }

        MethodHook methodHook = mMethodHookMap.get(method.getName());
        if (methodHook == null || !methodHook.isEnable()) {
            return invokeBase(method, args);
        }

        Object result = methodHook.beforeHook(mBase, method, args);
        if (result != null) return applyVirtualPermissionFlags(result);
        result = methodHook.hook(mBase, method, args);
        result = methodHook.afterHook(result);
        return applyVirtualPermissionFlags(result);
    }
}
