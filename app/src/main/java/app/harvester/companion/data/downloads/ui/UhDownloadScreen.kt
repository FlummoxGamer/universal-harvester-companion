package app.harvester.companion.data.downloads.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.data.downloads.UhDownloadUIAction
import app.harvester.companion.data.downloads.UhDownloadUIState
import app.harvester.companion.data.downloads.UhDownloadUIStore
import app.harvester.companion.data.downloads.UhFileItem
import app.harvester.companion.data.downloads.UhHeaderItem
import app.harvester.companion.data.downloads.ui.components.UhDownloadFilters
import app.harvester.companion.data.downloads.ui.components.UhDownloadRenameDialog
import app.harvester.companion.data.downloads.ui.components.UhDownloadSearchBar
import app.harvester.companion.settings.components.UhWindowHeader
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
                message = "Download removed",
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

    UHTheme {
        when (val dialog = uiState.dialogState) {
            is UhDownloadUIState.DialogState.DeleteConfirmation -> {
                AlertDialog(
                    onDismissRequest = { downloadsStore.dispatch(UhDownloadUIAction.DismissDeleteDialog) },
                    title = { Text(pluralStringResource(R.plurals.downloads_delete_dialog_title, dialog.items.size, dialog.items.size)) },
                    text = { Text(stringResource(R.string.download_delete_multi_select_dialog_confirmation)) },
                    confirmButton = {
                        TextButton(onClick = { downloadsStore.dispatch(UhDownloadUIAction.AddPendingDeletionSet(dialog.items, true)) }) {
                            Text(stringResource(R.string.download_delete_multi_select_dialog_confirm), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { downloadsStore.dispatch(UhDownloadUIAction.DismissDeleteDialog) }) {
                            Text(stringResource(R.string.download_delete_multi_select_dialog_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            }
            is UhDownloadUIState.DialogState.MultiSelectDeleteConfirmation -> {
                AlertDialog(
                    onDismissRequest = { downloadsStore.dispatch(UhDownloadUIAction.DismissDeleteDialog) },
                    title = { Text(pluralStringResource(R.plurals.downloads_delete_dialog_title, dialog.items.size, dialog.items.size)) },
                    text = { Text(stringResource(R.string.download_delete_multi_select_dialog_confirmation)) },
                    confirmButton = {
                        TextButton(onClick = { downloadsStore.dispatch(UhDownloadUIAction.ConfirmMultiSelectDelete(dialog.items)) }) {
                            Text(stringResource(R.string.download_delete_multi_select_dialog_confirm), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { downloadsStore.dispatch(UhDownloadUIAction.DismissDeleteDialog) }) {
                            Text(stringResource(R.string.download_delete_multi_select_dialog_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
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

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                when {
                    uiState.isSearchFieldVisible -> {
                        Box(modifier = Modifier.background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
                            UhDownloadSearchBar(
                                query = uiState.searchQuery,
                                onQueryChanged = { downloadsStore.dispatch(UhDownloadUIAction.SearchQueryEntered(it)) },
                                onDismiss = { downloadsStore.dispatch(UhDownloadUIAction.SearchBarDismissRequest) }
                            )
                        }
                    }
                    uiState.mode is UhDownloadUIState.Mode.Editing -> {
                        UhWindowHeader(
                            title = stringResource(R.string.download_multi_select_title, uiState.mode.selectedItems.size),
                            onBack = { downloadsStore.dispatch(UhDownloadUIAction.ExitEditMode) },
                            backIconRes = R.drawable.fx_ic_close_24,
                            backContentDescription = "Exit selection",
                            actions = {
                                IconButton(onClick = { downloadsStore.dispatch(UhDownloadUIAction.AddAllItemsForRemoval) }) {
                                    Icon(
                                        painter = painterResource(R.drawable.fx_ic_check_24),
                                        contentDescription = stringResource(R.string.download_select_all_description),
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                IconButton(onClick = {
                                    downloadsStore.dispatch(UhDownloadUIAction.RequestDeleteMultiple(uiState.mode.selectedItems))
                                }) {
                                    Icon(
                                        painter = painterResource(R.drawable.fx_ic_delete_24),
                                        contentDescription = stringResource(R.string.download_delete_selected_description),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        )
                    }
                    else -> {
                        UhWindowHeader(
                            title = stringResource(R.string.library_downloads),
                            onBack = onBackClick,
                            backContentDescription = stringResource(R.string.download_navigate_back_description),
                            actions = {
                                if (uiState.isSearchIconVisible) {
                                    IconButton(onClick = { downloadsStore.dispatch(UhDownloadUIAction.SearchBarVisibilityRequest) }) {
                                        Icon(
                                            painter = painterResource(R.drawable.fx_ic_search_24),
                                            contentDescription = stringResource(R.string.download_search_placeholder),
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                                if (uiState.items.isNotEmpty()) {
                                    IconButton(onClick = {
                                        downloadsStore.dispatch(UhDownloadUIAction.RequestDeleteMultiple(uiState.items.toSet()))
                                    }) {
                                        Icon(
                                            painter = painterResource(R.drawable.fx_ic_delete_24),
                                            contentDescription = "Clear All",
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                                IconButton(onClick = onSettingsClick) {
                                    Icon(
                                        painter = painterResource(R.drawable.fx_ic_settings_24),
                                        contentDescription = stringResource(R.string.download_navigate_settings_description),
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        )
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
                        EmptyDownloadsView(
                            iconRes = R.drawable.fx_ic_downloads_24,
                            title = stringResource(R.string.download_empty_message_2),
                            subtitle = "Files you download from websites appear here"
                        )
                    }
                    is UhDownloadUIState.ItemsState.NoSearchResults -> {
                        EmptyDownloadsView(
                            iconRes = R.drawable.fx_ic_search_24,
                            title = stringResource(R.string.download_search_no_results_found),
                            subtitle = "Try searching with a different filename"
                        )
                    }
                    is UhDownloadUIState.ItemsState.Items -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            items(
                                items = state.items,
                                key = { item ->
                                    when (item) {
                                        is UhHeaderItem -> "header_${item.timeCategory.name}"
                                        is UhFileItem -> "file_${item.id}"
                                        else -> item.hashCode().toString()
                                    }
                                }
                            ) { item ->
                                when (item) {
                                    is UhHeaderItem -> {
                                        Text(
                                            text = stringResource(id = item.timeCategory.stringRes),
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp)
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

@Composable
private fun EmptyDownloadsView(
    iconRes: Int,
    title: String,
    subtitle: String,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
    }
}
