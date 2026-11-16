package app.harvester.companion.home

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import app.harvester.companion.R
import app.harvester.companion.Shortcut
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    shortcuts: List<Shortcut>,
    wallpaperPath: String?,
    isVideo: Boolean,
    textColorInt: Int,
    onEditHomeClick: () -> Unit,
    onShortcutOpen: (String) -> Unit,
    onMoveShortcut: (Int, Int) -> Unit,
    onAddShortcut: (Shortcut) -> Unit,
    onRemoveShortcut: (Shortcut) -> Unit,
    onUpdateShortcut: (Shortcut, Shortcut) -> Unit
) {
    var isAdding by remember { mutableStateOf(false) }
    var shortcutToEdit by remember { mutableStateOf<Shortcut?>(null) }

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

    val textColor = if (textColorInt == 0) MaterialTheme.colorScheme.onBackground else Color(textColorInt)
    val luminance = androidx.core.graphics.ColorUtils.calculateLuminance(textColor.toArgb())
    val shadowColor = if (luminance > 0.5) Color.Black else Color.White

    val config = LocalConfiguration.current
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    Box(modifier = Modifier.fillMaxSize().background(if (wallpaperPath == null || wallpaperPath == "default") MaterialTheme.colorScheme.background else Color.Transparent)) {
        WallpaperBackground(path = wallpaperPath, isVideo = isVideo)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(bottom = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(if (isLandscape) 0.05f else 0.3f))

            if (!isLandscape) {
                Text(
                    text = "Universal Harvester",
                    color = textColor,
                    fontSize = 48.sp,
                    fontFamily = FontFamily(Font(R.font.logo_font)),
                    style = TextStyle(shadow = Shadow(color = shadowColor, offset = Offset(0f, 2f), blurRadius = 8f)),
                    modifier = Modifier.padding(bottom = 36.dp)
                )
            }

            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(if (isLandscape) 5 else 4),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                itemsIndexed(shortcuts, key = { _, item -> item.id }) { index, shortcut ->
                    val isDragging = draggedIndex == index
                    val itemModifier = if (isDragging) {
                        Modifier
                            .zIndex(1f)
                            .graphicsLayer {
                                translationY = dragOffset.y
                                translationX = dragOffset.x
                            }
                    } else {
                        Modifier
                    }

                    ShortcutItem(
                        name = shortcut.name,
                        iconUrl = shortcut.iconUrl,
                        textColor = textColor,
                        shadowColor = shadowColor,
                        onOpen = { onShortcutOpen(shortcut.url) },
                        onEdit = { shortcutToEdit = shortcut },
                        onRemove = { onRemoveShortcut(shortcut) },
                        modifier = itemModifier.pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var isDragReady = false
                                var isPopupShowing = false
                                var isDragging = false

                                val dragJob = scope.launch {
                                    delay(150)
                                    isDragReady = true
                                }
                                val popupJob = scope.launch {
                                    delay(500)
                                    if (!isDragging && !isPopupShowing) {
                                        isPopupShowing = true
                                        shortcutToEdit = shortcut
                                    }
                                }

                                var offset = Offset.Zero

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break

                                    if (isPopupShowing) break

                                    val delta = change.positionChange()
                                    offset += delta

                                    if (offset.getDistance() > 20f) {
                                        popupJob.cancel()
                                        if (isDragReady && !isDragging) {
                                            isDragging = true
                                            draggedIndex = index
                                            dragOffset = Offset.Zero
                                        } else if (!isDragReady) {
                                            dragJob.cancel()
                                        }
                                    }

                                    if (isDragging) {
                                        dragOffset += delta

                                        val currentItem = gridState.layoutInfo.visibleItemsInfo.find { it.index == draggedIndex }
                                        if (currentItem != null) {
                                            val centerY = currentItem.offset.y + currentItem.size.height / 2 + dragOffset.y
                                            val targetItem = gridState.layoutInfo.visibleItemsInfo.find {
                                                centerY.toInt() in it.offset.y..(it.offset.y + it.size.height) && it.index != draggedIndex
                                            }
                                            if (targetItem != null && targetItem.index < shortcuts.size) {
                                                onMoveShortcut(draggedIndex, targetItem.index)
                                                draggedIndex = targetItem.index
                                                dragOffset = Offset.Zero
                                            }
                                        }
                                    }

                                    if (change.pressed) change.consume()
                                }

                                dragJob.cancel()
                                popupJob.cancel()

                                if (isDragging) {
                                    draggedIndex = -1
                                    dragOffset = Offset.Zero
                                }
                            }
                        }
                    )
                }
                item(key = "add_shortcut") {
                    AddShortcutItem(
                        textColor = textColor,
                        shadowColor = shadowColor,
                        onClick = { isAdding = true }
                    )
                }
            }

            Spacer(modifier = Modifier.weight(if (isLandscape) 0.05f else 0.7f))
        }

        IconButton(
            onClick = onEditHomeClick,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            ShadowedPencilIcon(
                tint = textColor,
                shadowColor = shadowColor
            )
        }
    }
}

@Composable
private fun ShortcutItem(
    name: String,
    iconUrl: String,
    textColor: Color,
    shadowColor: Color,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable { onOpen() }
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                ShortcutIcon(iconUrl = iconUrl, modifier = Modifier.fillMaxSize())
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = name,
                color = textColor,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = TextStyle(shadow = Shadow(color = shadowColor, offset = Offset(0f, 2f), blurRadius = 8f))
            )
        }

        if (showMenu) {
            Popup(
                onDismissRequest = { showMenu = false },
                properties = PopupProperties(focusable = true)
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.width(160.dp)
                ) {
                    Column {
                        Text(
                            text = "Edit",
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showMenu = false; onEdit() }
                                .padding(16.dp)
                        )
                        Text(
                            text = "Remove",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showMenu = false; onRemove() }
                                .padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddShortcutItem(
    textColor: Color,
    shadowColor: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_add),
                contentDescription = "Add",
                tint = textColor
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Add",
            color = textColor,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            style = TextStyle(shadow = Shadow(color = shadowColor, offset = Offset(0f, 2f), blurRadius = 8f))
        )
    }
}
