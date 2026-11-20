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
import app.harvester.companion.UHLog
import app.harvester.companion.core.downloads.DownloadLocationResolver
import app.harvester.companion.data.downloads.UhDownloadUIAction
import app.harvester.companion.data.downloads.UhDownloadUIState

class UhDownloadUIShareMiddleware(
    private val applicationContext: Context,
    private val downloadFileUtils: DownloadFileUtils = DefaultDownloadFileUtils(applicationContext) {
        DownloadLocationResolver.resolveTargetDirectory(applicationContext)
    },
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
                val uri = if (action.directoryPath.startsWith("content://")) {
                    DownloadLocationResolver.findSafFileUri(applicationContext, action.directoryPath, action.fileName)
                } else {
                    downloadFileUtils.findShareableDownloadFileUri(
                        fileName = action.fileName,
                        directoryPath = action.directoryPath,
                    )
                }
                if (uri == null) {
                    UHLog.w("download_warn", "Cannot share download file; URI not found for ${action.fileName}")
                } else {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = action.contentType ?: "*/*"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    applicationContext.startActivity(Intent.createChooser(intent, "Share File").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            else -> {}
        }
    }
}
