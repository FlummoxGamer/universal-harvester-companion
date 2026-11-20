package app.harvester.companion.data.downloads.di

import android.content.Context
import androidx.navigation.NavController
import kotlinx.coroutines.CoroutineScope
import mozilla.components.lib.state.Middleware
import mozilla.components.support.utils.DefaultDownloadFileUtils
import app.harvester.companion.UHApp
import app.harvester.companion.core.downloads.DownloadLocationResolver
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
        navControllerProvider: () -> NavController?,
    ): List<Middleware<UhDownloadUIState, UhDownloadUIAction>> {
        val components = (applicationContext as UHApp).components
        val downloadFileUtils = DefaultDownloadFileUtils(applicationContext) {
            DownloadLocationResolver.resolveTargetDirectory(applicationContext)
        }

        return listOf(
            UhDownloadUIMapperMiddleware(
                browserStore = components.store,
                waitingForNetworkIdsFlow = components.waitingForNetworkIds,
                fileItemDescriptionProvider = UhDefaultFileItemDescriptionProvider(
                    context = applicationContext,
                    fileSizeFormatter = UhDefaultFileSizeFormatter(applicationContext),
                    downloadEstimator = UhDownloadEstimator(DefaultDateTimeProvider()),
                    isWaitingForNetwork = { id -> id in components.waitingForNetworkIds.value },
                ),
                scope = coroutineScope,
            ),
            UhDownloadUIShareMiddleware(applicationContext, downloadFileUtils),
            UhDownloadDeleteMiddleware(
                undoDelay = 4000L,
                removeDownloadUseCase = components.useCases.downloadsUseCases.removeDownload,
                deleteBehaviorProvider = {
                    when (app.harvester.companion.SettingsPrefs.getString(applicationContext, app.harvester.companion.SettingsPrefs.K_DELETE_BEHAVIOR, "ask")) {
                        "device" -> UhDeleteDownloadBehavior.DELETE_FROM_DEVICE
                        "history" -> UhDeleteDownloadBehavior.DELETE_FROM_HISTORY
                        else -> UhDeleteDownloadBehavior.ASK_WHEN_DELETING
                    }
                }
            ),
            UhDownloadsServiceCommunicationMiddleware(applicationContext),
            UhDownloadNavigationMiddleware(navControllerProvider),
            UhDownloadUIRenameMiddleware(
                applicationContext = applicationContext,
                browserStore = components.store,
                scope = coroutineScope,
                downloadFileUtils = downloadFileUtils,
            )
        )
    }
}
