package app.harvester.companion

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DownloadHelper {

    const val HARVESTER_DIR = "Harvester"

    fun saveFromUrl(ctx: Context, url: String, mime: String?) {
        try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val ext = when {
                mime?.contains("json", true) == true -> "json"
                url.endsWith(".json", true) -> "json"
                url.endsWith(".m3u8", true) -> "m3u8"
                url.endsWith(".vtt", true) -> "vtt"
                url.endsWith(".srt", true) -> "srt"
                url.endsWith(".ass", true) -> "ass"
                else -> "bin"
            }
            val filename = "uh_$ts.$ext"
            val req = DownloadManager.Request(Uri.parse(url))
            req.setTitle(filename)
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            req.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "$HARVESTER_DIR/$filename"
            )
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(req)
        } catch (_: Exception) {}
    }

    fun saveTextToDownloads(
        ctx: Context,
        filename: String,
        content: String,
        subfolder: String? = HARVESTER_DIR
    ): Boolean = saveBytesToDownloads(
        ctx, filename, content.toByteArray(Charsets.UTF_8), "application/json", subfolder
    )

    fun saveBytesToDownloads(
        ctx: Context,
        filename: String,
        bytes: ByteArray,
        mime: String = "application/octet-stream",
        subfolder: String? = HARVESTER_DIR
    ): Boolean {
        return try {
            val relPath = if (subfolder.isNullOrBlank()) Environment.DIRECTORY_DOWNLOADS
                          else "${Environment.DIRECTORY_DOWNLOADS}/$subfolder"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val uri = ctx.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return false
            ctx.contentResolver.openOutputStream(uri)?.use { os -> os.write(bytes) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                ctx.contentResolver.update(uri, done, null, null)
            }
            true
        } catch (_: Exception) { false }
    }
}
