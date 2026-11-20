package app.harvester.companion.settings.appearance

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.palette.graphics.Palette
import app.harvester.companion.R
import app.harvester.companion.Shortcut
import app.harvester.companion.ShortcutStore
import app.harvester.companion.UHBrand
import app.harvester.companion.UHLog
import app.harvester.companion.WallpaperStore
import app.harvester.companion.home.ShortcutIcon
import app.harvester.companion.theme.UHTheme
import app.harvester.companion.wallpaper.SeamlessVideoWallpaperPlayer
import app.harvester.companion.wallpaper.VideoClipBounds
import app.harvester.companion.wallpaper.WallpaperCatalog
import app.harvester.companion.wallpaper.WallpaperDisplayMetrics
import app.harvester.companion.wallpaper.WallpaperMediaCache
import app.harvester.companion.wallpaper.WallpaperMediaProcessor
import app.harvester.companion.wallpaper.WallpaperTransform
import app.harvester.companion.wallpaper.WallpaperZoomableViewport
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import coil.size.Precision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class WallpaperPreviewActivity : AppCompatActivity() {

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT

        val initialPath = intent.getStringExtra("imagePath")
        val initialUrl = intent.getStringExtra("imageUrl")
        val catalogUrls = intent.getStringArrayListExtra("catalogUrls")
        val isCustom = intent.getBooleanExtra("isCustom", true)
        val forceVideo = intent.getBooleanExtra("isVideo", false)

        val uris = buildList {
            if (isCustom) {
                val recents = WallpaperStore.getRecent(this@WallpaperPreviewActivity).toMutableList()
                if (initialPath != null) {
                    recents.remove(initialPath)
                    recents.add(0, initialPath)
                }
                addAll(recents)
            } else if (!catalogUrls.isNullOrEmpty()) {
                addAll(catalogUrls)
            } else {
                add(initialUrl ?: "default")
            }
            if (isEmpty()) add("default")
        }

        val initialKey = if (isCustom) initialPath else initialUrl
        val initialIndex = uris.indexOf(initialKey).coerceAtLeast(0)

        setContent {
            UHTheme(
                lightStatusBarOverride = false,
                lightNavigationBarOverride = false,
            ) {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val configuration = LocalConfiguration.current
                val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                val shortcuts = remember { ShortcutStore.list(context).take(8) }
                val storedState by WallpaperStore.wallpaperFlow(context).collectAsState()
                val activeDownloads by WallpaperMediaCache.downloadProgress.collectAsState()

                val pagerState = rememberPagerState(initialPage = initialIndex) { uris.size }
                val previewDominantColors = remember { mutableStateMapOf<Int, Int>() }
                val pageTransforms = remember { mutableStateMapOf<Int, WallpaperTransform>() }
                val pageMediaAspects = remember { mutableStateMapOf<Int, Float>() }
                val pageImageLoading = remember { mutableStateMapOf<Int, Boolean>() }
                var viewportSize by remember { mutableStateOf(IntSize.Zero) }

                // Seed transform & dim slider if previewing the currently active wallpaper
                val initialIsCurrent = remember(initialKey, storedState.path, storedState.sourceUrl) {
                    initialKey != null && (
                        initialKey == storedState.path ||
                            WallpaperStore.isRemoteUrlActive(context, initialKey, storedState)
                        )
                }
                LaunchedEffect(initialIsCurrent, isLandscape) {
                    if (initialIsCurrent && pageTransforms[initialIndex] == null) {
                        val saved = if (isLandscape) storedState.landscapeTransform else storedState.portraitTransform
                        pageTransforms[initialIndex] = saved
                    }
                }

                var dimAlpha by remember {
                    mutableFloatStateOf(if (initialIsCurrent) storedState.dimAlpha else 0f)
                }
                var showHomeOverlay by remember { mutableStateOf(true) }
                var isGesturing by remember { mutableStateOf(false) }
                // 0 = idle, 1 = Setting up..., 2 = Downloading...
                var isApplying by remember { mutableIntStateOf(0) }

                val currentPage = pagerState.currentPage.coerceIn(0, (uris.size - 1).coerceAtLeast(0))
                val currentItem = uris[currentPage]
                val currentIsVideo = WallpaperStore.isVideoFileOrUrl(currentItem) ||
                    (forceVideo && !isCustom) ||
                    (forceVideo && currentPage == initialIndex)
                val currentTransform = pageTransforms[currentPage] ?: WallpaperTransform.Identity
                val currentMediaAspect = pageMediaAspects[currentPage] ?: 0f
                val viewAspect = if (viewportSize.width > 0 && viewportSize.height > 0) {
                    viewportSize.width.toFloat() / viewportSize.height.toFloat()
                } else 0f
                val (maxFracX, maxFracY) = WallpaperTransform.computeMaxPanFractions(
                    scale = currentTransform.scale,
                    mediaAspect = currentMediaAspect,
                    viewportAspect = viewAspect,
                )

                val overlayAlpha by animateFloatAsState(
                    targetValue = if (showHomeOverlay && !isGesturing) 1f else 0f,
                    animationSpec = tween(durationMillis = 200),
                    label = "home_overlay_alpha",
                )

                // Keep Dim + Pan/Pinch dock stacked cleanly ABOVE the bottom mock Address Bar
                val dockBottomPadding by animateDpAsState(
                    targetValue = if (showHomeOverlay) 72.dp else 14.dp,
                    animationSpec = tween(durationMillis = 200),
                    label = "dock_bottom_clearance",
                )

                // Live download status for compact floating pill
                val currentUrlHash = remember(currentItem) {
                    if (currentItem.startsWith("http")) WallpaperMediaCache.urlHash(currentItem) else null
                }
                val videoDownloadProgress = currentUrlHash?.let { activeDownloads[it] }
                val isCurrentImageDownloading = !currentIsVideo &&
                    currentItem.startsWith("http") &&
                    (pageImageLoading[currentPage] == true)
                val showDownloadPill = !isGesturing && isApplying == 0 &&
                    (videoDownloadProgress != null || isCurrentImageDownloading)

                fun nudgeTransform(dxFrac: Float, dyFrac: Float) {
                    val base = pageTransforms[currentPage] ?: WallpaperTransform.Identity
                    val updated = base.copy(
                        offsetXFraction = (base.offsetXFraction + dxFrac).coerceIn(-maxFracX, maxFracX),
                        offsetYFraction = (base.offsetYFraction + dyFrac).coerceIn(-maxFracY, maxFracY),
                    ).clampedForAspect(currentMediaAspect, viewAspect)
                    pageTransforms[currentPage] = updated
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .onSizeChanged { viewportSize = it },
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        val item = uris[page]
                        val isVideo = WallpaperStore.isVideoFileOrUrl(item) ||
                            (forceVideo && !isCustom) ||
                            (forceVideo && page == initialIndex)
                        val isPageActive = page == pagerState.currentPage
                        val dominantArgb = previewDominantColors[page] ?: AndroidColor.DKGRAY
                        val effectiveColor = if (dimAlpha > 0.01f) {
                            ColorUtils.blendARGB(dominantArgb, AndroidColor.BLACK, dimAlpha)
                        } else dominantArgb
                        val titleColorInt = if (ColorUtils.calculateLuminance(effectiveColor) > 0.5) {
                            AndroidColor.BLACK
                        } else {
                            AndroidColor.WHITE
                        }

                        WallpaperPreviewPage(
                            item = item,
                            isVideo = isVideo,
                            isPageActive = isPageActive,
                            isLandscape = isLandscape,
                            dimAlpha = dimAlpha,
                            overlayAlpha = overlayAlpha,
                            shortcuts = shortcuts,
                            titleColorInt = titleColorInt,
                            transform = pageTransforms[page] ?: WallpaperTransform.Identity,
                            mediaAspect = pageMediaAspects[page] ?: 0f,
                            onMediaAspectResolved = { aspect ->
                                if (aspect > 0.01f) pageMediaAspects[page] = aspect
                            },
                            onImageLoadingChanged = { loading ->
                                pageImageLoading[page] = loading
                            },
                            onTransformChange = { updated -> pageTransforms[page] = updated },
                            onInteractionChanged = { active -> isGesturing = active },
                            onDetectedDominantColor = { dom -> previewDominantColors[page] = dom },
                        )
                    }

                    // Top Floating Controls Bar + Compact Download Indicator Pill
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(
                                    onClick = { finish() },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.48f))
                                        .border(0.6.dp, Color.White.copy(alpha = 0.16f), CircleShape),
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.fx_ic_close_24),
                                        contentDescription = "Close preview",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }

                                Surface(
                                    shape = CircleShape,
                                    color = if (showHomeOverlay) {
                                        Color.Black.copy(alpha = 0.48f)
                                    } else {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                    },
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .border(0.6.dp, Color.White.copy(alpha = 0.16f), CircleShape)
                                        .clickable { showHomeOverlay = !showHomeOverlay },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_image_frame),
                                            contentDescription = "Toggle Home preview overlay",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Text(
                                            text = if (showHomeOverlay) "Hide UI" else "Show UI",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                }
                            }

                            Button(
                                enabled = isApplying == 0,
                                onClick = {
                                    if (currentPage !in uris.indices) return@Button
                                    val selectedUri = uris[currentPage]
                                    if (selectedUri == "default") {
                                        WallpaperStore.setCurrentWallpaper(context, "default", 0, false)
                                        finish()
                                        return@Button
                                    }

                                    val isVideo = currentIsVideo
                                    val transformToSave = (pageTransforms[currentPage] ?: WallpaperTransform.Identity)
                                        .clampedForAspect(currentMediaAspect, viewAspect)
                                    val appliedDim = dimAlpha.coerceIn(0f, WallpaperStore.MAX_DIM_ALPHA)
                                    val isRemote = selectedUri.startsWith("http")
                                    val alreadyAvailableLocally = !isRemote ||
                                        WallpaperStore.hasSavedRemoteWallpaper(context, selectedUri) ||
                                        WallpaperMediaCache.isRemoteAssetCached(context, selectedUri, isVideo)

                                    // Show "Setting up..." when cached/local; only "Downloading..." if genuinely transferring
                                    isApplying = if (alreadyAvailableLocally) 1 else 2

                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            var finalPath = selectedUri
                                            if (isRemote) {
                                                val downloaded = WallpaperStore.downloadAndPrepareWallpaper(
                                                    ctx = context,
                                                    rawUrl = selectedUri,
                                                    onStageChanged = { downloading ->
                                                        scope.launch(Dispatchers.Main) {
                                                            if (isApplying != 0) {
                                                                isApplying = if (downloading) 2 else 1
                                                            }
                                                        }
                                                    },
                                                )
                                                if (downloaded == null) {
                                                    withContext(Dispatchers.Main) {
                                                        isApplying = 0
                                                        Toast.makeText(context, "Failed to download wallpaper", Toast.LENGTH_SHORT).show()
                                                    }
                                                    return@launch
                                                }
                                                finalPath = downloaded
                                            }

                                            withContext(Dispatchers.Main) {
                                                if (isApplying != 0) isApplying = 1
                                            }

                                            val resolvedIsVideo = isVideo || WallpaperStore.isVideoFileOrUrl(finalPath)
                                            val clipStartMs = if (resolvedIsVideo) {
                                                WallpaperStore.getVideoClipBounds(context, finalPath)?.startMs ?: 0L
                                            } else 0L

                                            val bitmap = if (resolvedIsVideo) {
                                                val poster = WallpaperStore.posterFileFor(finalPath)
                                                if (poster.exists()) {
                                                    WallpaperStore.getSampledBitmap(poster.absolutePath, 240, 420)
                                                } else {
                                                    WallpaperStore.generateVideoThumbnail(finalPath, clipStartMs)
                                                }
                                            } else {
                                                WallpaperStore.getSampledBitmap(finalPath, 240, 420)
                                            }

                                            val textColor = if (bitmap != null) {
                                                val palette = Palette.from(bitmap).generate()
                                                val rawDom = palette.getDominantColor(AndroidColor.DKGRAY)
                                                val blended = if (appliedDim > 0.01f) {
                                                    ColorUtils.blendARGB(rawDom, AndroidColor.BLACK, appliedDim)
                                                } else rawDom
                                                val lum = ColorUtils.calculateLuminance(blended)
                                                bitmap.recycle()
                                                if (lum > 0.5) AndroidColor.BLACK else AndroidColor.WHITE
                                            } else {
                                                AndroidColor.WHITE
                                            }

                                            withContext(Dispatchers.Main) {
                                                val switchingWallpaper = storedState.path != finalPath
                                                if (switchingWallpaper) {
                                                    WallpaperStore.resetWallpaperTransforms(context)
                                                }
                                                WallpaperStore.setWallpaperTransform(context, isLandscape, transformToSave)
                                                WallpaperStore.setCurrentWallpaper(
                                                    ctx = context,
                                                    path = finalPath,
                                                    textColor = textColor,
                                                    isVideo = resolvedIsVideo,
                                                    sourceUrl = if (isRemote) selectedUri else null,
                                                    dimAlpha = appliedDim,
                                                )
                                                finish()
                                            }
                                        } catch (e: Exception) {
                                            UHLog.e("wallpaper_error", "Preview apply failed: ${e.message}")
                                            withContext(Dispatchers.Main) {
                                                isApplying = 0
                                                Toast.makeText(context, "Could not apply wallpaper", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(18.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
                            ) {
                                if (isApplying != 0) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isApplying == 2) "Downloading..." else "Setting up...",
                                        fontSize = 13.sp,
                                    )
                                } else {
                                    Icon(
                                        painter = painterResource(id = R.drawable.fx_ic_check_24),
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Set Wallpaper", fontSize = 13.sp)
                                }
                            }
                        }

                        // Compact Floating Download Progress Pill (for remote wallpapers)
                        AnimatedVisibility(
                            visible = showDownloadPill,
                            enter = fadeIn(tween(150)),
                            exit = fadeOut(tween(180)),
                        ) {
                            val pctText = if (videoDownloadProgress != null && videoDownloadProgress >= 0f) {
                                "Downloading ${(videoDownloadProgress * 100f).roundToInt().coerceIn(0, 100)}%"
                            } else {
                                "Downloading..."
                            }
                            Surface(
                                shape = CircleShape,
                                color = Color.Black.copy(alpha = 0.62f),
                                modifier = Modifier
                                    .padding(top = 10.dp)
                                    .border(0.6.dp, Color.White.copy(alpha = 0.18f), CircleShape),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (videoDownloadProgress != null && videoDownloadProgress >= 0f) {
                                        CircularProgressIndicator(
                                            progress = { videoDownloadProgress.coerceIn(0f, 1f) },
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                            color = Color.White,
                                            trackColor = Color.White.copy(alpha = 0.25f),
                                        )
                                    } else {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                            color = Color.White,
                                        )
                                    }
                                    Text(
                                        text = pctText,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }

                    // Bottom Floating Framing Nudge & Dim Control Dock (stacked cleanly ABOVE Address Bar)
                    if (currentItem != "default") {
                        val canPanX = maxFracX > 0.02f
                        val canPanY = maxFracY > 0.02f
                        val showFramingPill = !isGesturing && (canPanX || canPanY || !currentTransform.isIdentity)

                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(start = 16.dp, end = 16.dp, bottom = dockBottomPadding),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Directional Pan Nudge + Zoom/Reset Framing Pill (both Photos & Videos)
                            AnimatedVisibility(
                                visible = showFramingPill,
                                enter = fadeIn(tween(160)),
                                exit = fadeOut(tween(160)),
                            ) {
                                val stepX = (maxFracX * 0.35f).coerceIn(0.14f, 0.45f)
                                val stepY = (maxFracY * 0.35f).coerceIn(0.14f, 0.45f)
                                val canGoLeft = canPanX && currentTransform.offsetXFraction < maxFracX - 0.01f
                                val canGoRight = canPanX && currentTransform.offsetXFraction > -maxFracX + 0.01f
                                val canGoUp = canPanY && currentTransform.offsetYFraction < maxFracY - 0.01f
                                val canGoDown = canPanY && currentTransform.offsetYFraction > -maxFracY + 0.01f

                                Surface(
                                    shape = CircleShape,
                                    color = Color.Black.copy(alpha = 0.58f),
                                    modifier = Modifier.border(
                                        0.6.dp,
                                        Color.White.copy(alpha = 0.18f),
                                        CircleShape,
                                    ),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        if (canPanX) {
                                            PanNudgeButton(
                                                rotationDegrees = 180f,
                                                enabled = canGoLeft,
                                                contentDescription = "Pan left",
                                                onClick = { nudgeTransform(stepX, 0f) },
                                            )
                                        }
                                        if (canPanY) {
                                            PanNudgeButton(
                                                rotationDegrees = -90f,
                                                enabled = canGoUp,
                                                contentDescription = "Pan up",
                                                onClick = { nudgeTransform(0f, stepY) },
                                            )
                                            PanNudgeButton(
                                                rotationDegrees = 90f,
                                                enabled = canGoDown,
                                                contentDescription = "Pan down",
                                                onClick = { nudgeTransform(0f, -stepY) },
                                            )
                                        }

                                        if (!currentTransform.isIdentity) {
                                            Row(
                                                modifier = Modifier
                                                    .clip(CircleShape)
                                                    .clickable { pageTransforms[currentPage] = WallpaperTransform.Identity }
                                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                Text(
                                                    text = "${"%.2f".format(Locale.US, currentTransform.scale)}x",
                                                    color = Color.White,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                                Icon(
                                                    painter = painterResource(id = R.drawable.ac_ic_arrow_counter_clockwise_24),
                                                    contentDescription = "Reset framing",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(14.dp),
                                                )
                                                Text(
                                                    text = "Reset",
                                                    color = Color.White.copy(alpha = 0.9f),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium,
                                                )
                                            }
                                        } else {
                                            Text(
                                                text = "Pan / Pinch",
                                                color = Color.White.copy(alpha = 0.84f),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier.padding(horizontal = 8.dp),
                                            )
                                        }

                                        if (canPanX) {
                                            PanNudgeButton(
                                                rotationDegrees = 0f,
                                                enabled = canGoRight,
                                                contentDescription = "Pan right",
                                                onClick = { nudgeTransform(-stepX, 0f) },
                                            )
                                        }
                                    }
                                }
                            }

                            // Wallpaper Dim Overlay Slider Pill
                            AnimatedVisibility(
                                visible = !isGesturing,
                                enter = fadeIn(tween(160)),
                                exit = fadeOut(tween(160)),
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = Color.Black.copy(alpha = 0.54f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .widthIn(max = 420.dp)
                                        .border(
                                            0.6.dp,
                                            Color.White.copy(alpha = 0.16f),
                                            RoundedCornerShape(18.dp),
                                        ),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Text(
                                            text = "Dim",
                                            color = Color.White.copy(alpha = 0.92f),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Slider(
                                            value = dimAlpha,
                                            onValueChange = { dimAlpha = it.coerceIn(0f, WallpaperStore.MAX_DIM_ALPHA) },
                                            valueRange = 0f..WallpaperStore.MAX_DIM_ALPHA,
                                            colors = SliderDefaults.colors(
                                                thumbColor = Color.White,
                                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                                inactiveTrackColor = Color.White.copy(alpha = 0.24f),
                                            ),
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            text = "${(dimAlpha * 100f).roundToInt()}%",
                                            color = Color.White.copy(alpha = 0.85f),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.widthIn(min = 34.dp),
                                            textAlign = TextAlign.End,
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
private fun PanNudgeButton(
    rotationDegrees: Float,
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(32.dp),
    ) {
        Icon(
            painter = painterResource(id = R.drawable.fx_ic_chevron_right_24),
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = if (enabled) 0.95f else 0.28f),
            modifier = Modifier
                .size(18.dp)
                .rotate(rotationDegrees),
        )
    }
}

@Composable
private fun WallpaperPreviewPage(
    item: String,
    isVideo: Boolean,
    isPageActive: Boolean,
    isLandscape: Boolean,
    dimAlpha: Float,
    overlayAlpha: Float,
    shortcuts: List<Shortcut>,
    titleColorInt: Int,
    transform: WallpaperTransform,
    mediaAspect: Float,
    onMediaAspectResolved: (Float) -> Unit,
    onImageLoadingChanged: (Boolean) -> Unit,
    onTransformChange: (WallpaperTransform) -> Unit,
    onInteractionChanged: (Boolean) -> Unit,
    onDetectedDominantColor: (Int) -> Unit,
) {
    val context = LocalContext.current
    val titleColor = if (item == "default") MaterialTheme.colorScheme.onBackground else Color(titleColorInt)
    val shadowColor = if (titleColorInt == AndroidColor.BLACK) Color.White else Color.Black

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            item == "default" -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                )
            }
            isVideo -> {
                val clipBounds = remember(item) {
                    if (!item.startsWith("http")) WallpaperStore.getVideoClipBounds(context, item) else null
                }
                WallpaperZoomableViewport(
                    transform = transform,
                    mediaAspectRatio = mediaAspect,
                    applyGraphicsLayerToContent = false,
                    onTransformChange = onTransformChange,
                    onInteractionChanged = onInteractionChanged,
                    modifier = Modifier.fillMaxSize(),
                ) { liveTransform ->
                    PreviewVideoLayer(
                        item = item,
                        clipBounds = clipBounds,
                        isPageActive = isPageActive,
                        transform = liveTransform,
                        mediaAspect = mediaAspect,
                        onAspectResolved = onMediaAspectResolved,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            else -> {
                val isRemoteImage = item.startsWith("http")
                val resolvedUrl = remember(item) {
                    if (isRemoteImage) WallpaperCatalog.fullResolutionUrl(context, item) else item
                }
                val thumbUrl = remember(item) {
                    if (isRemoteImage) WallpaperCatalog.thumbnailUrl(item) else null
                }
                val imageModel: Any = remember(resolvedUrl) {
                    if (resolvedUrl.startsWith("http")) resolvedUrl else File(resolvedUrl)
                }

                // Resolve intrinsic aspect ratio + dominant color for local files on IO thread
                LaunchedEffect(resolvedUrl) {
                    if (!resolvedUrl.startsWith("http")) {
                        val (aspect, dom) = withContext(Dispatchers.IO) {
                            val a = WallpaperMediaProcessor.getImageAspectRatio(resolvedUrl)
                            val c = try {
                                val bmp = WallpaperStore.getSampledBitmap(resolvedUrl, 160, 280)
                                if (bmp != null) {
                                    val color = Palette.from(bmp).generate().getDominantColor(AndroidColor.DKGRAY)
                                    bmp.recycle()
                                    color
                                } else AndroidColor.DKGRAY
                            } catch (_: Exception) {
                                AndroidColor.DKGRAY
                            }
                            a to c
                        }
                        if (aspect > 0.01f) onMediaAspectResolved(aspect)
                        onDetectedDominantColor(dom)
                        onImageLoadingChanged(false)
                    }
                }

                val thumbRequest = remember(context, thumbUrl) {
                    thumbUrl?.let {
                        ImageRequest.Builder(context)
                            .data(it)
                            .size(480, 720)
                            .crossfade(false)
                            .build()
                    }
                }

                val imageRequest = remember(context, imageModel, transform.scale) {
                    val (targetW, targetH) = WallpaperDisplayMetrics.getDecodeTargetSize(context, transform.scale)
                    ImageRequest.Builder(context)
                        .data(imageModel)
                        .size(targetW, targetH)
                        .precision(Precision.INEXACT)
                        .bitmapConfig(Bitmap.Config.ARGB_8888)
                        .allowHardware(true)
                        .crossfade(if (isRemoteImage) 160 else 0)
                        .build()
                }

                WallpaperZoomableViewport(
                    transform = transform,
                    mediaAspectRatio = mediaAspect,
                    applyGraphicsLayerToContent = true,
                    onTransformChange = onTransformChange,
                    onInteractionChanged = onInteractionChanged,
                    modifier = Modifier.fillMaxSize(),
                ) { _ ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Instant 0ms base layer using the already-cached gallery thumbnail
                        if (thumbRequest != null) {
                            AsyncImage(
                                model = thumbRequest,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                onSuccess = { state ->
                                    if (mediaAspect <= 0.01f) {
                                        val d = state.result.drawable
                                        if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
                                            onMediaAspectResolved(d.intrinsicWidth.toFloat() / d.intrinsicHeight.toFloat())
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        // Full-resolution sharp layer fading cleanly in on top
                        AsyncImage(
                            model = imageRequest,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            onLoading = {
                                if (isRemoteImage) onImageLoadingChanged(true)
                            },
                            onSuccess = { state ->
                                onImageLoadingChanged(false)
                                val d = state.result.drawable
                                if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
                                    onMediaAspectResolved(d.intrinsicWidth.toFloat() / d.intrinsicHeight.toFloat())
                                }
                            },
                            onError = {
                                onImageLoadingChanged(false)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        // Live Dim Scrim Overlay (identical to HomeScreen rendering)
        if (item != "default" && dimAlpha > 0.005f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = dimAlpha)),
            )
        }

        // Home Layout Preview Overlay (Address Bar anchored cleanly at true bottom)
        if (overlayAlpha > 0.01f) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(overlayAlpha)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (isLandscape) {
                    Spacer(modifier = Modifier.height(52.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Column(
                            modifier = Modifier.widthIn(min = 120.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = UHBrand.NAME,
                                color = titleColor,
                                fontSize = 36.sp,
                                fontFamily = FontFamily(Font(R.font.logo_font)),
                                letterSpacing = 3.sp,
                                style = TextStyle(
                                    shadow = if (item == "default") null else Shadow(shadowColor, Offset(0f, 2f), 8f),
                                ),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            PreviewShortcutRows(
                                shortcuts = shortcuts,
                                columns = 4,
                                titleColor = titleColor,
                                shadowColor = shadowColor,
                                hasCustomBg = item != "default",
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(78.dp))
                    Text(
                        text = UHBrand.NAME,
                        color = titleColor,
                        fontSize = 42.sp,
                        fontFamily = FontFamily(Font(R.font.logo_font)),
                        letterSpacing = 3.sp,
                        style = TextStyle(
                            shadow = if (item == "default") null else Shadow(
                                color = shadowColor,
                                offset = Offset(0f, 2f),
                                blurRadius = 8f,
                            ),
                        ),
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    PreviewShortcutRows(
                        shortcuts = shortcuts,
                        columns = 4,
                        titleColor = titleColor,
                        shadowColor = shadowColor,
                        hasCustomBg = item != "default",
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }

                // Mock Home Address Bar anchored at the very bottom (Dim + Pan/Pinch stack above it)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .height(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Search or enter address",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                    Text(
                        text = "1",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * Seizure-safe, zero-flash motion preview layer:
 * - Uses the persistent local WebP thumbnail (`WallpaperMediaCache.getCachedMotionThumb`)
 *   or local poster file immediately at 0ms underneath, and NEVER unmounts it while the video
 *   fades in on top — guaranteeing constant luminance with zero blank/dark flash.
 * - Keeps the poster sizing stable (`fillMaxSize()` + `ContentScale.Crop` at identity, and
 *   identical crop geometry when zoomed/panned) so there is zero size jump when metadata arrives.
 * - Uses [SeamlessVideoWallpaperPlayer] with the two-frame GPU matrix commit gate when active.
 */
@Composable
private fun PreviewVideoLayer(
    item: String,
    clipBounds: VideoClipBounds?,
    isPageActive: Boolean,
    transform: WallpaperTransform,
    mediaAspect: Float,
    onAspectResolved: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var probe by remember(item) { mutableStateOf<WallpaperMediaProcessor.VideoProbe?>(null) }

    val cachedLocalFile = remember(item, isPageActive) {
        if (item.startsWith("http")) WallpaperMediaCache.getCachedVideoFile(context, item) else null
    }
    var remoteThumbFile by remember(item) {
        mutableStateOf(
            if (item.startsWith("http")) WallpaperMediaCache.getCachedMotionThumb(context, item) else null,
        )
    }
    val localPosterFile = remember(item) {
        if (!item.startsWith("http")) {
            WallpaperStore.posterFileFor(item).takeIf { it.exists() && it.length() > 0L }
        } else null
    }

    LaunchedEffect(item, cachedLocalFile) {
        if (item.startsWith("http") && remoteThumbFile == null) {
            remoteThumbFile = WallpaperMediaCache.getOrExtractMotionThumb(context, item)
        }
        val localPath = when {
            !item.startsWith("http") -> item
            cachedLocalFile != null -> cachedLocalFile.absolutePath
            else -> null
        }
        if (localPath != null) {
            val p = withContext(Dispatchers.IO) { WallpaperMediaProcessor.probeVideo(localPath) }
            probe = p
            if (p.aspectRatio > 0.01f) onAspectResolved(p.aspectRatio)
        }
    }

    LaunchedEffect(item, isPageActive) {
        if (isPageActive && item.startsWith("http")) {
            WallpaperMediaCache.prefetchVideoAsync(context, item)
        }
    }

    val posterFile = localPosterFile ?: remoteThumbFile
    val posterRequest = remember(context, item, posterFile, cachedLocalFile, clipBounds) {
        val data: Any? = posterFile
            ?: cachedLocalFile
            ?: if (!item.startsWith("http")) File(item) else null
        data?.let {
            ImageRequest.Builder(context)
                .data(it)
                .apply {
                    if (posterFile == null) {
                        videoFrameMillis(clipBounds?.startMs ?: 0L)
                    }
                }
                .crossfade(false)
                .build()
        }
    }

    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxSize().clipToBounds(),
    ) {
        val viewW = constraints.maxWidth.toFloat()
        val viewH = constraints.maxHeight.toFloat()
        val viewAspect = if (viewW > 0f && viewH > 0f) viewW / viewH else 0f
        val effectiveAspect = mediaAspect.takeIf { it > 0.01f } ?: (probe?.aspectRatio ?: 0f)

        // Permanent base poster layer (never unmounts during video reveal -> zero black flash)
        if (posterRequest != null) {
            val posterModifier = if (!transform.isIdentity && viewW > 0f && viewH > 0f && effectiveAspect > 0.01f) {
                val (cropX, cropY) = WallpaperTransform.computeCropFactors(effectiveAspect, viewAspect)
                val exact = transform.clampedForAspect(effectiveAspect, viewAspect)
                val contentW = with(density) { (viewW * cropX).toDp() }
                val contentH = with(density) { (viewH * cropY).toDp() }
                Modifier
                    .requiredSize(contentW, contentH)
                    .graphicsLayer {
                        scaleX = exact.scale
                        scaleY = exact.scale
                        translationX = exact.offsetXFraction * viewW
                        translationY = exact.offsetYFraction * viewH
                    }
            } else {
                Modifier.fillMaxSize()
            }

            AsyncImage(
                model = posterRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { state ->
                    if (effectiveAspect <= 0.01f) {
                        val d = state.result.drawable
                        if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
                            onAspectResolved(d.intrinsicWidth.toFloat() / d.intrinsicHeight.toFloat())
                        }
                    }
                },
                modifier = posterModifier,
            )
        }

        if (isPageActive) {
            val mediaUri = remember(item, cachedLocalFile) {
                when {
                    cachedLocalFile != null -> Uri.fromFile(cachedLocalFile)
                    item.startsWith("http") -> Uri.parse(item)
                    else -> Uri.fromFile(File(item))
                }
            }

            SeamlessVideoWallpaperPlayer(
                mediaUri = mediaUri,
                clipBounds = clipBounds,
                isPlaying = true,
                transform = transform,
                initialVideoWidth = probe?.width ?: 0,
                initialVideoHeight = probe?.height ?: 0,
                initialRotationDegrees = probe?.rotationDegrees ?: 0,
                enableCrossfadeLoop = true,
                onVideoAspectResolved = { aspect ->
                    if (aspect > 0.01f) onAspectResolved(aspect)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun PreviewShortcutRows(
    shortcuts: List<Shortcut>,
    columns: Int,
    titleColor: Color,
    shadowColor: Color,
    hasCustomBg: Boolean,
) {
    shortcuts.chunked(columns).forEach { rowItems ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            rowItems.forEach { s ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                            .border(0.5.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        ShortcutIcon(
                            iconUrl = s.iconUrl,
                            pageUrl = s.url,
                            label = s.name,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = s.name,
                        color = titleColor,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        style = TextStyle(
                            shadow = if (!hasCustomBg) null else Shadow(
                                color = shadowColor,
                                offset = Offset(0f, 1.5f),
                                blurRadius = 6f,
                            ),
                        ),
                    )
                }
            }
            repeat(columns - rowItems.size) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}
