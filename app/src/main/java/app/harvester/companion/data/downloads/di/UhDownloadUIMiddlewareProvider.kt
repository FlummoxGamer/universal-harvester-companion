package app.harvester.companion.data.downloads.di

import android.content.Context
import androidx.navigation.NavController
import kotlinx.coroutines.CoroutineScope
import mozilla.components.lib.state.Middleware
import mozilla.components.support.utils.DefaultDownloadFileUtils
import mozilla.components.support.utils.DownloadFileUtils
import app.harvester.companion.UHApp
import app.harvester.companion.core.downloads.UhDefaultFileSizeFormatter
import app.harvester.companion.core.downloads.UhDownloadEstimator
import app.harvester.companion.data.downloads.UhDownloadUIAction
import app.harvester.companion.data.downloads.UhDownloadUIState
import app.harvester.companion.data.downloads.middleware.UhDefaultFileItemDescriptionProvider
import app.harvester.companion.data.downloads.middleware.UhDeleteDownloadBehavior
import app.harvester.companion.data.downloads.middleware.UhDownloadDeleteMiddleware
import app.harvester.companion.data.downloads.middleware.UhDownloadNavigationMiddleware
import app.harvester.companion.data.downloads.middleware.UhDownloadUIMapperMiddleware
import app.harvester.companion.data.downloads.middleware.UhDownloadUIRenameMiddleware
import app.harvester.companion.data.downloads.middleware.UhDownloadUIShareMiddleware
import app.harvester.companion.data.downloads.middleware.UhDownloadsServiceCommunicationMiddleware
import mozilla.components.support.utils.DefaultDateTimeProvider

object UhDownloadUIMiddlewareProvider {

    internal fun provideMiddleware(
        coroutineScope: CoroutineScope,
        applicationContext: Context,
        navController: NavController,
    ): List<Middleware<UhDownloadUIState, UhDownloadUIAction>> {
        val components = (applicationContext as UHApp).components
        val downloadFileUtils = DefaultDownloadFileUtils(applicationContext) {
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).path
        }

        return listOf(
            UhDownloadUIMapperMiddleware(
                browserStore = components.store,
                fileItemDescriptionProvider = UhDefaultFileItemDescriptionProvider(
                    context = applicationContext,
                    fileSizeFormatter = UhDefaultFileSizeFormatter(applicationContext),
                    downloadEstimator = UhDownloadEstimator(DefaultDateTimeProvider()),
                ),
                scope = coroutineScope,
            ),
            UhDownloadUIShareMiddleware(applicationContext, downloadFileUtils),
            UhDownloadDeleteMiddleware(
                undoDelay = 4000L,
                removeDownloadUseCase = components.useCases.downloadsUseCases.removeDownload,
                deleteBehaviorProvider = { UhDeleteDownloadBehavior.ASK_WHEN_DELETING }
            ),
            UhDownloadsServiceCommunicationMiddleware(applicationContext),
            UhDownloadNavigationMiddleware(navController),
            UhDownloadUIRenameMiddleware(
                browserStore = components.store,
                scope = coroutineScope,
                downloadFileUtils = downloadFileUtils,
            )
        )
    }
}
