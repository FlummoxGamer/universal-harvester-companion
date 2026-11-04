package app.harvester.companion

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class UHApp : Application(), coil.ImageLoaderFactory {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isSafeModeTriggered = AtomicBoolean(false)

    override fun newImageLoader(): coil.ImageLoader {
        return coil.ImageLoader.Builder(this)
            .components {
                add(coil.decode.VideoFrameDecoder.Factory())
                add(coil.decode.GifDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        
        checkNativeCrashes()
        checkSafeMode()

        try {
            setupSentry()
        } catch (e: Throwable) {
            android.util.Log.e("UHApp", "Sentry init failed safely: ${e.message}")
        }
        
        ThemePrefs.applyCurrent(this)
        
        applicationScope.launch {
            UHLog.init(this@UHApp)
            SessionRecorder.init(this@UHApp)
            DownloadEngine.initNetworkListener(this@UHApp)
            scheduleCleanup()
        }
        
        if (BuildConfig.DEBUG) {
            StrictModeManager.enableStrictMode(true)
            LeakCanarySetup.setup(this)
        }
    
        try {
            startService(Intent(this, SessionService::class.java))
        } catch (_: Exception) {}
    }

    private fun setupSentry() {
        SentryAndroid.init(this) { options ->
            options.dsn = ""
            options.isEnableNdk = true
            options.setDiagnosticLevel(SentryLevel.FATAL)
            options.isAttachThreads = true
            options.isAttachAnrThreadDump = true
            
            options.beforeSend = object : io.sentry.SentryOptions.BeforeSendCallback {
                override fun execute(event: io.sentry.SentryEvent, hint: io.sentry.Hint): io.sentry.SentryEvent? {
                    if (isSafeModeTriggered.compareAndSet(false, true)) {
                        val msgString = event.message?.formatted ?: "Unknown Sentry Crash"
                        val throwable = event.throwable ?: Exception(msgString)
                        handleCaughtCrash(throwable)
                    }
                    return null
                }
            }
        }
    }

    private fun handleCaughtCrash(e: Throwable) {
        try {
            val crashFile = File(filesDir, "last_crash.txt")
            FileOutputStream(crashFile).use { out ->
                out.write("Sentry Caught Crash: ${e.javaClass.name}: ${e.message}\n".toByteArray())
                e.stackTrace.forEach { 
                    out.write("  at $it\n".toByteArray()) 
                }
                out.flush()
                out.fd.sync()
            }
            getSharedPreferences("uh_safe_mode", MODE_PRIVATE).edit().putBoolean("force_safe_mode", true).apply()
        } catch (_: Exception) {}
    }

    private fun checkNativeCrashes() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val activityManager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
                val exitInfoList = activityManager.getHistoricalProcessExitReasons(packageName, 0, 1)
                if (exitInfoList.isNotEmpty()) {
                    val exitInfo = exitInfoList[0]
                    if (exitInfo.reason == android.app.ApplicationExitInfo.REASON_LOW_MEMORY || 
                        exitInfo.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE) {
                        
                        val crashFile = File(filesDir, "last_crash.txt")
                        FileOutputStream(crashFile).use { out ->
                            out.write("ApplicationExitInfo Detected:\n".toByteArray())
                            out.write("Reason: ${exitInfo.reason}\n".toByteArray())
                            out.write("Description: ${exitInfo.description}\n".toByteArray())
                            out.write("Timestamp: ${exitInfo.timestamp}\n".toByteArray())
                            out.flush()
                            out.fd.sync()
                        }
                        getSharedPreferences("uh_safe_mode", MODE_PRIVATE).edit().putBoolean("force_safe_mode", true).apply()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun scheduleCleanup() {
        try {
            val req = PeriodicWorkRequestBuilder<CleanupWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "uh_cleanup",
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        } catch (_: Exception) {}
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            try {
                TabManager.get(this).evictForMemory(1)
            } catch (_: Exception) {}
        }
    }

    private fun checkSafeMode() {
        val crashFile = File(filesDir, "last_crash.txt")
        if (crashFile.exists()) {
            val prefs = getSharedPreferences("uh_safe_mode", MODE_PRIVATE)
            prefs.edit().putBoolean("force_safe_mode", true).apply()
            
            val crashDir = File(filesDir, "crashes").apply { mkdirs() }
            val dest = File(crashDir, "crash_${System.currentTimeMillis()}.txt")
            crashFile.renameTo(dest)
        }
    }
}
