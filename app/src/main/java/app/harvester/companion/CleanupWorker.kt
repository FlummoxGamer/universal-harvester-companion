package app.harvester.companion

import android.content.Context
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
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
        val dirs = listOfNotNull(ctx.filesDir, ctx.getExternalFilesDir(null))
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        
        for (dir in dirs) {
            val folders = dir.listFiles { f -> f.isDirectory && f.name.startsWith("sess_") }
                ?.sortedByDescending { it.lastModified() } ?: continue
            
            folders.forEachIndexed { idx, f ->
                val tooOld = f.lastModified() < cutoff
                val beyondLimit = idx >= 5
                if (tooOld || beyondLimit) {
                    try { f.deleteRecursively() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun cleanupPublicSessions(ctx: Context) {
        val customUriStr = SettingsPrefs.getString(ctx, "custom_download_uri", "")
        if (customUriStr.isNotBlank()) {
            try {
                val treeUri = android.net.Uri.parse(customUriStr)
                val parent = DocumentFile.fromTreeUri(ctx, treeUri)
                val harvesterDir = parent?.findFile("Harvester")
                if (harvesterDir != null && harvesterDir.isDirectory) {
                    val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
                    val folders = harvesterDir.listFiles()
                        .filter { it.isDirectory && it.name?.startsWith("sess_") == true }
                        .sortedByDescending { it.lastModified() }
                    
                    folders.forEachIndexed { idx, f ->
                        val tooOld = f.lastModified() < cutoff
                        val beyondLimit = idx >= 5
                        if (tooOld || beyondLimit) {
                            try { f.delete() } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {}
            return
        }

        val collection = android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            android.provider.MediaStore.MediaColumns._ID,
            android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
            android.provider.MediaStore.MediaColumns.DATE_ADDED
        )
        val selection = "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%${Environment.DIRECTORY_DOWNLOADS}/Harvester/sess_%")

        val folderDates = mutableMapOf<String, Long>()
        val folderIds = mutableMapOf<String, MutableList<Long>>()

        try {
            ctx.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID)
                val pathCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.RELATIVE_PATH)
                val dateCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val path = cursor.getString(pathCol)
                    val date = cursor.getLong(dateCol) * 1000L
                    
                    if (path != null) {
                        val folder = path.substringBeforeLast("/") + "/"
                        folderDates[folder] = maxOf(folderDates[folder] ?: 0L, date)
                        folderIds.getOrPut(folder) { mutableListOf() }.add(id)
                    }
                }
            }
        } catch (_: Exception) { return }

        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        val sortedFolders = folderDates.entries.sortedByDescending { it.value }

        sortedFolders.forEachIndexed { idx, entry ->
            val folder = entry.key
            val date = entry.value
            val tooOld = date < cutoff
            val beyondLimit = idx >= 5

            if (tooOld || beyondLimit) {
                folderIds[folder]?.forEach { id ->
                    try {
                        val uri = android.content.ContentUris.withAppendedId(collection, id)
                        ctx.contentResolver.delete(uri, null, null)
                    } catch (_: Exception) {}
                }
            }
        }
    }
}
