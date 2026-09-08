package top.niunaijun.blackbox.core.system.permission;

import android.Manifest;

/**
 * Process-independent definition of permissions managed by the virtual container.
 *
 * This class deliberately has no Binder/client dependencies so the system permission service can
 * use it without loading the guest-side fake-framework facade.
 */
public final class VirtualPermissionCatalog {
    private VirtualPermissionCatalog() {}

    public static final int STATE_DEFAULT = 0;
    public static final int STATE_GRANTED = 1;
    public static final int STATE_DENIED = 2;
    public static final int STATE_DENIED_FIXED = 3;

    public static final String GROUP_LOCATION = "location";
    public static final String GROUP_CAMERA = "camera";
    public static final String GROUP_MICROPHONE = "microphone";
    public static final String GROUP_CONTACTS = "contacts";
    public static final String GROUP_CALENDAR = "calendar";
    public static final String GROUP_STORAGE = "storage";
    public static final String GROUP_MEDIA_VISUAL = "media_visual";
    public static final String GROUP_MEDIA_AUDIO = "media_audio";
    public static final String GROUP_PHONE = "phone";
    public static final String GROUP_SMS = "sms";
    public static final String GROUP_SENSORS = "sensors";
    public static final String GROUP_ACTIVITY = "activity";
    public static final String GROUP_NEARBY = "nearby";
    public static final String GROUP_NOTIFICATIONS = "notifications";
    public static final String GROUP_OTHER = "other";

    private static final String PERMISSION_ADD_VOICEMAIL = "com.android.voicemail.permission.ADD_VOICEMAIL";

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
            case PERMISSION_ADD_VOICEMAIL:
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

    public static String getPermissionGroup(String permission) {
        if (permission == null) return GROUP_OTHER;
        switch (permission) {
            case "android.permission.ACCESS_COARSE_LOCATION":
            case "android.permission.ACCESS_FINE_LOCATION":
            case "android.permission.ACCESS_BACKGROUND_LOCATION": return GROUP_LOCATION;
            case "android.permission.CAMERA": return GROUP_CAMERA;
            case "android.permission.RECORD_AUDIO": return GROUP_MICROPHONE;
            case "android.permission.READ_CONTACTS":
            case "android.permission.WRITE_CONTACTS":
            case "android.permission.GET_ACCOUNTS": return GROUP_CONTACTS;
            case "android.permission.READ_CALENDAR":
            case "android.permission.WRITE_CALENDAR": return GROUP_CALENDAR;
            case "android.permission.READ_EXTERNAL_STORAGE":
            case "android.permission.WRITE_EXTERNAL_STORAGE": return GROUP_STORAGE;
            case "android.permission.READ_MEDIA_IMAGES":
            case "android.permission.READ_MEDIA_VIDEO": return GROUP_MEDIA_VISUAL;
            case "android.permission.READ_MEDIA_AUDIO": return GROUP_MEDIA_AUDIO;
            case "android.permission.READ_PHONE_STATE":
            case "android.permission.READ_PHONE_NUMBERS":
            case "android.permission.CALL_PHONE":
            case "android.permission.ANSWER_PHONE_CALLS":
            case PERMISSION_ADD_VOICEMAIL:
            case "android.permission.USE_SIP":
            case "android.permission.PROCESS_OUTGOING_CALLS": return GROUP_PHONE;
            case "android.permission.SEND_SMS":
            case "android.permission.RECEIVE_SMS":
            case "android.permission.READ_SMS":
            case "android.permission.RECEIVE_WAP_PUSH":
            case "android.permission.RECEIVE_MMS": return GROUP_SMS;
            case "android.permission.BODY_SENSORS":
            case "android.permission.BODY_SENSORS_BACKGROUND": return GROUP_SENSORS;
            case "android.permission.ACTIVITY_RECOGNITION": return GROUP_ACTIVITY;
            case "android.permission.BLUETOOTH_SCAN":
            case "android.permission.BLUETOOTH_CONNECT":
            case "android.permission.BLUETOOTH_ADVERTISE":
            case "android.permission.NEARBY_WIFI_DEVICES": return GROUP_NEARBY;
            case "android.permission.POST_NOTIFICATIONS": return GROUP_NOTIFICATIONS;
            default: return GROUP_OTHER;
        }
    }

    public static String getPermissionGroupLabel(String group) {
        if (GROUP_LOCATION.equals(group)) return "位置信息";
        if (GROUP_CAMERA.equals(group)) return "相机";
        if (GROUP_MICROPHONE.equals(group)) return "麦克风";
        if (GROUP_CONTACTS.equals(group)) return "通讯录";
        if (GROUP_CALENDAR.equals(group)) return "日历";
        if (GROUP_STORAGE.equals(group)) return "文件与存储";
        if (GROUP_MEDIA_VISUAL.equals(group)) return "照片和视频";
        if (GROUP_MEDIA_AUDIO.equals(group)) return "音乐和音频";
        if (GROUP_PHONE.equals(group)) return "电话";
        if (GROUP_SMS.equals(group)) return "短信";
        if (GROUP_SENSORS.equals(group)) return "身体传感器";
        if (GROUP_ACTIVITY.equals(group)) return "身体活动";
        if (GROUP_NEARBY.equals(group)) return "附近设备";
        if (GROUP_NOTIFICATIONS.equals(group)) return "通知";
        return "其他权限";
    }

    public static boolean isLocationPermission(String permission) {
        return Manifest.permission.ACCESS_FINE_LOCATION.equals(permission)
                || Manifest.permission.ACCESS_COARSE_LOCATION.equals(permission)
                || Manifest.permission.ACCESS_BACKGROUND_LOCATION.equals(permission);
    }
}
