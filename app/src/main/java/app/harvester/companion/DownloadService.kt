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
        
        fun start(ctx: Context, filename: String) {
            val intent = Intent(ctx, DownloadService::class.java).apply {
                putExtra("filename", filename)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun updateProgress(ctx: Context, filename: String, progress: Int, eta: String) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(ctx, filename, progress, eta, false))
        }

        fun finish(ctx: Context, filename: String, success: Boolean) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val text = if (success) "Download complete" else "Download failed"
            val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_downloads)
                .setContentTitle(filename)
                .setContentText(text)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFICATION_ID, n)
            ctx.stopService(Intent(ctx, DownloadService::class.java))
        }

        private fun buildNotification(ctx: Context, filename: String, progress: Int, eta: String, indeterminate: Boolean): android.app.Notification {
            val intent = Intent(ctx, DownloadsActivity::class.java)
            val pi = PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            
            return NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_downloads)
                .setContentTitle(filename)
                .setContentText("$eta remaining")
                .setContentIntent(pi)
                .setOnlyAlertOnce(true)
                .setProgress(100, progress, indeterminate)
                .setOngoing(true)
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
        val filename = intent?.getStringExtra("filename") ?: "Downloading..."
        startForeground(NOTIFICATION_ID, buildNotification(this, filename, 0, "Starting...", true))
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun buildNotification(ctx: Context, filename: String, progress: Int, eta: String, indeterminate: Boolean): android.app.Notification {
        return Companion.buildNotification(ctx, filename, progress, eta, indeterminate)
    }
}
