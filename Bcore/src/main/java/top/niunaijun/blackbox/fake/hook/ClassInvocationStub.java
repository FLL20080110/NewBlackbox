package top.niunaijun.blackbox.fake.hook;

import android.Manifest;
import android.content.pm.PackageInfo;
import android.text.TextUtils;
import android.util.Log;

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
        if (mBase == null) {
            return;
        }
        mProxyInvocation = Proxy.newProxyInstance(mBase.getClass().getClassLoader(), MethodParameterUtils.getAllInterface(mBase.getClass()), this);
        if (!onlyProxy) {
            inject(mBase, mProxyInvocation);
        }

        onBindMethod();
        Class<?>[] declaredClasses = this.getClass().getDeclaredClasses();
        for (Class<?> declaredClass : declaredClasses) {
            initAnnotation(declaredClass);
        }
        ScanClass scanClass = this.getClass().getAnnotation(ScanClass.class);
        if (scanClass != null) {
            for (Class<?> aClass : scanClass.value()) {
                for (Class<?> declaredClass : aClass.getDeclaredClasses()) {
                    initAnnotation(declaredClass);
                }
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
            String[] value = proxyMethods.value();
            for (String name : value) {
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

    private String findLocationPermission(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (!(arg instanceof String)) continue;
            String value = (String) arg;
            if (Manifest.permission.ACCESS_FINE_LOCATION.equals(value)
                    || Manifest.permission.ACCESS_COARSE_LOCATION.equals(value)
                    || Manifest.permission.ACCESS_BACKGROUND_LOCATION.equals(value)) {
                return value;
            }
        }
        return null;
    }

    /** Keep all Binder permission-query paths consistent for a virtual guest. */
    private Integer getVirtualLocationPermissionResult(Method method, Object[] args) {
        String methodName = method.getName();
        if (!"checkPermission".equals(methodName)
                && !"checkSelfPermission".equals(methodName)
                && !"checkUidPermission".equals(methodName)
                && !"checkPermissionUncached".equals(methodName)) {
            return null;
        }
        if (!isPermissionProxy()) return null;

        String permission = findLocationPermission(args);
        String packageName = BActivityThread.getAppPackageName();
        if (permission == null || packageName == null) return null;
        return VirtualPermissionManager.checkPermission(
                packageName, BActivityThread.getUserId(), permission);
    }

    /**
     * A virtual guest cannot have a real Android package settings record. If our own
     * location permission store already has a decision, do not tell the guest that it
     * needs to redirect the user to the host OS settings screen.
     */
    private Boolean getVirtualLocationRationaleResult(Method method, Object[] args) {
        if (!"shouldShowRequestPermissionRationale".equals(method.getName()) || !isPermissionProxy()) {
            return null;
        }
        String permission = findLocationPermission(args);
        String packageName = BActivityThread.getAppPackageName();
        if (permission == null || packageName == null) return null;
        return false;
    }

    /**
     * Some SDKs inspect PackageInfo.requestedPermissionsFlags instead of calling
     * checkSelfPermission. Mirror the per-app virtual location state into those flags.
     */
    private Object applyVirtualLocationPermissionFlags(Object result) {
        if (!(result instanceof PackageInfo)) return result;
        PackageInfo packageInfo = (PackageInfo) result;
        if (packageInfo.requestedPermissions == null || packageInfo.requestedPermissionsFlags == null) {
            return result;
        }

        String packageName = packageInfo.packageName;
        if (packageName == null || packageName.length() == 0) {
            packageName = BActivityThread.getAppPackageName();
        }
        if (packageName == null) return result;

        int count = Math.min(packageInfo.requestedPermissions.length,
                packageInfo.requestedPermissionsFlags.length);
        for (int i = 0; i < count; i++) {
            String permission = packageInfo.requestedPermissions[i];
            if (!VirtualPermissionManager.isLocationPermission(permission)) continue;
            if (VirtualPermissionManager.isPermissionGranted(
                    packageName, BActivityThread.getUserId(), permission)) {
                packageInfo.requestedPermissionsFlags[i] |= PackageInfo.REQUESTED_PERMISSION_GRANTED;
            } else {
                packageInfo.requestedPermissionsFlags[i] &= ~PackageInfo.REQUESTED_PERMISSION_GRANTED;
            }
        }
        return packageInfo;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Integer virtualPermissionResult = getVirtualLocationPermissionResult(method, args);
        if (virtualPermissionResult != null) {
            return virtualPermissionResult;
        }
        Boolean rationaleResult = getVirtualLocationRationaleResult(method, args);
        if (rationaleResult != null) {
            return rationaleResult;
        }

        MethodHook methodHook = mMethodHookMap.get(method.getName());
        if (methodHook == null || !methodHook.isEnable()) {
            try {
                return applyVirtualLocationPermissionFlags(method.invoke(mBase, args));
            } catch (Throwable e) {
                throw e.getCause();
            }
        }

        Object result = methodHook.beforeHook(mBase, method, args);
        if (result != null) {
            return applyVirtualLocationPermissionFlags(result);
        }
        result = methodHook.hook(mBase, method, args);
        result = methodHook.afterHook(result);
        return applyVirtualLocationPermissionFlags(result);
    }
}
