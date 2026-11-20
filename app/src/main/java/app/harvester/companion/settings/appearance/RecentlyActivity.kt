package app.harvester.companion.settings.appearance

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.harvester.companion.R
import app.harvester.companion.WallpaperStore
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme
import app.harvester.companion.utils.reorderable.DragGestureDetector
import app.harvester.companion.utils.reorderable.ReorderableItem
import app.harvester.companion.utils.reorderable.rememberReorderableLazyGridState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RecentlyActivity : AppCompatActivity() {

    private var reloadTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContent {
            UHTheme {
                val context = LocalContext.current
                val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                val columns = if (isLandscape) 6 else 4
                val tick = reloadTick
                val wallpaperState by WallpaperStore.wallpaperFlow(context).collectAsState()

                var recentPaths by remember { mutableStateOf(WallpaperStore.getRecent(context)) }
                var popupForPath by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(tick, wallpaperState.path) {
                    recentPaths = WallpaperStore.getRecent(context)
                }

                val gridState = rememberLazyGridState()
                val reorderableState = rememberReorderableLazyGridState(gridState) { from, to ->
                    val current = recentPaths.toMutableList()
                    if (from.index in current.indices && to.index in current.indices) {
                        val moved = current.removeAt(from.index)
                        current.add(to.index, moved)
                        recentPaths = current
                        WallpaperStore.saveRecentOrder(context, current)
                    }
                }

                fun openPreview(path: String) {
                    context.startActivity(
                        Intent(context, WallpaperPreviewActivity::class.java).apply {
                            putExtra("imagePath", path)
                            putExtra("isCustom", true)
                            putExtra("isVideo", WallpaperStore.isVideoFileOrUrl(path))
                        },
                    )
                }

                UhSettingsScaffold(
                    title = "Recent Wallpapers",
                    subtitle = if (recentPaths.isNotEmpty()) {
                        "${recentPaths.size} / ${WallpaperStore.MAX_RECENT_ITEMS} • Hold & drag to reorder"
                    } else null,
                    scrollable = false,
                    onBack = { finish() },
                ) {
                    if (recentPaths.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    painter = painterResource(id = R.drawable.fx_ic_appearance_24),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(44.dp),
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "No recent wallpapers yet",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 15.sp,
                                )
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Fixed(columns),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            itemsIndexed(recentPaths, key = { _, path -> path }) { _, path ->
                                val tile = if (WallpaperStore.isVideoFileOrUrl(path)) {
                                    HomeWallpaperTile.Motion(path)
                                } else {
                                    HomeWallpaperTile.Custom(path)
                                }

                                ReorderableItem(reorderableState, key = path) { isDragging ->
                                    WallpaperGridTile(
                                        tile = tile,
                                        wallpaperState = wallpaperState,
                                        onClick = { openPreview(path) },
                                        onSetFromMenu = { selected -> openPreview(selected) },
                                        onDeleteFromMenu = { selected ->
                                            WallpaperStore.deleteWallpaper(context, selected)
                                            recentPaths = WallpaperStore.getRecent(context)
                                        },
                                        isDragging = isDragging,
                                        enableInternalClick = false,
                                        externalMenuExpanded = popupForPath == path,
                                        onExternalMenuDismiss = {
                                            if (popupForPath == path) popupForPath = null
                                        },
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
                                                                    popupForPath = path
                                                                }
                                                            }

                                                            while (true) {
                                                                val event = awaitPointerEvent()
                                                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                                if (!change.pressed || change.changedToUpIgnoreConsumed()) {
                                                                    val heldMs = System.currentTimeMillis() - downTimeMs
                                                                    if (!activeDragging && !isPopupShowing && !movedPastSlop && heldMs < 350L) {
                                                                        openPreview(path)
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
                                                                        // Yield immediately to LazyVerticalGrid smooth scroll
                                                                        break
                                                                    }
                                                                }

                                                                if (activeDragging) {
                                                                    onDrag(change, delta)
                                                                    change.consume()
                                                                }
                                                            }

                                                            dragJob.cancel()
                                                            popupJob.cancel()
                                                            if (activeDragging) onDragEnd() else onDragCancel()
                                                        }
                                                    }
                                                },
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reloadTick++
    }
}
