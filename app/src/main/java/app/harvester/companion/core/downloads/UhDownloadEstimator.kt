package app.harvester.companion.core.downloads

import mozilla.components.support.utils.DateTimeProvider

private const val NUM_MS_IN_SEC = 1000f

class UhDownloadEstimator(private val dateTimeProvider: DateTimeProvider) {

    fun estimatedRemainingTime(
        startTime: Long,
        bytesDownloaded: Long,
        totalBytes: Long,
    ): Long? {
        if (bytesDownloaded <= 0L || totalBytes <= 0L || totalBytes < bytesDownloaded) return null
        val timeDeltaInSecs: Float = (dateTimeProvider.currentTimeMillis() - startTime) / NUM_MS_IN_SEC

        if (timeDeltaInSecs > 0) {
            val bytesPerSec = bytesDownloaded / timeDeltaInSecs
            if (bytesPerSec > 0) {
                val secsRemaining = (totalBytes - bytesDownloaded) / bytesPerSec
                return secsRemaining.toLong()
            }
        }
        return null
    }
}
