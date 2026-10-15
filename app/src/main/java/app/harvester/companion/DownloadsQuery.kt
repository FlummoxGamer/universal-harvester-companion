package app.harvester.companion

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DownloadsQuery {

    data class Item(
        val id: Long,
        val title: String,
        val filename: String,
        val sizeText: String,
        val dateText: String,
        val mime: String
    )

    private var sortMode = 0

    fun setSort(mode: Int) { sortMode = mode }

    fun list(ctx: Context): List<Item> {
        val out = mutableListOf<Item>()
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.TITLE,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_ADDED,
            MediaStore.Downloads.MIME_TYPE
        )
        val order = when (sortMode) {
            1 -> "${MediaStore.Downloads.SIZE} DESC"
            2 -> "${MediaStore.Downloads.DISPLAY_NAME} ASC"
            else -> "${MediaStore.Downloads.DATE_ADDED} DESC"
        }
        try {
            ctx.contentResolver.query(collection, projection, null, null, order)?.use { c ->
                while (c.moveToNext() && out.size < 500) {
                    val id = c.getLong(0)
                    val display = c.getString(1) ?: ""
                    val title = c.getString(2) ?: display
                    val size = c.getLong(3)
                    val dateSec = c.getLong(4)
                    val mime = c.getString(5) ?: "*/*"
                    out.add(
                        Item(
                            id = id,
                            title = title,
                            filename = display,
                            sizeText = humanSize(size),
                            dateText = humanDate(dateSec),
                            mime = mime
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return out
    }

    fun uriFor(id: Long): Uri =
        Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())

    private fun humanSize(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / (1024.0 * 1024))
        else -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
    }

    private fun humanDate(sec: Long): String {
        val t = sec * 1000L
        val now = System.currentTimeMillis()
        val diff = now - t
        return when {
            diff < 60_000 -> "just now"
            diff < 3600_000 -> "${diff / 60_000}m ago"
            diff < 86_400_000 -> "${diff / 3_600_000}h ago"
            diff < 7L * 86_400_000 -> "${diff / 86_400_000}d ago"
            else -> SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(t))
        }
    }
}
