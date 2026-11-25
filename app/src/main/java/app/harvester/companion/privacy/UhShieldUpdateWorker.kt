package app.harvester.companion.privacy

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.harvester.companion.UHLog

/**
 * WorkManager periodic filter-list sync worker for UH Shield.
 */
class UhShieldUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return try {
            UhShieldEngine.init(applicationContext)
            val ok = UhShieldEngine.syncEnabledListsSuspend(applicationContext)
            if (ok) Result.success() else Result.retry()
        } catch (e: Exception) {
            UHLog.w("shield", "UhShieldUpdateWorker failed: ${e.message}")
            Result.retry()
        }
    }
}
