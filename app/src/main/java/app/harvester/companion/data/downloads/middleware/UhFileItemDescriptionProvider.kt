package app.harvester.companion.data.downloads.middleware

import android.content.Context
import app.harvester.companion.R
import kotlin.time.Duration.Companion.seconds
import mozilla.components.browser.state.state.content.DownloadState
import app.harvester.companion.core.downloads.UhDownloadEstimator
import app.harvester.companion.core.downloads.UhFileSizeFormatter

interface UhFileItemDescriptionProvider {
    suspend fun getDescription(downloadState: DownloadState): String
}

class UhDefaultFileItemDescriptionProvider(
    private val context: Context,
    private val fileSizeFormatter: UhFileSizeFormatter,
    private val downloadEstimator: UhDownloadEstimator,
    private val isWaitingForNetwork: (String) -> Boolean = { false },
) : UhFileItemDescriptionProvider {

    override suspend fun getDescription(downloadState: DownloadState): String {
        if (isWaitingForNetwork(downloadState.id) &&
            (downloadState.status == DownloadState.Status.PAUSED ||
                downloadState.status == DownloadState.Status.FAILED ||
                downloadState.status == DownloadState.Status.INITIATED)
        ) {
            val contentLength = downloadState.contentLength?.takeIf { it > 0 }
            val copied = fileSizeFormatter.formatSizeInBytes(downloadState.currentBytesCopied)
            return if (contentLength == null) {
                context.getString(R.string.download_item_waiting_for_network_unknown_size, copied)
            } else {
                context.getString(
                    R.string.download_item_waiting_for_network_description,
                    copied,
                    fileSizeFormatter.formatSizeInBytes(contentLength)
                )
            }
        }

        return when (downloadState.status) {
            DownloadState.Status.COMPLETED -> {
                val formattedContentLength = fileSizeFormatter.formatSizeInBytes(downloadState.contentLength ?: 0)
                val shortenedUrl = try { android.net.Uri.parse(downloadState.url).host ?: "" } catch (_: Exception) { "" }
                listOf(formattedContentLength, shortenedUrl).filter { it.isNotEmpty() }.joinToString(" • ")
            }
            DownloadState.Status.FAILED -> context.getString(R.string.download_item_status_failed)
            DownloadState.Status.CANCELLED -> ""
            DownloadState.Status.PAUSED -> {
                val contentLength = downloadState.contentLength?.takeIf { it > 0 }
                val copied = fileSizeFormatter.formatSizeInBytes(downloadState.currentBytesCopied)
                if (contentLength == null) {
                    context.getString(R.string.download_item_paused_description_unknown_total_size, copied)
                } else {
                    context.getString(
                        R.string.download_item_paused_description,
                        copied,
                        fileSizeFormatter.formatSizeInBytes(contentLength)
                    )
                }
            }
            DownloadState.Status.INITIATED -> context.getString(R.string.download_item_in_progress_description_preparing)
            DownloadState.Status.DOWNLOADING -> {
                val contentLength = downloadState.contentLength?.takeIf { it > 0 }
                val copied = fileSizeFormatter.formatSizeInBytes(downloadState.currentBytesCopied)
                if (contentLength == null) {
                    copied
                } else {
                    val total = fileSizeFormatter.formatSizeInBytes(contentLength)
                    val estimatedSecsRemaining = downloadEstimator.estimatedRemainingTime(
                        startTime = downloadState.createdTime,
                        bytesDownloaded = downloadState.currentBytesCopied,
                        totalBytes = contentLength,
                    )
                    if (estimatedSecsRemaining == null) {
                        "$copied / $total • pending"
                    } else {
                        "$copied / $total • ${estimatedSecsRemaining.seconds} left"
                    }
                }
            }
        }
    }
}
