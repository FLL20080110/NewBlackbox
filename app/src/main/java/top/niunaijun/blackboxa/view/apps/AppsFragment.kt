package top.niunaijun.blackboxa.view.apps

import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import cbfg.rvadapter.RVAdapter
import com.afollestad.materialdialogs.MaterialDialog
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.fake.frameworks.VirtualPermissionManager
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.bean.AppInfo
import top.niunaijun.blackboxa.databinding.FragmentAppsBinding
import top.niunaijun.blackboxa.util.InjectionUtil
import top.niunaijun.blackboxa.util.ShortcutUtil
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackboxa.view.base.LoadingActivity
import top.niunaijun.blackboxa.view.main.MainActivity
import java.util.Collections

class AppsFragment : Fragment() {

    var userID: Int = 0
    private lateinit var viewModel: AppsViewModel
    private lateinit var mAdapter: RVAdapter<AppInfo>
    private val viewBinding: FragmentAppsBinding by inflate()
    private var popupMenu: PopupMenu? = null

    companion object {
        private const val TAG = "AppsFragment"

        fun newInstance(userID: Int): AppsFragment {
            return AppsFragment().apply {
                arguments = bundleOf("userID" to userID)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this, InjectionUtil.getAppsFactory())
            .get(AppsViewModel::class.java)
        userID = requireArguments().getInt("userID", 0)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        viewBinding.stateView.showEmpty()

        mAdapter = RVAdapter<AppInfo>(requireContext(), AppsAdapter()).bind(viewBinding.recyclerView)
        viewBinding.recyclerView.adapter = mAdapter
        viewBinding.recyclerView.layoutManager = GridLayoutManager(requireContext(), 4)
        viewBinding.recyclerView.setItemViewCacheSize(20)
        viewBinding.recyclerView.setHasFixedSize(true)

        val touchCallBack = AppsTouchCallBack { from, to ->
            onItemMove(from, to)
            viewModel.updateSortLiveData.postValue(true)
        }
        ItemTouchHelper(touchCallBack).attachToRecyclerView(viewBinding.recyclerView)

        mAdapter.setItemClickListener { _, data, _ ->
            try {
                showLoading()
                viewModel.launchApk(data.packageName, userID)
            } catch (e: Exception) {
                Log.e(TAG, "Error launching app: ${e.message}")
                hideLoading()
            }
        }

        setOnLongClick()
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initData()
    }

    override fun onStart() {
        super.onStart()
        try {
            BlackBoxCore.get().addServiceAvailableCallback {
                viewModel.getInstalledAppsWithRetry(userID)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering service callback: ${e.message}")
        }
        viewModel.getInstalledAppsWithRetry(userID)
    }

    private fun onItemMove(fromPosition: Int, toPosition: Int) {
        try {
            val items = mAdapter.getItems()
            if (fromPosition !in items.indices || toPosition !in items.indices) return
            if (fromPosition < toPosition) {
                for (i in fromPosition until toPosition) Collections.swap(items, i, i + 1)
            } else {
                for (i in fromPosition downTo toPosition + 1) Collections.swap(items, i, i - 1)
            }
            mAdapter.notifyItemMoved(fromPosition, toPosition)
        } catch (e: Exception) {
            Log.e(TAG, "Error moving item: ${e.message}")
        }
    }

    private fun setOnLongClick() {
        mAdapter.setItemLongClickListener { view, data, _ ->
            try {
                popupMenu = PopupMenu(requireContext(), view).also { menu ->
                    menu.inflate(R.menu.app_menu)
                    menu.setOnMenuItemClickListener { item ->
                        try {
                            when (item.itemId) {
                                R.id.app_permissions -> showVirtualPermissions(data)
                                R.id.app_remove -> {
                                    if (data.isXpModule) toast(R.string.uninstall_module_toast)
                                    else unInstallApk(data)
                                }
                                R.id.app_clear -> clearApk(data)
                                R.id.app_stop -> stopApk(data)
                                R.id.app_shortcut -> ShortcutUtil.createShortcut(requireContext(), userID, data)
                            }
                            true
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in app menu: ${e.message}")
                            false
                        }
                    }
                    menu.show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in long click: ${e.message}")
            }
        }
    }

    private fun showVirtualPermissions(info: AppInfo) {
        val packageName = info.packageName
        val labels = arrayOf(
            getString(R.string.permission_coarse_location),
            getString(R.string.permission_fine_location),
            getString(R.string.permission_background_location)
        )
        val checked = booleanArrayOf(
            VirtualPermissionManager.isCoarseLocationGranted(packageName, userID),
            VirtualPermissionManager.isFineLocationGranted(packageName, userID),
            VirtualPermissionManager.isBackgroundLocationGranted(packageName, userID)
        )

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.virtual_permissions_title, info.name))
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.done) { _, _ ->
                // Android treats precise/background location as depending on location access.
                // Keep the virtual permission state internally consistent.
                val coarse = checked[0] || checked[1] || checked[2]
                val fine = checked[1]
                val background = checked[2]

                VirtualPermissionManager.setPermission(
                    packageName, userID, android.Manifest.permission.ACCESS_COARSE_LOCATION, coarse
                )
                VirtualPermissionManager.setPermission(
                    packageName, userID, android.Manifest.permission.ACCESS_FINE_LOCATION, fine
                )
                VirtualPermissionManager.setPermission(
                    packageName, userID, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION, background
                )

                try {
                    BlackBoxCore.get().stopPackage(packageName, userID)
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to stop app after permission change: ${e.message}")
                }
                toast(R.string.virtual_permissions_saved)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun initData() {
        viewBinding.stateView.showLoading()
        viewModel.getInstalledApps(userID)

        viewModel.appsLiveData.observe(viewLifecycleOwner) {
            if (it != null) {
                mAdapter.setItems(it)
                if (it.isEmpty()) viewBinding.stateView.showEmpty()
                else viewBinding.stateView.showContent()
            }
        }

        viewModel.resultLiveData.observe(viewLifecycleOwner) {
            if (!TextUtils.isEmpty(it)) {
                hideLoading()
                requireContext().toast(it)
                viewModel.getInstalledApps(userID)
                scanUser()
            }
        }

        viewModel.launchLiveData.observe(viewLifecycleOwner) {
            it?.let { success ->
                hideLoading()
                if (!success) toast(R.string.start_fail)
            }
        }

        viewModel.updateSortLiveData.observe(viewLifecycleOwner) {
            if (this::mAdapter.isInitialized) {
                viewModel.updateApkOrder(userID, mAdapter.getItems())
            }
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.resultLiveData.value = null
        viewModel.launchLiveData.value = null
    }

    private fun unInstallApk(info: AppInfo) {
        MaterialDialog(requireContext()).show {
            title(R.string.uninstall_app)
            message(text = getString(R.string.uninstall_app_hint, info.name))
            positiveButton(R.string.done) {
                showLoading()
                viewModel.unInstall(info.packageName, userID)
            }
            negativeButton(R.string.cancel)
        }
    }

    private fun stopApk(info: AppInfo) {
        MaterialDialog(requireContext()).show {
            title(R.string.app_stop)
            message(text = getString(R.string.app_stop_hint, info.name))
            positiveButton(R.string.done) {
                BlackBoxCore.get().stopPackage(info.packageName, userID)
                toast(getString(R.string.is_stop, info.name))
            }
            negativeButton(R.string.cancel)
        }
    }

    private fun clearApk(info: AppInfo) {
        MaterialDialog(requireContext()).show {
            title(R.string.app_clear)
            message(text = getString(R.string.app_clear_hint, info.name))
            positiveButton(R.string.done) {
                showLoading()
                viewModel.clearApkData(info.packageName, userID)
            }
            negativeButton(R.string.cancel)
        }
    }

    fun installApk(source: String) {
        try {
            showLoading()
            viewModel.install(source, userID)
        } catch (e: Exception) {
            Log.e(TAG, "Error installing APK: ${e.message}")
            hideLoading()
        }
    }

    private fun scanUser() {
        (requireActivity() as? MainActivity)?.scanUser()
    }

    private fun showLoading() {
        (requireActivity() as? LoadingActivity)?.showLoading()
    }

    private fun hideLoading() {
        (requireActivity() as? LoadingActivity)?.hideLoading()
    }
}
