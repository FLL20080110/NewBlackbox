package top.niunaijun.blackboxa.view.permissions

import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.fake.frameworks.VirtualPermissionManager
import top.niunaijun.blackboxa.R

/**
 * Host-owned virtual permission settings screen.
 *
 * This intentionally uses a normal Activity view hierarchy instead of nested dialogs. Guest apps
 * can open this screen through the container settings bridge without creating guest-window tokens,
 * which is substantially more stable on Android 16 and on OEM framework variants.
 */
class VirtualPermissionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "_B_|_virtual_permission_package_"
        const val EXTRA_USER_ID = "_B_|_virtual_permission_user_"
        private const val TAG = "VirtualPermissionActivity"
    }

    private var closing = false
    private var packageName: String = ""
    private var userId: Int = 0
    private var permissions: Array<String> = emptyArray()
    private lateinit var checked: BooleanArray
    private val boxes = linkedMapOf<Int, CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            packageName = intent?.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
            userId = intent?.getIntExtra(EXTRA_USER_ID, 0) ?: 0
            if (packageName.isBlank()) {
                safeFinish()
                return
            }
            buildPermissionScreen()
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to open virtual permission manager", t)
            Toast.makeText(this, "权限设置暂时无法打开", Toast.LENGTH_SHORT).show()
            safeFinish()
        }
    }

    private fun buildPermissionScreen() {
        permissions = try {
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
        checked = try {
            VirtualPermissionUi.checked(packageName, userId, permissions)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to read virtual permission state", t)
            BooleanArray(permissions.size)
        }

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.virtual_permissions_title, packageName)
            textSize = 20f
            setPadding(0, 0, 0, dp(6))
        }, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        root.addView(TextView(this).apply {
            text = "权限状态仅作用于虚拟空间中的该应用，不修改主空间应用权限。"
            textSize = 13f
            alpha = 0.75f
            setPadding(0, 0, 0, dp(14))
        }, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val grouped = linkedMapOf<String, MutableList<Int>>()
        permissions.forEachIndexed { index, permission ->
            val group = VirtualPermissionManager.getPermissionGroup(permission)
            grouped.getOrPut(group) { mutableListOf() }.add(index)
        }

        grouped.forEach { (group, indexes) ->
            root.addView(TextView(this).apply {
                text = VirtualPermissionManager.getPermissionGroupLabel(group)
                textSize = 16f
                setPadding(0, dp(12), 0, dp(4))
            }, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

            indexes.forEach { index ->
                val permission = permissions[index]
                val label = labels.getOrElse(index) { permission.substringAfterLast('.') }
                val box = CheckBox(this).apply {
                    text = label.substringAfter(" · ", label)
                    isChecked = index in checked.indices && checked[index]
                    setPadding(0, dp(3), 0, dp(3))
                    setOnCheckedChangeListener { _, value ->
                        if (index in checked.indices) checked[index] = value
                    }
                }
                boxes[index] = box
                root.addView(box, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            setPadding(0, dp(18), 0, 0)
        }
        val reset = Button(this).apply {
            text = "恢复默认"
            setOnClickListener { resetToDefault() }
        }
        val cancel = Button(this).apply {
            text = getString(R.string.cancel)
            setOnClickListener { safeFinish() }
        }
        val save = Button(this).apply {
            text = getString(R.string.done)
            setOnClickListener { saveAndFinish() }
        }
        buttons.addView(reset)
        buttons.addView(cancel)
        buttons.addView(save)
        root.addView(buttons, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        setContentView(scroll)
    }

    private fun resetToDefault() {
        try {
            permissions.forEachIndexed { index, permission ->
                VirtualPermissionManager.setPermissionState(
                    packageName, userId, permission, VirtualPermissionManager.STATE_DEFAULT
                )
                if (index in checked.indices) checked[index] = false
                boxes[index]?.isChecked = false
            }
            Toast.makeText(this, "已恢复默认权限状态", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to reset virtual permissions", t)
            Toast.makeText(this, "恢复默认失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveAndFinish() {
        try {
            VirtualPermissionUi.save(packageName, userId, permissions, checked)
            try {
                BlackBoxCore.get().stopPackage(packageName, userId)
            } catch (t: Throwable) {
                Log.w(TAG, "Unable to stop guest after permission update", t)
            }
            Toast.makeText(this, R.string.virtual_permissions_saved, Toast.LENGTH_SHORT).show()
            safeFinish()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to persist virtual permissions", t)
            Toast.makeText(this, "权限保存失败", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        boxes.clear()
        super.onDestroy()
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
