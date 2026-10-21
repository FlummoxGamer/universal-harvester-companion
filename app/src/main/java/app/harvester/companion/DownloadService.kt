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
        const val ACTION_PAUSE = "app.harvester.companion.PAUSE"
        const val ACTION_RESUME = "app.harvester.companion.RESUME"
        const val ACTION_CANCEL = "app.harvester.companion.CANCEL"

        fun start(ctx: Context) {
            val intent = Intent(ctx, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
            else ctx.startService(intent)
        }

        fun updateNotification(ctx: Context, id: String) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val task = DownloadStore.getTask(id) ?: return
            val notificationId = id.hashCode()
            
            if (task.status == DownloadStore.Status.CANCELLED) {
                nm.cancel(notificationId)
                return
            }
            
            nm.notify(notificationId, buildNotification(ctx, task))
        }

        fun notifyComplete(ctx: Context, id: String, filename: String) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val notificationId = id.hashCode()
            nm.cancel(notificationId)
            
            val openIntent = Intent(ctx, DownloadsActivity::class.java)
            val piOpen = PendingIntent.getActivity(ctx, notificationId, openIntent, PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_downloads)
                .setContentTitle(filename)
                .setContentText("Download complete")
                .setContentIntent(piOpen)
                .setAutoCancel(true)
                .build()
            nm.notify(System.currentTimeMillis().toInt(), n)
        }

        fun notifyFailed(ctx: Context, id: String, filename: String, error: String) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val notificationId = id.hashCode()
            nm.cancel(notificationId)
            
            val openIntent = Intent(ctx, DownloadsActivity::class.java)
            val piOpen = PendingIntent.getActivity(ctx, notificationId, openIntent, PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(filename)
                .setContentText("Download failed: $error")
                .setStyle(NotificationCompat.BigTextStyle().bigText("Download failed: $error"))
                .setContentIntent(piOpen)
                .setAutoCancel(true)
                .build()
            nm.notify(System.currentTimeMillis().toInt(), n)
        }

        fun cancelNotification(ctx: Context, id: String) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.cancel(id.hashCode())
        }

        private fun buildNotification(ctx: Context, task: DownloadStore.Task): android.app.Notification {
            val openIntent = Intent(ctx, DownloadsActivity::class.java)
            val notificationId = task.id.hashCode()
            val piOpen = PendingIntent.getActivity(ctx, notificationId, openIntent, PendingIntent.FLAG_IMMUTABLE)

            val isPaused = task.status == DownloadStore.Status.PAUSED
            
            val pauseIntent = Intent(ctx, DownloadService::class.java).apply {
                action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
                putExtra("id", task.id)
            }
            val piPause = PendingIntent.getService(ctx, notificationId + 1, pauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val cancelIntent = Intent(ctx, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra("id", task.id)
            }
            val piCancel = PendingIntent.getService(ctx, notificationId + 2, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val speed = formatSpeed(task.speed)
            val eta = if (task.etaSeconds > 0) "${task.etaSeconds}s left" else "Calculating..."
            val size = "${formatSize(task.bytesDownloaded)} / ${formatSize(task.totalBytes)}"
            val text = if (isPaused) "Paused • $size" else "$size • $speed • $eta"

            val progress = if (task.totalBytes > 0) ((task.bytesDownloaded * 100) / task.totalBytes).toInt() else 0

            return NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_downloads)
                .setContentTitle(task.filename)
                .setContentText(text)
                .setContentIntent(piOpen)
                .setOnlyAlertOnce(true)
                .setProgress(100, progress, task.totalBytes <= 0)
                .setOngoing(true)
                .addAction(if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause, if (isPaused) "Resume" else "Pause", piPause)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", piCancel)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .build()
        }

        private fun formatSpeed(b: Long): String = if (b <= 0) "0 B/s" else "${formatSize(b)}/s"

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
            ACTION_PAUSE -> if (id != null) DownloadEngine.pauseDownload(this, id)
            ACTION_RESUME -> if (id != null) DownloadEngine.resumeDownload(this, id)
            ACTION_CANCEL -> if (id != null) DownloadEngine.cancelDownload(this, id)
        }
        
        if (DownloadStore.activeDownloads.value.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            val firstTask = DownloadStore.activeDownloads.value.first()
            startForeground(firstTask.id.hashCode(), buildNotification(this, firstTask))
        }
        
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
