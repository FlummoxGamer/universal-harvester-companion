package app.harvester.companion

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id == -1L) return
        
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(id)
        val cursor = dm.query(query)
        
        if (cursor.moveToFirst()) {
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                val localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                val uri = Uri.parse(localUri)
                val file = File(uri.path!!)
                showInstallNotification(context, file, id)
            }
        }
        cursor.close()
    }

    private fun showInstallNotification(context: Context, apkFile: File, downloadId: Long) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val chId = "uh_updates"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(chId, "App Updates", NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(channel)
        }

        val intent = Intent(Intent.ACTION_VIEW)
        val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        
        val pi = PendingIntent.getActivity(context, downloadId.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val n = NotificationCompat.Builder(context, chId)
            .setSmallIcon(R.drawable.ic_downloads)
            .setContentTitle("Update Ready")
            .setContentText("Tap to install Universal Harvester")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
            
        nm.notify(9001, n)
    }
}
