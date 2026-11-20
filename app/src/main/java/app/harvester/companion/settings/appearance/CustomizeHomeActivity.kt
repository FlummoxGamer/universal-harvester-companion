package app.harvester.companion.settings.appearance

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.harvester.companion.ShortcutStore
import app.harvester.companion.WallpaperStore
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme
import app.harvester.companion.wallpaper.WallpaperCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CustomizeHomeActivity : AppCompatActivity() {

    private var reloadTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT

        setContent {
            UHTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                val columns = if (isLandscape) 6 else 4
                val tick = reloadTick
                val wallpaperState by WallpaperStore.wallpaperFlow(context).collectAsState()
                val storedRecents = remember(tick, wallpaperState.path) {
                    WallpaperStore.getRecent(context).take(if (isLandscape) 4 else 2)
                }
                var showResetConfirm by remember { mutableStateOf(false) }
                var isImportingImage by remember { mutableStateOf(false) }
                var showGallerySheet by remember { mutableStateOf(false) }

                fun handleSelectedMedia(uri: android.net.Uri, knownVideo: Boolean? = null) {
                    val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
                    val isVideo = knownVideo ?: (
                        mime.startsWith("video/") ||
                            WallpaperStore.isVideoFileOrUrl(uri.toString())
                        )
                    if (isVideo) {
                        context.startActivity(
                            Intent(context, TrimVideoActivity::class.java)
                                .putExtra("videoUri", uri.toString())
                        )
                    } else {
                        isImportingImage = true
                        scope.launch(Dispatchers.IO) {
                            val localPath = WallpaperStore.saveWallpaper(context, uri)
                            withContext(Dispatchers.Main) {
                                isImportingImage = false
                                if (localPath != null) {
                                    context.startActivity(
                                        Intent(context, WallpaperPreviewActivity::class.java).apply {
                                            putExtra("imagePath", localPath)
                                            putExtra("isCustom", true)
                                        }
                                    )
                                } else {
                                    Toast.makeText(context, "Failed to import wallpaper", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }

                // Fallback external file picker invoked from the Gallery Sheet's "Browse Files" button
                val pickMedia = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) {
                        handleSelectedMedia(uri)
                    }
                }

                if (showGallerySheet) {
                    GalleryPickerSheet(
                        onDismiss = { showGallerySheet = false },
                        onMediaSelected = { uri, isVideo ->
                            handleSelectedMedia(uri, knownVideo = isVideo)
                        },
                        onBrowseFiles = {
                            pickMedia.launch(arrayOf("image/*", "video/*"))
                        },
                    )
                }

                if (isImportingImage) {
                    AlertDialog(
                        onDismissRequest = {},
                        confirmButton = {},
                        title = { Text("Importing Image", color = MaterialTheme.colorScheme.onSurface) },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                Text("Saving full-quality original...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(18.dp)
                    )
                }

                if (showResetConfirm) {
                    AlertDialog(
                        onDismissRequest = { showResetConfirm = false },
                        title = { Text("Reset Home?", color = MaterialTheme.colorScheme.onSurface) },
                        text = {
                            Text(
                                "Are you sure you want to reset all widgets and wallpapers to defaults?",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showResetConfirm = false
                                    WallpaperStore.setCurrentWallpaper(context, "default", 0, false)
                                    ShortcutStore.clear(context)
                                    Toast.makeText(context, "Home reset to defaults", Toast.LENGTH_SHORT).show()
                                    finish()
                                }
                            ) {
                                Text("Confirm", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showResetConfirm = false }) {
                                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp)
                    )
                }

                UhSettingsScaffold(
                    title = "Customize Home",
                    onBack = { finish() }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "MY WALLPAPERS",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        TextButton(
                            onClick = {
                                context.startActivity(Intent(context, RecentlyActivity::class.java))
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("See All", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    val topTiles: List<HomeWallpaperTile> = buildList {
                        add(HomeWallpaperTile.Add)
                        add(HomeWallpaperTile.Default)
                        storedRecents.forEach { path ->
                            add(
                                if (WallpaperStore.isVideoFileOrUrl(path)) HomeWallpaperTile.Motion(path)
                                else HomeWallpaperTile.Custom(path)
                            )
                        }
                    }

                    WallpaperTileGrid(
                        tiles = topTiles,
                        columns = columns,
                        wallpaperState = wallpaperState,
                        onTileClick = { tile ->
                            when (tile) {
                                is HomeWallpaperTile.Add -> {
                                    showGallerySheet = true
                                }
                                is HomeWallpaperTile.Default -> {
                                    WallpaperStore.setCurrentWallpaper(context, "default", 0, false)
                                    finish()
                                }
                                is HomeWallpaperTile.Custom -> {
                                    context.startActivity(
                                        Intent(context, WallpaperPreviewActivity::class.java).apply {
                                            putExtra("imagePath", tile.path)
                                            putExtra("isCustom", true)
                                        }
                                    )
                                }
                                is HomeWallpaperTile.Motion -> {
                                    context.startActivity(
                                        Intent(context, WallpaperPreviewActivity::class.java).apply {
                                            putExtra("imagePath", tile.path)
                                            putExtra("isCustom", true)
                                            putExtra("isVideo", true)
                                        }
                                    )
                                }
                                is HomeWallpaperTile.Remote -> Unit
                            }
                        },
                        onSetFromMenu = { path ->
                            context.startActivity(
                                Intent(context, WallpaperPreviewActivity::class.java).apply {
                                    putExtra("imagePath", path)
                                    putExtra("isCustom", true)
                                    putExtra("isVideo", WallpaperStore.isVideoFileOrUrl(path))
                                }
                            )
                        },
                        onDeleteFromMenu = { path ->
                            WallpaperStore.deleteWallpaper(context, path)
                            reloadTick++
                        }
                    )

                    WallpaperCatalog.categorizedWallpapers.forEach { (genre, urls) ->
                        Text(
                            text = genre,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 20.dp, bottom = 10.dp)
                        )
                        val isVideo = genre == "Motion"
                        WallpaperTileGrid(
                            tiles = urls.map { HomeWallpaperTile.Remote(it, isVideo) },
                            columns = columns,
                            wallpaperState = wallpaperState,
                            onTileClick = { tile ->
                                if (tile is HomeWallpaperTile.Remote) {
                                    context.startActivity(
                                        Intent(context, WallpaperPreviewActivity::class.java).apply {
                                            putExtra("imageUrl", tile.url)
                                            putStringArrayListExtra("catalogUrls", ArrayList(urls))
                                            putExtra("isCustom", false)
                                            putExtra("isVideo", tile.isVideo)
                                        }
                                    )
                                }
                            },
                            onSetFromMenu = {},
                            onDeleteFromMenu = {}
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    OutlinedButton(
                        onClick = { showResetConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Reset Home to Defaults", modifier = Modifier.padding(vertical = 4.dp))
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reloadTick++
    }
}
