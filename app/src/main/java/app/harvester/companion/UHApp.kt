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
        checkSafeMode()
        setupCrashHandler()
        ThemePrefs.applyCurrent(this)
        UHLog.init(this)
        SessionRecorder.init(this)
        try {
            startService(Intent(this, SessionService::class.java))
        } catch (_: Exception) {}
        scheduleCleanup()
    
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val checker = UpdateChecker.checkSilently(this@UHApp)
                if (checker != null) {
                    val nm = getSystemService(android.app.NotificationManager::class.java)
                    val chId = "uh_updates"
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        val channel = android.app.NotificationChannel(chId, "App Updates", android.app.NotificationManager.IMPORTANCE_HIGH)
                        nm.createNotificationChannel(channel)
                    }
                    val intent = Intent(this@UHApp, UpdateChecker::class.java)
                    val pi = android.app.PendingIntent.getService(this@UHApp, 0, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
                    val n = androidx.core.app.NotificationCompat.Builder(this@UHApp, chId)
                        .setSmallIcon(R.drawable.ic_downloads)
                        .setContentTitle("Update Available")
                        .setContentText("Tap to download v${checker.versionName}")
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .build()
                    nm.notify(9000, n)
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
        val keep = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> 12
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> 8
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> 4
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> 8
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> 4
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> 1
            else -> return
        }
        try {
            TabManager.get(this).evictForMemory(keep)
        } catch (_: Exception) {}
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
