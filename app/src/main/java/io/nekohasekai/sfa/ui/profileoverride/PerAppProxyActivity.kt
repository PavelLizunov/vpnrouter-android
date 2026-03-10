package io.nekohasekai.sfa.ui.profileoverride

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.databinding.ActivityPerAppProxyBinding
import io.nekohasekai.sfa.databinding.ViewAppListItemBinding
import io.nekohasekai.sfa.ktx.clipboardText
import io.nekohasekai.sfa.ui.shared.AbstractActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PerAppProxyActivity : AbstractActivity<ActivityPerAppProxyBinding>() {
    enum class SortMode {
        NAME, PACKAGE_NAME, UID, INSTALL_TIME, UPDATE_TIME,
    }

    private var proxyMode = Settings.PER_APP_PROXY_INCLUDE
    private var sortMode = SortMode.NAME
    private var sortReverse = false
    private var hideSystemApps = false
    private var hideOfflineApps = true
    private var hideDisabledApps = true

    inner class PackageCache(
        private val packageInfo: PackageInfo,
        private val appInfo: ApplicationInfo,
    ) {

        val packageName: String get() = packageInfo.packageName

        val uid get() = packageInfo.applicationInfo!!.uid

        val installTime get() = packageInfo.firstInstallTime
        val updateTime get() = packageInfo.lastUpdateTime
        val isSystem get() = appInfo.flags and ApplicationInfo.FLAG_SYSTEM == 1
        val isOffline get() = packageInfo.requestedPermissions?.contains(Manifest.permission.INTERNET) != true
        val isDisabled get() = appInfo.flags and ApplicationInfo.FLAG_INSTALLED == 0

        val applicationIcon by lazy {
            appInfo.loadIcon(packageManager)
        }

        val applicationLabel by lazy {
            appInfo.loadLabel(packageManager).toString()
        }
    }

    private lateinit var adapter: ApplicationAdapter
    private var packages = listOf<PackageCache>()
    private var displayPackages = listOf<PackageCache>()
    private var currentPackages = listOf<PackageCache>()
    private var selectedUIDs = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTitle(R.string.title_per_app_proxy)

        ViewCompat.setOnApplyWindowInsetsListener(binding.appList) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                proxyMode = if (Settings.perAppProxyMode == Settings.PER_APP_PROXY_INCLUDE) {
                    Settings.PER_APP_PROXY_INCLUDE
                } else {
                    Settings.PER_APP_PROXY_EXCLUDE
                }
                withContext(Dispatchers.Main) {
                    if (proxyMode == Settings.PER_APP_PROXY_INCLUDE) {
                        binding.perAppProxyMode.setText(R.string.per_app_proxy_mode_include_description)
                    } else {
                        binding.perAppProxyMode.setText(R.string.per_app_proxy_mode_exclude_description)
                    }
                }
                reloadApplicationList()
                filterApplicationList()
                withContext(Dispatchers.Main) {
                    adapter = ApplicationAdapter(displayPackages)
                    binding.appList.adapter = adapter
                    delay(500L)
                    binding.progress.isVisible = false
                }
            }
        }
    }

    private fun reloadApplicationList() {
        val packageManagerFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            PackageManager.GET_PERMISSIONS or PackageManager.MATCH_UNINSTALLED_PACKAGES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_PERMISSIONS or PackageManager.GET_UNINSTALLED_PACKAGES
        }
        val installedPackages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(
                    packageManagerFlags.toLong()
                )
            )
        } else {
            @Suppress("DEPRECATION") packageManager.getInstalledPackages(packageManagerFlags)
        }
        val packages = mutableListOf<PackageCache>()
        for (packageInfo in installedPackages) {
            if (packageInfo.packageName == packageName) continue
            val appInfo = packageInfo.applicationInfo ?: continue
            packages.add(PackageCache(packageInfo, appInfo))
        }
        val selectedPackageNames = Settings.perAppProxyList.toMutableSet()
        val selectedUIDs = mutableSetOf<Int>()
        for (packageCache in packages) {
            if (selectedPackageNames.contains(packageCache.packageName)) {
                selectedUIDs.add(packageCache.uid)
            }
        }
        this.packages = packages
        this.selectedUIDs = selectedUIDs
    }

    private fun filterApplicationList(selectedUIDs: Set<Int> = this.selectedUIDs) {
        val displayPackages = mutableListOf<PackageCache>()
        for (packageCache in packages) {
            if (hideSystemApps && packageCache.isSystem) continue
            if (hideOfflineApps && packageCache.isOffline) continue
            if (hideDisabledApps && packageCache.isDisabled) continue
            displayPackages.add(packageCache)
        }
        displayPackages.sortWith(if (!sortReverse) compareBy {
            when (sortMode) {
                SortMode.NAME -> it.applicationLabel
                SortMode.PACKAGE_NAME -> it.packageName
                SortMode.UID -> it.uid
                SortMode.INSTALL_TIME -> it.installTime
                SortMode.UPDATE_TIME -> it.updateTime
            }
        } else compareByDescending {
            when (sortMode) {
                SortMode.NAME -> it.applicationLabel
                SortMode.PACKAGE_NAME -> it.packageName
                SortMode.UID -> it.uid
                SortMode.INSTALL_TIME -> it.installTime
                SortMode.UPDATE_TIME -> it.updateTime
            }
        })

        this.displayPackages = displayPackages
        this.currentPackages = displayPackages
    }

    private fun updateApplicationSelection(packageCache: PackageCache, selected: Boolean) {
        val performed = if (selected) {
            selectedUIDs.add(packageCache.uid)
        } else {
            selectedUIDs.remove(packageCache.uid)
        }
        if (!performed) return
        currentPackages.forEachIndexed { index, it ->
            if (it.uid == packageCache.uid) {
                adapter.notifyItemChanged(index, PayloadUpdateSelection(selected))
            }
        }
        saveSelectedApplications()
    }

    data class PayloadUpdateSelection(val selected: Boolean)

    inner class ApplicationAdapter(private var applicationList: List<PackageCache>) :
        RecyclerView.Adapter<ApplicationViewHolder>() {

        @SuppressLint("NotifyDataSetChanged")
        fun setApplicationList(applicationList: List<PackageCache>) {
            this.applicationList = applicationList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(
            parent: ViewGroup, viewType: Int
        ): ApplicationViewHolder {
            return ApplicationViewHolder(
                ViewAppListItemBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )
        }

        override fun getItemCount(): Int {
            return applicationList.size
        }

        override fun onBindViewHolder(
            holder: ApplicationViewHolder, position: Int
        ) {
            holder.bind(applicationList[position])
        }

        override fun onBindViewHolder(
            holder: ApplicationViewHolder, position: Int, payloads: MutableList<Any>
        ) {
            if (payloads.isEmpty()) {
                onBindViewHolder(holder, position)
                return
            }
            payloads.forEach {
                when (it) {
                    is PayloadUpdateSelection -> holder.updateSelection(it.selected)
                }
            }
        }
    }

    inner class ApplicationViewHolder(
        private val binding: ViewAppListItemBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        @SuppressLint("SetTextI18n")
        fun bind(packageCache: PackageCache) {
            binding.appIcon.setImageDrawable(packageCache.applicationIcon)
            binding.applicationLabel.text = packageCache.applicationLabel
            binding.packageName.text = "${packageCache.packageName} (${packageCache.uid})"
            binding.selected.isChecked = selectedUIDs.contains(packageCache.uid)
            binding.root.setOnClickListener {
                updateApplicationSelection(packageCache, !binding.selected.isChecked)
            }
            binding.root.setOnLongClickListener {
                val popup = PopupMenu(it.context, it)
                popup.setForceShowIcon(true)
                popup.gravity = Gravity.END
                popup.menuInflater.inflate(R.menu.app_menu, popup.menu)
                popup.setOnMenuItemClickListener {
                    when (it.itemId) {
                        R.id.action_copy_application_label -> {
                            clipboardText = packageCache.applicationLabel
                            true
                        }

                        R.id.action_copy_package_name -> {
                            clipboardText = packageCache.packageName
                            true
                        }

                        R.id.action_copy_uid -> {
                            clipboardText = packageCache.uid.toString()
                            true
                        }

                        else -> false
                    }
                }
                popup.show()
                true
            }
        }

        fun updateSelection(selected: Boolean) {
            binding.selected.isChecked = selected
        }
    }

    private fun searchApplications(searchText: String) {
        currentPackages = if (searchText.isEmpty()) {
            displayPackages
        } else {
            displayPackages.filter {
                it.applicationLabel.contains(
                    searchText, ignoreCase = true
                ) || it.packageName.contains(
                    searchText, ignoreCase = true
                ) || it.uid.toString().contains(searchText)
            }
        }
        adapter.setApplicationList(currentPackages)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.per_app_menu, menu)

        if (menu != null) {
            val searchView = menu.findItem(R.id.action_search).actionView as SearchView
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String): Boolean {
                    return true
                }

                override fun onQueryTextChange(newText: String): Boolean {
                    searchApplications(newText)
                    return true
                }
            })
            searchView.setOnCloseListener {
                searchApplications("")
                true
            }
            when (proxyMode) {
                Settings.PER_APP_PROXY_INCLUDE -> {
                    menu.findItem(R.id.action_mode_include).isChecked = true
                }

                Settings.PER_APP_PROXY_EXCLUDE -> {
                    menu.findItem(R.id.action_mode_exclude).isChecked = true
                }
            }
            when (sortMode) {
                SortMode.NAME -> {
                    menu.findItem(R.id.action_sort_by_name).isChecked = true
                }

                SortMode.PACKAGE_NAME -> {
                    menu.findItem(R.id.action_sort_by_package_name).isChecked = true
                }

                SortMode.UID -> {
                    menu.findItem(R.id.action_sort_by_uid).isChecked = true
                }

                SortMode.INSTALL_TIME -> {
                    menu.findItem(R.id.action_sort_by_install_time).isChecked = true
                }

                SortMode.UPDATE_TIME -> {
                    menu.findItem(R.id.action_sort_by_update_time).isChecked = true
                }
            }
            menu.findItem(R.id.action_sort_reverse).isChecked = sortReverse
            menu.findItem(R.id.action_hide_system_apps).isChecked = hideSystemApps
            menu.findItem(R.id.action_hide_offline_apps).isChecked = hideOfflineApps
            menu.findItem(R.id.action_hide_disabled_apps).isChecked = hideDisabledApps
        }

        return super.onCreateOptionsMenu(menu)
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_mode_include -> {
                item.isChecked = true
                proxyMode = Settings.PER_APP_PROXY_INCLUDE
                binding.perAppProxyMode.setText(R.string.per_app_proxy_mode_include_description)
                lifecycleScope.launch {
                    Settings.perAppProxyMode = Settings.PER_APP_PROXY_INCLUDE
                }
            }

            R.id.action_mode_exclude -> {
                item.isChecked = true
                proxyMode = Settings.PER_APP_PROXY_EXCLUDE
                binding.perAppProxyMode.setText(R.string.per_app_proxy_mode_exclude_description)
                lifecycleScope.launch {
                    Settings.perAppProxyMode = Settings.PER_APP_PROXY_EXCLUDE
                }
            }

            R.id.action_sort_by_name -> {
                item.isChecked = true
                sortMode = SortMode.NAME
                filterApplicationList()
                adapter.setApplicationList(currentPackages)
            }

            R.id.action_sort_by_package_name -> {
                item.isChecked = true
                sortMode = SortMode.PACKAGE_NAME
                filterApplicationList()
                adapter.setApplicationList(currentPackages)
            }

            R.id.action_sort_by_uid -> {
                item.isChecked = true
                sortMode = SortMode.UID
                filterApplicationList()
                adapter.setApplicationList(currentPackages)
            }

            R.id.action_sort_by_install_time -> {
                item.isChecked = true
                sortMode = SortMode.INSTALL_TIME
                filterApplicationList()
                adapter.setApplicationList(currentPackages)
            }

            R.id.action_sort_by_update_time -> {
                item.isChecked = true
                sortMode = SortMode.UPDATE_TIME
                filterApplicationList()
                adapter.setApplicationList(currentPackages)
            }

            R.id.action_sort_reverse -> {
                item.isChecked = !item.isChecked
                sortReverse = item.isChecked
                filterApplicationList()
                adapter.setApplicationList(currentPackages)
            }

            R.id.action_hide_system_apps -> {
                item.isChecked = !item.isChecked
                hideSystemApps = item.isChecked
                filterApplicationList()
                adapter.setApplicationList(currentPackages)
            }

            R.id.action_hide_offline_apps -> {
                item.isChecked = !item.isChecked
                hideOfflineApps = item.isChecked
                filterApplicationList()
                adapter.setApplicationList(currentPackages)
            }

            R.id.action_hide_disabled_apps -> {
                item.isChecked = !item.isChecked
                hideDisabledApps = item.isChecked
                filterApplicationList()
                adapter.setApplicationList(currentPackages)
            }

            R.id.action_select_all -> {
                val selectedUIDs = mutableSetOf<Int>()
                currentPackages.forEach {
                    selectedUIDs.add(it.uid)
                }
                lifecycleScope.launch {
                    postSaveSelectedApplications(selectedUIDs)
                }
            }

            R.id.action_deselect_all -> {
                lifecycleScope.launch {
                    postSaveSelectedApplications(mutableSetOf())
                }
            }

            R.id.action_export -> {
                lifecycleScope.launch {
                    val packageList = mutableListOf<String>()
                    for (packageCache in packages) {
                        if (selectedUIDs.contains(packageCache.uid)) {
                            packageList.add(packageCache.packageName)
                        }
                    }
                    clipboardText = packageList.joinToString("\n")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@PerAppProxyActivity,
                            R.string.toast_copied_to_clipboard,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            R.id.action_import -> {
                val packageNames = clipboardText?.split("\n")?.distinct()
                    ?.takeIf { it.isNotEmpty() && it[0].isNotEmpty() }
                if (packageNames.isNullOrEmpty()) {
                    Toast.makeText(
                        this@PerAppProxyActivity,
                        R.string.toast_clipboard_empty,
                        Toast.LENGTH_SHORT
                    ).show()
                    return true
                }
                val selectedUIDs = mutableSetOf<Int>()
                for (packageCache in packages) {
                    if (packageNames.contains(packageCache.packageName)) {
                        selectedUIDs.add(packageCache.uid)
                    }
                }
                lifecycleScope.launch {
                    postSaveSelectedApplications(selectedUIDs)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@PerAppProxyActivity,
                            R.string.toast_imported_from_clipboard,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

            }

            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    @SuppressLint("NotifyDataSetChanged")
    private suspend fun postSaveSelectedApplications(newUIDs: MutableSet<Int>) {
        filterApplicationList(newUIDs)
        withContext(Dispatchers.Main) {
            selectedUIDs = newUIDs
            adapter.notifyDataSetChanged()
        }
        val packageList = selectedUIDs.mapNotNull { uid ->
            packages.find { it.uid == uid }?.packageName
        }
        Settings.perAppProxyList = packageList.toSet()
    }

    private fun saveSelectedApplications() {
        lifecycleScope.launch {
            val packageList = selectedUIDs.mapNotNull { uid ->
                packages.find { it.uid == uid }?.packageName
            }
            Settings.perAppProxyList = packageList.toSet()
        }
    }


}
