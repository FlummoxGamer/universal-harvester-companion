package app.harvester.companion

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

class UHApp : Application() {
    override fun onCreate() {
        super.onCreate()
        setupCrashHandler()
        checkSafeMode()
        ThemePrefs.applyCurrent(this)
        UHLog.init(this)
        SessionRecorder.init(this)
        if (BuildConfig.DEBUG) {
            try {
                val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val ch1 = android.app.NotificationChannel("LeakCanary", "LeakCanary", android.app.NotificationManager.IMPORTANCE_NONE)
                    ch1.setShowBadge(false)
                    nm.createNotificationChannel(ch1)
                    val ch2 = android.app.NotificationChannel("leakcanary", "leakcanary", android.app.NotificationManager.IMPORTANCE_NONE)
                    ch2.setShowBadge(false)
                    nm.createNotificationChannel(ch2)
                }
            } catch (_: Exception) {}
            StrictModeManager.enableStrictMode(true)
            LeakCanarySetup.setup(this)
        }
        try {
            startService(Intent(this, SessionService::class.java))
        } catch (_: Exception) {}
            scheduleCleanup()
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
        val bootMarker = File(filesDir, "boot_marker.tmp")
        val crashFile = File(filesDir, "last_crash.txt")
        
        if (crashFile.exists()) {
            val prefs = getSharedPreferences("uh_safe_mode", MODE_PRIVATE)
            prefs.edit().putBoolean("force_safe_mode", true).apply()
            
            val crashDir = File(filesDir, "crashes").apply { mkdirs() }
            val dest = File(crashDir, "crash_${System.currentTimeMillis()}.txt")
            crashFile.renameTo(dest)
            
            if (bootMarker.exists()) bootMarker.delete()
        }
        
        bootMarker.createNewFile()
    }

    private fun setupCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val crashFile = File(filesDir, "last_crash.txt")
                FileOutputStream(crashFile).use { out ->
                    out.write("Thread: ${t.name}\n".toByteArray())
                    out.write("Exception: ${e.javaClass.name}: ${e.message}\n".toByteArray())
                    e.stackTrace.forEach { 
                        out.write("  at $it\n".toByteArray()) 
                    }
                    var cause = e.cause
                    while (cause != null) {
                        out.write("Caused by: ${cause.javaClass.name}: ${cause.message}\n".toByteArray())
                        cause.stackTrace.forEach { 
                            out.write("  at $it\n".toByteArray()) 
                        }
                        cause = cause.cause
                    }
                    out.flush()
                    out.fd.sync()
                }
            } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
    }
}
