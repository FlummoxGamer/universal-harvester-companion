package app.harvester.companion.settings.appearance

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.harvester.companion.R
import app.harvester.companion.WallpaperStore
import app.harvester.companion.wallpaper.WallpaperCatalog
import app.harvester.companion.wallpaper.WallpaperMediaCache
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import java.io.File

sealed class HomeWallpaperTile {
    data object Add : HomeWallpaperTile()
    data object Default : HomeWallpaperTile()
    data class Custom(val path: String) : HomeWallpaperTile()
    data class Motion(val path: String) : HomeWallpaperTile()
    data class Remote(val url: String, val isVideo: Boolean) : HomeWallpaperTile()
}

@Composable
fun WallpaperTileGrid(
    tiles: List<HomeWallpaperTile>,
    columns: Int = 4,
    wallpaperState: WallpaperStore.WallpaperState,
    onTileClick: (HomeWallpaperTile) -> Unit,
    onSetFromMenu: (String) -> Unit,
    onDeleteFromMenu: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(columns).forEach { rowTiles ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowTiles.forEach { tile ->
                    Box(modifier = Modifier.weight(1f)) {
                        WallpaperGridTile(
                            tile = tile,
                            wallpaperState = wallpaperState,
                            onClick = { onTileClick(tile) },
                            onSetFromMenu = onSetFromMenu,
                            onDeleteFromMenu = onDeleteFromMenu,
                        )
                    }
                }
                repeat(columns - rowTiles.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WallpaperGridTile(
    tile: HomeWallpaperTile,
    wallpaperState: WallpaperStore.WallpaperState,
    onClick: () -> Unit,
    onSetFromMenu: (String) -> Unit,
    onDeleteFromMenu: (String) -> Unit,
    modifier: Modifier = Modifier,
    isDragging: Boolean = false,
    enableInternalClick: Boolean = true,
    externalMenuExpanded: Boolean? = null,
    onExternalMenuDismiss: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var internalMenuExpanded by remember { mutableStateOf(false) }
    val showMenu = externalMenuExpanded ?: internalMenuExpanded

    val activePath = wallpaperState.path ?: "default"
    val customPath = when (tile) {
        is HomeWallpaperTile.Custom -> tile.path
        is HomeWallpaperTile.Motion -> tile.path
        else -> null
    }

    val isActive = when (tile) {
        is HomeWallpaperTile.Default -> activePath == "default" || activePath.isBlank()
        is HomeWallpaperTile.Custom -> tile.path == activePath
        is HomeWallpaperTile.Motion -> tile.path == activePath
        is HomeWallpaperTile.Remote -> WallpaperStore.isRemoteUrlActive(context, tile.url, wallpaperState)
        is HomeWallpaperTile.Add -> false
    }

    val isMotionTile = when (tile) {
        is HomeWallpaperTile.Motion -> true
        is HomeWallpaperTile.Remote -> tile.isVideo
        else -> false
    }

    val borderColor = when {
        isDragging -> MaterialTheme.colorScheme.primary
        isActive -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    val borderWidth = if (isActive || isDragging) 2.5.dp else 1.dp

    val clickableModifier = if (enableInternalClick) {
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = if (customPath != null) {
                { internalMenuExpanded = true }
            } else null,
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.66f)
            .graphicsLayer {
                if (isDragging) {
                    scaleX = 1.04f
                    scaleY = 1.04f
                    alpha = 0.94f
                }
            }
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(borderWidth, borderColor, RoundedCornerShape(14.dp))
            .then(clickableModifier),
        contentAlignment = Alignment.Center,
    ) {
        when (tile) {
            is HomeWallpaperTile.Add -> {
                Icon(
                    painter = painterResource(id = R.drawable.ic_add),
                    contentDescription = "Add wallpaper",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }
            is HomeWallpaperTile.Default -> {
                Icon(
                    painter = painterResource(id = R.drawable.ic_home),
                    contentDescription = "Default theme",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp),
                )
            }
            is HomeWallpaperTile.Custom -> {
                val model: Any = if (tile.path.startsWith("http")) tile.path else File(tile.path)
                val req = remember(tile.path) {
                    ImageRequest.Builder(context)
                        .data(model)
                        .size(480, 720)
                        .crossfade(false)
                        .build()
                }
                AsyncImage(
                    model = req,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            is HomeWallpaperTile.Motion -> {
                val poster = WallpaperStore.posterFileFor(tile.path)
                val hasPoster = poster.exists() && poster.length() > 0L
                val model: Any = when {
                    hasPoster -> poster
                    tile.path.startsWith("http") -> tile.path
                    else -> File(tile.path)
                }
                val req = remember(tile.path, hasPoster) {
                    ImageRequest.Builder(context)
                        .data(model)
                        .size(480, 720)
                        .apply {
                            if (!hasPoster) videoFrameMillis(0L)
                        }
                        .crossfade(false)
                        .build()
                }
                AsyncImage(
                    model = req,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    error = painterResource(id = R.drawable.fx_ic_video_24),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            is HomeWallpaperTile.Remote -> {
                if (tile.isVideo) {
                    // Persistent local WebP thumbnail stored once in filesDir/wallpaper_thumbs
                    var cachedThumb by remember(tile.url) {
                        mutableStateOf(WallpaperMediaCache.getCachedMotionThumb(context, tile.url))
                    }
                    LaunchedEffect(tile.url) {
                        if (cachedThumb == null) {
                            cachedThumb = WallpaperMediaCache.getOrExtractMotionThumb(context, tile.url)
                        }
                    }

                    val thumbFile = cachedThumb
                    if (thumbFile != null) {
                        val req = remember(thumbFile.absolutePath) {
                            ImageRequest.Builder(context)
                                .data(thumbFile)
                                .size(480, 720)
                                .crossfade(false)
                                .build()
                        }
                        AsyncImage(
                            model = req,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            error = painterResource(id = R.drawable.fx_ic_video_24),
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.fx_ic_video_24),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                } else {
                    val displayUrl = remember(tile.url) {
                        WallpaperCatalog.thumbnailUrl(tile.url)
                    }
                    val req = remember(displayUrl) {
                        ImageRequest.Builder(context)
                            .data(displayUrl)
                            .size(480, 720)
                            .crossfade(true)
                            .build()
                    }
                    AsyncImage(
                        model = req,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        error = painterResource(id = R.drawable.fx_ic_appearance_24),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // Subtle motion indicator badge at bottom-start
        if (isMotionTile) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.56f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_play_24),
                    contentDescription = "Motion wallpaper",
                    tint = Color.White,
                    modifier = Modifier.size(11.dp),
                )
            }
        }

        if (isActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_check_24),
                    contentDescription = "Active wallpaper",
                    tint = Color.White,
                    modifier = Modifier.size(13.dp),
                )
            }
        }

        if (customPath != null) {
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = {
                    internalMenuExpanded = false
                    onExternalMenuDismiss?.invoke()
                },
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                DropdownMenuItem(
                    text = { Text("Set Wallpaper") },
                    onClick = {
                        internalMenuExpanded = false
                        onExternalMenuDismiss?.invoke()
                        onSetFromMenu(customPath)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        internalMenuExpanded = false
                        onExternalMenuDismiss?.invoke()
                        onDeleteFromMenu(customPath)
                    },
                )
            }
        }
    }
}
