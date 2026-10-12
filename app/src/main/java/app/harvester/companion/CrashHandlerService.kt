package app.harvester.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
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

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        // Marker: proves the service was invoked at all.
        try {
            File(filesDir, "crash_service_invoked_$stamp.txt").writeText(
                "action=${intent?.action}\nextras=${intent?.extras}\n"
            )
        } catch (_: Exception) {}

        if (intent?.action == GeckoRuntime.ACTION_CRASHED) {
            val visibility = intent.getStringExtra(GeckoRuntime.EXTRA_CRASH_PROCESS_VISIBILITY)
            val processType = intent.getStringExtra(GeckoRuntime.EXTRA_CRASH_PROCESS_TYPE)
            val remoteType = intent.getStringExtra(GeckoRuntime.EXTRA_CRASH_REMOTE_TYPE)
            val minidump = intent.getStringExtra(GeckoRuntime.EXTRA_MINIDUMP_PATH)
            val extras = intent.getStringExtra(GeckoRuntime.EXTRA_EXTRAS_PATH)
            writeCrashLog(stamp, visibility, processType, remoteType, minidump, extras)
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
        stamp: String,
        visibility: String?,
        processType: String?,
        remoteType: String?,
        minidump: String?,
        extras: String?
    ) {
        val label = when (visibility) {
            GeckoRuntime.CRASHED_PROCESS_VISIBILITY_MAIN -> "MAIN (fatal)"
            GeckoRuntime.CRASHED_PROCESS_VISIBILITY_FOREGROUND_CHILD -> "FOREGROUND_CHILD"
            GeckoRuntime.CRASHED_PROCESS_VISIBILITY_BACKGROUND_CHILD -> "BACKGROUND_CHILD"
            else -> visibility ?: "UNKNOWN"
        }

        val body = buildString {
            append("visibility=$label\n")
            append("processType=$processType\n")
            append("remoteType=$remoteType\n")
            append("minidump=$minidump\n")
            append("extras=$extras\n\n")
        }

        // Always write to filesDir first. Guaranteed path.
        try {
            FileWriter(File(filesDir, "uh_native_crash.txt"), true).use { w ->
                w.write("=== $stamp ===\n")
                w.write(body)
            }
        } catch (_: Exception) {}

        // Try to copy extras + minidump to filesDir as well.
        try {
            if (!extras.isNullOrBlank()) {
                val f = File(extras)
                if (f.isFile) File(filesDir, "crash_extras_$stamp.txt").writeBytes(f.readBytes())
            }
        } catch (_: Exception) {}
        try {
            if (!minidump.isNullOrBlank()) {
                val f = File(minidump)
                if (f.isFile) File(filesDir, "crash_minidump_$stamp.dmp").writeBytes(f.readBytes())
            }
        } catch (_: Exception) {}

        // Now try Downloads. If this fails, filesDir copies are still safe.
        try {
            DownloadHelper.saveTextToDownloads(
                this, "crash_meta_$stamp.txt", body
            )
        } catch (_: Exception) {}
        try {
            if (!extras.isNullOrBlank()) {
                val f = File(extras)
                if (f.isFile) {
                    DownloadHelper.saveBytesToDownloads(
                        this, "crash_extras_$stamp.txt", f.readBytes(), "text/plain"
                    )
                }
            }
        } catch (_: Exception) {}
        try {
            if (!minidump.isNullOrBlank()) {
                val f = File(minidump)
                if (f.isFile) {
                    DownloadHelper.saveBytesToDownloads(
                        this, "crash_minidump_$stamp.dmp", f.readBytes()
                    )
                }
            }
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
