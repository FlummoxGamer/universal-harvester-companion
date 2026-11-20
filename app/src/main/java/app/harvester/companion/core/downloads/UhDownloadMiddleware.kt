package app.harvester.companion.core.downloads

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.harvester.companion.data.downloads.UhDownloadStorage
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.action.DownloadAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.selector.findTabOrCustomTab
import mozilla.components.browser.state.selector.getNormalOrPrivateTabs
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.feature.downloads.AbstractFetchDownloadService.Companion.ACTION_REMOVE_PRIVATE_DOWNLOAD
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import mozilla.components.support.utils.DownloadFileUtils

class UhDownloadMiddleware(
    private val applicationContext: Context,
    private val downloadServiceClass: Class<*>,
    private val deleteFileFromStorage: () -> Boolean,
    private val downloadFileUtils: DownloadFileUtils,
    private val downloadStorage: UhDownloadStorage = UhDownloadStorage(applicationContext),
    private val isNetworkAvailableProvider: () -> Boolean = { true },
    private val isWaitingForNetworkProvider: (String) -> Boolean = { false },
    private val onMarkedWaitingForNetwork: (String) -> Unit = {},
    private val onClearedWaitingForNetwork: (String) -> Unit = {},
) : Middleware<BrowserState, BrowserAction> {

    private val scope = CoroutineScope(
        Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            app.harvester.companion.UHLog.e("download", "DB Error: ${throwable.message}")
        }
    )

    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        var effectiveAction = action
        when (action) {
            is DownloadAction.RemoveDownloadAction -> {
                onClearedWaitingForNetwork(action.downloadId)
                removeDownload(action.downloadId, action.removeFromDisk, store)
            }
            is DownloadAction.RemoveAllDownloadsAction -> removeDownloads()
            is DownloadAction.UpdateDownloadAction -> {
                val dl = action.download
                val updated = if (
                    dl.status == DownloadState.Status.FAILED &&
                    (!isNetworkAvailableProvider() || isWaitingForNetworkProvider(dl.id))
                ) {
                    onMarkedWaitingForNetwork(dl.id)
                    dl.copy(status = DownloadState.Status.PAUSED)
                } else {
                    if (
                        dl.status == DownloadState.Status.COMPLETED ||
                        dl.status == DownloadState.Status.CANCELLED ||
                        (dl.status == DownloadState.Status.DOWNLOADING && isNetworkAvailableProvider())
                    ) {
                        onClearedWaitingForNetwork(dl.id)
                    }
                    dl
                }
                effectiveAction = DownloadAction.UpdateDownloadAction(updated)
                updateDownload(updated, store)
            }
            is DownloadAction.RestoreDownloadsStateAction -> restoreDownloads(store)
            is DownloadAction.RemoveDeletedDownloads -> removeDeletedDownloads(store)
            is ContentAction.CancelDownloadAction -> closeDownloadResponse(store, action.sessionId)
            is DownloadAction.AddDownloadAction -> {
                if (!action.download.private && !saveDownload(store, action.download)) {
                    return
                }
            }
            else -> {}
        }

        next(effectiveAction)

        when (effectiveAction) {
            is TabListAction.RemoveAllTabsAction,
            is TabListAction.RemoveAllPrivateTabsAction -> removePrivateNotifications(store)
            is TabListAction.RemoveTabsAction,
            is TabListAction.RemoveTabAction -> {
                val privateTabs = store.state.getNormalOrPrivateTabs(private = true)
                if (privateTabs.isEmpty()) {
                    removePrivateNotifications(store)
                }
            }
            is DownloadAction.AddDownloadAction -> sendDownloadIntent(effectiveAction.download)
            is DownloadAction.RestoreDownloadStateAction -> sendDownloadIntent(effectiveAction.download)
            else -> {}
        }
    }

    private fun removeDownload(downloadId: String, removeFromDisk: Boolean?, store: Store<BrowserState, BrowserAction>) {
        val downloadToDelete = store.state.downloads[downloadId] ?: return
        val shouldDeleteFromDisk = removeFromDisk ?: deleteFileFromStorage()
        removeDownload(downloadToDelete, shouldDeleteFromDisk)
    }

    private fun removeDownload(downloadToDelete: DownloadState, removeFromDisk: Boolean) {
        scope.launch {
            if (removeFromDisk) {
                removeFileFromStorage(downloadToDelete)
            }
            downloadStorage.remove(downloadToDelete)
        }
    }

    private fun downloadExists(download: DownloadState): Boolean {
        return if (download.directoryPath.startsWith("content://")) {
            DownloadLocationResolver.safFileExists(applicationContext, download.directoryPath, download.fileName)
        } else {
            downloadFileUtils.fileExists(directoryPath = download.directoryPath, fileName = download.fileName)
        }
    }

    private fun removeFileFromStorage(download: DownloadState) {
        if (download.directoryPath.startsWith("content://")) {
            if (!DownloadLocationResolver.deleteSafFile(applicationContext, download.directoryPath, download.fileName)) {
                app.harvester.companion.UHLog.w("download_warn", "Failed to delete SAF download file: ${download.fileName}")
            }
            return
        }
        val fileExists = downloadFileUtils.fileExists(directoryPath = download.directoryPath, fileName = download.fileName)
        if (!fileExists) return
        downloadFileUtils.deleteMediaFile(
            contentResolver = applicationContext.contentResolver,
            directoryPath = download.directoryPath,
            fileName = download.fileName,
        )
    }

    private fun removeDownloads() = scope.launch {
        downloadStorage.removeAllDownloads()
    }

    private fun updateDownload(updated: DownloadState, store: Store<BrowserState, BrowserAction>) {
        if (updated.private) return
        store.state.downloads[updated.id]?.let { old ->
            if (!UhDownloadStorage.isSameDownload(old, updated)) {
                scope.launch { downloadStorage.update(updated) }
            }
        }
    }

    private fun removeDeletedDownloads(store: Store<BrowserState, BrowserAction>) = scope.launch {
        downloadStorage.getDownloadsList()
            .filter {
                (it.status == DownloadState.Status.COMPLETED || it.status == DownloadState.Status.CANCELLED) &&
                    !downloadExists(it)
            }
            .forEach { download -> store.dispatch(DownloadAction.RemoveDownloadAction(download.id)) }
    }

    private fun restoreDownloads(store: Store<BrowserState, BrowserAction>) = scope.launch {
        downloadStorage.getDownloadsList().forEach { download ->
            val canBeMissingPartial = download.status == DownloadState.Status.DOWNLOADING ||
                download.status == DownloadState.Status.PAUSED ||
                download.status == DownloadState.Status.INITIATED
            if (!downloadExists(download) && !canBeMissingPartial) {
                downloadStorage.remove(download)
            } else if (!store.state.downloads.containsKey(download.id) && !download.private) {
                val restored = if (download.status == DownloadState.Status.DOWNLOADING) {
                    if (!isNetworkAvailableProvider()) {
                        onMarkedWaitingForNetwork(download.id)
                    }
                    download.copy(status = DownloadState.Status.PAUSED)
                } else {
                    download
                }
                store.dispatch(DownloadAction.RestoreDownloadStateAction(restored))
            }
        }
    }

    private fun saveDownload(store: Store<BrowserState, BrowserAction>, download: DownloadState): Boolean {
        return if (!store.state.downloads.containsKey(download.id) && !download.private) {
            scope.launch { downloadStorage.add(download) }
            true
        } else false
    }

    private fun closeDownloadResponse(store: Store<BrowserState, BrowserAction>, tabId: String) {
        store.state.findTabOrCustomTab(tabId)?.let {
            it.content.download?.response?.close()
        }
    }

    private fun sendDownloadIntent(download: DownloadState) {
        if (download.status !in arrayOf(
                DownloadState.Status.COMPLETED,
                DownloadState.Status.CANCELLED,
                DownloadState.Status.FAILED,
                DownloadState.Status.PAUSED,
            )
        ) {
            val intent = Intent(applicationContext, downloadServiceClass).apply {
                putExtra("downloadId", download.id)
                putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, download.id)
                putExtra("INTENT_EXTRA_DOWNLOAD_ID", download.id)
            }
            try {
                ContextCompat.startForegroundService(applicationContext, intent)
            } catch (e: Exception) {
                app.harvester.companion.UHLog.throwable("download_warn", "Unable to start foreground download service for ${download.id}", e)
            }
        }
    }

    private fun removeStatusBarNotification(store: Store<BrowserState, BrowserAction>, download: DownloadState) {
        download.notificationId?.let {
            val intent = Intent(applicationContext, downloadServiceClass)
            intent.action = ACTION_REMOVE_PRIVATE_DOWNLOAD
            intent.putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, download.id)
            applicationContext.startService(intent)
            store.dispatch(DownloadAction.DismissDownloadNotificationAction(download.id))
        }
    }

    private fun removePrivateNotifications(store: Store<BrowserState, BrowserAction>) {
        store.state.downloads.filterValues { it.private }.forEach { removeStatusBarNotification(store, it.value) }
    }
}
