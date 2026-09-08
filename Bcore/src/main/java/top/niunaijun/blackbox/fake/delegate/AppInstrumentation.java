package top.niunaijun.blackbox.fake.delegate;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.Fragment;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.util.Log;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public ActivityResult execStartActivity(Context context, IBinder contextThread, IBinder token,
                                            Activity activity, Intent intent, int requestCode) throws Throwable {
        if (handleVirtualPermissionRequest(activity, intent, requestCode)) return null;
        return super.execStartActivity(context, contextThread, token, activity, intent, requestCode);
    }

    public ActivityResult execStartActivity(Context context, IBinder contextThread, IBinder token,
                                            Fragment fragment, Intent intent, int requestCode) throws Throwable {
        Activity activity = fragment != null ? fragment.getActivity() : null;
        if (handleVirtualPermissionRequest(activity, intent, requestCode)) return null;
        return super.execStartActivity(context, contextThread, token, fragment, intent, requestCode);
    }

    public ActivityResult execStartActivity(Context context, IBinder contextThread, IBinder token,
                                            Fragment fragment, Intent intent, int requestCode,
                                            Bundle options) throws Throwable {
        Activity activity = fragment != null ? fragment.getActivity() : null;
        if (handleVirtualPermissionRequest(activity, intent, requestCode)) return null;
        return super.execStartActivity(context, contextThread, token, fragment, intent, requestCode, options);
    }

    public ActivityResult execStartActivity(Context context, IBinder contextThread, IBinder token,
                                            String target, Intent intent, int requestCode,
                                            Bundle options) throws Throwable {
        Activity activity = context instanceof Activity ? (Activity) context : null;
        if (handleVirtualPermissionRequest(activity, intent, requestCode)) return null;
        return super.execStartActivity(context, contextThread, token, target, intent, requestCode, options);
    }

    public ActivityResult execStartActivity(Context context, IBinder contextThread, IBinder token,
                                            Activity activity, Intent intent, int requestCode,
                                            Bundle options, UserHandle userHandle) throws Throwable {
        if (handleVirtualPermissionRequest(activity, intent, requestCode)) return null;
        return super.execStartActivity(context, contextThread, token, activity, intent,
                requestCode, options, userHandle);
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

        List<String> promptable = new ArrayList<>();
        for (String permission : permissions) {
            int state = VirtualPermissionManager.getPermissionState(packageName, userId, permission);
            if (state == VirtualPermissionManager.STATE_DEFAULT
                    || state == VirtualPermissionManager.STATE_DENIED) {
                promptable.add(permission);
            }
        }

        final String[] callbackPermissions = permissions.clone();
        if (promptable.isEmpty()) {
            deliverPermissionResult(activity, packageName, userId, requestCode, callbackPermissions);
        } else {
            final String[] promptPermissions = promptable.toArray(new String[0]);
            activity.runOnUiThread(() -> showVirtualPermissionSequence(
                    activity, packageName, userId, requestCode, callbackPermissions, promptPermissions));
        }
        return true;
    }

    /**
     * Background location is staged after foreground location. Other permissions are then shown by
     * logical permission group while their grant state remains stored per concrete permission.
     */
    private void showVirtualPermissionSequence(Activity activity, String packageName, int userId,
                                               int requestCode, String[] callbackPermissions,
                                               String[] promptPermissions) {
        boolean asksBackground = containsPermission(promptPermissions,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION);

        List<String> firstStage = new ArrayList<>();
        for (String permission : promptPermissions) {
            if (!Manifest.permission.ACCESS_BACKGROUND_LOCATION.equals(permission)) {
                firstStage.add(permission);
            }
        }

        Runnable finish = () -> deliverPermissionResult(
                activity, packageName, userId, requestCode, callbackPermissions);

        if (!asksBackground) {
            showVirtualPermissionGroups(activity, packageName, userId,
                    firstStage.toArray(new String[0]), finish);
            return;
        }

        Runnable backgroundStage = () -> {
            if (hasForegroundLocation(packageName, userId)) {
                showVirtualPermissionGroups(activity, packageName, userId,
                        new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, finish);
            } else {
                VirtualPermissionManager.setPermissionState(packageName, userId,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                        VirtualPermissionManager.STATE_DENIED);
                finish.run();
            }
        };

        if (firstStage.isEmpty()) {
            backgroundStage.run();
        } else {
            showVirtualPermissionGroups(activity, packageName, userId,
                    firstStage.toArray(new String[0]), backgroundStage);
        }
    }

    private boolean hasForegroundLocation(String packageName, int userId) {
        return VirtualPermissionManager.isPermissionGranted(packageName, userId,
                Manifest.permission.ACCESS_FINE_LOCATION)
                || VirtualPermissionManager.isPermissionGranted(packageName, userId,
                Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    private boolean containsPermission(String[] permissions, String wanted) {
        if (permissions == null) return false;
        for (String permission : permissions) if (wanted.equals(permission)) return true;
        return false;
    }

    private void showVirtualPermissionGroups(Activity activity, String packageName, int userId,
                                             String[] promptPermissions, Runnable completion) {
        if (promptPermissions == null || promptPermissions.length == 0) {
            completion.run();
            return;
        }

        LinkedHashMap<String, List<String>> grouped = new LinkedHashMap<>();
        for (String permission : promptPermissions) {
            String group = VirtualPermissionManager.getPermissionGroup(permission);
            List<String> values = grouped.get(group);
            if (values == null) {
                values = new ArrayList<>();
                grouped.put(group, values);
            }
            values.add(permission);
        }
        List<Map.Entry<String, List<String>>> groups = new ArrayList<>(grouped.entrySet());
        showVirtualPermissionGroupAt(activity, packageName, userId, groups, 0, completion);
    }

    private void showVirtualPermissionGroupAt(Activity activity, String packageName, int userId,
                                              List<Map.Entry<String, List<String>>> groups,
                                              int index, Runnable completion) {
        if (index >= groups.size()) {
            completion.run();
            return;
        }
        Map.Entry<String, List<String>> entry = groups.get(index);
        String label = VirtualPermissionManager.getPermissionGroupLabel(entry.getKey());
        String[] permissions = entry.getValue().toArray(new String[0]);
        showVirtualPermissionPrompt(activity, packageName, userId, label, permissions,
                () -> showVirtualPermissionGroupAt(activity, packageName, userId,
                        groups, index + 1, completion));
    }

    private void showVirtualPermissionPrompt(Activity activity, String packageName, int userId,
                                             String groupLabel, String[] promptPermissions,
                                             Runnable completion) {
        final boolean[] handled = {false};
        StringBuilder message = new StringBuilder();
        for (String permission : promptPermissions) {
            if (message.length() > 0) message.append('\n');
            message.append("• ").append(permission.substring(permission.lastIndexOf('.') + 1));
        }
        try {
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle("权限请求 · " + groupLabel)
                    .setMessage(message.toString())
                    .setPositiveButton("允许", (d, which) -> {
                        handled[0] = true;
                        setPermissionStates(packageName, userId, promptPermissions,
                                VirtualPermissionManager.STATE_GRANTED);
                        completion.run();
                    })
                    .setNegativeButton("拒绝", (d, which) -> {
                        handled[0] = true;
                        setPermissionStates(packageName, userId, promptPermissions,
                                VirtualPermissionManager.STATE_DENIED);
                        completion.run();
                    })
                    .setNeutralButton("拒绝且不再询问", (d, which) -> {
                        handled[0] = true;
                        setPermissionStates(packageName, userId, promptPermissions,
                                VirtualPermissionManager.STATE_DENIED_FIXED);
                        completion.run();
                    }).create();
            dialog.setOnCancelListener(d -> {
                if (!handled[0]) {
                    setPermissionStates(packageName, userId, promptPermissions,
                            VirtualPermissionManager.STATE_DENIED);
                    completion.run();
                }
            });
            dialog.show();
        } catch (Throwable e) {
            Log.e(TAG, "Unable to show virtual permission prompt", e);
            completion.run();
        }
    }

    private void setPermissionStates(String packageName, int userId,
                                     String[] permissions, int state) {
        for (String permission : permissions) {
            VirtualPermissionManager.setPermissionState(packageName, userId, permission, state);
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
