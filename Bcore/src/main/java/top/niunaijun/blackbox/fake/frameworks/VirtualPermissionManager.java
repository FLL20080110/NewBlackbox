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
    public static final int STATE_DEFAULT = 0;
    public static final int STATE_GRANTED = 1;
    public static final int STATE_DENIED = 2;
    public static final int STATE_DENIED_FIXED = 3;

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

    public static void setPermissionState(String packageName, int userId, String permission, int state) {
        if (packageName == null || permission == null) return;
        if (state < STATE_DEFAULT || state > STATE_DENIED_FIXED) state = STATE_DEFAULT;
        IBPermissionManagerService service = get().getService();
        if (service == null) return;
        try {
            service.setPermissionState(packageName, userId, permission, state);
        } catch (RemoteException ignored) {
            get().clearServiceCache();
        }
    }

    public static int getPermissionState(String packageName, int userId, String permission) {
        if (packageName == null || permission == null) return STATE_DEFAULT;
        IBPermissionManagerService service = get().getService();
        if (service == null) return STATE_DEFAULT;
        try {
            return service.getPermissionState(packageName, userId, permission);
        } catch (RemoteException ignored) {
            get().clearServiceCache();
            return STATE_DEFAULT;
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

    /** Runtime permissions whose grant state belongs to the virtual guest, not the host package. */
    public static boolean isManagedRuntimePermission(String permission) {
        if (permission == null) return false;
        switch (permission) {
            case "android.permission.ACCESS_COARSE_LOCATION":
            case "android.permission.ACCESS_FINE_LOCATION":
            case "android.permission.ACCESS_BACKGROUND_LOCATION":
            case "android.permission.CAMERA":
            case "android.permission.RECORD_AUDIO":
            case "android.permission.READ_CONTACTS":
            case "android.permission.WRITE_CONTACTS":
            case "android.permission.GET_ACCOUNTS":
            case "android.permission.READ_CALENDAR":
            case "android.permission.WRITE_CALENDAR":
            case "android.permission.READ_EXTERNAL_STORAGE":
            case "android.permission.WRITE_EXTERNAL_STORAGE":
            case "android.permission.READ_MEDIA_IMAGES":
            case "android.permission.READ_MEDIA_VIDEO":
            case "android.permission.READ_MEDIA_AUDIO":
            case "android.permission.READ_PHONE_STATE":
            case "android.permission.READ_PHONE_NUMBERS":
            case "android.permission.CALL_PHONE":
            case "android.permission.ANSWER_PHONE_CALLS":
            case "android.permission.ADD_VOICEMAIL":
            case "android.permission.USE_SIP":
            case "android.permission.PROCESS_OUTGOING_CALLS":
            case "android.permission.SEND_SMS":
            case "android.permission.RECEIVE_SMS":
            case "android.permission.READ_SMS":
            case "android.permission.RECEIVE_WAP_PUSH":
            case "android.permission.RECEIVE_MMS":
            case "android.permission.BODY_SENSORS":
            case "android.permission.BODY_SENSORS_BACKGROUND":
            case "android.permission.ACTIVITY_RECOGNITION":
            case "android.permission.BLUETOOTH_SCAN":
            case "android.permission.BLUETOOTH_CONNECT":
            case "android.permission.BLUETOOTH_ADVERTISE":
            case "android.permission.NEARBY_WIFI_DEVICES":
            case "android.permission.POST_NOTIFICATIONS":
                return true;
            default:
                return false;
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
