package top.niunaijun.blackboxa.view.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.fake.frameworks.VirtualPermissionManager

object VirtualPermissionUi {
    fun requestedPermissions(packageName: String, userId: Int): Array<String> {
        val info = BlackBoxCore.getBPackageManager()
            .getPackageInfo(packageName, PackageManager.GET_PERMISSIONS, userId)
        return info?.requestedPermissions
            ?.filter { VirtualPermissionManager.isManagedRuntimePermission(it) }
            ?.distinct()
            ?.toTypedArray()
            ?: emptyArray()
    }

    fun labels(context: Context, permissions: Array<String>): Array<String> {
        val pm = context.packageManager
        return permissions.map { permission ->
            try {
                pm.getPermissionInfo(permission, 0).loadLabel(pm).toString()
            } catch (_: Throwable) {
                permission.substringAfterLast('.')
            }
        }.toTypedArray()
    }

    fun checked(packageName: String, userId: Int, permissions: Array<String>): BooleanArray =
        BooleanArray(permissions.size) { index ->
            VirtualPermissionManager.isPermissionGranted(packageName, userId, permissions[index])
        }

    fun save(packageName: String, userId: Int, permissions: Array<String>, checked: BooleanArray) {
        if (permissions.isEmpty()) return
        val grants = checked.copyOf()

        val coarse = permissions.indexOf(Manifest.permission.ACCESS_COARSE_LOCATION)
        val fine = permissions.indexOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val background = permissions.indexOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        if (coarse >= 0) {
            grants[coarse] = grants[coarse] || (fine >= 0 && grants[fine]) || (background >= 0 && grants[background])
        }

        VirtualPermissionManager.setPermissions(packageName, userId, permissions, grants)
    }
}