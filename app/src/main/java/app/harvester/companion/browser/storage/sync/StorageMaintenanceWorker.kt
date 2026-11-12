package app.harvester.companion.browser.storage.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

abstract class StorageMaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    final override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            try {
                operate()
                Result.success()
            } catch (exception: Exception) {
                onError(exception)
                Result.failure()
            }
        }

    abstract suspend fun operate()

    abstract fun onError(exception: Exception)

    companion object {
        const val WORKER_PERIOD_IN_HOURS = 12L
    }
}
