package top.niunaijun.blackbox.fake.hook;

import android.Manifest;
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

    /**
     * Android routes runtime permission queries through different Binder services
     * depending on framework/API path. Keep location permission state consistent
     * for a guest across PackageManager, ActivityManager and PermissionManager.
     */
    private Integer getVirtualLocationPermissionResult(Method method, Object[] args) {
        String methodName = method.getName();
        if (!"checkPermission".equals(methodName)
                && !"checkSelfPermission".equals(methodName)
                && !"checkUidPermission".equals(methodName)
                && !"checkPermissionUncached".equals(methodName)) {
            return null;
        }

        String proxyName = getClass().getSimpleName();
        if (!"IPackageManagerProxy".equals(proxyName)
                && !"IActivityManagerProxy".equals(proxyName)
                && !"IPermissionManagerProxy".equals(proxyName)) {
            return null;
        }

        String permission = null;
        if (args != null) {
            for (Object arg : args) {
                if (!(arg instanceof String)) continue;
                String value = (String) arg;
                if (Manifest.permission.ACCESS_FINE_LOCATION.equals(value)
                        || Manifest.permission.ACCESS_COARSE_LOCATION.equals(value)
                        || Manifest.permission.ACCESS_BACKGROUND_LOCATION.equals(value)) {
                    permission = value;
                    break;
                }
            }
        }
        if (permission == null) {
            return null;
        }

        String packageName = BActivityThread.getAppPackageName();
        if (packageName == null) {
            return null;
        }
        return VirtualPermissionManager.checkPermission(
                packageName, BActivityThread.getUserId(), permission);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Integer virtualPermissionResult = getVirtualLocationPermissionResult(method, args);
        if (virtualPermissionResult != null) {
            return virtualPermissionResult;
        }

        MethodHook methodHook = mMethodHookMap.get(method.getName());
        if (methodHook == null || !methodHook.isEnable()) {
            try {
                return method.invoke(mBase, args);
            } catch (Throwable e) {
                throw e.getCause();
            }
        }

        Object result = methodHook.beforeHook(mBase, method, args);
        if (result != null) {
            return result;
        }
        result = methodHook.hook(mBase, method, args);
        result = methodHook.afterHook(result);
        return result;
    }
}
