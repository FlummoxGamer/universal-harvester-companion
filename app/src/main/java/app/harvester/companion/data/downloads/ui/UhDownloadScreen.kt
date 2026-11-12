package app.harvester.companion.data.downloads.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.data.downloads.UhDownloadListItem
import app.harvester.companion.data.downloads.UhDownloadUIAction
import app.harvester.companion.data.downloads.UhDownloadUIState
import app.harvester.companion.data.downloads.UhDownloadUIStore
import app.harvester.companion.data.downloads.UhFileItem
import app.harvester.companion.data.downloads.UhHeaderItem
import app.harvester.companion.data.downloads.ui.components.UhDownloadFilters
import app.harvester.companion.data.downloads.ui.components.UhDownloadRenameDialog
import app.harvester.companion.data.downloads.ui.components.UhDownloadSearchBar
import app.harvester.companion.theme.UHTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UhDownloadScreen(
    downloadsStore: UhDownloadUIStore,
    onItemClick: (UhFileItem) -> Unit,
    onSettingsClick: () -> Unit,
    onBackClick: () -> Unit
) {
    val uiState by downloadsStore.stateFlow.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.download_undo_delete_snackbar_action)

LaunchedEffect(uiState.deletionSnackbarState) {
    val state = uiState.deletionSnackbarState
    if (state is UhDownloadUIState.SnackbarState.UndoDeletion) {
        val result = snackbarHostState.showSnackbar(
            message = "Downloads deleted",
            actionLabel = undoLabel
        )
        if (result == SnackbarResult.ActionPerformed) {
            downloadsStore.dispatch(UhDownloadUIAction.UndoPendingDeletion)
        }
    }
}
    
    BackHandler(uiState.isBackHandlerEnabled) {
        if (uiState.mode is UhDownloadUIState.Mode.Editing) {
            downloadsStore.dispatch(UhDownloadUIAction.ExitEditMode)
        } else if (uiState.isSearchFieldVisible) {
            downloadsStore.dispatch(UhDownloadUIAction.SearchBarDismissRequest)
        }
    }

    when (val dialog = uiState.dialogState) {
        is UhDownloadUIState.DialogState.DeleteConfirmation -> {
            AlertDialog(
                onDismissRequest = { downloadsStore.dispatch(UhDownloadUIAction.DismissDeleteDialog) },
                title = { Text(pluralStringResource(R.plurals.downloads_delete_dialog_title, dialog.items.size, dialog.items.size)) },
                text = { Text(stringResource(R.string.download_delete_multi_select_dialog_confirmation)) },
                confirmButton = {
                    TextButton(onClick = { downloadsStore.dispatch(UhDownloadUIAction.AddPendingDeletionSet(dialog.items, true)) }) {
                        Text(stringResource(R.string.download_delete_multi_select_dialog_confirm), color = colorResource(id = R.color.red))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { downloadsStore.dispatch(UhDownloadUIAction.DismissDeleteDialog) }) {
                        Text(stringResource(R.string.download_delete_multi_select_dialog_cancel), color = colorResource(id = R.color.subtext))
                    }
                }
            )
        }
        is UhDownloadUIState.DialogState.MultiSelectDeleteConfirmation -> {
            AlertDialog(
                onDismissRequest = { downloadsStore.dispatch(UhDownloadUIAction.DismissDeleteDialog) },
                title = { Text(pluralStringResource(R.plurals.downloads_delete_dialog_title, dialog.items.size, dialog.items.size)) },
                text = { Text(stringResource(R.string.download_delete_multi_select_dialog_confirmation)) },
                confirmButton = {
                    TextButton(onClick = { downloadsStore.dispatch(UhDownloadUIAction.ConfirmMultiSelectDelete(dialog.items)) }) {
                        Text(stringResource(R.string.download_delete_multi_select_dialog_confirm), color = colorResource(id = R.color.red))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { downloadsStore.dispatch(UhDownloadUIAction.DismissDeleteDialog) }) {
                        Text(stringResource(R.string.download_delete_multi_select_dialog_cancel), color = colorResource(id = R.color.subtext))
                    }
                }
            )
        }
        else -> {}
    }

    if (uiState.fileToRename != null) {
        UhDownloadRenameDialog(
            originalName = uiState.fileToRename!!.fileName ?: "download",
            onDismiss = { downloadsStore.dispatch(UhDownloadUIAction.RenameFileDismissed) },
            onConfirm = { newName -> downloadsStore.dispatch(UhDownloadUIAction.RenameFileConfirmed(uiState.fileToRename!!, newName)) }
        )
    }

    UHTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                Column(modifier = Modifier.statusBarsPadding()) {
                    if (uiState.isSearchFieldVisible) {
                        UhDownloadSearchBar(
                            query = uiState.searchQuery,
                            onQueryChanged = { downloadsStore.dispatch(UhDownloadUIAction.SearchQueryEntered(it)) },
                            onDismiss = { downloadsStore.dispatch(UhDownloadUIAction.SearchBarDismissRequest) }
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colorResource(id = R.color.surface))
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (uiState.mode is UhDownloadUIState.Mode.Editing) {
                                IconButton(onClick = { downloadsStore.dispatch(UhDownloadUIAction.ExitEditMode) }) {
                                    Icon(painterResource(R.drawable.ic_close), "Close")
                                }
                                Text(
                                    text = stringResource(R.string.download_multi_select_title, uiState.mode.selectedItems.size),
                                    color = colorResource(id = R.color.text),
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = {
                                    downloadsStore.dispatch(UhDownloadUIAction.RequestDeleteMultiple(uiState.mode.selectedItems))
                                }) {
                                    Icon(painterResource(R.drawable.ic_delete), "Delete", tint = colorResource(id = R.color.red))
                                }
                            } else {
                                IconButton(onClick = onBackClick) {
                                    Icon(painterResource(R.drawable.ic_back), stringResource(R.string.download_navigate_back_description))
                                }
                                Text(
                                    text = stringResource(R.string.library_downloads),
                                    color = colorResource(id = R.color.text),
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { downloadsStore.dispatch(UhDownloadUIAction.SearchBarVisibilityRequest) }) {
                                    Icon(painterResource(R.drawable.ic_search), stringResource(R.string.download_search_placeholder))
                                }
                                
                                IconButton(onClick = {
                                    downloadsStore.dispatch(UhDownloadUIAction.RequestDeleteMultiple(uiState.items.toSet()))
                                }) {
                                    Icon(painterResource(R.drawable.ic_delete), "Clear All", tint = colorResource(id = R.color.red))
                                }

                                IconButton(onClick = onSettingsClick) {
                                    Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.download_navigate_settings_description))
                                }
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .navigationBarsPadding()
            ) {
                if (uiState.filtersToDisplay.isNotEmpty() && !uiState.isSearchFieldVisible) {
                    UhDownloadFilters(
                        selectedFilter = uiState.selectedContentTypeFilter,
                        filters = uiState.filtersToDisplay,
                        onFilterSelected = { downloadsStore.dispatch(UhDownloadUIAction.ContentTypeSelected(it)) }
                    )
                }

                when (val state = uiState.itemsState) {
                    is UhDownloadUIState.ItemsState.NoItems -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.download_empty_message_2), color = colorResource(id = R.color.subtext))
                        }
                    }
                    is UhDownloadUIState.ItemsState.NoSearchResults -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.download_search_no_results_found), color = colorResource(id = R.color.subtext))
                        }
                    }
                    is UhDownloadUIState.ItemsState.Items -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(state.items) { item ->
                                when (item) {
                                    is UhHeaderItem -> {
                                        Text(
                                            text = stringResource(id = item.timeCategory.stringRes),
                                            color = colorResource(id = R.color.subtext),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(16.dp)
                                        )
                                    }
                                    is UhFileItem -> {
                                        val isSelected = uiState.mode.selectedItems.contains(item)
                                        UhDownloadListItem(
                                            fileItem = item,
                                            isSelected = isSelected,
                                            areIconsVisible = uiState.mode is UhDownloadUIState.Mode.Normal,
                                            swipingEnabled = uiState.mode is UhDownloadUIState.Mode.Normal,
                                            onClick = {
                                                if (uiState.mode is UhDownloadUIState.Mode.Editing) {
                                                    if (isSelected) downloadsStore.dispatch(UhDownloadUIAction.RemoveItemForRemoval(item))
                                                    else downloadsStore.dispatch(UhDownloadUIAction.AddItemForRemoval(item))
                                                } else {
                                                    onItemClick(item)
                                                }
                                            },
                                            onLongClick = {
                                                downloadsStore.dispatch(UhDownloadUIAction.AddItemForRemoval(item))
                                            },
                                            onPauseClick = { downloadsStore.dispatch(UhDownloadUIAction.PauseDownload(item.id)) },
                                            onResumeClick = { downloadsStore.dispatch(UhDownloadUIAction.ResumeDownload(item.id)) },
                                            onRetryClick = { downloadsStore.dispatch(UhDownloadUIAction.RetryDownload(item.id)) },
                                            onDeleteClick = { downloadsStore.dispatch(UhDownloadUIAction.RequestDelete(item)) },
                                            onShareUrlClick = { downloadsStore.dispatch(UhDownloadUIAction.ShareUrlClicked(item.url)) },
                                            onShareFileClick = { downloadsStore.dispatch(UhDownloadUIAction.ShareFileClicked(item.directoryPath, item.fileName, item.contentType)) },
                                            onRenameClick = { downloadsStore.dispatch(UhDownloadUIAction.RenameFileClicked(item)) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
