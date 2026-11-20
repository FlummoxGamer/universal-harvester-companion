package app.harvester.companion.home

import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.harvester.companion.WallpaperStore
import app.harvester.companion.wallpaper.SeamlessVideoWallpaperPlayer
import app.harvester.companion.wallpaper.VideoClipBounds
import app.harvester.companion.wallpaper.WallpaperDisplayMetrics
import app.harvester.companion.wallpaper.WallpaperMediaProcessor
import app.harvester.companion.wallpaper.WallpaperTransform
import app.harvester.companion.wallpaper.rememberPowerSaveMode
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import coil.size.Precision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun WallpaperBackground(
    path: String?,
    isVideo: Boolean,
    dimAlpha: Float = 0f,
    portraitTransform: WallpaperTransform = WallpaperTransform.Identity,
    landscapeTransform: WallpaperTransform = WallpaperTransform.Identity,
    clipBounds: VideoClipBounds? = null,
    isPlaybackActive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (path.isNullOrBlank() || path == "default") return

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val activeTransform = if (isLandscape) landscapeTransform else portraitTransform

    Box(modifier = modifier.fillMaxSize().clipToBounds()) {
        if (isVideo) {
            VideoWallpaper(
                path = path,
                transform = activeTransform,
                clipBounds = clipBounds,
                isPlaybackActive = isPlaybackActive,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            ImageWallpaper(
                path = path,
                transform = activeTransform,
                modifier = Modifier.fillMaxSize(),
            )
        }

        val safeDim = dimAlpha.coerceIn(0f, WallpaperStore.MAX_DIM_ALPHA)
        if (safeDim > 0.005f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = safeDim)),
            )
        }
    }
}

@Composable
private fun ImageWallpaper(
    path: String,
    transform: WallpaperTransform,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val clamped = remember(transform) { transform.clamped() }

    val model = remember(path) {
        if (path.startsWith("content://") || path.startsWith("http://") ||
            path.startsWith("https://") || path.startsWith("file://") || path.startsWith("asset://")
        ) {
            Uri.parse(path)
        } else {
            File(path)
        }
    }

    var mediaAspect by remember(path) { mutableFloatStateOf(0f) }
    LaunchedEffect(path) {
        if (!path.startsWith("http") && !path.startsWith("content://")) {
            val aspect = withContext(Dispatchers.IO) {
                WallpaperMediaProcessor.getImageAspectRatio(path)
            }
            if (aspect > 0f) mediaAspect = aspect
        }
    }

    val request = remember(context, model, clamped.scale) {
        val (targetW, targetH) = WallpaperDisplayMetrics.getDecodeTargetSize(context, clamped.scale)
        ImageRequest.Builder(context)
            .data(model)
            .size(targetW, targetH)
            .precision(Precision.INEXACT)
            .bitmapConfig(Bitmap.Config.ARGB_8888)
            .allowHardware(true)
            .crossfade(false)
            .build()
    }

    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxSize().clipToBounds(),
    ) {
        val viewW = constraints.maxWidth.toFloat()
        val viewH = constraints.maxHeight.toFloat()
        val viewAspect = if (viewW > 0f && viewH > 0f) viewW / viewH else 0f
        val (cropX, cropY) = WallpaperTransform.computeCropFactors(mediaAspect, viewAspect)
        val exact = clamped.clampedForAspect(mediaAspect, viewAspect)

        val imageModifier = if (viewW > 0f && viewH > 0f && mediaAspect > 0.01f) {
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
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (!exact.isIdentity) {
                        scaleX = exact.scale
                        scaleY = exact.scale
                        translationX = exact.offsetXFraction * size.width
                        translationY = exact.offsetYFraction * size.height
                    }
                }
        }

        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onSuccess = { state ->
                if (mediaAspect <= 0.01f) {
                    val d = state.result.drawable
                    if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
                        mediaAspect = d.intrinsicWidth.toFloat() / d.intrinsicHeight.toFloat()
                    }
                }
            },
            modifier = imageModifier,
        )
    }
}

@Composable
private fun VideoWallpaper(
    path: String,
    transform: WallpaperTransform,
    clipBounds: VideoClipBounds?,
    isPlaybackActive: Boolean,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isPowerSaveMode by rememberPowerSaveMode()

    var isLifecycleResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var firstFrameRendered by remember(path, clipBounds) { mutableStateOf(false) }
    var probe by remember(path) { mutableStateOf<WallpaperMediaProcessor.VideoProbe?>(null) }
    var resolvedAspect by remember(path) { mutableFloatStateOf(0f) }

    LaunchedEffect(path) {
        if (!path.startsWith("http") && !path.startsWith("content://")) {
            val p = withContext(Dispatchers.IO) { WallpaperMediaProcessor.probeVideo(path) }
            probe = p
            if (p.aspectRatio > 0f) resolvedAspect = p.aspectRatio
        }
    }

    DisposableEffect(lifecycleOwner) {
        isLifecycleResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isLifecycleResumed = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> isLifecycleResumed = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val shouldPlay = isPlaybackActive && isLifecycleResumed && !isPowerSaveMode

    val posterFile = remember(path) {
        WallpaperStore.posterFileFor(path).takeIf { it.exists() && it.length() > 0L }
    }

    val mediaUri = remember(path) {
        if (path.startsWith("content://") || path.startsWith("http://") ||
            path.startsWith("https://") || path.startsWith("file://") || path.startsWith("asset://")
        ) {
            Uri.parse(path)
        } else {
            Uri.fromFile(File(path))
        }
    }

    val posterRequest = remember(context, path, posterFile, clipBounds) {
        val (targetW, targetH) = WallpaperDisplayMetrics.getDecodeTargetSize(context, transform.scale)
        val data: Any = posterFile ?: mediaUri
        ImageRequest.Builder(context)
            .data(data)
            .size(targetW, targetH)
            .precision(Precision.INEXACT)
            .bitmapConfig(Bitmap.Config.ARGB_8888)
            .apply {
                if (posterFile == null) videoFrameMillis(clipBounds?.startMs ?: 0L)
            }
            .crossfade(false)
            .build()
    }

    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxSize().clipToBounds(),
    ) {
        val viewW = constraints.maxWidth.toFloat()
        val viewH = constraints.maxHeight.toFloat()
        val viewAspect = if (viewW > 0f && viewH > 0f) viewW / viewH else 0f
        val effectiveAspect = resolvedAspect.takeIf { it > 0.01f } ?: (probe?.aspectRatio ?: 0f)
        val exactTransform = transform.clampedForAspect(effectiveAspect, viewAspect)

        // Hold pixel-aligned full-res poster underneath so startup & Battery Saver never flash black
        if (!firstFrameRendered || isPowerSaveMode) {
            val (cropX, cropY) = WallpaperTransform.computeCropFactors(effectiveAspect, viewAspect)
            val posterModifier = if (viewW > 0f && viewH > 0f && effectiveAspect > 0.01f) {
                val contentW = with(density) { (viewW * cropX).toDp() }
                val contentH = with(density) { (viewH * cropY).toDp() }
                Modifier
                    .requiredSize(contentW, contentH)
                    .graphicsLayer {
                        scaleX = exactTransform.scale
                        scaleY = exactTransform.scale
                        translationX = exactTransform.offsetXFraction * viewW
                        translationY = exactTransform.offsetYFraction * viewH
                    }
            } else {
                Modifier.fillMaxSize()
            }

            AsyncImage(
                model = posterRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = posterModifier,
            )
        }

        if (!isPowerSaveMode) {
            SeamlessVideoWallpaperPlayer(
                mediaUri = mediaUri,
                clipBounds = clipBounds,
                isPlaying = shouldPlay,
                transform = exactTransform,
                initialVideoWidth = probe?.width ?: 0,
                initialVideoHeight = probe?.height ?: 0,
                initialRotationDegrees = probe?.rotationDegrees ?: 0,
                enableCrossfadeLoop = true,
                onVideoAspectResolved = { aspect ->
                    if (aspect > 0.01f) resolvedAspect = aspect
                },
                onFirstFrameRendered = {
                    firstFrameRendered = true
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
