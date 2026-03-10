package io.nekohasekai.sfa

import android.app.Application
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.core.content.getSystemService
import go.Seq
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.sfa.bg.UpdateProfileWork
import io.nekohasekai.sfa.constant.Bugs
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import io.nekohasekai.sfa.Application as BoxApplication

class Application : Application() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        application = this
    }

    override fun onCreate() {
        super.onCreate()

        Seq.setContext(this)
        Libbox.setLocale(Locale.getDefault().toLanguageTag().replace("-", "_"))

        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) {
            initialize()
            ensureDefaults()
            UpdateProfileWork.reconfigureUpdater()
        }

    }

    private fun initialize() {
        val baseDir = filesDir
        baseDir.mkdirs()
        val workingDir = getExternalFilesDir(null) ?: return
        workingDir.mkdirs()
        val tempDir = cacheDir
        tempDir.mkdirs()
        Libbox.setup(SetupOptions().also {
            it.basePath = baseDir.path
            it.workingPath = workingDir.path
            it.tempPath = tempDir.path
            it.fixAndroidStack = Bugs.fixAndroidStack
        })
        Libbox.redirectStderr(File(workingDir, "stderr.log").path)
    }

    private suspend fun ensureDefaults() {
        try {
            if (Settings.perAppProxyList.isNotEmpty()) return

            // Set default per-app VPN list on first run
            Settings.perAppProxyEnabled = true
            Settings.perAppProxyMode = Settings.PER_APP_PROXY_INCLUDE
            Settings.perAppProxyList = setOf(
                // Browsers
                "com.android.chrome",
                "org.mozilla.firefox",
                "com.brave.browser",
                "com.opera.browser",
                "com.microsoft.emmx",
                // Social media
                "com.instagram.android",
                "com.facebook.katana",
                "com.facebook.lite",
                "com.twitter.android",
                "com.zhiliaoapp.musically",
                "com.linkedin.android",
                "com.reddit.frontpage",
                "com.pinterest",
                "com.snapchat.android",
                "com.threads.android",
                // Messengers
                "org.telegram.messenger",
                "com.whatsapp",
                "com.whatsapp.w4b",
                "org.thoughtcrime.securesms",
                "com.discord",
                "com.viber.voip",
                // Video & Streaming
                "com.google.android.youtube",
                "com.spotify.music",
                "com.netflix.mediaclient",
                "tv.twitch.android.app",
                // AI assistants
                "com.anthropic.claude",
                "com.openai.chatgpt",
                "com.google.android.apps.bard",
                "com.microsoft.copilot",
                // Play Store + Google Play Services (auth, push, updates)
                "com.android.vending",
                "com.google.android.gms",
            )
        } catch (e: Exception) {
            // Silently ignore — user can create profile manually
        }
    }

    companion object {
        lateinit var application: BoxApplication
        val notification by lazy { application.getSystemService<NotificationManager>()!! }
        val connectivity by lazy { application.getSystemService<ConnectivityManager>()!! }
        val packageManager by lazy { application.packageManager }
        val powerManager by lazy { application.getSystemService<PowerManager>()!! }
        val notificationManager by lazy { application.getSystemService<NotificationManager>()!! }
        val wifiManager by lazy { application.getSystemService<WifiManager>()!! }
        val clipboard by lazy { application.getSystemService<ClipboardManager>()!! }
    }

}
