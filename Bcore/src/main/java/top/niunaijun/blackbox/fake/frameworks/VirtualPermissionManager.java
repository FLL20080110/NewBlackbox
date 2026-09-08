package top.niunaijun.blackbox.fake.frameworks;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.RemoteException;

import top.niunaijun.blackbox.core.system.ServiceManager;
import top.niunaijun.blackbox.core.system.permission.IBPermissionManagerService;

/**
 * Client facade for container-owned guest permission state.
 *
 * All processes talk to the BlackBox system process over Binder so permission changes are
 * immediately visible to guest processes. This intentionally does not use Android's real host
 * UID/package permission state.
 */
public final class VirtualPermissionManager extends BlackManager<IBPermissionManagerService> {
    private static final VirtualPermissionManager sManager = new VirtualPermissionManager();

    private VirtualPermissionManager() {
    }

    public static VirtualPermissionManager get() {
        return sManager;
    }

    @Override
    protected String getServiceName() {
        return ServiceManager.PERMISSION_MANAGER;
    }

    public static void setPermission(String packageName, int userId, String permission, boolean granted) {
        if (packageName == null || permission == null) return;
        IBPermissionManagerService service = get().getService();
        if (service == null) return;
        try {
            service.setPermission(packageName, userId, permission, granted);
        } catch (RemoteException ignored) {
            get().clearServiceCache();
        }
    }

    public static void setPermissions(String packageName, int userId, String[] permissions, boolean[] grants) {
        if (packageName == null || permissions == null || grants == null) return;
        IBPermissionManagerService service = get().getService();
        if (service == null) return;
        try {
            service.setPermissions(packageName, userId, permissions, grants);
        } catch (RemoteException ignored) {
            get().clearServiceCache();
        }
    }

    public static boolean isPermissionGranted(String packageName, int userId, String permission) {
        if (packageName == null || permission == null) return false;
        IBPermissionManagerService service = get().getService();
        if (service == null) return false;
        try {
            return service.isPermissionGranted(packageName, userId, permission);
        } catch (RemoteException ignored) {
            get().clearServiceCache();
            return false;
        }
    }

    public static int checkPermission(String packageName, int userId, String permission) {
        if (packageName == null || permission == null) return PackageManager.PERMISSION_DENIED;
        IBPermissionManagerService service = get().getService();
        if (service == null) return PackageManager.PERMISSION_DENIED;
        try {
            return service.checkPermission(packageName, userId, permission);
        } catch (RemoteException ignored) {
            get().clearServiceCache();
            return PackageManager.PERMISSION_DENIED;
        }
    }

    public static String[] getGrantedPermissions(String packageName, int userId) {
        IBPermissionManagerService service = get().getService();
        if (service == null) return new String[0];
        try {
            String[] permissions = service.getGrantedPermissions(packageName, userId);
            return permissions == null ? new String[0] : permissions;
        } catch (RemoteException ignored) {
            get().clearServiceCache();
            return new String[0];
        }
    }

    public static void clearPackage(String packageName, int userId) {
        IBPermissionManagerService service = get().getService();
        if (service == null) return;
        try {
            service.clearPackage(packageName, userId);
        } catch (RemoteException ignored) {
            get().clearServiceCache();
        }
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
