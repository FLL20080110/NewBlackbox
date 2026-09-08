package top.niunaijun.blackbox.fake.service;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.os.IBinder;

import java.lang.reflect.Method;

import black.android.app.BRAppOpsManager;
import black.android.os.BRServiceManager;
import black.com.android.internal.app.BRIAppOpsServiceStub;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.frameworks.VirtualPermissionManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import top.niunaijun.blackbox.utils.Slog;

public class IAppOpsManagerProxy extends BinderInvocationStub {
    public IAppOpsManagerProxy() {
        super(BRServiceManager.get().getService(Context.APP_OPS_SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder call = BRServiceManager.get().getService(Context.APP_OPS_SERVICE);
        return BRIAppOpsServiceStub.get().asInterface(call);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        if (BRAppOpsManager.get(null)._check_mService() != null) {
            AppOpsManager appOpsManager = (AppOpsManager) BlackBoxCore.getContext().getSystemService(Context.APP_OPS_SERVICE);
            try {
                BRAppOpsManager.get(appOpsManager)._set_mService(getProxyInvocation());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        replaceSystemService(Context.APP_OPS_SERVICE);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();
        if (methodName.startsWith("check") || methodName.startsWith("note") || methodName.startsWith("start")) {
            Integer virtualMode = getVirtualMode(args);
            if (virtualMode != null && returnsModeInt(method)) {
                Slog.d(TAG, "AppOps " + methodName + " -> virtual mode " + virtualMode);
                return virtualMode;
            }

            // Android 12+ introduced AppOps methods whose result is a parcelable/object rather
            // than an int mode (for example SyncNotedAppOp variants). Returning an Integer from
            // those methods corrupts the Binder call contract and can crash callers on Android 16.
            // Keep their exact framework return type and only virtualize classic int-mode calls.
            return invokeOriginal(method, args);
        }
        if (methodName.startsWith("finish")) {
            try {
                invokeOriginal(method, args);
            } catch (Throwable ignored) {
            }
            return null;
        }

        try {
            return super.invoke(proxy, method, args);
        } catch (Throwable e) {
            Slog.w(TAG, "AppOps fallback for " + methodName + ": " + e.getMessage());
            return defaultValue(method.getReturnType());
        }
    }

    private static boolean returnsModeInt(Method method) {
        Class<?> type = method.getReturnType();
        return type == int.class || type == Integer.class;
    }

    private Object invokeOriginal(Method method, Object[] args) throws Throwable {
        try {
            MethodParameterUtils.replaceFirstAppPkg(args);
            MethodParameterUtils.replaceLastUid(args);
            return method.invoke(getBase(), args);
        } catch (Throwable e) {
            Throwable cause = e.getCause();
            if (cause != null) throw cause;
            throw e;
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("noteProxyOperation")
    public static class NoteProxyOperation extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) { return modeOrAllowed(args); }
    }

    @ProxyMethod("checkPackage")
    public static class CheckPackage extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) { return AppOpsManager.MODE_ALLOWED; }
    }

    @ProxyMethod("checkOperation")
    public static class CheckOperation extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) { return modeOrAllowed(args); }
    }

    @ProxyMethod("checkOperationForDevice")
    public static class CheckOperationForDevice extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) { return modeOrAllowed(args); }
    }

    @ProxyMethod("noteOperation")
    public static class NoteOperation extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) { return modeOrAllowed(args); }
    }

    @ProxyMethod("checkOpNoThrow")
    public static class CheckOpNoThrow extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) { return modeOrAllowed(args); }
    }

    @ProxyMethod("startOp")
    public static class StartOp extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) { return modeOrAllowed(args); }
    }

    @ProxyMethod("startOpNoThrow")
    public static class StartOpNoThrow extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) { return modeOrAllowed(args); }
    }

    @ProxyMethod("finishOp")
    public static class FinishOp extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) { return null; }
    }

    @ProxyMethod("noteOp")
    public static class NoteOp extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) { return modeOrAllowed(args); }
    }

    @ProxyMethod("noteOpNoThrow")
    public static class NoteOpNoThrow extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) { return modeOrAllowed(args); }
    }

    private static int modeOrAllowed(Object[] args) {
        Integer mode = getVirtualMode(args);
        return mode != null ? mode : AppOpsManager.MODE_ALLOWED;
    }

    private static Integer getVirtualMode(Object[] args) {
        String permission = permissionFromArgs(args);
        if (permission == null) return null;
        String pkg = BActivityThread.getAppPackageName();
        if (pkg == null) return null;
        boolean granted = VirtualPermissionManager.isPermissionGranted(pkg, BActivityThread.getUserId(), permission);
        return granted ? AppOpsManager.MODE_ALLOWED : AppOpsManager.MODE_IGNORED;
    }

    private static String permissionFromArgs(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof String) {
                String permission = permissionForOpName((String) arg);
                if (permission != null) return permission;
            }
        }
        for (Object arg : args) {
            if (!(arg instanceof Integer)) continue;
            int candidate = (Integer) arg;
            if (candidate < 0 || candidate >= 10000) continue;
            String permission = permissionForOpName(getOpPublicName(candidate));
            if (permission != null) return permission;
        }
        return null;
    }

    private static String permissionForOpName(String name) {
        if (name == null) return null;
        String n = name.toUpperCase();
        if (n.contains("FINE_LOCATION")) return Manifest.permission.ACCESS_FINE_LOCATION;
        if (n.contains("COARSE_LOCATION")) return Manifest.permission.ACCESS_COARSE_LOCATION;
        if (n.contains("BACKGROUND_LOCATION")) return Manifest.permission.ACCESS_BACKGROUND_LOCATION;
        if (n.contains("CAMERA")) return Manifest.permission.CAMERA;
        if (n.contains("RECORD_AUDIO") || n.contains("MICROPHONE")) return Manifest.permission.RECORD_AUDIO;
        if (n.contains("READ_CONTACTS")) return Manifest.permission.READ_CONTACTS;
        if (n.contains("WRITE_CONTACTS")) return Manifest.permission.WRITE_CONTACTS;
        if (n.contains("GET_ACCOUNTS")) return Manifest.permission.GET_ACCOUNTS;
        if (n.contains("READ_CALENDAR")) return Manifest.permission.READ_CALENDAR;
        if (n.contains("WRITE_CALENDAR")) return Manifest.permission.WRITE_CALENDAR;
        if (n.contains("READ_MEDIA_IMAGES") && android.os.Build.VERSION.SDK_INT >= 33) return Manifest.permission.READ_MEDIA_IMAGES;
        if (n.contains("READ_MEDIA_VIDEO") && android.os.Build.VERSION.SDK_INT >= 33) return Manifest.permission.READ_MEDIA_VIDEO;
        if (n.contains("READ_MEDIA_AUDIO") && android.os.Build.VERSION.SDK_INT >= 33) return Manifest.permission.READ_MEDIA_AUDIO;
        if (n.contains("READ_EXTERNAL_STORAGE")) return Manifest.permission.READ_EXTERNAL_STORAGE;
        if (n.contains("WRITE_EXTERNAL_STORAGE")) return Manifest.permission.WRITE_EXTERNAL_STORAGE;
        if (n.contains("READ_PHONE_NUMBERS") && android.os.Build.VERSION.SDK_INT >= 26) return Manifest.permission.READ_PHONE_NUMBERS;
        if (n.contains("READ_PHONE_STATE")) return Manifest.permission.READ_PHONE_STATE;
        if (n.contains("CALL_PHONE")) return Manifest.permission.CALL_PHONE;
        if (n.contains("ANSWER_PHONE_CALLS") && android.os.Build.VERSION.SDK_INT >= 26) return Manifest.permission.ANSWER_PHONE_CALLS;
        if (n.contains("SEND_SMS")) return Manifest.permission.SEND_SMS;
        if (n.contains("RECEIVE_SMS")) return Manifest.permission.RECEIVE_SMS;
        if (n.contains("READ_SMS")) return Manifest.permission.READ_SMS;
        if (n.contains("RECEIVE_MMS")) return Manifest.permission.RECEIVE_MMS;
        if (n.contains("RECEIVE_WAP_PUSH")) return Manifest.permission.RECEIVE_WAP_PUSH;
        if (n.contains("ACTIVITY_RECOGNITION") && android.os.Build.VERSION.SDK_INT >= 29) return Manifest.permission.ACTIVITY_RECOGNITION;
        if (n.contains("BODY_SENSORS_BACKGROUND") && android.os.Build.VERSION.SDK_INT >= 33) return Manifest.permission.BODY_SENSORS_BACKGROUND;
        if (n.contains("BODY_SENSORS")) return Manifest.permission.BODY_SENSORS;
        if (n.contains("BLUETOOTH_SCAN") && android.os.Build.VERSION.SDK_INT >= 31) return Manifest.permission.BLUETOOTH_SCAN;
        if (n.contains("BLUETOOTH_CONNECT") && android.os.Build.VERSION.SDK_INT >= 31) return Manifest.permission.BLUETOOTH_CONNECT;
        if (n.contains("BLUETOOTH_ADVERTISE") && android.os.Build.VERSION.SDK_INT >= 31) return Manifest.permission.BLUETOOTH_ADVERTISE;
        if (n.contains("NEARBY_WIFI") && android.os.Build.VERSION.SDK_INT >= 33) return Manifest.permission.NEARBY_WIFI_DEVICES;
        if (n.contains("POST_NOTIFICATION") && android.os.Build.VERSION.SDK_INT >= 33) return Manifest.permission.POST_NOTIFICATIONS;
        return null;
    }

    private static String getOpPublicName(int op) {
        try {
            Method m = AppOpsManager.class.getMethod("opToPublicName", int.class);
            Object name = m.invoke(null, op);
            return name != null ? name.toString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == void.class) return null;
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return AppOpsManager.MODE_ALLOWED;
        if (returnType == long.class) return 0L;
        if (returnType == float.class) return 0f;
        if (returnType == double.class) return 0d;
        return null;
    }
}
