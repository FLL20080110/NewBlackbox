package top.niunaijun.blackbox.fake.frameworks;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.RemoteException;

import top.niunaijun.blackbox.core.system.ServiceManager;
import top.niunaijun.blackbox.core.system.permission.IBPermissionManagerService;
import top.niunaijun.blackbox.core.system.permission.VirtualPermissionCatalog;

/** Client facade for container-owned guest permission state. */
public final class VirtualPermissionManager extends BlackManager<IBPermissionManagerService> {
    public static final int STATE_DEFAULT = VirtualPermissionCatalog.STATE_DEFAULT;
    public static final int STATE_GRANTED = VirtualPermissionCatalog.STATE_GRANTED;
    public static final int STATE_DENIED = VirtualPermissionCatalog.STATE_DENIED;
    public static final int STATE_DENIED_FIXED = VirtualPermissionCatalog.STATE_DENIED_FIXED;

    public static final String GROUP_LOCATION = VirtualPermissionCatalog.GROUP_LOCATION;
    public static final String GROUP_CAMERA = VirtualPermissionCatalog.GROUP_CAMERA;
    public static final String GROUP_MICROPHONE = VirtualPermissionCatalog.GROUP_MICROPHONE;
    public static final String GROUP_CONTACTS = VirtualPermissionCatalog.GROUP_CONTACTS;
    public static final String GROUP_CALENDAR = VirtualPermissionCatalog.GROUP_CALENDAR;
    public static final String GROUP_STORAGE = VirtualPermissionCatalog.GROUP_STORAGE;
    public static final String GROUP_MEDIA_VISUAL = VirtualPermissionCatalog.GROUP_MEDIA_VISUAL;
    public static final String GROUP_MEDIA_AUDIO = VirtualPermissionCatalog.GROUP_MEDIA_AUDIO;
    public static final String GROUP_PHONE = VirtualPermissionCatalog.GROUP_PHONE;
    public static final String GROUP_SMS = VirtualPermissionCatalog.GROUP_SMS;
    public static final String GROUP_SENSORS = VirtualPermissionCatalog.GROUP_SENSORS;
    public static final String GROUP_ACTIVITY = VirtualPermissionCatalog.GROUP_ACTIVITY;
    public static final String GROUP_NEARBY = VirtualPermissionCatalog.GROUP_NEARBY;
    public static final String GROUP_NOTIFICATIONS = VirtualPermissionCatalog.GROUP_NOTIFICATIONS;
    public static final String GROUP_OTHER = VirtualPermissionCatalog.GROUP_OTHER;

    private static final VirtualPermissionManager sManager = new VirtualPermissionManager();

    private VirtualPermissionManager() {}

    public static VirtualPermissionManager get() { return sManager; }

    @Override
    protected String getServiceName() { return ServiceManager.PERMISSION_MANAGER; }

    public static void setPermission(String packageName, int userId, String permission, boolean granted) {
        if (packageName == null || permission == null) return;
        IBPermissionManagerService service = get().getService();
        if (service == null) return;
        try { service.setPermission(packageName, userId, permission, granted); }
        catch (RemoteException ignored) { get().clearServiceCache(); }
    }

    public static void setPermissionState(String packageName, int userId, String permission, int state) {
        if (packageName == null || permission == null) return;
        if (state < STATE_DEFAULT || state > STATE_DENIED_FIXED) state = STATE_DEFAULT;
        IBPermissionManagerService service = get().getService();
        if (service == null) return;
        try { service.setPermissionState(packageName, userId, permission, state); }
        catch (RemoteException ignored) { get().clearServiceCache(); }
    }

    public static int getPermissionState(String packageName, int userId, String permission) {
        if (packageName == null || permission == null) return STATE_DEFAULT;
        IBPermissionManagerService service = get().getService();
        if (service == null) return STATE_DEFAULT;
        try { return service.getPermissionState(packageName, userId, permission); }
        catch (RemoteException ignored) { get().clearServiceCache(); return STATE_DEFAULT; }
    }

    public static void setPermissions(String packageName, int userId, String[] permissions, boolean[] grants) {
        if (packageName == null || permissions == null || grants == null) return;
        IBPermissionManagerService service = get().getService();
        if (service == null) return;
        try { service.setPermissions(packageName, userId, permissions, grants); }
        catch (RemoteException ignored) { get().clearServiceCache(); }
    }

    public static boolean isPermissionGranted(String packageName, int userId, String permission) {
        if (packageName == null || permission == null) return false;
        IBPermissionManagerService service = get().getService();
        if (service == null) return false;
        try { return service.isPermissionGranted(packageName, userId, permission); }
        catch (RemoteException ignored) { get().clearServiceCache(); return false; }
    }

    public static int checkPermission(String packageName, int userId, String permission) {
        if (packageName == null || permission == null) return PackageManager.PERMISSION_DENIED;
        IBPermissionManagerService service = get().getService();
        if (service == null) return PackageManager.PERMISSION_DENIED;
        try { return service.checkPermission(packageName, userId, permission); }
        catch (RemoteException ignored) { get().clearServiceCache(); return PackageManager.PERMISSION_DENIED; }
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
        try { service.clearPackage(packageName, userId); }
        catch (RemoteException ignored) { get().clearServiceCache(); }
    }

    public static boolean isManagedRuntimePermission(String permission) {
        return VirtualPermissionCatalog.isManagedRuntimePermission(permission);
    }

    public static String getPermissionGroup(String permission) {
        return VirtualPermissionCatalog.getPermissionGroup(permission);
    }

    public static String getPermissionGroupLabel(String group) {
        return VirtualPermissionCatalog.getPermissionGroupLabel(group);
    }

    public static boolean isLocationPermission(String permission) {
        return VirtualPermissionCatalog.isLocationPermission(permission);
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
