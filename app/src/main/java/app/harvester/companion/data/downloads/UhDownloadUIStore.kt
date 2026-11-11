package app.harvester.companion.data.downloads

import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import app.harvester.companion.data.downloads.UhDownloadUIState.Mode

class UhDownloadUIStore(
    initialState: UhDownloadUIState,
    middleware: List<Middleware<UhDownloadUIState, UhDownloadUIAction>> = emptyList(),
) : Store<UhDownloadUIState, UhDownloadUIAction>(
    initialState = initialState,
    reducer = ::downloadStateReducer,
    middleware = middleware,
) {
    init {
        dispatch(UhDownloadUIAction.Init)
    }
}

private fun downloadStateReducer(
    state: UhDownloadUIState,
    action: UhDownloadUIAction,
): UhDownloadUIState {
    return when (action) {
        is UhDownloadUIAction.AddItemForRemoval -> state.copy(mode = Mode.Editing(state.mode.selectedItems + action.item))
        is UhDownloadUIAction.AddAllItemsForRemoval -> {
            state.copy(mode = Mode.Editing(selectedItems = state.itemsMatchingFilters.filter { it.status == UhFileItem.Status.Completed }.toSet()))
        }
        is UhDownloadUIAction.RemoveItemForRemoval -> {
            val selected = state.mode.selectedItems - action.item
            state.copy(mode = if (selected.isEmpty()) Mode.Normal else Mode.Editing(selected))
        }
        is UhDownloadUIAction.ExitEditMode -> state.copy(mode = Mode.Normal)
        is UhDownloadUIAction.AddPendingDeletionSet ->
            state.copy(
                pendingDeletionIds = state.pendingDeletionIds + action.items.map { it.id }.toSet(),
                deletionSnackbarState = UhDownloadUIState.SnackbarState.UndoDeletion(action.items),
                dialogState = UhDownloadUIState.DialogState.None,
                mode = Mode.Normal,
                unconfirmedDeletionIds = state.unconfirmedDeletionIds - action.items.map { it.id }.toSet(),
            )
        is UhDownloadUIAction.UndoPendingDeletionSet ->
            state.copy(
                pendingDeletionIds = state.pendingDeletionIds - action.itemIds,
                deletionSnackbarState = UhDownloadUIState.SnackbarState.None,
            )
        is UhDownloadUIAction.UpdateFileItems -> state.copy(items = action.items)
        is UhDownloadUIAction.ContentTypeSelected -> state.copy(userSelectedContentTypeFilter = action.contentTypeFilter)
        is UhDownloadUIAction.FileItemDeletedSuccessfully -> state.copy(deletionSnackbarState = UhDownloadUIState.SnackbarState.None)
        is UhDownloadUIAction.SearchQueryEntered -> state.copy(searchQuery = action.searchQuery)
        UhDownloadUIAction.Init -> state
        is UhDownloadUIAction.ShareUrlClicked -> state
        is UhDownloadUIAction.ShareFileClicked -> state
        is UhDownloadUIAction.RenameFileClicked -> state.copy(fileToRename = action.item)
        is UhDownloadUIAction.RenameFileConfirmed -> state
        is UhDownloadUIAction.RenameFileDismissed ->
            state.copy(
                fileToRename = null,
                renameFileError = null,
                isChangeFileExtensionDialogVisible = false,
                itemToChangeExtension = null,
            )
        is UhDownloadUIAction.RenameFileFailed -> state.copy(renameFileError = action.error)
        is UhDownloadUIAction.RenameFileFailureDismissed -> state.copy(renameFileError = null)
        is UhDownloadUIAction.FileExtensionChangedByUser -> state
        is UhDownloadUIAction.ShowChangeFileExtensionDialog ->
            state.copy(
                isChangeFileExtensionDialogVisible = true,
                itemToChangeExtension = action.item,
            )
        is UhDownloadUIAction.CloseChangeFileExtensionDialog -> state.copy(isChangeFileExtensionDialogVisible = false)
        is UhDownloadUIAction.UndoPendingDeletion -> state
        is UhDownloadUIAction.PauseDownload -> state
        is UhDownloadUIAction.ResumeDownload -> state
        is UhDownloadUIAction.RetryDownload -> state
        is UhDownloadUIAction.CancelDownload ->
            state.copy(
                unconfirmedDeletionIds = state.unconfirmedDeletionIds - action.downloadId,
                pendingDeletionIds = state.pendingDeletionIds - action.downloadId,
            )
        is UhDownloadUIAction.NavigationIconClicked -> state
        is UhDownloadUIAction.SettingsIconClicked -> state
        is UhDownloadUIAction.RequestDeleteMultiple -> state
        is UhDownloadUIAction.RequestDelete ->
            if (action.isSwipe) state.copy(unconfirmedDeletionIds = state.unconfirmedDeletionIds + action.item.id) else state
        is UhDownloadUIAction.ShowDeleteDialog -> state.copy(dialogState = UhDownloadUIState.DialogState.DeleteConfirmation(action.items))
        is UhDownloadUIAction.DismissDeleteDialog -> {
            val itemsToRestore =
                when (val dialogState = state.dialogState) {
                    is UhDownloadUIState.DialogState.DeleteConfirmation -> dialogState.items
                    is UhDownloadUIState.DialogState.MultiSelectDeleteConfirmation -> dialogState.items
                    else -> emptySet()
                }.map { it.id }.toSet()
            state.copy(
                dialogState = UhDownloadUIState.DialogState.None,
                unconfirmedDeletionIds = state.unconfirmedDeletionIds - itemsToRestore,
            )
        }
        is UhDownloadUIAction.SearchBarDismissRequest ->
            state.copy(
                isSearchFieldRequested = false,
                searchQuery = "",
            )
        is UhDownloadUIAction.SearchBarVisibilityRequest -> state.copy(isSearchFieldRequested = true)
        is UhDownloadUIAction.SortingModeSelected -> state.copy(sortingMode = action.mode)
        is UhDownloadUIAction.ClearAllClicked -> state
        is UhDownloadUIAction.ShowMultiSelectDeleteDialog -> state.copy(dialogState = UhDownloadUIState.DialogState.MultiSelectDeleteConfirmation(items = action.items))
        is UhDownloadUIAction.ConfirmMultiSelectDelete ->
            state.copy(
                dialogState = UhDownloadUIState.DialogState.None,
                unconfirmedDeletionIds = state.unconfirmedDeletionIds - action.items.map { it.id }.toSet(),
            )
    }
}
