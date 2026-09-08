package top.niunaijun.blackbox.core.system.permission;

interface IBPermissionManagerService {
    boolean isPermissionGranted(String packageName, int userId, String permission);
    int checkPermission(String packageName, int userId, String permission);
    void setPermission(String packageName, int userId, String permission, boolean granted);
    void setPermissions(String packageName, int userId, in String[] permissions, in boolean[] grants);
    String[] getGrantedPermissions(String packageName, int userId);
    void clearPackage(String packageName, int userId);
}
