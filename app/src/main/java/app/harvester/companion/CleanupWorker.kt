package app.harvester.companion

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File

class CleanupWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        return try {
            cleanupPrivateSessions(applicationContext)
            cleanupPublicSessions(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.success()
        }
    }

    private fun cleanupPrivateSessions(ctx: Context) {
        val dir = File(ctx.filesDir, "sessions")
        if (!dir.isDirectory) return
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        val folders = dir.listFiles { f -> f.isDirectory }?.sortedByDescending { it.lastModified() }
            ?: return
        folders.forEachIndexed { idx, f ->
            val tooOld = f.lastModified() < cutoff
            val beyondLimit = idx >= 5
            if (tooOld || beyondLimit) {
                try { f.deleteRecursively() } catch (_: Exception) {}
            }
        }
    }

    private fun cleanupPublicSessions(ctx: Context) {
        val resolver = ctx.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relPath = "Download/Harvester/"
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED
        )
        val cutoffSec = (System.currentTimeMillis() / 1000L) - 24L * 60 * 60
        try {
            val toDelete = mutableListOf<Uri>()
            resolver.query(
                collection, projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("$relPath%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { c ->
                var idx = 0
                while (c.moveToNext() && idx < 2000) {
                    val id = c.getLong(0)
                    val date = c.getLong(2)
                    val beyondLimit = idx >= 40
                    if (date < cutoffSec || beyondLimit) {
                        toDelete.add(Uri.withAppendedPath(collection, id.toString()))
                    }
                    idx++
                }
            }
            for (u in toDelete) {
                try { resolver.delete(u, null, null) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
}
