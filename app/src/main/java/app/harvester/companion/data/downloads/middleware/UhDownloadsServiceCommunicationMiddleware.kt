package app.harvester.companion.data.downloads.middleware

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.harvester.companion.UHApp
import app.harvester.companion.core.downloads.UhDownloadService
import app.harvester.companion.data.downloads.UhDownloadUIAction
import app.harvester.companion.data.downloads.UhDownloadUIState
import mozilla.components.feature.downloads.AbstractFetchDownloadService
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store

class UhDownloadsServiceCommunicationMiddleware(private val context: Context) : Middleware<UhDownloadUIState, UhDownloadUIAction> {

    override fun invoke(
        store: Store<UhDownloadUIState, UhDownloadUIAction>,
        next: (UhDownloadUIAction) -> Unit,
        action: UhDownloadUIAction,
    ) {
        next(action)
        val components = (context.applicationContext as? UHApp)?.components
        when (action) {
            is UhDownloadUIAction.PauseDownload -> {
                components?.clearWaitingForNetwork(action.downloadId)
                sendBroadcast(action.downloadId, AbstractFetchDownloadService.ACTION_PAUSE)
            }
            is UhDownloadUIAction.ResumeDownload -> {
                components?.clearWaitingForNetwork(action.downloadId)
                sendBroadcast(action.downloadId, AbstractFetchDownloadService.ACTION_RESUME)
                startServiceAction(action.downloadId, AbstractFetchDownloadService.ACTION_RESUME)
            }
            is UhDownloadUIAction.CancelDownload -> {
                components?.clearWaitingForNetwork(action.downloadId)
                sendBroadcast(action.downloadId, AbstractFetchDownloadService.ACTION_CANCEL)
            }
            is UhDownloadUIAction.RetryDownload -> {
                components?.clearWaitingForNetwork(action.downloadId)
                sendBroadcast(action.downloadId, AbstractFetchDownloadService.ACTION_TRY_AGAIN)
                startServiceAction(action.downloadId, AbstractFetchDownloadService.ACTION_TRY_AGAIN)
            }
            else -> {}
        }
    }

    private fun sendBroadcast(downloadId: String, action: String) {
        val intent = Intent(action).apply {
            setPackage(context.packageName)
            putExtra("downloadId", downloadId)
            putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, downloadId)
            putExtra("INTENT_EXTRA_DOWNLOAD_ID", downloadId)
        }
        context.sendBroadcast(intent)
    }

    private fun startServiceAction(downloadId: String, action: String) {
        val intent = Intent(context, UhDownloadService::class.java).apply {
            this.action = action
            putExtra("downloadId", downloadId)
            putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, downloadId)
            putExtra("INTENT_EXTRA_DOWNLOAD_ID", downloadId)
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (_: Exception) {}
    }
}
