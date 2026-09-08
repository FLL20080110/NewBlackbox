package top.niunaijun.blackbox.core.system.permission;

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
import top.niunaijun.blackbox.utils.Slog;

/**
 * Container-owned permission state for virtual packages.
 *
 * The Android package manager only knows the host package, therefore guest runtime permissions
 * must have a separate source of truth. This service lives in the BlackBox system process and is
 * accessed through Binder from manager UI and guest processes, avoiding SharedPreferences
 * multi-process cache races.
 */
public final class BPermissionManagerService extends IBPermissionManagerService.Stub implements ISystemService {
    private static final String TAG = "BPermissionManagerService";
    private static final BPermissionManagerService sService = new BPermissionManagerService();

    private final Object mLock = new Object();
    private final Map<Integer, Map<String, Set<String>>> mGranted = new HashMap<>();
    private AtomicFile mStateFile;
    private boolean mLoaded;

    public static BPermissionManagerService get() {
        return sService;
    }

    private BPermissionManagerService() {
    }

    @Override
    public void systemReady() {
        synchronized (mLock) {
            ensureLoadedLocked();
        }
    }

    @Override
    public boolean isPermissionGranted(String packageName, int userId, String permission) {
        if (packageName == null || permission == null) return false;
        synchronized (mLock) {
            ensureLoadedLocked();
            Map<String, Set<String>> packages = mGranted.get(userId);
            if (packages == null) return false;
            Set<String> permissions = packages.get(packageName);
            return permissions != null && permissions.contains(permission);
        }
    }

    @Override
    public int checkPermission(String packageName, int userId, String permission) {
        return isPermissionGranted(packageName, userId, permission)
                ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
    }

    @Override
    public void setPermission(String packageName, int userId, String permission, boolean granted) {
        if (packageName == null || permission == null) return;
        synchronized (mLock) {
            ensureLoadedLocked();
            setPermissionLocked(packageName, userId, permission, granted);
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
                if (permissions[i] != null) {
                    setPermissionLocked(packageName, userId, permissions[i], grants[i]);
                }
            }
            saveLocked();
        }
    }

    @Override
    public String[] getGrantedPermissions(String packageName, int userId) {
        if (packageName == null) return new String[0];
        synchronized (mLock) {
            ensureLoadedLocked();
            Map<String, Set<String>> packages = mGranted.get(userId);
            if (packages == null) return new String[0];
            Set<String> permissions = packages.get(packageName);
            if (permissions == null || permissions.isEmpty()) return new String[0];
            return new ArrayList<>(permissions).toArray(new String[0]);
        }
    }

    @Override
    public void clearPackage(String packageName, int userId) {
        if (packageName == null) return;
        synchronized (mLock) {
            ensureLoadedLocked();
            Map<String, Set<String>> packages = mGranted.get(userId);
            if (packages != null) {
                packages.remove(packageName);
                if (packages.isEmpty()) mGranted.remove(userId);
                saveLocked();
            }
        }
    }

    private void setPermissionLocked(String packageName, int userId, String permission, boolean granted) {
        Map<String, Set<String>> packages = mGranted.get(userId);
        if (packages == null) {
            packages = new HashMap<>();
            mGranted.put(userId, packages);
        }
        Set<String> permissions = packages.get(packageName);
        if (permissions == null) {
            permissions = new HashSet<>();
            packages.put(packageName, permissions);
        }
        if (granted) {
            permissions.add(permission);
        } else {
            permissions.remove(permission);
            if (permissions.isEmpty()) packages.remove(packageName);
        }
        if (packages.isEmpty()) mGranted.remove(userId);
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
                Map<String, Set<String>> packages = new HashMap<>();
                for (int p = 0; p < packagesArray.length(); p++) {
                    JSONObject pkg = packagesArray.optJSONObject(p);
                    if (pkg == null) continue;
                    String name = pkg.optString("name", null);
                    if (name == null) continue;
                    JSONArray perms = pkg.optJSONArray("permissions");
                    Set<String> permissionSet = new HashSet<>();
                    if (perms != null) {
                        for (int q = 0; q < perms.length(); q++) {
                            String perm = perms.optString(q, null);
                            if (perm != null) permissionSet.add(perm);
                        }
                    }
                    if (!permissionSet.isEmpty()) packages.put(name, permissionSet);
                }
                if (!packages.isEmpty()) mGranted.put(userId, packages);
            }
        } catch (Throwable e) {
            Slog.e(TAG, "Unable to load virtual permissions: " + e.getMessage());
        }
    }

    private void saveLocked() {
        if (mStateFile == null) return;
        FileOutputStream out = null;
        try {
            JSONObject root = new JSONObject();
            JSONArray users = new JSONArray();
            for (Map.Entry<Integer, Map<String, Set<String>>> userEntry : mGranted.entrySet()) {
                JSONObject user = new JSONObject();
                user.put("id", userEntry.getKey());
                JSONArray packages = new JSONArray();
                for (Map.Entry<String, Set<String>> packageEntry : userEntry.getValue().entrySet()) {
                    JSONObject pkg = new JSONObject();
                    pkg.put("name", packageEntry.getKey());
                    JSONArray permissions = new JSONArray();
                    for (String permission : packageEntry.getValue()) permissions.put(permission);
                    pkg.put("permissions", permissions);
                    packages.put(pkg);
                }
                user.put("packages", packages);
                users.put(user);
            }
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
}
