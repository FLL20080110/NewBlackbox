package top.niunaijun.blackboxa.view.permissions

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.fake.frameworks.VirtualPermissionManager
import top.niunaijun.blackboxa.R

class VirtualPermissionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "_B_|_virtual_permission_package_"
        const val EXTRA_USER_ID = "_B_|_virtual_permission_user_"
        private const val TAG = "VirtualPermissionActivity"
    }

    private var rootDialog: AlertDialog? = null
    private var childDialog: AlertDialog? = null
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            showPermissionManager()
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to open virtual permission manager", t)
            Toast.makeText(this, "权限设置暂时无法打开", Toast.LENGTH_SHORT).show()
            safeFinish()
        }
    }

    private fun showPermissionManager() {
        val packageName = intent?.getStringExtra(EXTRA_PACKAGE_NAME)
        val userId = intent?.getIntExtra(EXTRA_USER_ID, 0) ?: 0
        if (packageName.isNullOrBlank()) {
            safeFinish()
            return
        }

        val permissions = try {
            VirtualPermissionUi.requestedPermissions(packageName, userId)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to query permissions for $packageName/$userId", t)
            emptyArray()
        }
        if (permissions.isEmpty()) {
            Toast.makeText(this, "该应用没有可管理的运行时权限", Toast.LENGTH_SHORT).show()
            safeFinish()
            return
        }

        val labels = try {
            VirtualPermissionUi.labels(this, permissions)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to resolve permission labels", t)
            permissions.map { it.substringAfterLast('.') }.toTypedArray()
        }
        val checked = try {
            VirtualPermissionUi.checked(packageName, userId, permissions)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to read virtual permission state", t)
            BooleanArray(permissions.size)
        }

        val groups = linkedMapOf<String, MutableList<Int>>()
        permissions.forEachIndexed { index, permission ->
            val group = VirtualPermissionManager.getPermissionGroup(permission)
            groups.getOrPut(group) { mutableListOf() }.add(index)
        }
        val groupEntries = groups.entries.toList()

        fun groupLabels(): Array<String> = groupEntries.map { entry ->
            val allowed = entry.value.count { checked[it] }
            val title = VirtualPermissionManager.getPermissionGroupLabel(entry.key)
            "$title · $allowed/${entry.value.size} 已允许"
        }.toTypedArray()

        rootDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.virtual_permissions_title, packageName))
            .setItems(groupLabels()) { _, which ->
                if (which in groupEntries.indices && !isFinishing && !isDestroyed) {
                    val entry = groupEntries[which]
                    showGroupDialog(entry.key, entry.value, labels, checked)
                }
            }
            .setPositiveButton(R.string.done) { _, _ ->
                try {
                    VirtualPermissionUi.save(packageName, userId, permissions, checked)
                    try {
                        BlackBoxCore.get().stopPackage(packageName, userId)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Unable to stop guest after permission update", t)
                    }
                    Toast.makeText(this, R.string.virtual_permissions_saved, Toast.LENGTH_SHORT).show()
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to persist virtual permissions", t)
                    Toast.makeText(this, "权限保存失败", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        rootDialog?.setOnDismissListener {
            rootDialog = null
            if (childDialog == null) safeFinish()
        }
        rootDialog?.show()
    }

    private fun showGroupDialog(
        group: String,
        indexes: List<Int>,
        allLabels: Array<String>,
        checked: BooleanArray
    ) {
        try {
            childDialog?.dismiss()
            val itemLabels = indexes.map { index ->
                allLabels.getOrElse(index) { "权限" }.substringAfter(" · ", allLabels.getOrElse(index) { "权限" })
            }.toTypedArray()
            val itemChecked = BooleanArray(indexes.size) { local ->
                val original = indexes[local]
                original in checked.indices && checked[original]
            }

            childDialog = AlertDialog.Builder(this)
                .setTitle(VirtualPermissionManager.getPermissionGroupLabel(group))
                .setMultiChoiceItems(itemLabels, itemChecked) { _, which, isChecked ->
                    if (which in itemChecked.indices) itemChecked[which] = isChecked
                }
                .setPositiveButton(R.string.done) { _, _ ->
                    indexes.forEachIndexed { localIndex, originalIndex ->
                        if (localIndex in itemChecked.indices && originalIndex in checked.indices) {
                            checked[originalIndex] = itemChecked[localIndex]
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .create()
            childDialog?.setOnDismissListener { childDialog = null }
            childDialog?.show()
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to show permission group $group", t)
            childDialog = null
            Toast.makeText(this, "该权限组暂时无法打开", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) dismissDialogs()
    }

    override fun onDestroy() {
        dismissDialogs()
        super.onDestroy()
    }

    private fun dismissDialogs() {
        try { childDialog?.setOnDismissListener(null) } catch (_: Throwable) {}
        try { childDialog?.dismiss() } catch (_: Throwable) {}
        childDialog = null
        try { rootDialog?.setOnDismissListener(null) } catch (_: Throwable) {}
        try { rootDialog?.dismiss() } catch (_: Throwable) {}
        rootDialog = null
    }

    private fun safeFinish() {
        if (closing) return
        closing = true
        try {
            if (!isFinishing) finish()
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to finish permission activity cleanly", t)
        }
    }
}
