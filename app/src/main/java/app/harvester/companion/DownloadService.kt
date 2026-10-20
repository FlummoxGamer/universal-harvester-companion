package app.harvester.companion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class DownloadService : Service() {
    companion object {
        const val CHANNEL_ID = "uh_downloads"
        const val NOTIFICATION_ID = 4040
        const val ACTION_PAUSE = "app.harvester.companion.PAUSE"
        const val ACTION_RESUME = "app.harvester.companion.RESUME"
        const val ACTION_CANCEL = "app.harvester.companion.CANCEL"

        // start
        fun start(ctx: Context) {
            val intent = Intent(ctx, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
            else ctx.startService(intent)
        }

        // updateNotification
        fun updateNotification(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val active = DownloadStore.activeDownloads.value
            
            if (active.isEmpty()) {
                nm.cancel(NOTIFICATION_ID)
                ctx.stopService(Intent(ctx, DownloadService::class.java))
                return
            }
            nm.notify(NOTIFICATION_ID, buildNotification(ctx, active))
        }

        // notifyComplete
        fun notifyComplete(ctx: Context, filename: String) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val openIntent = Intent(ctx, DownloadsActivity::class.java)
            val piOpen = PendingIntent.getActivity(ctx, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_downloads)
                .setContentTitle(filename)
                .setContentText("Download complete")
                .setContentIntent(piOpen)
                .setAutoCancel(true)
                .build()
            nm.notify(System.currentTimeMillis().toInt(), n)
        }

        // buildNotification
        private fun buildNotification(ctx: Context, tasks: List<DownloadStore.Task>): android.app.Notification {
            val openIntent = Intent(ctx, DownloadsActivity::class.java)
            val piOpen = PendingIntent.getActivity(ctx, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)

            if (tasks.isEmpty()) {
                return NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_downloads)
                    .setContentTitle("Downloads")
                    .setContentText("No active downloads")
                    .setContentIntent(piOpen)
                    .setAutoCancel(true)
                    .build()
            }

            val primaryTask = tasks.first()
            val isPaused = primaryTask.status == DownloadStore.Status.PAUSED
            
            val pauseIntent = Intent(ctx, DownloadService::class.java).apply {
                action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
                putExtra("id", primaryTask.id)
            }
            val piPause = PendingIntent.getService(ctx, 1, pauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val cancelIntent = Intent(ctx, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra("id", primaryTask.id)
            }
            val piCancel = PendingIntent.getService(ctx, 2, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val title = if (tasks.size > 1) "${tasks.size} downloads in progress" else primaryTask.filename
            
            val speed = formatSpeed(primaryTask.speed)
            val eta = if (primaryTask.etaSeconds > 0) "${primaryTask.etaSeconds}s left" else "Calculating..."
            val size = "${formatSize(primaryTask.bytesDownloaded)} / ${formatSize(primaryTask.totalBytes)}"
            val text = if (isPaused) "Paused • $size" else "$size • $speed • $eta"

            val progress = if (primaryTask.totalBytes > 0) ((primaryTask.bytesDownloaded * 100) / primaryTask.totalBytes).toInt() else 0

            return NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_downloads)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(piOpen)
                .setOnlyAlertOnce(true)
                .setProgress(100, progress, primaryTask.totalBytes <= 0)
                .setOngoing(true)
                .addAction(if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause, if (isPaused) "Resume" else "Pause", piPause)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", piCancel)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .build()
        }

        // formatSpeed
        private fun formatSpeed(b: Long): String = if (b <= 0) "0 B/s" else "${formatSize(b)}/s"

        // formatSize
        private fun formatSize(b: Long): String = when {
            b < 0 -> "?"
            b < 1024 -> "$b B"
            b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
            b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / (1024.0 * 1024))
            else -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("id")
        when (intent?.action) {
            ACTION_PAUSE -> if (id != null) DownloadEngine.pauseDownload(id)
            ACTION_RESUME -> if (id != null) DownloadEngine.resumeDownload(this, id)
            ACTION_CANCEL -> if (id != null) DownloadEngine.cancelDownload(this, id)
        }
        
        val active = DownloadStore.activeDownloads.value
        startForeground(NOTIFICATION_ID, buildNotification(this, active))
        
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
