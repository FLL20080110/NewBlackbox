package top.niunaijun.blackbox.core.system.permission;

import android.Manifest;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.system.ISystemService;
import top.niunaijun.blackbox.fake.frameworks.VirtualPermissionManager;
import top.niunaijun.blackbox.utils.Slog;

/** Container-owned permission state for virtual packages. */
public final class BPermissionManagerService extends IBPermissionManagerService.Stub implements ISystemService {
    private static final String TAG = "BPermissionManagerService";
    private static final int STATE_DEFAULT = 0;
    private static final int STATE_GRANTED = 1;
    private static final int STATE_DENIED = 2;
    private static final int STATE_DENIED_FIXED = 3;
    private static final BPermissionManagerService sService = new BPermissionManagerService();

    private final Object mLock = new Object();
    private final Map<Integer, Map<String, Set<String>>> mGranted = new HashMap<>();
    private final Map<Integer, Map<String, Set<String>>> mDenied = new HashMap<>();
    private final Map<Integer, Map<String, Set<String>>> mDeniedFixed = new HashMap<>();
    private AtomicFile mStateFile;
    private boolean mLoaded;

    public static BPermissionManagerService get() { return sService; }
    private BPermissionManagerService() {}

    @Override
    public void systemReady() {
        synchronized (mLock) { ensureLoadedLocked(); }
    }

    @Override
    public boolean isPermissionGranted(String packageName, int userId, String permission) {
        return getPermissionState(packageName, userId, permission) == STATE_GRANTED;
    }

    @Override
    public int checkPermission(String packageName, int userId, String permission) {
        return isPermissionGranted(packageName, userId, permission)
                ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
    }

    @Override
    public int getPermissionState(String packageName, int userId, String permission) {
        if (packageName == null || permission == null) return STATE_DEFAULT;
        if (VirtualPermissionManager.isManagedRuntimePermission(permission)
                && !isPermissionDeclared(packageName, userId, permission)) {
            return STATE_DENIED_FIXED;
        }
        synchronized (mLock) {
            ensureLoadedLocked();
            if (containsLocked(mGranted, packageName, userId, permission)) return STATE_GRANTED;
            if (containsLocked(mDeniedFixed, packageName, userId, permission)) return STATE_DENIED_FIXED;
            if (containsLocked(mDenied, packageName, userId, permission)) return STATE_DENIED;
            return STATE_DEFAULT;
        }
    }

    @Override
    public void setPermission(String packageName, int userId, String permission, boolean granted) {
        setPermissionState(packageName, userId, permission, granted ? STATE_GRANTED : STATE_DENIED);
    }

    @Override
    public void setPermissionState(String packageName, int userId, String permission, int state) {
        if (packageName == null || permission == null) return;
        if (state < STATE_DEFAULT || state > STATE_DENIED_FIXED) state = STATE_DEFAULT;
        synchronized (mLock) {
            ensureLoadedLocked();
            if (state == STATE_GRANTED && VirtualPermissionManager.isManagedRuntimePermission(permission)
                    && !isPermissionDeclared(packageName, userId, permission)) {
                Slog.w(TAG, "Rejecting undeclared virtual permission " + permission + " for " + packageName);
                state = STATE_DENIED_FIXED;
            }
            setPermissionStateLocked(packageName, userId, permission, state);
            normalizeLocationLocked(packageName, userId);
            saveLocked();
        }
    }

    @Override
    public void setPermissions(String packageName, int userId, String[] permissions, boolean[] grants) {
        if (packageName == null || permissions == null || grants == null) return;
        synchronized (mLock) {
            ensureLoadedLocked();
            int count = Math.min(permissions.length, grants.length);
            for (int i = 0; i < count; i++) {
                String permission = permissions[i];
                if (permission == null) continue;
                int state = grants[i] ? STATE_GRANTED : STATE_DENIED;
                if (state == STATE_GRANTED && VirtualPermissionManager.isManagedRuntimePermission(permission)
                        && !isPermissionDeclared(packageName, userId, permission)) {
                    state = STATE_DENIED_FIXED;
                }
                setPermissionStateLocked(packageName, userId, permission, state);
            }
            normalizeLocationLocked(packageName, userId);
            saveLocked();
        }
    }

    @Override
    public String[] getGrantedPermissions(String packageName, int userId) {
        if (packageName == null) return new String[0];
        synchronized (mLock) {
            ensureLoadedLocked();
            Set<String> permissions = getSetLocked(mGranted, packageName, userId, false);
            if (permissions == null || permissions.isEmpty()) return new String[0];
            ArrayList<String> result = new ArrayList<>();
            for (String permission : permissions) {
                if (!VirtualPermissionManager.isManagedRuntimePermission(permission)
                        || isPermissionDeclared(packageName, userId, permission)) result.add(permission);
            }
            return result.toArray(new String[0]);
        }
    }

    @Override
    public void clearPackage(String packageName, int userId) {
        if (packageName == null) return;
        synchronized (mLock) {
            ensureLoadedLocked();
            removePackageLocked(mGranted, packageName, userId);
            removePackageLocked(mDenied, packageName, userId);
            removePackageLocked(mDeniedFixed, packageName, userId);
            saveLocked();
        }
    }

    private void setPermissionStateLocked(String packageName, int userId, String permission, int state) {
        removePermissionLocked(mGranted, packageName, userId, permission);
        removePermissionLocked(mDenied, packageName, userId, permission);
        removePermissionLocked(mDeniedFixed, packageName, userId, permission);
        if (state == STATE_GRANTED) getSetLocked(mGranted, packageName, userId, true).add(permission);
        else if (state == STATE_DENIED) getSetLocked(mDenied, packageName, userId, true).add(permission);
        else if (state == STATE_DENIED_FIXED) getSetLocked(mDeniedFixed, packageName, userId, true).add(permission);
    }

    private boolean containsLocked(Map<Integer, Map<String, Set<String>>> store,
                                   String packageName, int userId, String permission) {
        Set<String> set = getSetLocked(store, packageName, userId, false);
        return set != null && set.contains(permission);
    }

    private Set<String> getSetLocked(Map<Integer, Map<String, Set<String>>> store,
                                     String packageName, int userId, boolean create) {
        Map<String, Set<String>> packages = store.get(userId);
        if (packages == null) {
            if (!create) return null;
            packages = new HashMap<>();
            store.put(userId, packages);
        }
        Set<String> set = packages.get(packageName);
        if (set == null && create) {
            set = new HashSet<>();
            packages.put(packageName, set);
        }
        return set;
    }

    private void removePermissionLocked(Map<Integer, Map<String, Set<String>>> store,
                                        String packageName, int userId, String permission) {
        Map<String, Set<String>> packages = store.get(userId);
        if (packages == null) return;
        Set<String> set = packages.get(packageName);
        if (set == null) return;
        set.remove(permission);
        if (set.isEmpty()) packages.remove(packageName);
        if (packages.isEmpty()) store.remove(userId);
    }

    private void removePackageLocked(Map<Integer, Map<String, Set<String>>> store,
                                     String packageName, int userId) {
        Map<String, Set<String>> packages = store.get(userId);
        if (packages == null) return;
        packages.remove(packageName);
        if (packages.isEmpty()) store.remove(userId);
    }

    /** Keep Android location permission dependencies internally consistent. */
    private void normalizeLocationLocked(String packageName, int userId) {
        Set<String> granted = getSetLocked(mGranted, packageName, userId, false);
        if (granted == null) return;
        boolean fine = granted.contains(Manifest.permission.ACCESS_FINE_LOCATION);
        boolean background = granted.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        if ((fine || background) && isPermissionDeclared(packageName, userId, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            setPermissionStateLocked(packageName, userId, Manifest.permission.ACCESS_COARSE_LOCATION, STATE_GRANTED);
            granted = getSetLocked(mGranted, packageName, userId, false);
        }
        boolean hasForeground = granted != null && (granted.contains(Manifest.permission.ACCESS_COARSE_LOCATION)
                || granted.contains(Manifest.permission.ACCESS_FINE_LOCATION));
        if (!hasForeground) {
            setPermissionStateLocked(packageName, userId, Manifest.permission.ACCESS_BACKGROUND_LOCATION, STATE_DENIED);
        }
    }

    private boolean isPermissionDeclared(String packageName, int userId, String permission) {
        try {
            PackageInfo info = BlackBoxCore.getBPackageManager().getPackageInfo(
                    packageName, PackageManager.GET_PERMISSIONS, userId);
            if (info == null || info.requestedPermissions == null) return false;
            for (String requested : info.requestedPermissions) if (permission.equals(requested)) return true;
        } catch (Throwable e) {
            Slog.w(TAG, "Unable to validate permission declaration for " + packageName + ": " + e.getMessage());
        }
        return false;
    }

    private void ensureLoadedLocked() {
        if (mLoaded) return;
        mLoaded = true;
        File file = new File(BlackBoxCore.getContext().getFilesDir(), "blackbox_virtual_permissions.json");
        mStateFile = new AtomicFile(file);
        if (!file.exists()) return;
        try (FileInputStream in = mStateFile.openRead()) {
            byte[] data = new byte[(int) file.length()];
            int offset = 0;
            while (offset < data.length) {
                int read = in.read(data, offset, data.length - offset);
                if (read < 0) break;
                offset += read;
            }
            JSONObject root = new JSONObject(new String(data, 0, offset, StandardCharsets.UTF_8));
            JSONArray users = root.optJSONArray("users");
            if (users == null) return;
            for (int i = 0; i < users.length(); i++) {
                JSONObject user = users.optJSONObject(i);
                if (user == null) continue;
                int userId = user.optInt("id", 0);
                JSONArray packagesArray = user.optJSONArray("packages");
                if (packagesArray == null) continue;
                for (int p = 0; p < packagesArray.length(); p++) {
                    JSONObject pkg = packagesArray.optJSONObject(p);
                    if (pkg == null) continue;
                    String name = pkg.optString("name", null);
                    if (name == null) continue;
                    // "permissions" is the legacy granted-only format; keep migration support.
                    loadPermissionArrayLocked(mGranted, name, userId, pkg.optJSONArray("permissions"));
                    loadPermissionArrayLocked(mGranted, name, userId, pkg.optJSONArray("granted"));
                    loadPermissionArrayLocked(mDenied, name, userId, pkg.optJSONArray("denied"));
                    loadPermissionArrayLocked(mDeniedFixed, name, userId, pkg.optJSONArray("deniedFixed"));
                }
            }
        } catch (Throwable e) {
            Slog.e(TAG, "Unable to load virtual permissions: " + e.getMessage());
        }
    }

    private void loadPermissionArrayLocked(Map<Integer, Map<String, Set<String>>> store,
                                           String packageName, int userId, JSONArray array) {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            String permission = array.optString(i, null);
            if (permission == null) continue;
            if (VirtualPermissionManager.isManagedRuntimePermission(permission)
                    && !isPermissionDeclared(packageName, userId, permission)) continue;
            getSetLocked(store, packageName, userId, true).add(permission);
        }
    }

    private void saveLocked() {
        if (mStateFile == null) return;
        FileOutputStream out = null;
        try {
            JSONObject root = new JSONObject();
            JSONArray users = new JSONArray();
            Set<Integer> userIds = new HashSet<>();
            userIds.addAll(mGranted.keySet());
            userIds.addAll(mDenied.keySet());
            userIds.addAll(mDeniedFixed.keySet());
            for (Integer userId : userIds) {
                JSONObject user = new JSONObject();
                user.put("id", userId);
                JSONArray packages = new JSONArray();
                Set<String> packageNames = new HashSet<>();
                Map<String, Set<String>> grantedPackages = mGranted.get(userId);
                Map<String, Set<String>> deniedPackages = mDenied.get(userId);
                Map<String, Set<String>> fixedPackages = mDeniedFixed.get(userId);
                if (grantedPackages != null) packageNames.addAll(grantedPackages.keySet());
                if (deniedPackages != null) packageNames.addAll(deniedPackages.keySet());
                if (fixedPackages != null) packageNames.addAll(fixedPackages.keySet());
                for (String packageName : packageNames) {
                    JSONObject pkg = new JSONObject();
                    pkg.put("name", packageName);
                    pkg.put("granted", toJsonArray(grantedPackages == null ? null : grantedPackages.get(packageName)));
                    pkg.put("denied", toJsonArray(deniedPackages == null ? null : deniedPackages.get(packageName)));
                    pkg.put("deniedFixed", toJsonArray(fixedPackages == null ? null : fixedPackages.get(packageName)));
                    packages.put(pkg);
                }
                user.put("packages", packages);
                users.put(user);
            }
            root.put("version", 3);
            root.put("users", users);
            byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
            out = mStateFile.startWrite();
            out.write(bytes);
            out.flush();
            mStateFile.finishWrite(out);
        } catch (Throwable e) {
            if (out != null) mStateFile.failWrite(out);
            Slog.e(TAG, "Unable to save virtual permissions: " + e.getMessage());
        }
    }

    private JSONArray toJsonArray(Set<String> permissions) {
        JSONArray array = new JSONArray();
        if (permissions != null) for (String permission : permissions) array.put(permission);
        return array;
    }
}
