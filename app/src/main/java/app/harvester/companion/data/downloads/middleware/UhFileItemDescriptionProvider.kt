package app.harvester.companion.data.downloads.middleware

import android.content.Context
import kotlin.time.Duration.Companion.seconds
import mozilla.components.browser.state.state.content.DownloadState
import app.harvester.companion.core.downloads.UhDownloadEstimator
import app.harvester.companion.core.downloads.UhFileSizeFormatter
import app.harvester.companion.data.downloads.UhFileItem

interface UhFileItemDescriptionProvider {
    suspend fun getDescription(downloadState: DownloadState): String
}

class UhDefaultFileItemDescriptionProvider(
    private val context: Context,
    private val fileSizeFormatter: UhFileSizeFormatter,
    private val downloadEstimator: UhDownloadEstimator,
) : UhFileItemDescriptionProvider {

    override suspend fun getDescription(downloadState: DownloadState): String =
        when (downloadState.status) {
            DownloadState.Status.COMPLETED -> {
                val formattedContentLength = fileSizeFormatter.formatSizeInBytes(downloadState.contentLength ?: 0)
                val shortenedUrl = try { android.net.Uri.parse(downloadState.url).host ?: "" } catch (e: Exception) { "" }
                listOf(formattedContentLength, shortenedUrl).filter { it.isNotEmpty() }.joinToString(" • ")
            }
            DownloadState.Status.FAILED -> "Failed"
            DownloadState.Status.CANCELLED -> ""
            DownloadState.Status.PAUSED -> {
                val contentLength = downloadState.contentLength?.takeIf { it > 0 }
                if (contentLength == null) {
                    "Paused • ${fileSizeFormatter.formatSizeInBytes(downloadState.currentBytesCopied)}"
                } else {
                    "Paused • ${fileSizeFormatter.formatSizeInBytes(downloadState.currentBytesCopied)} / ${fileSizeFormatter.formatSizeInBytes(contentLength)}"
                }
            }
            DownloadState.Status.INITIATED -> "Preparing download…"
            DownloadState.Status.DOWNLOADING -> {
                val contentLength = downloadState.contentLength?.takeIf { it > 0 }
                if (contentLength == null) {
                    "${fileSizeFormatter.formatSizeInBytes(downloadState.currentBytesCopied)}"
                } else {
                    val estimatedSecsRemaining = downloadEstimator.estimatedRemainingTime(
                        startTime = downloadState.createdTime,
                        bytesDownloaded = downloadState.currentBytesCopied,
                        totalBytes = contentLength,
                    )
                    if (estimatedSecsRemaining == null) {
                        "${fileSizeFormatter.formatSizeInBytes(downloadState.currentBytesCopied)} / ${fileSizeFormatter.formatSizeInBytes(contentLength)} • pending"
                    } else {
                        "${fileSizeFormatter.formatSizeInBytes(downloadState.currentBytesCopied)} / ${fileSizeFormatter.formatSizeInBytes(contentLength)} • in ${estimatedSecsRemaining.seconds}"
                    }
                }
            }
        }
}
