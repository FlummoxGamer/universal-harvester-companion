package app.harvester.companion.data.downloads.middleware

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import mozilla.components.support.utils.DefaultDownloadFileUtils
import mozilla.components.support.utils.DownloadFileUtils
import app.harvester.companion.data.downloads.UhDownloadUIAction
import app.harvester.companion.data.downloads.UhDownloadUIState

class UhDownloadUIShareMiddleware(
    private val applicationContext: Context,
    private val downloadFileUtils: DownloadFileUtils = DefaultDownloadFileUtils(applicationContext) { "" },
) : Middleware<UhDownloadUIState, UhDownloadUIAction> {

    override fun invoke(
        store: Store<UhDownloadUIState, UhDownloadUIAction>,
        next: (UhDownloadUIAction) -> Unit,
        action: UhDownloadUIAction,
    ) {
        next(action)
        when (action) {
            is UhDownloadUIAction.ShareUrlClicked -> {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, action.url)
                }
                applicationContext.startActivity(Intent.createChooser(intent, "Share URL").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            is UhDownloadUIAction.ShareFileClicked -> {
                val uri = downloadFileUtils.findShareableDownloadFileUri(
                    fileName = action.fileName,
                    directoryPath = action.directoryPath,
                )
                uri?.let {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = action.contentType ?: "*/*"
                        putExtra(Intent.EXTRA_STREAM, it)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    applicationContext.startActivity(Intent.createChooser(intent, "Share File").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            else -> {}
        }
    }
}
