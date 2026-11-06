package app.harvester.companion

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.harvester.companion.components.Components
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import xcrash.ICrashCallback
import xcrash.XCrash

class UHApp : Application(), coil.ImageLoaderFactory {

    val components by lazy { Components(this) }
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        initXCrash(base)
    }

    override fun onCreate() {
        super.onCreate()
        UHLog.init(this)
        SessionRecorder.init(this)
        ThemePrefs.applyCurrent(this)
        DownloadEngine.initNetworkListener(this)
        movePendingCrashFile()

        applicationScope.launch {
            try {
                val req = PeriodicWorkRequestBuilder<CleanupWorker>(6, TimeUnit.HOURS).build()
                WorkManager.getInstance(this@UHApp).enqueueUniquePeriodicWork(
                    "uh_cleanup",
                    ExistingPeriodicWorkPolicy.KEEP,
                    req,
                )
            } catch (_: Exception) {}
        }

        if (BuildConfig.DEBUG) {
            StrictModeManager.enableStrictMode(true)
            LeakCanarySetup.setup(this)
        }

        try {
            startService(Intent(this, SessionService::class.java))
        } catch (_: Exception) {}
    }

    override fun newImageLoader(): coil.ImageLoader {
        return coil.ImageLoader.Builder(this)
            .components {
                add(coil.decode.VideoFrameDecoder.Factory())
                add(coil.decode.GifDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }

    private fun movePendingCrashFile() {
        try {
            val crashFile = File(filesDir, "last_crash.txt")
            if (crashFile.exists()) {
                val crashDir = File(filesDir, "crashes").apply { mkdirs() }
                crashFile.renameTo(File(crashDir, "crash_${System.currentTimeMillis()}.txt"))
            }
        } catch (_: Exception) {}
    }

    private fun initXCrash(context: Context) {
        try {
            val params = XCrash.InitParameters()
            params.setAppVersion(BuildConfig.VERSION_NAME)
            params.setLogDir("${context.filesDir.absolutePath}/crashes")

            val callback = ICrashCallback { logPath, emergency ->
                try {
                    val crashFile = File(context.filesDir, "last_crash.txt")
                    val content = if (!logPath.isNullOrBlank() && File(logPath).exists()) {
                        File(logPath).readText()
                    } else {
                        emergency ?: "Unknown Crash"
                    }
                    crashFile.writeText(content)
                    File(context.filesDir, "safe_mode.flag").createNewFile()
                } catch (_: Exception) {}
            }

            params.setJavaCallback(callback)
            params.setNativeCallback(callback)
            params.setAnrCallback(callback)

            XCrash.init(context, params)
        } catch (_: Throwable) {}
    }
}
