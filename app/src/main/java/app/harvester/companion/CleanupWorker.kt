package app.harvester.companion

import android.content.Context
import android.os.Environment
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

    // cleanupPrivateSessions
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

    // cleanupPublicSessions
    private fun cleanupPublicSessions(ctx: Context) {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val harvesterDir = File(downloadDir, "Harvester")
        if (!harvesterDir.isDirectory) return
        
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        val folders = harvesterDir.listFiles { f -> f.isDirectory && f.name.startsWith("sess_") }
            ?.sortedByDescending { it.lastModified() } ?: return

        folders.forEachIndexed { idx, f ->
            val tooOld = f.lastModified() < cutoff
            val beyondLimit = idx >= 5
            if (tooOld || beyondLimit) {
                try { f.deleteRecursively() } catch (_: Exception) {}
            }
        }
    }
}
