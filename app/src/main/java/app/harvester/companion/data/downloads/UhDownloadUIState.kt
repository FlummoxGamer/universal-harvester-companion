package app.harvester.companion.data.downloads

import mozilla.components.lib.state.State

data class UhDownloadUIState(
    val items: List<UhFileItem>,
    val mode: Mode,
    val pendingDeletionIds: Set<String>,
    val unconfirmedDeletionIds: Set<String> = emptySet(),
    val searchQuery: String = "",
    val fileToRename: UhFileItem? = null,
    val renameFileError: UhRenameFileError? = null,
    val isChangeFileExtensionDialogVisible: Boolean = false,
    val itemToChangeExtension: UhFileItem? = null,
    val dialogState: DialogState = DialogState.None,
    val deletionSnackbarState: SnackbarState = SnackbarState.None,
    val sortingMode: SortingMode = SortingMode.DATE,
    private val isSearchFieldRequested: Boolean = false,
    private val userSelectedContentTypeFilter: UhFileItem.ContentTypeFilter = UhFileItem.ContentTypeFilter.All,
) : State {

    private val visibleItems = items.filter {
        it.id !in pendingDeletionIds && it.id !in unconfirmedDeletionIds
    }.let { filtered ->
        when (sortingMode) {
            SortingMode.DATE -> filtered.sortedByDescending { it.description }
            SortingMode.NAME -> filtered.sortedBy { it.fileName?.lowercase() }
            SortingMode.SIZE -> filtered.sortedByDescending { it.description }
        }
    }

    val selectedContentTypeFilter: UhFileItem.ContentTypeFilter
        get() {
            val selectedTypeContainsItems =
                visibleItems
                    .filter {
                        userSelectedContentTypeFilter == UhFileItem.ContentTypeFilter.All ||
                            it.status == UhFileItem.Status.Completed
                    }
                    .any { download -> userSelectedContentTypeFilter.predicate(download.contentType) }

            return if (selectedTypeContainsItems) userSelectedContentTypeFilter else UhFileItem.ContentTypeFilter.All
        }

    val itemsMatchingFilters =
        visibleItems
            .filter {
                selectedContentTypeFilter == UhFileItem.ContentTypeFilter.All || it.status == UhFileItem.Status.Completed
            }
            .filter { selectedContentTypeFilter.predicate(it.contentType) }
            .filter { it.stringToMatchForSearchQuery.contains(searchQuery, ignoreCase = true) }

    private val itemsToDisplay: List<UhDownloadListItem> =
        itemsMatchingFilters
            .groupBy { it.timeCategory }
            .toSortedMap()
            .flatMap { (createdTime, fileItems) ->
                listOf(UhHeaderItem(createdTime)) + fileItems
            }

    private val matchingFilters: List<UhFileItem.ContentTypeFilter> =
        visibleItems
            .filter { it.status == UhFileItem.Status.Completed }
            .map { it.matchingContentTypeFilter }
            .distinct()
            .sorted()

    val filtersToDisplay: List<UhFileItem.ContentTypeFilter> =
        if (matchingFilters.size > 1) {
            listOf(UhFileItem.ContentTypeFilter.All) + matchingFilters
        } else {
            emptyList()
        }

    val itemsState: ItemsState =
        when {
            itemsToDisplay.isEmpty() && searchQuery.isNotEmpty() -> ItemsState.NoSearchResults
            itemsToDisplay.isEmpty() -> ItemsState.NoItems
            else -> ItemsState.Items(itemsToDisplay)
        }

    val isSearchFieldVisible: Boolean get() = isSearchFieldRequested && mode is Mode.Normal
    val isSearchIconVisible: Boolean get() = visibleItems.isNotEmpty() && !isSearchFieldVisible && mode is Mode.Normal
    val isSettingsIconVisible: Boolean get() = !isSearchFieldVisible && mode is Mode.Normal
    val isBackHandlerEnabled: Boolean get() = isSearchFieldRequested || mode is Mode.Editing

    companion object {
        val INITIAL = UhDownloadUIState(
            items = emptyList(),
            mode = Mode.Normal,
            pendingDeletionIds = emptySet(),
            unconfirmedDeletionIds = emptySet(),
            userSelectedContentTypeFilter = UhFileItem.ContentTypeFilter.All,
        )
    }

    sealed interface ItemsState {
        data object NoItems : ItemsState
        data object NoSearchResults : ItemsState
        data class Items(val items: List<UhDownloadListItem>) : ItemsState
    }

    sealed class Mode {
        open val selectedItems = emptySet<UhFileItem>()
        data object Normal : Mode()
        data class Editing(override val selectedItems: Set<UhFileItem>) : Mode()
    }

    sealed interface SnackbarState {
        data object None : SnackbarState
        data class UndoDeletion(val items: Set<UhFileItem>) : SnackbarState
    }

    sealed interface DialogState {
        data object None : DialogState
        data class DeleteConfirmation(val items: Set<UhFileItem>) : DialogState
        data class MultiSelectDeleteConfirmation(val items: Set<UhFileItem>) : DialogState
    }

    enum class SortingMode {
        DATE, NAME, SIZE
    }
}
