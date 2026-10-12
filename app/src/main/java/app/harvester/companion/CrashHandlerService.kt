package app.harvester.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import org.mozilla.geckoview.GeckoRuntime
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashHandlerService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()

        if (intent?.action == GeckoRuntime.ACTION_CRASHED) {
            val visibility = intent.getStringExtra(GeckoRuntime.EXTRA_CRASH_PROCESS_VISIBILITY)
            val processType = intent.getStringExtra(GeckoRuntime.EXTRA_CRASH_PROCESS_TYPE)
            val remoteType = intent.getStringExtra(GeckoRuntime.EXTRA_CRASH_REMOTE_TYPE)
            val minidump = intent.getStringExtra(GeckoRuntime.EXTRA_MINIDUMP_PATH)
            val extras = intent.getStringExtra(GeckoRuntime.EXTRA_EXTRAS_PATH)
            writeCrashLog(visibility, processType, remoteType, minidump, extras)
        }

        stopSelf()
        return START_NOT_STICKY
    }

    private fun promoteToForeground() {
        val channelId = "uh_crash"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId, "Crash handler", NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val notif: Notification = Notification.Builder(this, channelId)
            .setContentTitle("UH Companion crash")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .build()
        startForeground(99001, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun writeCrashLog(
        visibility: String?, processType: String?, remoteType: String?,
        minidump: String?, extras: String?
    ) {
        try {
            val label = when (visibility) {
                GeckoRuntime.CRASHED_PROCESS_VISIBILITY_MAIN -> "MAIN (fatal)"
                GeckoRuntime.CRASHED_PROCESS_VISIBILITY_FOREGROUND_CHILD -> "FOREGROUND_CHILD"
                GeckoRuntime.CRASHED_PROCESS_VISIBILITY_BACKGROUND_CHILD -> "BACKGROUND_CHILD"
                else -> visibility ?: "UNKNOWN"
            }
            val out = File(filesDir, "uh_native_crash.txt")
            FileWriter(out, true).use { w ->
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                w.write("=== $ts ===\n")
                w.write("visibility=$label\n")
                w.write("processType=$processType\n")
                w.write("remoteType=$remoteType\n")
                w.write("minidump=$minidump\n")
                w.write("extras=$extras\n\n")
            }

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

            if (!extras.isNullOrBlank()) {
                try {
                    val f = File(extras)
                    if (f.isFile) {
                        DownloadHelper.saveTextToDownloads(
                            this,
                            "crash_extras_$stamp.txt",
                            f.readText()
                        )
                    }
                } catch (_: Exception) {}
            }

            if (!minidump.isNullOrBlank()) {
                try {
                    val f = File(minidump)
                    if (f.isFile) {
                        DownloadHelper.saveBytesToDownloads(
                            this,
                            "crash_minidump_$stamp.dmp",
                            f.readBytes()
                        )
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
