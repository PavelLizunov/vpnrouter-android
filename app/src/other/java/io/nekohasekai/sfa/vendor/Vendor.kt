package io.nekohasekai.sfa.vendor

import android.app.Activity
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.utils.HTTPClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Vendor : VendorInterface {

    private const val TAG = "Vendor"
    private const val GITHUB_REPO = "PavelLizunov/vpnrouter-android"
    private const val API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    override fun checkUpdateAvailable(): Boolean {
        return true
    }

    override fun checkUpdate(activity: Activity, byUser: Boolean) {
        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val json = HTTPClient().use { it.getString(API_URL) }
                val release = JSONObject(json)
                val latestVersion = release.getString("tag_name").removePrefix("v")
                val currentVersion = BuildConfig.VERSION_NAME

                if (!isNewerVersion(latestVersion, currentVersion)) {
                    if (byUser) {
                        withContext(Dispatchers.Main) {
                            MaterialAlertDialogBuilder(activity)
                                .setTitle(R.string.check_update)
                                .setMessage(R.string.no_updates_available)
                                .setPositiveButton(R.string.ok, null)
                                .show()
                        }
                    }
                    return@launch
                }

                // Find arm64 APK asset
                val assets = release.getJSONArray("assets")
                var apkUrl: String? = null
                var apkName: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    if (name.endsWith(".apk") && (name.contains("arm64") || name.contains("universal"))) {
                        apkUrl = asset.getString("browser_download_url")
                        apkName = name
                        if (name.contains("arm64")) break // prefer arm64 over universal
                    }
                }

                if (apkUrl == null) {
                    Log.w(TAG, "No suitable APK found in release assets")
                    return@launch
                }

                val body = release.optString("body", "").take(500)

                withContext(Dispatchers.Main) {
                    MaterialAlertDialogBuilder(activity)
                        .setTitle("Update available: v$latestVersion")
                        .setMessage("Current: v$currentVersion\n\n$body")
                        .setPositiveButton("Download") { _, _ ->
                            downloadAndInstall(activity, apkUrl, apkName!!)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "checkUpdate failed", e)
                if (byUser) {
                    withContext(Dispatchers.Main) {
                        MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.check_update)
                            .setMessage("Update check failed: ${e.message}")
                            .setPositiveButton(R.string.ok, null)
                            .show()
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun downloadAndInstall(activity: Activity, apkUrl: String, fileName: String) {
        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) {
            var progressDialog: ProgressDialog? = null
            try {
                withContext(Dispatchers.Main) {
                    progressDialog = ProgressDialog(activity).apply {
                        setTitle("Downloading update")
                        setMessage(fileName)
                        setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
                        max = 100
                        isIndeterminate = false
                        setCancelable(false)
                        show()
                    }
                }

                val cacheDir = File(activity.cacheDir, "updates").also { it.mkdirs() }
                val apkFile = File(cacheDir, fileName)

                val connection = URL(apkUrl).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.connect()
                val totalSize = connection.contentLength
                var downloaded = 0L

                connection.inputStream.use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            if (totalSize > 0) {
                                val percent = (downloaded * 100 / totalSize).toInt()
                                withContext(Dispatchers.Main) {
                                    progressDialog?.progress = percent
                                }
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    progressDialog?.dismiss()
                }

                val uri = FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.cache",
                    apkFile
                )

                withContext(Dispatchers.Main) {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    activity.startActivity(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                withContext(Dispatchers.Main) {
                    progressDialog?.dismiss()
                    MaterialAlertDialogBuilder(activity)
                        .setTitle("Download failed")
                        .setMessage(e.message)
                        .setPositiveButton(R.string.ok, null)
                        .show()
                }
            }
        }
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        val l = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(l.size, c.size)) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv > cv) return true
            if (lv < cv) return false
        }
        return false
    }

    override fun createQRCodeAnalyzer(
        onSuccess: (String) -> Unit,
        onFailure: (Exception) -> Unit
    ): ImageAnalysis.Analyzer? {
        return null
    }
}
