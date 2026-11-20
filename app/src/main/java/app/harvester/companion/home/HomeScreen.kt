package app.harvester.companion.home

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
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
import app.harvester.companion.UHBrand
import app.harvester.companion.UHLog
import app.harvester.companion.WallpaperStore
import app.harvester.companion.utils.reorderable.DragGestureDetector
import app.harvester.companion.utils.reorderable.ReorderableItem
import app.harvester.companion.utils.reorderable.rememberReorderableLazyGridState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    shortcuts: List<Shortcut>,
    wallpaperState: WallpaperStore.WallpaperState,
    isWallpaperPlaybackActive: Boolean = true,
    onEditHomeClick: () -> Unit,
    onShortcutOpen: (String) -> Unit,
    onMoveShortcut: (Int, Int) -> Unit,
    onAddShortcut: (Shortcut) -> Unit,
    onRemoveShortcut: (Shortcut) -> Unit,
    onUpdateShortcut: (Shortcut, Shortcut) -> Unit
) {
    var isAdding by remember { mutableStateOf(false) }
    var shortcutToEdit by remember { mutableStateOf<Shortcut?>(null) }
    var popupForShortcut by remember { mutableStateOf<Shortcut?>(null) }

    if (isAdding || shortcutToEdit != null) {
        ShortcutDialog(
            existing = shortcutToEdit,
            onDismiss = { isAdding = false; shortcutToEdit = null },
            onSave = { updated ->
                if (shortcutToEdit != null) {
                    onUpdateShortcut(shortcutToEdit!!, updated)
                } else {
                    onAddShortcut(updated)
                }
                isAdding = false
                shortcutToEdit = null
            }
        )
    }

    val wallpaperPath = wallpaperState.path
    val textColorInt = wallpaperState.textColor
    val hasWallpaper = !wallpaperPath.isNullOrBlank() && wallpaperPath != "default"
    val textColor = if (textColorInt == 0 || !hasWallpaper) {
        MaterialTheme.colorScheme.onBackground
    } else {
        Color(textColorInt)
    }
    val luminance = androidx.core.graphics.ColorUtils.calculateLuminance(textColor.toArgb())
    val shadowColor = if (luminance > 0.5) Color.Black.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.72f)

    val config = LocalConfiguration.current
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

    val gridState = rememberLazyGridState()
    val reorderableState = rememberReorderableLazyGridState(gridState) { from, to ->
        onMoveShortcut(from.index, to.index)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (!hasWallpaper) MaterialTheme.colorScheme.background else Color.Transparent)
    ) {
        WallpaperBackground(
            path = wallpaperState.path,
            isVideo = wallpaperState.isVideo,
            dimAlpha = wallpaperState.dimAlpha,
            portraitTransform = wallpaperState.portraitTransform,
            landscapeTransform = wallpaperState.landscapeTransform,
            clipBounds = wallpaperState.clipBounds,
            isPlaybackActive = isWallpaperPlaybackActive,
        )

        if (isLandscape) {
            // Dedicated Landscape Two-Zone Layout: Clean hero brand left, bounded scrollable shortcut canvas right
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(start = 24.dp, end = 58.dp, top = 16.dp, bottom = 74.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(0.32f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    UhWordmarkLockup(
                        textColor = textColor,
                        shadowColor = shadowColor,
                        hasWallpaper = hasWallpaper,
                        compact = true
                    )
                }

                // Invisible Bounded Scrollable Container (zero outer background, clean clipping)
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(0.68f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    val containerMaxHeight = (maxHeight * 0.94f).coerceAtLeast(160.dp)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = containerMaxHeight)
                            .background(Color.Transparent)
                            .clipToBounds()
                    ) {
                        ShortcutsGrid(
                            shortcuts = shortcuts,
                            columns = 5,
                            gridState = gridState,
                            reorderableState = reorderableState,
                            textColor = textColor,
                            shadowColor = shadowColor,
                            hasWallpaper = hasWallpaper,
                            popupForShortcut = popupForShortcut,
                            onPopupChange = { popupForShortcut = it },
                            onShortcutOpen = onShortcutOpen,
                            onEditShortcut = { shortcutToEdit = it },
                            onRemoveShortcut = onRemoveShortcut,
                            onAddClick = { isAdding = true }
                        )
                    }
                }
            }
        } else {
            // Portrait Layout: Balanced UH Wordmark + Invisible Bounded Scrollable Widget Container
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(bottom = 82.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.weight(0.22f))

                UhWordmarkLockup(
                    textColor = textColor,
                    shadowColor = shadowColor,
                    hasWallpaper = hasWallpaper,
                    compact = false
                )

                Spacer(modifier = Modifier.height(24.dp))

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    val boundedHeight = maxHeight.coerceIn(180.dp, 380.dp)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = boundedHeight)
                            .background(Color.Transparent)
                            .clipToBounds()
                    ) {
                        ShortcutsGrid(
                            shortcuts = shortcuts,
                            columns = 4,
                            gridState = gridState,
                            reorderableState = reorderableState,
                            textColor = textColor,
                            shadowColor = shadowColor,
                            hasWallpaper = hasWallpaper,
                            popupForShortcut = popupForShortcut,
                            onPopupChange = { popupForShortcut = it },
                            onShortcutOpen = onShortcutOpen,
                            onEditShortcut = { shortcutToEdit = it },
                            onRemoveShortcut = onRemoveShortcut,
                            onAddClick = { isAdding = true }
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(0.38f))
            }
        }

        IconButton(
            onClick = onEditHomeClick,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 12.dp, end = 12.dp)
        ) {
            ShadowedPencilIcon(
                tint = textColor,
                shadowColor = shadowColor
            )
        }
    }
}

@Composable
private fun UhWordmarkLockup(
    textColor: Color,
    shadowColor: Color,
    hasWallpaper: Boolean,
    compact: Boolean
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = UHBrand.NAME,
            color = textColor,
            fontSize = if (compact) 38.sp else 46.sp,
            fontFamily = FontFamily(Font(R.font.logo_font)),
            letterSpacing = if (compact) 3.sp else 4.sp,
            style = TextStyle(
                shadow = if (hasWallpaper) {
                    Shadow(color = shadowColor, offset = Offset(0f, 2f), blurRadius = 8f)
                } else null
            )
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .width(if (compact) 28.dp else 36.dp)
                .height(3.dp)
                .clip(CircleShape)
                .background(
                    Brush.horizontalGradient(
                        listOf(accent.copy(alpha = 0.88f), accent.copy(alpha = 0.35f))
                    )
                )
        )
    }
}

@Composable
private fun ShortcutsGrid(
    shortcuts: List<Shortcut>,
    columns: Int,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    reorderableState: app.harvester.companion.utils.reorderable.ReorderableLazyGridState,
    textColor: Color,
    shadowColor: Color,
    hasWallpaper: Boolean,
    popupForShortcut: Shortcut?,
    onPopupChange: (Shortcut?) -> Unit,
    onShortcutOpen: (String) -> Unit,
    onEditShortcut: (Shortcut) -> Unit,
    onRemoveShortcut: (Shortcut) -> Unit,
    onAddClick: () -> Unit
) {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columns),
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent),
        contentPadding = PaddingValues(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        itemsIndexed(shortcuts, key = { _, item -> item.id }) { _, shortcut ->
            ReorderableItem(reorderableState, key = shortcut.id) { isDragging ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Transparent)
                ) {
                    ShortcutItem(
                        shortcut = shortcut,
                        textColor = textColor,
                        shadowColor = shadowColor,
                        hasWallpaper = hasWallpaper,
                        isDragging = isDragging,
                        modifier = Modifier
                            .fillMaxWidth()
                            .draggableHandle(
                                dragGestureDetector = DragGestureDetector.Compose { onDragStart, onDragEnd, onDragCancel, onDrag ->
                                    coroutineScope {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            val downTimeMs = System.currentTimeMillis()
                                            var isDragReady = false
                                            var isPopupShowing = false
                                            var activeDragging = false
                                            var movedPastSlop = false
                                            var offset = Offset.Zero

                                            val dragJob = launch {
                                                delay(150)
                                                isDragReady = true
                                            }
                                            val popupJob = launch {
                                                delay(550)
                                                if (!activeDragging && !isPopupShowing && !movedPastSlop) {
                                                    isPopupShowing = true
                                                    onPopupChange(shortcut)
                                                }
                                            }

                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                if (!change.pressed || change.changedToUpIgnoreConsumed()) {
                                                    val heldMs = System.currentTimeMillis() - downTimeMs
                                                    if (!activeDragging && !isPopupShowing && !movedPastSlop && heldMs < 350L) {
                                                        if (shortcut.url.isBlank()) {
                                                            UHLog.w("ui_warn", "Shortcut tap ignored: blank URL for '${shortcut.name}'.")
                                                        } else {
                                                            onShortcutOpen(shortcut.url)
                                                        }
                                                    }
                                                    change.consume()
                                                    break
                                                }
                                                if (isPopupShowing) break

                                                val delta = change.positionChange()
                                                offset += delta

                                                if (offset.getDistance() > 20f) {
                                                    movedPastSlop = true
                                                    popupJob.cancel()
                                                    if (isDragReady && !activeDragging) {
                                                        activeDragging = true
                                                        onDragStart(down.position)
                                                    } else if (!isDragReady) {
                                                        dragJob.cancel()
                                                    }
                                                }

                                                if (activeDragging) {
                                                    onDrag(change, delta)
                                                }
                                                change.consume()
                                            }

                                            dragJob.cancel()
                                            popupJob.cancel()
                                            if (activeDragging) onDragEnd() else onDragCancel()
                                        }
                                    }
                                }
                            )
                    )

                    if (popupForShortcut?.id == shortcut.id) {
                        EditRemovePopup(
                            onDismiss = { onPopupChange(null) },
                            onEdit = {
                                onEditShortcut(shortcut)
                                onPopupChange(null)
                            },
                            onRemove = {
                                onRemoveShortcut(shortcut)
                                onPopupChange(null)
                            }
                        )
                    }
                }
            }
        }

        item(key = "add_shortcut") {
            AddShortcutItem(
                textColor = textColor,
                shadowColor = shadowColor,
                hasWallpaper = hasWallpaper,
                onClick = onAddClick
            )
        }
    }
}

@Composable
private fun ShortcutItem(
    shortcut: Shortcut,
    textColor: Color,
    shadowColor: Color,
    hasWallpaper: Boolean,
    isDragging: Boolean,
    modifier: Modifier = Modifier
) {
    val tileSurface = MaterialTheme.colorScheme.surface.copy(alpha = if (isDragging) 0.94f else 0.78f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.background(Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(tileSurface)
                .border(0.6.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            ShortcutIcon(
                iconUrl = shortcut.iconUrl,
                pageUrl = shortcut.url,
                label = shortcut.name,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = shortcut.name,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 72.dp),
            style = TextStyle(
                shadow = if (hasWallpaper) {
                    Shadow(color = shadowColor, offset = Offset(0f, 1.5f), blurRadius = 6f)
                } else null
            )
        )
    }
}

@Composable
private fun EditRemovePopup(
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit
) {
    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.width(168.dp)
    ) {
        DropdownMenuItem(
            text = { Text("Edit", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp) },
            onClick = onEdit,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        DropdownMenuItem(
            text = { Text("Remove", color = MaterialTheme.colorScheme.error, fontSize = 15.sp) },
            onClick = onRemove,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

@Composable
private fun AddShortcutItem(
    textColor: Color,
    shadowColor: Color,
    hasWallpaper: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.66f))
                .border(0.6.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_add),
                contentDescription = "Add",
                tint = textColor,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Add",
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            style = TextStyle(
                shadow = if (hasWallpaper) {
                    Shadow(color = shadowColor, offset = Offset(0f, 1.5f), blurRadius = 6f)
                } else null
            )
        )
    }
}
