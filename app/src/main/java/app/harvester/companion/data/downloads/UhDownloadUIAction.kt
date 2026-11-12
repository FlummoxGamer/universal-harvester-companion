package app.harvester.companion.data.downloads

import mozilla.components.lib.state.Action

sealed interface UhDownloadUIAction : Action {
    data object Init : UhDownloadUIAction
    data object ExitEditMode : UhDownloadUIAction
    data class AddItemForRemoval(val item: UhFileItem) : UhDownloadUIAction
    data object AddAllItemsForRemoval : UhDownloadUIAction
    data class RemoveItemForRemoval(val item: UhFileItem) : UhDownloadUIAction
    data class AddPendingDeletionSet(val items: Set<UhFileItem>, val removeFromDisk: Boolean) : UhDownloadUIAction
    data object UndoPendingDeletion : UhDownloadUIAction
    data class UndoPendingDeletionSet(val itemIds: Set<String>) : UhDownloadUIAction
    data object FileItemDeletedSuccessfully : UhDownloadUIAction
    data class UpdateFileItems(val items: List<UhFileItem>) : UhDownloadUIAction
    data class ContentTypeSelected(val contentTypeFilter: UhFileItem.ContentTypeFilter) : UhDownloadUIAction
    data class ShareUrlClicked(val url: String) : UhDownloadUIAction
    data class ShareFileClicked(val directoryPath: String, val fileName: String?, val contentType: String?) : UhDownloadUIAction
    data class RenameFileClicked(val item: UhFileItem) : UhDownloadUIAction
    data class RenameFileConfirmed(val item: UhFileItem, val newName: String) : UhDownloadUIAction
    data object RenameFileDismissed : UhDownloadUIAction
    data class FileExtensionChangedByUser(val item: UhFileItem, val newName: String) : UhDownloadUIAction
    data class ShowChangeFileExtensionDialog(val item: UhFileItem) : UhDownloadUIAction
    data object CloseChangeFileExtensionDialog : UhDownloadUIAction
    data class RenameFileFailed(val error: UhRenameFileError) : UhDownloadUIAction
    data object RenameFileFailureDismissed : UhDownloadUIAction
    data class SearchQueryEntered(val searchQuery: String) : UhDownloadUIAction
    data object SearchBarVisibilityRequest : UhDownloadUIAction
    data object SearchBarDismissRequest : UhDownloadUIAction
    data object ClearAllClicked : UhDownloadUIAction
    data class PauseDownload(val downloadId: String) : UhDownloadUIAction
    data class ResumeDownload(val downloadId: String) : UhDownloadUIAction
    data class RetryDownload(val downloadId: String) : UhDownloadUIAction
    object SettingsIconClicked : UhDownloadUIAction
    object NavigationIconClicked : UhDownloadUIAction
    data class RequestDeleteMultiple(val items: Set<UhFileItem>) : UhDownloadUIAction
    data class RequestDelete(val item: UhFileItem, val isSwipe: Boolean = false) : UhDownloadUIAction
    data class CancelDownload(val downloadId: String) : UhDownloadUIAction
    data class ShowDeleteDialog(val items: Set<UhFileItem>) : UhDownloadUIAction
    data object DismissDeleteDialog : UhDownloadUIAction
    data class ShowMultiSelectDeleteDialog(val items: Set<UhFileItem>) : UhDownloadUIAction
    data class ConfirmMultiSelectDelete(val items: Set<UhFileItem>) : UhDownloadUIAction
}

sealed interface UhRenameFileError {
    data class NameAlreadyExists(val proposedFileName: String) : UhRenameFileError
    data class CaseOnlyNameChange(val proposedFileName: String) : UhRenameFileError
    data object InvalidFileName : UhRenameFileError
    data object CannotRename : UhRenameFileError
}
