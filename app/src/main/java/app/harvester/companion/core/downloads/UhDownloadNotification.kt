package app.harvester.companion.core.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat.IMPORTANCE_NONE
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import kotlin.random.Random
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.feature.downloads.AbstractFetchDownloadService.Companion.ACTION_CANCEL
import mozilla.components.feature.downloads.AbstractFetchDownloadService.Companion.ACTION_DISMISS
import mozilla.components.feature.downloads.AbstractFetchDownloadService.Companion.ACTION_PAUSE
import mozilla.components.feature.downloads.AbstractFetchDownloadService.Companion.ACTION_RESUME
import mozilla.components.feature.downloads.AbstractFetchDownloadService.Companion.ACTION_TRY_AGAIN
import mozilla.components.support.ktx.kotlin.truncateMiddle
import mozilla.components.support.ktx.kotlin.truncateMiddle
import mozilla.components.support.utils.DownloadFileUtils
import app.harvester.companion.R

internal object UhDownloadNotification {

    private const val NOTIFICATION_CHANNEL_ID = "uh_downloads_channel"
    private const val NOTIFICATION_GROUP_KEY = "uh_downloads_group"
    internal const val NOTIFICATION_DOWNLOAD_GROUP_ID = 100
    internal const val PERCENTAGE_MULTIPLIER = 100
    private const val MAX_FILENAME_LENGTH = 25

    internal fun createDownloadGroupNotification(
        context: Context,
        fileSizeFormatter: UhFileSizeFormatter,
        notifications: List<DownloadState>,
        notificationAccentColor: Int,
    ): Notification {
        val allDownloadsHaveFinished = notifications.all { it.status != DownloadState.Status.DOWNLOADING }
        val icon = if (allDownloadsHaveFinished) R.drawable.ic_uh_download_complete else R.drawable.ic_uh_download_ongoing
        
        val summaryList = getSummaryList(context, fileSizeFormatter, notifications)
        val summaryLine1 = summaryList.firstOrNull() ?: ""
        val summaryLine2 = if (summaryList.size > 1) summaryList[1] else ""

        return NotificationCompat.Builder(context, ensureChannelExists(context))
            .setSmallIcon(icon)
            .setColor(ContextCompat.getColor(context, notificationAccentColor))
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(summaryList.joinToString("\n"))
            .setStyle(NotificationCompat.InboxStyle().addLine(summaryLine1).addLine(summaryLine2))
            .setGroup(NOTIFICATION_GROUP_KEY)
            .setGroupSummary(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    fun createOngoingDownloadNotification(
        context: Context,
        downloadState: DownloadState,
        fileSizeFormatter: UhFileSizeFormatter,
        notificationAccentColor: Int,
        downloadEstimator: UhDownloadEstimator,
    ): Notification {
        val isIndeterminate = downloadState.isIndeterminate()
        val percentCopied = downloadState.getPercent() ?: -1
        val fileName = downloadState.fileName?.truncateMiddle(MAX_FILENAME_LENGTH).orEmpty()

        return NotificationCompat.Builder(context, ensureChannelExists(context))
            .setStyle(NotificationCompat.BigTextStyle().setBigContentTitle(fileName))
            .setSmallIcon(R.drawable.ic_uh_download_ongoing)
            .setContentTitle(fileName)
            .setContentText(downloadState.getProgress(fileSizeFormatter))
            .setColor(ContextCompat.getColor(context, notificationAccentColor))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(PERCENTAGE_MULTIPLIER, percentCopied, isIndeterminate)
            .setOngoing(true)
            .setWhen(downloadState.createdTime)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_uh_media_pause, "Pause", getAction(context, ACTION_PAUSE, downloadState.id))
            .addAction(R.drawable.ic_uh_close, "Cancel", getAction(context, ACTION_CANCEL, downloadState.id))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setGroup(NOTIFICATION_GROUP_KEY)
            .build()
    }

    fun createPausedDownloadNotification(
        context: Context,
        downloadState: DownloadState,
        createdTime: Long,
        notificationAccentColor: Int,
    ): Notification {
        val fileName = downloadState.fileName?.truncateMiddle(MAX_FILENAME_LENGTH).orEmpty()

        return NotificationCompat.Builder(context, ensureChannelExists(context))
            .setSmallIcon(R.drawable.ic_uh_file_download)
            .setContentTitle(fileName)
            .setContentText("Download paused")
            .setColor(ContextCompat.getColor(context, notificationAccentColor))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setWhen(createdTime)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_uh_media_play, "Resume", getAction(context, ACTION_RESUME, downloadState.id))
            .addAction(R.drawable.ic_uh_close, "Cancel", getAction(context, ACTION_CANCEL, downloadState.id))
            .setDeleteIntent(createDismissPendingIntent(context, downloadState.id))
            .setGroup(NOTIFICATION_GROUP_KEY)
            .build()
    }

    fun createDownloadCompletedNotification(
        context: Context,
        downloadState: DownloadState,
        createdTime: Long,
        notificationAccentColor: Int,
        downloadFileUtils: DownloadFileUtils,
        contentIntent: PendingIntent,
    ): Notification {
        val fileName = downloadState.fileName?.truncateMiddle(MAX_FILENAME_LENGTH).orEmpty()

        return NotificationCompat.Builder(context, ensureChannelExists(context))
            .setSmallIcon(R.drawable.ic_uh_download_complete)
            .setContentTitle(fileName)
            .setWhen(createdTime)
            .setOnlyAlertOnce(true)
            .setContentText("Download complete")
            .setColor(ContextCompat.getColor(context, notificationAccentColor))
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setDeleteIntent(createDismissPendingIntent(context, downloadState.id))
            .setGroup(NOTIFICATION_GROUP_KEY)
            .build()
    }

    fun createDownloadFailedNotification(
        context: Context,
        downloadState: DownloadState,
        createdTime: Long,
        notificationAccentColor: Int,
    ): Notification {
        val fileName = downloadState.fileName?.truncateMiddle(MAX_FILENAME_LENGTH).orEmpty()

        return NotificationCompat.Builder(context, ensureChannelExists(context))
            .setSmallIcon(R.drawable.ic_uh_download_failed)
            .setContentTitle(fileName)
            .setContentText("Download failed")
            .setColor(ContextCompat.getColor(context, notificationAccentColor))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .addAction(R.drawable.ic_uh_retry, "Try Again", getAction(context, ACTION_TRY_AGAIN, downloadState.id))
            .addAction(R.drawable.ic_uh_close, "Cancel", getAction(context, ACTION_CANCEL, downloadState.id))
            .setWhen(createdTime)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setDeleteIntent(createDismissPendingIntent(context, downloadState.id))
            .setGroup(NOTIFICATION_GROUP_KEY)
            .build()
    }

    internal fun getSummaryList(
        context: Context,
        fileSizeFormatter: UhFileSizeFormatter,
        notifications: List<DownloadState>,
    ): List<String> {
        return notifications.take(2).map { downloadState ->
            val fileName = downloadState.fileName.orEmpty().truncateMiddle(MAX_FILENAME_LENGTH)
            "$fileName ${downloadState.getStatusDescription(context, fileSizeFormatter)}"
        }
    }

    fun isChannelEnabled(context: Context): Boolean {
        val notificationManager: NotificationManager = context.getSystemService()!!
        if (!notificationManager.areNotificationsEnabled()) return false
        val channel = notificationManager.getNotificationChannel(ensureChannelExists(context))
        return channel.importance != IMPORTANCE_NONE
    }

    private fun ensureChannelExists(context: Context): String {
        val notificationManager: NotificationManager = context.getSystemService()!!
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)
        return NOTIFICATION_CHANNEL_ID
    }

    private fun getAction(context: Context, action: String, downloadStateId: String): PendingIntent {
        val intent = Intent(action)
        intent.setPackage(context.applicationContext.packageName)
        intent.putExtra("INTENT_EXTRA_DOWNLOAD_ID", downloadStateId)
        return PendingIntent.getBroadcast(
            context.applicationContext,
            Random.nextInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createDismissPendingIntent(context: Context, downloadStateId: String): PendingIntent {
        return getAction(context, ACTION_DISMISS, downloadStateId)
    }
}

private fun DownloadState.getPercent(): Int? = progress?.let { progress ->
    (UhDownloadNotification.PERCENTAGE_MULTIPLIER * progress).toInt()
}

internal fun DownloadState.getProgress(fileSizeFormatter: UhFileSizeFormatter): String {
    return if (isIndeterminate()) {
        fileSizeFormatter.formatSizeInBytes(currentBytesCopied)
    } else {
        "${fileSizeFormatter.formatSizeInBytes(currentBytesCopied)} / ${fileSizeFormatter.formatSizeInBytes(contentLength!!)}"
    }
}

private fun DownloadState.isIndeterminate(): Boolean = contentLength == null || contentLength == 0L

internal fun DownloadState.getStatusDescription(
    context: Context,
    fileSizeFormatter: UhFileSizeFormatter,
): String {
    return when (this.status) {
        DownloadState.Status.DOWNLOADING -> getProgress(fileSizeFormatter)
        DownloadState.Status.PAUSED -> "Paused"
        DownloadState.Status.COMPLETED -> "Complete"
        DownloadState.Status.FAILED -> "Failed"
        DownloadState.Status.CANCELLED, DownloadState.Status.INITIATED -> ""
    }
}
