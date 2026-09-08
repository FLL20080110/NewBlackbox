package top.niunaijun.blackbox.fake.frameworks;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import top.niunaijun.blackbox.BlackBoxCore;

/**
 * Per-guest virtual permission state shared by the manager UI and guest processes.
 * This is intentionally independent from Android's real package/UID permission state.
 */
public final class VirtualPermissionManager {
    private static final String PREFS = "blackbox_virtual_permissions";

    private VirtualPermissionManager() {
    }

    private static SharedPreferences prefs() {
        return BlackBoxCore.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(String packageName, int userId, String permission) {
        return userId + "|" + packageName + "|" + permission;
    }

    public static void setPermission(String packageName, int userId, String permission, boolean granted) {
        if (packageName == null || permission == null) return;
        prefs().edit().putBoolean(key(packageName, userId, permission), granted).apply();
    }

    public static boolean isPermissionGranted(String packageName, int userId, String permission) {
        if (packageName == null || permission == null) return false;
        return prefs().getBoolean(key(packageName, userId, permission), false);
    }

    public static int checkPermission(String packageName, int userId, String permission) {
        return isPermissionGranted(packageName, userId, permission)
                ? PackageManager.PERMISSION_GRANTED
                : PackageManager.PERMISSION_DENIED;
    }

    public static boolean isLocationPermission(String permission) {
        return Manifest.permission.ACCESS_FINE_LOCATION.equals(permission)
                || Manifest.permission.ACCESS_COARSE_LOCATION.equals(permission)
                || Manifest.permission.ACCESS_BACKGROUND_LOCATION.equals(permission);
    }

    public static boolean isFineLocationGranted(String packageName, int userId) {
        return isPermissionGranted(packageName, userId, Manifest.permission.ACCESS_FINE_LOCATION);
    }

    public static boolean isCoarseLocationGranted(String packageName, int userId) {
        return isPermissionGranted(packageName, userId, Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    public static boolean isBackgroundLocationGranted(String packageName, int userId) {
        return isPermissionGranted(packageName, userId, Manifest.permission.ACCESS_BACKGROUND_LOCATION);
    }
}
