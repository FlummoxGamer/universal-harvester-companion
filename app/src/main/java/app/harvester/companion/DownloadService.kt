package app.harvester.companion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat

class DownloadService : Service() {
    companion object {
        const val CHANNEL_ID = "uh_downloads"
        const val ACTION_PAUSE = "app.harvester.companion.PAUSE"
        const val ACTION_RESUME = "app.harvester.companion.RESUME"
        const val ACTION_CANCEL = "app.harvester.companion.CANCEL"
        const val ACTION_TRY_AGAIN = "app.harvester.companion.TRY_AGAIN"

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
            val progressId = id.hashCode()
            val notificationId = id.hashCode() + 10000
            nm.cancel(progressId)
            
            val downloadUri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            val cursor = try {
                ctx.contentResolver.query(downloadUri, null, selection, arrayOf(filename, "Download/%"), null)
            } catch (e: Throwable) {
                UHLog.e("download", "notify query failed: ${e.message}")
                null
            }
            var fileUri: android.net.Uri? = null
            var mimeType = "*/*"
            cursor?.use {
                if (it.moveToFirst()) {
                    val uriIndex = it.getColumnIndex(MediaStore.MediaColumns._ID)
                    if (uriIndex != -1) {
                        fileUri = ContentUris.withAppendedId(downloadUri, it.getLong(uriIndex))
                    }
                    val mimeIndex = it.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                    if (mimeIndex != -1) mimeType = it.getString(mimeIndex)
                }
            }
            
            if (fileUri == null) {
                val customUriStr = SettingsPrefs.getString(ctx, "custom_download_uri", "")
                if (customUriStr.isNotBlank()) {
                    val treeUri = android.net.Uri.parse(customUriStr)
                    val parent = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, treeUri)
                    val doc = parent?.findFile(filename)
                    if (doc != null) {
                        fileUri = doc.uri
                        mimeType = doc.type ?: "*/*"
                    }
                }
            }

            val isApk = filename.endsWith(".apk")
            val openIntent = if (fileUri != null) {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(fileUri, if (isApk) "application/vnd.android.package-archive" else mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(ctx, DownloadsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            if (isApk && fileUri != null) {
                try {
                    ctx.startActivity(openIntent)
                } catch (_: Exception) {}
            }

            val piOpen = PendingIntent.getActivity(ctx, notificationId, openIntent, PendingIntent.FLAG_IMMUTABLE)
            
            val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_downloads)
                .setContentTitle("Download Complete")
                .setContentText(filename)
                .setContentIntent(piOpen)
                .setAutoCancel(true)
                .setOngoing(false)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
                
            nm.notify(notificationId, n)
            
            if (DownloadStore.activeDownloads.value.none { it.status == DownloadStore.Status.RUNNING || it.status == DownloadStore.Status.PENDING }) {
                try { ctx.stopService(Intent(ctx, DownloadService::class.java)) } catch (_: Exception) {}
            }
        }

        fun notifyFailed(ctx: Context, id: String, filename: String, error: String) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val progressId = id.hashCode()
            val notificationId = id.hashCode() + 10000
            nm.cancel(progressId)
            
            val openIntent = Intent(ctx, DownloadsActivity::class.java)
            val piOpen = PendingIntent.getActivity(ctx, notificationId, openIntent, PendingIntent.FLAG_IMMUTABLE)
            
            val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(filename)
                .setContentText("Download failed: $error")
                .setStyle(NotificationCompat.BigTextStyle().bigText("Download failed: $error"))
                .setContentIntent(piOpen)
                .setAutoCancel(true)
                .setOngoing(false)
                .build()
                
            nm.notify(notificationId, n)
            
            if (DownloadStore.activeDownloads.value.none { it.status == DownloadStore.Status.RUNNING || it.status == DownloadStore.Status.PENDING }) {
                try { ctx.stopService(Intent(ctx, DownloadService::class.java)) } catch (_: Exception) {}
            }
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
            val isPending = task.status == DownloadStore.Status.PENDING
            
            val pauseIntent = Intent(ctx, DownloadService::class.java).apply {
                action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
                putExtra("id", task.id)
            }
            val piPause = PendingIntent.getService(ctx, notificationId + 1, pauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val retryIntent = Intent(ctx, DownloadService::class.java).apply {
                action = ACTION_TRY_AGAIN
                putExtra("id", task.id)
            }
            val piRetry = PendingIntent.getService(ctx, notificationId + 3, retryIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val cancelIntent = Intent(ctx, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra("id", task.id)
            }
            val piCancel = PendingIntent.getService(ctx, notificationId + 2, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val speed = formatSpeed(task.speed)
            val eta = formatEta(task.etaSeconds)
            val size = "${formatSize(task.bytesDownloaded)} / ${formatSize(task.totalBytes)}"
            
            val text = when {
                isPending -> "Queued..."
                isPaused -> "Paused • $size"
                else -> "$size • $speed • $eta"
            }

            val progress = if (task.totalBytes > 0) ((task.bytesDownloaded * 100) / task.totalBytes).toInt() else 0

            val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_downloads)
                .setContentTitle(task.filename)
                .setContentText(text)
                .setContentIntent(piOpen)
                .setOnlyAlertOnce(true)
                .setProgress(100, progress, task.totalBytes <= 0 && !isPending)
                .setOngoing(true)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                
            if (task.status == DownloadStore.Status.FAILED) {
                builder.addAction(R.drawable.ic_reload, "Retry", piRetry)
            } else if (!isPending) {
                builder.addAction(if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause, if (isPaused) "Resume" else "Pause", piPause)
            }
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", piCancel)

            return builder.build()
        }

        private fun formatSpeed(b: Long): String = if (b <= 0) "0 B/s" else "${formatSize(b)}/s"

        private fun formatSize(b: Long): String = when {
            b < 0 -> "?"
            b < 1024 -> "$b B"
            b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
            b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / (1024.0 * 1024))
            else -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
        }

        private fun formatEta(seconds: Long): String {
            if (seconds <= 0) return "Calculating..."
            return when {
                seconds < 60 -> "${seconds}s left"
                seconds < 3600 -> "${seconds / 60}m left"
                else -> "${seconds / 3600}h left"
            }
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
        ACTION_TRY_AGAIN -> if (id != null) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(id.hashCode())
            DownloadEngine.resumeDownload(this, id)
        }
    }
    
    val active = DownloadStore.activeDownloads.value.filter { it.status == DownloadStore.Status.RUNNING || it.status == DownloadStore.Status.PENDING || it.status == DownloadStore.Status.PAUSED }
    if (active.isEmpty()) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    } else {
        val firstTask = active.first()
        val notification = buildNotification(this, firstTask)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (firstTask.status == DownloadStore.Status.PAUSED || firstTask.status == DownloadStore.Status.PENDING) {
            nm.notify(firstTask.id.hashCode(), notification)
        } else {
            startForeground(firstTask.id.hashCode(), notification)
        }
    }
    
    return START_NOT_STICKY
}

    override fun onBind(intent: Intent?): IBinder? = null
}
