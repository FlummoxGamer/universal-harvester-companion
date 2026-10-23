package app.harvester.companion

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(9001)
            
            val customUriStr = SettingsPrefs.getString(context, "custom_download_uri", "")
            if (customUriStr.isNotBlank()) {
                try {
                    val treeUri = Uri.parse(customUriStr)
                    val parent = DocumentFile.fromTreeUri(context, treeUri)
                    parent?.listFiles()?.forEach { file ->
                        val name = file.name ?: return@forEach
                        if (file.isFile && name.endsWith(".apk") && 
                            (name.startsWith("uh_update_") || name.startsWith("uh-companion-"))) {
                            file.delete()
                        }
                    }
                } catch (_: Exception) {}
            } else {
                val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                downloadDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.endsWith(".apk") && 
                        (file.name.startsWith("uh_update_") || file.name.startsWith("uh-companion-"))) {
                        file.delete()
                    }
                }
            }
        }
    }
}
