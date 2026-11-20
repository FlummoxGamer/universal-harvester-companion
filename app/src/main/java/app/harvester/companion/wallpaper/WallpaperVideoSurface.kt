package app.harvester.companion.wallpaper

import android.graphics.Matrix
import android.view.TextureView
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer

/**
 * Pure Compose programmatic TextureView video surface:
 * - Uses ExoPlayer's native [ExoPlayer.setVideoTextureView] binding keyed cleanly to [exoPlayer]
 *   so switching wallpapers while paused or returning from another Activity attaches instantaneously
 *   with zero frozen-frame surface loss.
 * - Enforces a strict two-frame GPU commit barrier (`firstFrameRendered && hasValidMatrix` +
 *   two `TextureView.post` ticks after `setTransform`) before [revealAlpha] ever leaves `0f`,
 *   eliminating stretched/weird-size frames and split-second luminance flashes.
 * - Applies unified aspect-aware [WallpaperTransform] (including 1.0x panning of wide/tall clips
 *   and 1x..4x zoom) directly in hardware via `TextureView.setTransform(Matrix)`.
 */
@Composable
fun WallpaperVideoSurface(
    exoPlayer: ExoPlayer,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    transform: WallpaperTransform = WallpaperTransform.Identity,
    initialVideoWidth: Int = 0,
    initialVideoHeight: Int = 0,
    initialRotationDegrees: Int = 0,
    revealImmediately: Boolean = false,
    onVideoAspectResolved: ((Float) -> Unit)? = null,
    onFirstFrameRendered: (() -> Unit)? = null,
) {
    val latestTransform by rememberUpdatedState(transform)
    val latestContentScale by rememberUpdatedState(contentScale)
    val latestOnAspectResolved by rememberUpdatedState(onVideoAspectResolved)
    val latestOnFirstFrame by rememberUpdatedState(onFirstFrameRendered)

    key(exoPlayer) {
        var videoWidth by remember {
            mutableIntStateOf(
                if (exoPlayer.videoSize.width > 0) exoPlayer.videoSize.width else initialVideoWidth,
            )
        }
        var videoHeight by remember {
            mutableIntStateOf(
                if (exoPlayer.videoSize.height > 0) exoPlayer.videoSize.height else initialVideoHeight,
            )
        }
        var pixelRatio by remember {
            mutableFloatStateOf(
                if (exoPlayer.videoSize.pixelWidthHeightRatio > 0f) exoPlayer.videoSize.pixelWidthHeightRatio else 1f,
            )
        }
        var rotationDegrees by remember {
            mutableIntStateOf(
                if (exoPlayer.videoSize.width > 0) exoPlayer.videoSize.unappliedRotationDegrees else initialRotationDegrees,
            )
        }
        var firstFrameRendered by remember { mutableStateOf(revealImmediately) }
        var hasValidMatrix by remember { mutableStateOf(false) }
        var matrixCommitted by remember { mutableStateOf(false) }
        var notifiedFirstFrame by remember { mutableStateOf(false) }

        val textureViewRef = remember { mutableStateOf<TextureView?>(null) }
        val boundTextureViewRef = remember { mutableStateOf<TextureView?>(null) }

        fun syncTransform(tv: TextureView) {
            val aspect = applyVideoTransform(
                textureView = tv,
                videoWidth = videoWidth,
                videoHeight = videoHeight,
                pixelRatio = pixelRatio,
                rotationDegrees = rotationDegrees,
                contentScale = latestContentScale,
                transform = latestTransform,
            )
            if (aspect > 0f) {
                hasValidMatrix = true
                latestOnAspectResolved?.invoke(aspect)
            }
        }

        // Two-frame GPU commit barrier: wait until BOTH the first frame is decoded AND
        // the exact aspect/crop transform matrix has survived two Choreographer draw passes.
        LaunchedEffect(firstFrameRendered, hasValidMatrix, revealImmediately) {
            if (!matrixCommitted && hasValidMatrix && (firstFrameRendered || revealImmediately)) {
                val tv = textureViewRef.value
                if (tv != null) {
                    syncTransform(tv)
                    tv.post {
                        syncTransform(tv)
                        tv.post {
                            matrixCommitted = true
                            if (!notifiedFirstFrame && firstFrameRendered) {
                                notifiedFirstFrame = true
                                latestOnFirstFrame?.invoke()
                            }
                        }
                    }
                } else {
                    matrixCommitted = true
                    if (!notifiedFirstFrame && firstFrameRendered) {
                        notifiedFirstFrame = true
                        latestOnFirstFrame?.invoke()
                    }
                }
            }
        }

        DisposableEffect(exoPlayer) {
            val listener = object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        videoWidth = videoSize.width
                        videoHeight = videoSize.height
                        pixelRatio = if (videoSize.pixelWidthHeightRatio > 0f) videoSize.pixelWidthHeightRatio else 1f
                        rotationDegrees = videoSize.unappliedRotationDegrees
                        textureViewRef.value?.let { syncTransform(it) }
                    }
                }

                override fun onRenderedFirstFrame() {
                    textureViewRef.value?.let { syncTransform(it) }
                    firstFrameRendered = true
                }
            }
            exoPlayer.addListener(listener)
            onDispose {
                exoPlayer.removeListener(listener)
                boundTextureViewRef.value?.let { tv ->
                    try {
                        exoPlayer.clearVideoTextureView(tv)
                    } catch (_: Exception) {
                    }
                }
                boundTextureViewRef.value = null
            }
        }

        val isReadyToShow = if (revealImmediately) {
            hasValidMatrix && matrixCommitted
        } else {
            firstFrameRendered && hasValidMatrix && matrixCommitted
        }
        val revealAlpha by animateFloatAsState(
            targetValue = if (isReadyToShow) 1f else 0f,
            animationSpec = tween(durationMillis = if (revealImmediately) 0 else 160),
            label = "video_surface_reveal_alpha",
        )

        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    isOpaque = false
                    textureViewRef.value = this
                    boundTextureViewRef.value = this
                    addOnLayoutChangeListener(
                        View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            syncTransform(this)
                        },
                    )
                    exoPlayer.setVideoTextureView(this)
                    syncTransform(this)
                }
            },
            update = { tv ->
                textureViewRef.value = tv
                if (boundTextureViewRef.value !== tv) {
                    boundTextureViewRef.value = tv
                    exoPlayer.setVideoTextureView(tv)
                }
                syncTransform(tv)
            },
            modifier = modifier.graphicsLayer {
                alpha = revealAlpha
            },
        )
    }
}

/**
 * Computes and applies the exact aspect-preserving Crop/Fit + pan/zoom [Matrix] on [textureView].
 * Returns the resolved display aspect ratio (`effectiveWidth / effectiveHeight`), or `0f` if not ready.
 */
private fun applyVideoTransform(
    textureView: TextureView,
    videoWidth: Int,
    videoHeight: Int,
    pixelRatio: Float,
    rotationDegrees: Int,
    contentScale: ContentScale,
    transform: WallpaperTransform,
): Float {
    val viewW = textureView.width.toFloat()
    val viewH = textureView.height.toFloat()
    if (viewW <= 0f || viewH <= 0f || videoWidth <= 0 || videoHeight <= 0) return 0f

    val rotated = rotationDegrees == 90 || rotationDegrees == 270
    val effectiveVideoW = if (rotated) videoHeight.toFloat() else videoWidth * pixelRatio
    val effectiveVideoH = if (rotated) videoWidth * pixelRatio else videoHeight.toFloat()
    if (effectiveVideoW <= 0f || effectiveVideoH <= 0f) return 0f

    val videoAspect = effectiveVideoW / effectiveVideoH
    val viewAspect = viewW / viewH

    var baseScaleX = 1f
    var baseScaleY = 1f
    if (contentScale == ContentScale.Fit) {
        if (videoAspect > viewAspect) {
            baseScaleY = viewAspect / videoAspect
        } else {
            baseScaleX = videoAspect / viewAspect
        }
    } else {
        if (videoAspect > viewAspect) {
            baseScaleX = videoAspect / viewAspect
        } else {
            baseScaleY = viewAspect / viewAspect
        }
    }

    val safeScale = if (contentScale == ContentScale.Crop) {
        transform.scale.coerceIn(1f, WallpaperTransform.MAX_SCALE)
    } else 1f

    val totalScaleX = baseScaleX * safeScale
    val totalScaleY = baseScaleY * safeScale

    val tx = if (contentScale == ContentScale.Crop) {
        val maxOffX = ((totalScaleX - 1f) * viewW / 2f).coerceAtLeast(0f)
        (transform.offsetXFraction * viewW).coerceIn(-maxOffX, maxOffX)
    } else 0f

    val ty = if (contentScale == ContentScale.Crop) {
        val maxOffY = ((totalScaleY - 1f) * viewH / 2f).coerceAtLeast(0f)
        (transform.offsetYFraction * viewH).coerceIn(-maxOffY, maxOffY)
    } else 0f

    val pivotX = viewW / 2f
    val pivotY = viewH / 2f
    val matrix = Matrix()
    if (rotationDegrees != 0) {
        matrix.postRotate(rotationDegrees.toFloat(), pivotX, pivotY)
    }
    matrix.postScale(totalScaleX, totalScaleY, pivotX, pivotY)
    if (tx != 0f || ty != 0f) {
        matrix.postTranslate(tx, ty)
    }
    textureView.setTransform(matrix)
    return videoAspect
}
