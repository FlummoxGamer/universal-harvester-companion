package app.harvester.companion

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "uh_downloads"
        const val NOTIFICATION_ID = 4040

        fun startTracking(ctx: Context, downloadId: Long, filename: String) {
            val intent = Intent(ctx, DownloadService::class.java).apply {
                putExtra("id", downloadId)
                putExtra("filename", filename)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

    fun notifyBlobComplete(ctx: Context, filename: String) {
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val intent = Intent(ctx, DownloadsActivity::class.java)
    val pi = PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    
    val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_downloads)
        .setContentTitle(filename)
        .setContentText("Download complete")
        .setAutoCancel(true)
        .setContentIntent(pi)
        .build()
    nm.notify(System.currentTimeMillis().toInt(), n)
}

        private fun buildNotification(ctx: Context, filename: String, progress: Int, text: String, indeterminate: Boolean): android.app.Notification {
            val intent = Intent(ctx, DownloadsActivity::class.java)
            val pi = PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE)

            return NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_downloads)
                .setContentTitle(filename)
                .setContentText(text)
                .setContentIntent(pi)
                .setOnlyAlertOnce(true)
                .setProgress(100, progress, indeterminate)
                .setOngoing(indeterminate)
                .build()
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val downloadId = intent?.getLongExtra("id", -1L) ?: -1L
        val filename = intent?.getStringExtra("filename") ?: "Downloading..."
        
        startForeground(NOTIFICATION_ID, buildNotification(this, filename, 0, "Starting...", true))

        if (downloadId != -1L) {
            trackProgress(downloadId, filename)
        }

        return START_NOT_STICKY
    }

    private fun trackProgress(id: Long, filename: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        CoroutineScope(Dispatchers.IO).launch {
            var isRunning = true
            var lastBytes = 0L
            var lastTime = System.currentTimeMillis()

            while (isRunning) {
                val cursor = dm.query(DownloadManager.Query().setFilterById(id))
                if (cursor != null && cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))

                    if (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING) {
                        val now = System.currentTimeMillis()
                        val timeDiff = now - lastTime
                        val bytesDiff = downloaded - lastBytes
                        val speed = if (timeDiff > 0) (bytesDiff * 1000 / timeDiff) else 0
                        val etaSec = if (speed > 0 && total > 0) (total - downloaded) / speed else -1

                        val progress = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                        val text = if (total > 0) {
                            val etaStr = if (etaSec > 0) "$etaSec seconds left" else "Calculating..."
                            "${formatSize(downloaded)} / ${formatSize(total)} • $etaStr"
                        } else {
                            "${formatSize(downloaded)} downloaded"
                        }

                        withContext(Dispatchers.Main) {
                            nm.notify(NOTIFICATION_ID, buildNotification(this@DownloadService, filename, progress, text, false))
                        }
                        lastBytes = downloaded
                        lastTime = now
                    } else if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        isRunning = false
                        withContext(Dispatchers.Main) {
                            val n = NotificationCompat.Builder(this@DownloadService, CHANNEL_ID)
                                .setSmallIcon(R.drawable.ic_downloads)
                                .setContentTitle(filename)
                                .setContentText("Download complete • ${formatSize(total)}")
                                .setAutoCancel(true)
                                .setContentIntent(PendingIntent.getActivity(this@DownloadService, 0, Intent(this@DownloadService, DownloadsActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                                .build()
                            nm.notify(NOTIFICATION_ID, n)
                            stopForeground(false)
                            stopSelf()
                        }
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        isRunning = false
                        withContext(Dispatchers.Main) {
                            nm.cancel(NOTIFICATION_ID)
                            stopForeground(true)
                            stopSelf()
                        }
                    }
                }
                cursor?.close()
                delay(1000)
            }
        }
    }

    private fun formatSize(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / (1024.0 * 1024))
        else -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
