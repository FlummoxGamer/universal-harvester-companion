package app.harvester.companion

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DownloadHelper {

    const val HARVESTER_DIR = "Harvester"

    fun saveFromUrl(ctx: Context, url: String, mime: String?) {
        saveFromUrlWithCookies(ctx, url, null, mime, null)
    }

    fun saveFromUrlWithCookies(
        ctx: Context,
        url: String,
        suggestedName: String?,
        mime: String?,
        userAgent: String?
    ): Long {
        return try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val ext = guessExt(url, mime)
            val filename = sanitizeFilename(
                suggestedName?.takeIf { it.isNotBlank() }
                    ?: "uh_$ts.$ext"
            )
            val req = DownloadManager.Request(Uri.parse(url))
            req.setTitle(filename)
            req.setMimeType(mime)
            req.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            req.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "$HARVESTER_DIR/$filename"
            )
            try {
                val cookie = CookieManager.getInstance().getCookie(url)
                if (!cookie.isNullOrBlank()) req.addRequestHeader("Cookie", cookie)
            } catch (_: Exception) {}
            if (!userAgent.isNullOrBlank()) {
                req.addRequestHeader("User-Agent", userAgent)
            }
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(req)
            DownloadStore.ensureInstalled(ctx)
            DownloadStore.add(id, filename)
            id
        } catch (_: Exception) { -1L }
    }

    private fun guessExt(url: String, mime: String?): String {
        val fromUrl = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
        if (fromUrl.length in 1..5 && fromUrl.all { it.isLetterOrDigit() }) return fromUrl.lowercase()
        return when {
            mime?.contains("json", true) == true -> "json"
            mime?.contains("video/mp4", true) == true -> "mp4"
            mime?.contains("video/", true) == true -> "mkv"
            mime?.contains("image/png", true) == true -> "png"
            mime?.contains("image/jpeg", true) == true -> "jpg"
            mime?.contains("image/", true) == true -> "jpg"
            mime?.contains("pdf", true) == true -> "pdf"
            mime?.contains("zip", true) == true -> "zip"
            mime?.contains("text/plain", true) == true -> "txt"
            else -> "bin"
        }
    }

    private fun sanitizeFilename(name: String): String {
        return name
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .take(120)
            .ifBlank { "download_${System.currentTimeMillis()}" }
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
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                ctx.contentResolver.update(uri, done, null, null)
            }
            true
        } catch (_: Exception) { false }
    }
}
