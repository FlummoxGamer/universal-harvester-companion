package app.harvester.companion.data.downloads.middleware

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.action.DownloadAction
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import mozilla.components.support.utils.DownloadFileUtils
import app.harvester.companion.data.downloads.UhDownloadUIAction
import app.harvester.companion.data.downloads.UhDownloadUIState
import app.harvester.companion.data.downloads.UhFileItem
import app.harvester.companion.data.downloads.UhRenameFileError

class UhDownloadUIRenameMiddleware(
    private val browserStore: BrowserStore,
    private val scope: CoroutineScope,
    private val downloadFileUtils: DownloadFileUtils,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : Middleware<UhDownloadUIState, UhDownloadUIAction> {

    override fun invoke(
        store: Store<UhDownloadUIState, UhDownloadUIAction>,
        next: (UhDownloadUIAction) -> Unit,
        action: UhDownloadUIAction,
    ) {
        next(action)

        when (action) {
            is UhDownloadUIAction.RenameFileConfirmed -> processFileRenaming(store, action.item, action.newName)
            is UhDownloadUIAction.FileExtensionChangedByUser -> {
                val previousName = action.item.fileName ?: return
                val originalExtension = File(previousName.trim()).extension.lowercase()
                val proposedExtension = File(action.newName.trim()).extension.lowercase()

                if (proposedExtension != originalExtension && store.state.itemToChangeExtension?.fileName == null) {
                    store.dispatch(UhDownloadUIAction.ShowChangeFileExtensionDialog(action.item))
                } else {
                    store.dispatch(UhDownloadUIAction.CloseChangeFileExtensionDialog)
                    store.dispatch(UhDownloadUIAction.RenameFileConfirmed(action.item, action.newName))
                }
            }
            else -> {}
        }
    }

    private suspend fun dispatchAction(
        uiStore: Store<UhDownloadUIState, UhDownloadUIAction>,
        action: UhDownloadUIAction,
    ) = withContext(mainDispatcher) { uiStore.dispatch(action) }

    private fun processFileRenaming(
        uiStore: Store<UhDownloadUIState, UhDownloadUIAction>,
        item: UhFileItem,
        newName: String,
    ) {
        scope.launch {
            val download = browserStore.state.downloads[item.id]
            val currentName = download?.fileName

            if (download == null || currentName.isNullOrBlank()) {
                dispatchAction(uiStore, UhDownloadUIAction.RenameFileFailed(UhRenameFileError.CannotRename))
                return@launch
            }

            val newNameTrimmed = newName.trim()
            getRenameConflictError(download.directoryPath, currentName, newNameTrimmed)?.let { error ->
                dispatchAction(uiStore, UhDownloadUIAction.RenameFileFailed(error))
                return@launch
            }

            val attemptFileRename = downloadFileUtils.renameFile(
                directoryPath = download.directoryPath,
                oldName = download.fileName,
                newName = newNameTrimmed,
            )

            if (!attemptFileRename) {
                dispatchAction(uiStore, UhDownloadUIAction.RenameFileFailed(UhRenameFileError.CannotRename))
                return@launch
            } else {
                uiStore.dispatch(UhDownloadUIAction.RenameFileDismissed)
            }

            withContext(mainDispatcher) {
                val updated = download.copy(fileName = newNameTrimmed)
                browserStore.dispatch(DownloadAction.UpdateDownloadAction(updated))
            }
        }
    }

    private fun getRenameConflictError(
        directoryPath: String,
        currentName: String,
        newName: String,
    ): UhRenameFileError? {
        if (downloadFileUtils.fileExists(directoryPath, newName)) {
            return if (newName.equals(currentName, ignoreCase = true)) {
                UhRenameFileError.CaseOnlyNameChange(newName)
            } else {
                UhRenameFileError.NameAlreadyExists(newName)
            }
        }
        return null
    }
}
