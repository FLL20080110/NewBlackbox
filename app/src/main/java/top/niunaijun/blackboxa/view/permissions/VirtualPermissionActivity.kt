package top.niunaijun.blackboxa.view.permissions

import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.fake.frameworks.VirtualPermissionManager
import top.niunaijun.blackboxa.R

/** Host-owned virtual permission settings screen. */
class VirtualPermissionActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PACKAGE_NAME = "_B_|_virtual_permission_package_"
        const val EXTRA_USER_ID = "_B_|_virtual_permission_user_"
        private const val TAG = "VirtualPermissionActivity"
    }

    private var closing = false
    @Volatile private var busy = false
    @Volatile private var destroyed = false
    private var packageName: String = ""
    private var userId: Int = 0
    private var permissions: Array<String> = emptyArray()
    private var checked: BooleanArray = BooleanArray(0)
    private val boxes = linkedMapOf<Int, CheckBox>()
    private val actionButtons = mutableListOf<Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            packageName = intent?.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
            userId = intent?.getIntExtra(EXTRA_USER_ID, 0) ?: 0
            if (packageName.isBlank()) {
                safeFinish()
                return
            }
            showLoading()
            loadPermissionScreenAsync()
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to open virtual permission manager", t)
            Toast.makeText(this, "权限设置暂时无法打开", Toast.LENGTH_SHORT).show()
            safeFinish()
        }
    }

    private fun showLoading() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(24), dp(48), dp(24), dp(48))
        }
        root.addView(ProgressBar(this))
        root.addView(TextView(this).apply {
            text = "正在读取虚拟权限…"
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        })
        setContentView(root)
    }

    private fun loadPermissionScreenAsync() {
        val targetPackage = packageName
        val targetUser = userId
        Thread({
            var error: Throwable? = null
            var loadedPermissions: Array<String> = emptyArray()
            var loadedChecked = BooleanArray(0)
            try {
                loadedPermissions = VirtualPermissionUi.requestedPermissions(targetPackage, targetUser)
                if (loadedPermissions.isNotEmpty()) {
                    loadedChecked = VirtualPermissionUi.checked(targetPackage, targetUser, loadedPermissions)
                }
            } catch (t: Throwable) {
                error = t
                Log.e(TAG, "Failed to load virtual permissions for $targetPackage/$targetUser", t)
            }
            val resultPermissions = loadedPermissions
            val resultChecked = loadedChecked
            val resultError = error
            runOnUiThread {
                if (destroyed || isFinishing || isDestroyed) return@runOnUiThread
                if (resultError != null) {
                    Toast.makeText(this, "权限设置暂时无法打开", Toast.LENGTH_SHORT).show()
                    safeFinish()
                    return@runOnUiThread
                }
                if (resultPermissions.isEmpty()) {
                    Toast.makeText(this, "该应用没有可管理的运行时权限", Toast.LENGTH_SHORT).show()
                    safeFinish()
                    return@runOnUiThread
                }
                permissions = resultPermissions
                checked = resultChecked
                renderPermissionScreen()
            }
        }, "VirtualPermissionLoad").start()
    }

    private fun renderPermissionScreen() {
        val labels = try {
            VirtualPermissionUi.labels(this, permissions)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to resolve permission labels", t)
            permissions.map { it.substringAfterLast('.') }.toTypedArray()
        }
        boxes.clear()
        actionButtons.clear()

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
                        if (!busy && index in checked.indices) checked[index] = value
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
        val reset = Button(this).apply { text = "恢复默认"; setOnClickListener { resetToDefault() } }
        val cancel = Button(this).apply { text = getString(R.string.cancel); setOnClickListener { if (!busy) safeFinish() } }
        val save = Button(this).apply { text = getString(R.string.done); setOnClickListener { saveAndFinish() } }
        actionButtons += listOf(reset, cancel, save)
        buttons.addView(reset); buttons.addView(cancel); buttons.addView(save)
        root.addView(buttons, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
    }

    private fun setBusy(value: Boolean) {
        busy = value
        actionButtons.forEach { it.isEnabled = !value }
        boxes.values.forEach { it.isEnabled = !value }
    }

    private fun resetToDefault() {
        if (busy) return
        setBusy(true)
        val permissionSnapshot = permissions.clone()
        Thread({
            var error: Throwable? = null
            try {
                permissionSnapshot.forEach { permission ->
                    VirtualPermissionManager.setPermissionState(packageName, userId, permission, VirtualPermissionManager.STATE_DEFAULT)
                }
            } catch (t: Throwable) { error = t; Log.e(TAG, "Failed to reset virtual permissions", t) }
            runOnUiThread {
                if (destroyed || isFinishing || isDestroyed) return@runOnUiThread
                setBusy(false)
                if (error == null) {
                    checked.fill(false); boxes.values.forEach { it.isChecked = false }
                    Toast.makeText(this, "已恢复默认权限状态", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(this, "恢复默认失败", Toast.LENGTH_SHORT).show()
            }
        }, "VirtualPermissionReset").start()
    }

    private fun saveAndFinish() {
        if (busy) return
        setBusy(true)
        val permissionSnapshot = permissions.clone()
        val checkedSnapshot = checked.clone()
        val targetPackage = packageName
        val targetUser = userId
        Thread({
            var success = true
            try {
                VirtualPermissionUi.save(targetPackage, targetUser, permissionSnapshot, checkedSnapshot)
                try { BlackBoxCore.get().stopPackage(targetPackage, targetUser) }
                catch (t: Throwable) { Log.w(TAG, "Unable to stop guest after permission update", t) }
            } catch (t: Throwable) { success = false; Log.e(TAG, "Failed to persist virtual permissions", t) }
            runOnUiThread {
                if (destroyed || isFinishing || isDestroyed) return@runOnUiThread
                setBusy(false)
                if (success) { Toast.makeText(this, R.string.virtual_permissions_saved, Toast.LENGTH_SHORT).show(); safeFinish() }
                else Toast.makeText(this, "权限保存失败", Toast.LENGTH_SHORT).show()
            }
        }, "VirtualPermissionSave").start()
    }

    override fun onDestroy() {
        destroyed = true
        boxes.clear(); actionButtons.clear()
        super.onDestroy()
    }

    private fun safeFinish() {
        if (closing) return
        closing = true
        try { if (!isFinishing) finish() }
        catch (t: Throwable) { Log.w(TAG, "Unable to finish permission activity cleanly", t) }
    }
}
