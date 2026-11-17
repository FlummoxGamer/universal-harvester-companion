package app.harvester.companion.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.Shortcut
import app.harvester.companion.UHLog
import app.harvester.companion.utils.reorderable.ReorderableItem
import app.harvester.companion.utils.reorderable.detectReorderAfterLongPress
import app.harvester.companion.utils.reorderable.rememberReorderableLazyGridState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenUrl: (String) -> Unit,
    onOpenCustomize: () -> Unit
) {
    val shortcuts by viewModel.shortcuts.collectAsState()
    val wallpaperPath by viewModel.wallpaperPath.collectAsState()
    val wallpaperIsVideo by viewModel.wallpaperIsVideo.collectAsState()
    val addressBarPosition by viewModel.addressBarPosition.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editingShortcut by remember { mutableStateOf<Shortcut?>(null) }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val columnCount = if (isLandscape) 6 else 4

    val gridState = rememberLazyGridState()
    val reorderableState = rememberReorderableLazyGridState(
        gridState = gridState,
        onMove = { from, to ->
            viewModel.moveShortcut(from.index, to.index)
        },
        canDragOver = { draggedOver, _ ->
            draggedOver.index < shortcuts.size
        }
    )

    val topPadding = if (addressBarPosition == 0) 76.dp else 16.dp
    val bottomPadding = if (addressBarPosition == 1) 84.dp else 24.dp

    Box(modifier = Modifier.fillMaxSize()) {
        WallpaperBackground(
            path = wallpaperPath,
            isVideo = wallpaperIsVideo,
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = topPadding, bottom = bottomPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(0.35f))

            Text(
                text = stringResource(id = R.string.app_name),
                color = colorResource(id = R.color.accent),
                fontSize = if (isLandscape) 32.sp else 44.sp,
                fontFamily = FontFamily(Font(R.font.logo_font)),
                style = TextStyle(
                    shadow = Shadow(
                        color = Color(0x99000000),
                        offset = Offset(0f, 2f),
                        blurRadius = 6f
                    )
                ),
                modifier = Modifier.padding(bottom = if (isLandscape) 16.dp else 32.dp)
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(columnCount),
                state = gridState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(
                    items = shortcuts,
                    key = { it.url }
                ) { shortcut ->
                    ReorderableItem(
                        reorderableState = reorderableState,
                        key = shortcut.url
                    ) { isDragging ->
                        ShortcutTile(
                            shortcut = shortcut,
                            isDragging = isDragging,
                            reorderableModifier = Modifier.detectReorderAfterLongPress(reorderableState),
                            onTap = { onOpenUrl(shortcut.url) },
                            onEdit = { editingShortcut = shortcut },
                            onRemove = { viewModel.removeShortcut(shortcut) }
                        )
                    }
                }

                item(key = "add_widget_tile") {
                    AddWidgetTile(onClick = { showAddDialog = true })
                }
            }

            Spacer(modifier = Modifier.weight(0.35f))
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 12.dp, end = 12.dp)
                .size(44.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpenCustomize
                ),
            contentAlignment = Alignment.Center
        ) {
            ShadowedPencilIcon()
        }
    }

    if (showAddDialog) {
        ShortcutDialog(
            initialShortcut = null,
            onDismiss = { showAddDialog = false },
            onSave = { title, url ->
                viewModel.addShortcut(title, url)
                showAddDialog = false
            }
        )
    }

    editingShortcut?.let { target ->
        ShortcutDialog(
            initialShortcut = target,
            onDismiss = { editingShortcut = null },
            onSave = { title, url ->
                viewModel.updateShortcut(target, title, url)
                editingShortcut = null
            }
        )
    }
}

@Composable
private fun ShortcutTile(
    shortcut: Shortcut,
    isDragging: Boolean,
    reorderableModifier: Modifier,
    onTap: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(reorderableModifier)
            .pointerInput(shortcut.url) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downTime = down.uptimeMillis
                    var moved = false
                    var holdTriggered = false

                    val holdJob: Job = coroutineScope.launch {
                        delay(600L)
                        if (!moved && !isDragging) {
                            holdTriggered = true
                            showMenu = true
                        }
                    }

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) {
                                holdJob.cancel()
                                val duration = (change?.uptimeMillis ?: System.currentTimeMillis()) - downTime
                                if (!moved && !holdTriggered && !isDragging && duration in 0L..99L) {
                                    change?.consume()
                                    onTap()
                                }
                                break
                            }
                            val delta = change.positionChange()
                            if (delta.getDistance() > 14f) {
                                moved = true
                                holdJob.cancel()
                            }
                        }
                    } catch (e: Exception) {
                        holdJob.cancel()
                        UHLog.e("ui_error", "Widget touch error on ${shortcut.url}: ${e.message}", e)
                    }
                }
            }
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .then(if (isDragging) Modifier.shadow(8.dp, RoundedCornerShape(16.dp)) else Modifier)
                    .clip(RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                ShortcutIcon(
                    url = shortcut.url,
                    label = shortcut.name,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = shortcut.name,
                color = colorResource(id = R.color.text),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = TextStyle(
                    shadow = Shadow(
                        color = Color(0xCC000000),
                        offset = Offset(0f, 3f),
                        blurRadius = 6f
                    )
                )
            )
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            modifier = Modifier.background(colorResource(id = R.color.surface_2))
        ) {
            DropdownMenuItem(
                text = { Text("Edit", color = colorResource(id = R.color.text)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_edit_24),
                        contentDescription = null,
                        tint = colorResource(id = R.color.text)
                    )
                },
                onClick = {
                    showMenu = false
                    onEdit()
                }
            )
            DropdownMenuItem(
                text = { Text("Remove", color = colorResource(id = R.color.red)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_delete),
                        contentDescription = null,
                        tint = colorResource(id = R.color.red)
                    )
                },
                onClick = {
                    showMenu = false
                    onRemove()
                }
            )
        }
    }
}

@Composable
private fun AddWidgetTile(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.uh_ic_plus_24),
                contentDescription = "Add Shortcut",
                tint = colorResource(id = R.color.accent),
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Add",
            color = colorResource(id = R.color.text),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = TextStyle(
                shadow = Shadow(
                    color = Color(0xCC000000),
                    offset = Offset(0f, 3f),
                    blurRadius = 6f
                )
            )
        )
    }
}
