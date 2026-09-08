package top.niunaijun.blackboxa.view.permissions

import android.os.Bundle
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        val userId = intent.getIntExtra(EXTRA_USER_ID, 0)
        if (packageName.isNullOrBlank()) {
            finish()
            return
        }

        val permissions = VirtualPermissionUi.requestedPermissions(packageName, userId)
        if (permissions.isEmpty()) {
            Toast.makeText(this, "该应用没有可管理的运行时权限", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val labels = VirtualPermissionUi.labels(this, permissions)
        val checked = VirtualPermissionUi.checked(packageName, userId, permissions)
        val groups = linkedMapOf<String, MutableList<Int>>()
        permissions.forEachIndexed { index, permission ->
            val group = VirtualPermissionManager.getPermissionGroup(permission)
            groups.getOrPut(group) { mutableListOf() }.add(index)
        }

        val groupEntries = groups.entries.toList()
        val groupLabels = groupEntries.map { entry ->
            val allowed = entry.value.count { checked[it] }
            val title = VirtualPermissionManager.getPermissionGroupLabel(entry.key)
            "$title · $allowed/${entry.value.size} 已允许"
        }.toTypedArray()

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.virtual_permissions_title, packageName))
            .setItems(groupLabels) { _, which ->
                val entry = groupEntries[which]
                showGroupDialog(entry.key, entry.value, labels, checked)
            }
            .setPositiveButton(R.string.done) { _, _ ->
                VirtualPermissionUi.save(packageName, userId, permissions, checked)
                try {
                    BlackBoxCore.get().stopPackage(packageName, userId)
                } catch (_: Throwable) {
                }
                Toast.makeText(this, R.string.virtual_permissions_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnDismissListener { finish() }
        dialog.show()
    }

    private fun showGroupDialog(
        group: String,
        indexes: List<Int>,
        allLabels: Array<String>,
        checked: BooleanArray
    ) {
        val itemLabels = indexes.map { index ->
            allLabels[index].substringAfter(" · ", allLabels[index])
        }.toTypedArray()
        val itemChecked = BooleanArray(indexes.size) { checked[indexes[it]] }

        AlertDialog.Builder(this)
            .setTitle(VirtualPermissionManager.getPermissionGroupLabel(group))
            .setMultiChoiceItems(itemLabels, itemChecked) { _, which, isChecked ->
                itemChecked[which] = isChecked
            }
            .setPositiveButton(R.string.done) { _, _ ->
                indexes.forEachIndexed { localIndex, originalIndex ->
                    checked[originalIndex] = itemChecked[localIndex]
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
