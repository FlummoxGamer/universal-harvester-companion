package app.harvester.companion.data.downloads.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
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
import app.harvester.companion.theme.UHTheme

@Composable
fun UhDownloadsScreen(
    downloadsStore: UhDownloadUIStore,
    onItemClick: (UhFileItem) -> Unit,
    onSettingsClick: () -> Unit,
    onBackClick: () -> Unit
) {
    val uiState by downloadsStore.stateFlow.collectAsState()
    
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
                title = { Text("Delete download?") },
                text = { Text("This will remove the file from your device.") },
                confirmButton = {
                    TextButton(onClick = { downloadsStore.dispatch(UhDownloadUIAction.AddPendingDeletionSet(dialog.items, true)) }) {
                        Text("Delete", color = colorResource(id = R.color.red))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { downloadsStore.dispatch(UhDownloadUIAction.DismissDeleteDialog) }) {
                        Text("Cancel", color = colorResource(id = R.color.subtext))
                    }
                }
            )
        }
        is UhDownloadUIState.DialogState.MultiSelectDeleteConfirmation -> {
            AlertDialog(
                onDismissRequest = { downloadsStore.dispatch(UhDownloadUIAction.DismissDeleteDialog) },
                title = { Text("Delete ${dialog.items.size} downloads?") },
                text = { Text("This will remove the files from your device.") },
                confirmButton = {
                    TextButton(onClick = { downloadsStore.dispatch(UhDownloadUIAction.ConfirmMultiSelectDelete(dialog.items)) }) {
                        Text("Delete", color = colorResource(id = R.color.red))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { downloadsStore.dispatch(UhDownloadUIAction.DismissDeleteDialog) }) {
                        Text("Cancel", color = colorResource(id = R.color.subtext))
                    }
                }
            )
        }
        else -> {}
    }

    UHTheme {
        Scaffold(
            topBar = {
                if (uiState.isSearchFieldVisible) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colorResource(id = R.color.surface))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { downloadsStore.dispatch(UhDownloadUIAction.SearchBarDismissRequest) }) {
                            Icon(painterResource(R.drawable.ic_back), "Back")
                        }
                        OutlinedTextField(
                            value = uiState.searchQuery,
                            onValueChange = { downloadsStore.dispatch(UhDownloadUIAction.SearchQueryEntered(it)) },
                            placeholder = { Text("Search downloads") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
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
                                text = "${uiState.mode.selectedItems.size} selected",
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
                                Icon(painterResource(R.drawable.ic_back), "Back")
                            }
                            Text(
                                text = "Downloads",
                                color = colorResource(id = R.color.text),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { downloadsStore.dispatch(UhDownloadUIAction.SearchBarVisibilityRequest) }) {
                                Icon(painterResource(R.drawable.ic_search), "Search")
                            }
                            IconButton(onClick = onSettingsClick) {
                                Icon(painterResource(R.drawable.ic_settings), "Settings")
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                when (val state = uiState.itemsState) {
                    is UhDownloadUIState.ItemsState.NoItems -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No downloads yet", color = colorResource(id = R.color.subtext))
                        }
                    }
                    is UhDownloadUIState.ItemsState.NoSearchResults -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No results found", color = colorResource(id = R.color.subtext))
                        }
                    }
                    is UhDownloadUIState.ItemsState.Items -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(state.items) { item ->
                                when (item) {
                                    is UhHeaderItem -> {
                                        Text(
                                            text = item.timeCategory.name,
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
                                            onDeleteClick = { downloadsStore.dispatch(UhDownloadUIAction.RequestDelete(item)) }
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
