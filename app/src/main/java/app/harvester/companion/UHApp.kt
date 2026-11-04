package app.harvester.companion

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import xcrash.ICrashCallback
import xcrash.XCrash
import java.io.File
import java.util.concurrent.TimeUnit

class UHApp : Application(), coil.ImageLoaderFactory {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun newImageLoader(): coil.ImageLoader {
        return coil.ImageLoader.Builder(this)
            .components {
                add(coil.decode.VideoFrameDecoder.Factory())
                add(coil.decode.GifDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        initXCrash(base)
    }

    override fun onCreate() {
        val start = System.currentTimeMillis()
        super.onCreate()
        
        checkNativeCrashes()
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
    
        UHLog.d("perf", "UHApp.onCreate took ${System.currentTimeMillis() - start}ms")
    }

    private fun initXCrash(context: android.content.Context) {
        try {
            val params = XCrash.InitParameters()
            params.setAppVersion(BuildConfig.VERSION_NAME)
            params.setLogDir("${context.filesDir.absolutePath}/crashes")
            
            val callback = ICrashCallback { logPath, emergency ->
                handleXCrash(context, logPath, emergency)
            }
            
            params.setJavaCallback(callback)
            params.setNativeCallback(callback)
            params.setAnrCallback(callback)
            
            XCrash.init(context, params)
        } catch (_: Throwable) {}
    }

    private fun handleXCrash(context: android.content.Context, logPath: String?, emergency: String?) {
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
                        crashFile.writeText("ApplicationExitInfo Detected:\nReason: ${exitInfo.reason}\nDescription: ${exitInfo.description}\nTimestamp: ${exitInfo.timestamp}\n")
                        File(filesDir, "safe_mode.flag").createNewFile()
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
}
