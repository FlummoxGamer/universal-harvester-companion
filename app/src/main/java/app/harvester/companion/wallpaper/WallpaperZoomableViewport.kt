package app.harvester.companion.wallpaper

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Aspect-aware Jetpack Compose zoom & pan framing viewport supporting BOTH photos and videos:
 * - Unlocks full 1.0x panning across hidden off-screen edges of wide/tall wallpapers (e.g., 16:9
 *   desktop media on portrait phones) without zooming in and with zero black edge leaks.
 * - Drag-to-edge HorizontalPager handoff: single-finger swipes pan smoothly until reaching an edge,
 *   then seamlessly pass through to swipe to the next wallpaper.
 * - Centroid-anchored pinch zoom (1.0x..4.0x) and double-tap spring toggle (1.0x <-> 2.2x).
 * - Velocity-based fling inertia with strict aspect-aware boundary clamping.
 * - Rule-of-thirds alignment grid fading in smoothly while actively framing.
 *
 * When [applyGraphicsLayerToContent] is `true` (static images), the inner content is sized to the
 * exact unclipped `ContentScale.Crop` dimensions `(viewW * cropX, viewH * cropY)` and transformed
 * cleanly via `graphicsLayer`. When `false` (videos), the live [WallpaperTransform] is passed
 * directly to [content] so `WallpaperVideoSurface` applies the identical transform in hardware via
 * `TextureView.setTransform(Matrix)`.
 */
@Composable
fun WallpaperZoomableViewport(
    transform: WallpaperTransform,
    onTransformChange: (WallpaperTransform) -> Unit,
    modifier: Modifier = Modifier,
    mediaAspectRatio: Float = 0f,
    applyGraphicsLayerToContent: Boolean = true,
    minScale: Float = 1f,
    maxScale: Float = WallpaperTransform.MAX_SCALE,
    doubleTapScale: Float = 2.2f,
    showAlignmentGridWhileGesturing: Boolean = true,
    onInteractionChanged: ((Boolean) -> Unit)? = null,
    content: @Composable (liveTransform: WallpaperTransform) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val latestOnTransformChange by rememberUpdatedState(onTransformChange)
    val latestOnInteractionChanged by rememberUpdatedState(onInteractionChanged)
    val latestMediaAspect by rememberUpdatedState(mediaAspectRatio)

    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var isInteracting by remember { mutableStateOf(false) }

    val scaleAnim = remember { Animatable(transform.scale.coerceIn(minScale, maxScale)) }
    val offsetXAnim = remember { Animatable(0f) }
    val offsetYAnim = remember { Animatable(0f) }
    var activeAnimationJob by remember { mutableStateOf<Job?>(null) }

    fun currentMaxOffsetsPx(scale: Float, size: IntSize = viewportSize): Pair<Float, Float> {
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        if (w <= 0f || h <= 0f) return 0f to 0f
        val viewAspect = w / h
        val (maxFracX, maxFracY) = WallpaperTransform.computeMaxPanFractions(scale, latestMediaAspect, viewAspect)
        return (maxFracX * w) to (maxFracY * h)
    }

    // Synchronize external updates (e.g. nudge buttons, Reset button, orientation change, aspect resolve)
    LaunchedEffect(transform, viewportSize, mediaAspectRatio) {
        if (isInteracting || viewportSize.width <= 0 || viewportSize.height <= 0) return@LaunchedEffect
        val targetScale = transform.scale.coerceIn(minScale, maxScale)
        val (maxOffX, maxOffY) = currentMaxOffsetsPx(targetScale, viewportSize)
        val targetX = (transform.offsetXFraction * viewportSize.width).coerceIn(-maxOffX, maxOffX)
        val targetY = (transform.offsetYFraction * viewportSize.height).coerceIn(-maxOffY, maxOffY)

        val scaleDiff = abs(scaleAnim.value - targetScale)
        val xDiff = abs(offsetXAnim.value - targetX)
        val yDiff = abs(offsetYAnim.value - targetY)
        if (scaleDiff > 0.005f || xDiff > 1f || yDiff > 1f) {
            scaleAnim.snapTo(targetScale)
            offsetXAnim.snapTo(targetX)
            offsetYAnim.snapTo(targetY)
        }
    }

    fun emitNormalized(scale: Float, offX: Float, offY: Float) {
        val w = viewportSize.width.toFloat()
        val h = viewportSize.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val viewAspect = w / h
        val next = WallpaperTransform(
            scale = scale,
            offsetXFraction = offX / w,
            offsetYFraction = offY / h,
        ).clampedForAspect(latestMediaAspect, viewAspect)
        latestOnTransformChange(next)
    }

    val gridAlpha by animateFloatAsState(
        targetValue = if (isInteracting && showAlignmentGridWhileGesturing) 0.55f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "framing_grid_alpha",
    )

    val liveTransform = if (viewportSize.width > 0 && viewportSize.height > 0) {
        WallpaperTransform(
            scale = scaleAnim.value,
            offsetXFraction = offsetXAnim.value / viewportSize.width.toFloat(),
            offsetYFraction = offsetYAnim.value / viewportSize.height.toFloat(),
        ).clampedForAspect(
            mediaAspect = mediaAspectRatio,
            viewportAspect = viewportSize.width.toFloat() / viewportSize.height.toFloat(),
        )
    } else {
        transform
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { newSize ->
                val prev = viewportSize
                viewportSize = newSize
                if (prev != IntSize.Zero && newSize.width > 0 && newSize.height > 0 && prev != newSize) {
                    scope.launch {
                        val s = transform.scale.coerceIn(minScale, maxScale)
                        val (maxX, maxY) = currentMaxOffsetsPx(s, newSize)
                        scaleAnim.snapTo(s)
                        offsetXAnim.snapTo((transform.offsetXFraction * newSize.width).coerceIn(-maxX, maxX))
                        offsetYAnim.snapTo((transform.offsetYFraction * newSize.height).coerceIn(-maxY, maxY))
                    }
                }
            }
            // Double-tap spring toggle anchored at tap point
            .pointerInput(minScale, maxScale, doubleTapScale) {
                detectTapGestures(
                    onDoubleTap = { tapOffset ->
                        val w = viewportSize.width.toFloat()
                        val h = viewportSize.height.toFloat()
                        if (w <= 0f || h <= 0f) return@detectTapGestures

                        activeAnimationJob?.cancel()
                        val currentlyZoomed = scaleAnim.value > minScale + 0.08f
                        val targetScale = if (currentlyZoomed) minScale else doubleTapScale.coerceIn(minScale, maxScale)
                        val (maxX, maxY) = currentMaxOffsetsPx(targetScale)
                        val targetOffset = if (currentlyZoomed) {
                            // Preserve horizontal/vertical pan clamped cleanly to 1.0x bounds
                            Offset(
                                offsetXAnim.value.coerceIn(-maxX, maxX),
                                offsetYAnim.value.coerceIn(-maxY, maxY),
                            )
                        } else {
                            val center = Offset(w / 2f, h / 2f)
                            val deltaFromCenter = tapOffset - center
                            val ratio = targetScale / scaleAnim.value.coerceAtLeast(1f)
                            val desiredX = (offsetXAnim.value - deltaFromCenter.x) * ratio + deltaFromCenter.x
                            val desiredY = (offsetYAnim.value - deltaFromCenter.y) * ratio + deltaFromCenter.y
                            Offset(desiredX.coerceIn(-maxX, maxX), desiredY.coerceIn(-maxY, maxY))
                        }

                        val animSpec = spring<Float>(
                            dampingRatio = 0.85f,
                            stiffness = Spring.StiffnessMediumLow,
                        )
                        activeAnimationJob = scope.launch {
                            val j1 = launch { scaleAnim.animateTo(targetScale, animSpec) }
                            val j2 = launch { offsetXAnim.animateTo(targetOffset.x, animSpec) }
                            val j3 = launch { offsetYAnim.animateTo(targetOffset.y, animSpec) }
                            j1.join()
                            j2.join()
                            j3.join()
                            emitNormalized(scaleAnim.value, offsetXAnim.value, offsetYAnim.value)
                        }
                    },
                )
            }
            // Centroid-anchored pinch + aspect-aware 1.0x pan with drag-to-edge pager handoff + velocity fling
            .pointerInput(minScale, maxScale) {
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    activeAnimationJob?.cancel()
                    val velocityTracker = VelocityTracker()
                    var gestureCaptured = false
                    var wasMultiTouch = false

                    do {
                        val event = awaitPointerEvent()
                        val pressedChanges = event.changes.filter { it.pressed }
                        val pointerCount = pressedChanges.size
                        if (pointerCount >= 2) wasMultiTouch = true

                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        val centroid = event.calculateCentroid(useCurrent = true)

                        val w = viewportSize.width.toFloat()
                        val h = viewportSize.height.toFloat()
                        if (w <= 0f || h <= 0f) continue

                        val oldScale = scaleAnim.value
                        val (curMaxX, curMaxY) = currentMaxOffsetsPx(oldScale)
                        val isZoomed = oldScale > minScale + 0.01f
                        val canPanX = curMaxX > 2f
                        val canPanY = curMaxY > 2f
                        val shouldHandle = pointerCount >= 2 || isZoomed || canPanX || canPanY

                        if (shouldHandle && (zoomChange != 1f || panChange != Offset.Zero)) {
                            val newScale = (oldScale * zoomChange).coerceIn(minScale, maxScale)
                            val scaleRatio = if (oldScale > 0f) newScale / oldScale else 1f

                            val centerX = w / 2f
                            val centerY = h / 2f
                            val centroidFromCenterX = if (centroid != Offset.Unspecified) centroid.x - centerX else 0f
                            val centroidFromCenterY = if (centroid != Offset.Unspecified) centroid.y - centerY else 0f

                            val rawX = (offsetXAnim.value - centroidFromCenterX) * scaleRatio + centroidFromCenterX + panChange.x
                            val rawY = (offsetYAnim.value - centroidFromCenterY) * scaleRatio + centroidFromCenterY + panChange.y

                            val (maxX, maxY) = currentMaxOffsetsPx(newScale)

                            // Drag-to-edge pager handoff: when swiping horizontally with 1 finger and
                            // either there is no horizontal overflow or we already hit the boundary edge,
                            // yield directly to HorizontalPager.
                            val isHorizontalSwipe = pointerCount == 1 && !wasMultiTouch &&
                                abs(panChange.x) > abs(panChange.y) * 1.15f
                            val atHorizontalEdge = isHorizontalSwipe && (
                                !canPanX ||
                                    (offsetXAnim.value >= maxX - 2f && panChange.x > 0f) ||
                                    (offsetXAnim.value <= -maxX + 2f && panChange.x < 0f)
                                )

                            if (!atHorizontalEdge) {
                                if (!gestureCaptured) {
                                    gestureCaptured = true
                                    isInteracting = true
                                    latestOnInteractionChanged?.invoke(true)
                                }

                                val clampedX = rawX.coerceIn(-maxX, maxX)
                                val clampedY = rawY.coerceIn(-maxY, maxY)

                                scope.launch {
                                    scaleAnim.snapTo(newScale)
                                    offsetXAnim.snapTo(clampedX)
                                    offsetYAnim.snapTo(clampedY)
                                }
                                emitNormalized(newScale, clampedX, clampedY)

                                if (pointerCount == 1) {
                                    val primary = pressedChanges.firstOrNull { it.id == firstDown.id } ?: pressedChanges.first()
                                    velocityTracker.addPosition(primary.uptimeMillis, primary.position)
                                }

                                event.changes.forEach { change ->
                                    if (change.positionChanged()) change.consume()
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (gestureCaptured) {
                        val curScale = scaleAnim.value
                        val (maxX, maxY) = currentMaxOffsetsPx(curScale)
                        val canFling = !wasMultiTouch && (curScale > minScale + 0.02f || maxX > 2f || maxY > 2f)
                        val velocity = if (canFling) {
                            velocityTracker.calculateVelocity()
                        } else {
                            Velocity.Zero
                        }

                        activeAnimationJob = scope.launch {
                            try {
                                if (abs(velocity.x) > 120f || abs(velocity.y) > 120f) {
                                    val decay = exponentialDecay<Float>(frictionMultiplier = 2.4f)
                                    offsetXAnim.updateBounds(-maxX, maxX)
                                    offsetYAnim.updateBounds(-maxY, maxY)
                                    val fx = launch {
                                        if (maxX > 1f) {
                                            offsetXAnim.animateDecay(velocity.x, decay) {
                                                emitNormalized(scaleAnim.value, value, offsetYAnim.value)
                                            }
                                        }
                                    }
                                    val fy = launch {
                                        if (maxY > 1f) {
                                            offsetYAnim.animateDecay(velocity.y, decay) {
                                                emitNormalized(scaleAnim.value, offsetXAnim.value, value)
                                            }
                                        }
                                    }
                                    fx.join()
                                    fy.join()
                                    offsetXAnim.updateBounds(null, null)
                                    offsetYAnim.updateBounds(null, null)
                                }
                                // Cleanly settle scale to 1.0x if nearly 1.0x, while preserving 1.0x pan offsets
                                if (scaleAnim.value <= minScale + 0.02f) {
                                    val (baseMaxX, baseMaxY) = currentMaxOffsetsPx(minScale)
                                    val resetSpec = spring<Float>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium)
                                    val j1 = launch { scaleAnim.animateTo(minScale, resetSpec) }
                                    val j2 = launch { offsetXAnim.animateTo(offsetXAnim.value.coerceIn(-baseMaxX, baseMaxX), resetSpec) }
                                    val j3 = launch { offsetYAnim.animateTo(offsetYAnim.value.coerceIn(-baseMaxY, baseMaxY), resetSpec) }
                                    j1.join()
                                    j2.join()
                                    j3.join()
                                }
                                emitNormalized(scaleAnim.value, offsetXAnim.value, offsetYAnim.value)
                            } finally {
                                isInteracting = false
                                latestOnInteractionChanged?.invoke(false)
                            }
                        }
                    }
                }
            },
    ) {
        if (applyGraphicsLayerToContent) {
            val innerModifier = if (viewportSize.width > 0 && viewportSize.height > 0 && mediaAspectRatio > 0.01f) {
                val viewAspect = viewportSize.width.toFloat() / viewportSize.height.toFloat()
                val (cropX, cropY) = WallpaperTransform.computeCropFactors(mediaAspectRatio, viewAspect)
                val targetW = with(density) { (viewportSize.width * cropX).toDp() }
                val targetH = with(density) { (viewportSize.height * cropY).toDp() }
                Modifier
                    .requiredSize(targetW, targetH)
                    .graphicsLayer {
                        scaleX = scaleAnim.value
                        scaleY = scaleAnim.value
                        translationX = offsetXAnim.value
                        translationY = offsetYAnim.value
                    }
            } else {
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scaleAnim.value
                        scaleY = scaleAnim.value
                        translationX = offsetXAnim.value
                        translationY = offsetYAnim.value
                    }
            }

            Box(modifier = innerModifier) {
                content(liveTransform)
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                content(liveTransform)
            }
        }

        // Translucent rule-of-thirds alignment grid shown only while actively framing
        if (gridAlpha > 0.01f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokePx = 1.dp.toPx()
                val lineColor = Color.White.copy(alpha = gridAlpha)
                val thirdW = size.width / 3f
                val thirdH = size.height / 3f

                drawLine(lineColor, Offset(thirdW, 0f), Offset(thirdW, size.height), strokeWidth = strokePx)
                drawLine(lineColor, Offset(thirdW * 2f, 0f), Offset(thirdW * 2f, size.height), strokeWidth = strokePx)
                drawLine(lineColor, Offset(0f, thirdH), Offset(size.width, thirdH), strokeWidth = strokePx)
                drawLine(lineColor, Offset(0f, thirdH * 2f), Offset(size.width, thirdH * 2f), strokeWidth = strokePx)
            }
        }
    }
}
