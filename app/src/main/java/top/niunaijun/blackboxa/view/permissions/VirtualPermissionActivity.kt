package top.niunaijun.blackboxa.view.permissions

import android.Manifest
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

        val labels = arrayOf(
            getString(R.string.permission_coarse_location),
            getString(R.string.permission_fine_location),
            getString(R.string.permission_background_location)
        )
        val checked = booleanArrayOf(
            VirtualPermissionManager.isCoarseLocationGranted(packageName, userId),
            VirtualPermissionManager.isFineLocationGranted(packageName, userId),
            VirtualPermissionManager.isBackgroundLocationGranted(packageName, userId)
        )

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.virtual_permissions_title, packageName))
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.done) { _, _ ->
                val coarse = checked[0] || checked[1] || checked[2]
                val fine = checked[1]
                val background = checked[2]

                VirtualPermissionManager.setPermission(
                    packageName, userId, Manifest.permission.ACCESS_COARSE_LOCATION, coarse
                )
                VirtualPermissionManager.setPermission(
                    packageName, userId, Manifest.permission.ACCESS_FINE_LOCATION, fine
                )
                VirtualPermissionManager.setPermission(
                    packageName, userId, Manifest.permission.ACCESS_BACKGROUND_LOCATION, background
                )

                try {
                    BlackBoxCore.get().stopPackage(packageName, userId)
                } catch (_: Throwable) {
                }
                Toast.makeText(this, R.string.virtual_permissions_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { finish() }
            .show()
    }
}
