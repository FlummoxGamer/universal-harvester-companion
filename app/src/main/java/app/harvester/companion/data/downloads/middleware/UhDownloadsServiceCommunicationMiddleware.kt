package app.harvester.companion.data.downloads.middleware

import android.content.Context
import android.content.Intent
import mozilla.components.feature.downloads.AbstractFetchDownloadService
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import app.harvester.companion.data.downloads.UhDownloadUIAction
import app.harvester.companion.data.downloads.UhDownloadUIState

class UhDownloadsServiceCommunicationMiddleware(private val context: Context) : Middleware<UhDownloadUIState, UhDownloadUIAction> {

    override fun invoke(
        store: Store<UhDownloadUIState, UhDownloadUIAction>,
        next: (UhDownloadUIAction) -> Unit,
        action: UhDownloadUIAction,
    ) {
        next(action)
        when (action) {
            is UhDownloadUIAction.PauseDownload -> sendBroadcast(action.downloadId, AbstractFetchDownloadService.ACTION_PAUSE)
            is UhDownloadUIAction.ResumeDownload -> sendBroadcast(action.downloadId, AbstractFetchDownloadService.ACTION_RESUME)
            is UhDownloadUIAction.CancelDownload -> sendBroadcast(action.downloadId, AbstractFetchDownloadService.ACTION_CANCEL)
            is UhDownloadUIAction.RetryDownload -> sendBroadcast(action.downloadId, AbstractFetchDownloadService.ACTION_TRY_AGAIN)
            else -> {}
        }
    }

    private fun sendBroadcast(downloadId: String, action: String) {
        val intent = Intent(action)
        intent.setPackage(context.packageName)
        intent.putExtra("INTENT_EXTRA_DOWNLOAD_ID", downloadId)
        context.sendBroadcast(intent)
    }
}
