package top.niunaijun.blackbox.fake.delegate;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.util.Log;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import black.android.app.BRActivity;
import black.android.app.BRActivityThread;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.frameworks.VirtualPermissionManager;
import top.niunaijun.blackbox.fake.hook.HookManager;
import top.niunaijun.blackbox.fake.hook.IInjectHook;
import top.niunaijun.blackbox.fake.service.HCallbackProxy;
import top.niunaijun.blackbox.fake.service.IActivityClientProxy;
import top.niunaijun.blackbox.utils.HackAppUtils;
import top.niunaijun.blackbox.utils.compat.ActivityCompat;
import top.niunaijun.blackbox.utils.compat.ActivityManagerCompat;
import top.niunaijun.blackbox.utils.compat.ContextCompat;

public final class AppInstrumentation extends BaseInstrumentationDelegate implements IInjectHook {
    private static final String TAG = AppInstrumentation.class.getSimpleName();
    private static final String ACTION_REQUEST_PERMISSIONS = "android.content.pm.action.REQUEST_PERMISSIONS";
    private static final String EXTRA_REQUEST_PERMISSIONS_NAMES = "android.content.pm.extra.REQUEST_PERMISSIONS_NAMES";
    private static AppInstrumentation sAppInstrumentation;

    public static AppInstrumentation get() {
        if (sAppInstrumentation == null) {
            synchronized (AppInstrumentation.class) {
                if (sAppInstrumentation == null) sAppInstrumentation = new AppInstrumentation();
            }
        }
        return sAppInstrumentation;
    }

    public AppInstrumentation() {}

    @Override
    public void injectHook() {
        try {
            Instrumentation mInstrumentation = getCurrInstrumentation();
            if (mInstrumentation == this || checkInstrumentation(mInstrumentation)) return;
            mBaseInstrumentation = mInstrumentation;
            BRActivityThread.get(BlackBoxCore.mainThread())._set_mInstrumentation(this);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Instrumentation getCurrInstrumentation() {
        return BRActivityThread.get(BlackBoxCore.mainThread()).mInstrumentation();
    }

    @Override
    public boolean isBadEnv() { return !checkInstrumentation(getCurrInstrumentation()); }

    private boolean checkInstrumentation(Instrumentation instrumentation) {
        if (instrumentation instanceof AppInstrumentation) return true;
        Class<?> clazz = instrumentation.getClass();
        if (Instrumentation.class.equals(clazz)) return false;
        do {
            assert clazz != null;
            for (Field field : clazz.getDeclaredFields()) {
                if (Instrumentation.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    try {
                        if (field.get(instrumentation) instanceof AppInstrumentation) return true;
                    } catch (Exception e) {
                        return false;
                    }
                }
            }
            clazz = clazz.getSuperclass();
        } while (!Instrumentation.class.equals(clazz));
        return false;
    }

    private void checkHCallback() { HookManager.get().checkEnv(HCallbackProxy.class); }

    private void checkActivity(Activity activity) {
        Log.d(TAG, "callActivityOnCreate: " + activity.getClass().getName());
        HackAppUtils.enableQQLogOutput(activity.getPackageName(), activity.getClassLoader());
        checkHCallback();
        HookManager.get().checkEnv(IActivityClientProxy.class);
        ActivityInfo info = BRActivity.get(activity).mActivityInfo();
        ContextCompat.fix(activity);
        ActivityCompat.fix(activity);
        if (info.theme != 0) activity.getTheme().applyStyle(info.theme, true);
        ActivityManagerCompat.setActivityOrientation(activity, info.screenOrientation);
    }

    @Override
    public Application newApplication(ClassLoader cl, String className, Context context)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        ContextCompat.fix(context);
        return super.newApplication(cl, className, context);
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle, PersistableBundle persistentState) {
        checkActivity(activity);
        super.callActivityOnCreate(activity, icicle, persistentState);
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle) {
        checkActivity(activity);
        super.callActivityOnCreate(activity, icicle);
    }

    @Override
    public void callApplicationOnCreate(Application app) {
        checkHCallback();
        super.callApplicationOnCreate(app);
    }

    public Activity newActivity(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        try {
            return super.newActivity(cl, className, intent);
        } catch (ClassNotFoundException e) {
            return mBaseInstrumentation.newActivity(cl, className, intent);
        }
    }

    public ActivityResult execStartActivity(Context context, IBinder contextThread, IBinder token,
                                            Activity activity, Intent intent, int requestCode,
                                            Bundle options) throws Throwable {
        if (handleVirtualPermissionRequest(activity, intent, requestCode)) return null;
        return super.execStartActivity(context, contextThread, token, activity, intent, requestCode, options);
    }

    private boolean handleVirtualPermissionRequest(Activity activity, Intent intent, int requestCode) {
        if (activity == null || intent == null || !ACTION_REQUEST_PERMISSIONS.equals(intent.getAction())) return false;
        String[] permissions = intent.getStringArrayExtra(EXTRA_REQUEST_PERMISSIONS_NAMES);
        if (permissions == null || permissions.length == 0) return false;
        for (String permission : permissions) {
            if (!VirtualPermissionManager.isManagedRuntimePermission(permission)) return false;
        }

        final String packageName = BActivityThread.getAppPackageName();
        final int userId = BActivityThread.getUserId();
        if (packageName == null) return false;

        List<String> undecided = new ArrayList<>();
        for (String permission : permissions) {
            int state = VirtualPermissionManager.getPermissionState(packageName, userId, permission);
            if (state == VirtualPermissionManager.STATE_DEFAULT) undecided.add(permission);
        }

        final String[] callbackPermissions = permissions.clone();
        if (undecided.isEmpty()) {
            deliverPermissionResult(activity, packageName, userId, requestCode, callbackPermissions);
        } else {
            final String[] promptPermissions = undecided.toArray(new String[0]);
            activity.runOnUiThread(() -> showVirtualPermissionPrompt(
                    activity, packageName, userId, requestCode, callbackPermissions, promptPermissions));
        }
        return true;
    }

    private void showVirtualPermissionPrompt(Activity activity, String packageName, int userId,
                                             int requestCode, String[] callbackPermissions,
                                             String[] promptPermissions) {
        final boolean[] handled = {false};
        StringBuilder message = new StringBuilder();
        for (String permission : promptPermissions) {
            if (message.length() > 0) message.append('\n');
            message.append("• ").append(permission.substring(permission.lastIndexOf('.') + 1));
        }
        try {
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle("权限请求")
                    .setMessage(message.toString())
                    .setPositiveButton("允许", (d, which) -> {
                        handled[0] = true;
                        for (String permission : promptPermissions) {
                            VirtualPermissionManager.setPermissionState(packageName, userId, permission,
                                    VirtualPermissionManager.STATE_GRANTED);
                        }
                        deliverPermissionResult(activity, packageName, userId, requestCode, callbackPermissions);
                    })
                    .setNegativeButton("拒绝", (d, which) -> {
                        handled[0] = true;
                        for (String permission : promptPermissions) {
                            VirtualPermissionManager.setPermissionState(packageName, userId, permission,
                                    VirtualPermissionManager.STATE_DENIED);
                        }
                        deliverPermissionResult(activity, packageName, userId, requestCode, callbackPermissions);
                    }).create();
            dialog.setOnCancelListener(d -> {
                if (!handled[0]) {
                    for (String permission : promptPermissions) {
                        VirtualPermissionManager.setPermissionState(packageName, userId, permission,
                                VirtualPermissionManager.STATE_DENIED);
                    }
                    deliverPermissionResult(activity, packageName, userId, requestCode, callbackPermissions);
                }
            });
            dialog.show();
        } catch (Throwable e) {
            Log.e(TAG, "Unable to show virtual permission prompt", e);
            deliverPermissionResult(activity, packageName, userId, requestCode, callbackPermissions);
        }
    }

    private void deliverPermissionResult(Activity activity, String packageName, int userId,
                                         int requestCode, String[] permissions) {
        int[] results = new int[permissions.length];
        for (int i = 0; i < permissions.length; i++) {
            results[i] = VirtualPermissionManager.isPermissionGranted(packageName, userId, permissions[i])
                    ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
        }
        Log.d(TAG, "Virtual runtime permission result: " + Arrays.toString(permissions)
                + " => " + Arrays.toString(results));
        activity.runOnUiThread(() -> {
            try {
                activity.onRequestPermissionsResult(requestCode, permissions, results);
            } catch (Throwable e) {
                Log.e(TAG, "Failed to deliver virtual permission result", e);
            }
        });
    }
}
