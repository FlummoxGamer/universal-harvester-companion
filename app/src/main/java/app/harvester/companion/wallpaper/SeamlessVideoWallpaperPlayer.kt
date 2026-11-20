package app.harvester.companion.wallpaper

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import app.harvester.companion.UHLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Butter-smooth motion wallpaper player with live dual-surface crossfade looping:
 * - Eliminates harsh jumps on non-seamless video loops by pre-cueing a standby surface at the
 *   start of the clip and performing a constant-luminance 420ms live-motion crossfade dissolve
 *   (outgoing surface stays opaque underneath at zIndex 0 while incoming live motion ramps 0 -> 1
 *   on top at zIndex 1 — zero freeze, zero darkening dip, zero black flash).
 * - Automatically falls back to single-player native `REPEAT_MODE_ONE` for ultra-short clips
 *   (< 2.4s) or if a device hits concurrent hardware MediaCodec limits.
 */
@Composable
fun SeamlessVideoWallpaperPlayer(
    mediaUri: Uri,
    clipBounds: VideoClipBounds? = null,
    isPlaying: Boolean = true,
    transform: WallpaperTransform = WallpaperTransform.Identity,
    initialVideoWidth: Int = 0,
    initialVideoHeight: Int = 0,
    initialRotationDegrees: Int = 0,
    enableCrossfadeLoop: Boolean = true,
    modifier: Modifier = Modifier,
    onVideoAspectResolved: ((Float) -> Unit)? = null,
    onFirstFrameRendered: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val latestOnFirstFrame by rememberUpdatedState(onFirstFrameRendered)
    val latestOnAspect by rememberUpdatedState(onVideoAspectResolved)

    // Key player pair strictly on media identity + clip bounds
    val mediaItem = remember(mediaUri, clipBounds) {
        WallpaperPlaybackController.buildMediaItem(mediaUri, clipBounds)
    }

    var fallbackSinglePlayer by remember(mediaUri, clipBounds, enableCrossfadeLoop) {
        mutableStateOf(!enableCrossfadeLoop)
    }
    var activeSlot by remember(mediaUri, clipBounds) { mutableIntStateOf(0) }
    var isCrossfading by remember(mediaUri, clipBounds) { mutableStateOf(false) }
    val incomingAlpha = remember(mediaUri, clipBounds) { Animatable(0f) }

    val playerA = remember(mediaUri, clipBounds) {
        WallpaperPlaybackController.createWallpaperExoPlayer(
            context = context,
            repeatOne = !enableCrossfadeLoop,
        )
    }

    val playerB = remember(mediaUri, clipBounds, enableCrossfadeLoop) {
        if (enableCrossfadeLoop) {
            WallpaperPlaybackController.createWallpaperExoPlayer(
                context = context,
                repeatOne = false,
            )
        } else null
    }

    // Prepare players & wire automatic fallback if hardware refuses a second concurrent decoder
    DisposableEffect(playerA, playerB, mediaItem) {
        val listenerA = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                UHLog.e("video", "Wallpaper PlayerA error: ${error.message}")
            }
        }
        val listenerB = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                UHLog.w("video_warn", "Standby decoder unavailable (${error.message}); using single-player loop.")
                fallbackSinglePlayer = true
                playerA.repeatMode = Player.REPEAT_MODE_ONE
            }
        }

        playerA.addListener(listenerA)
        playerA.setMediaItem(mediaItem)
        playerA.prepare()

        playerB?.let { secondary ->
            secondary.addListener(listenerB)
            secondary.setMediaItem(mediaItem)
            secondary.playWhenReady = false
            secondary.prepare()
        }

        onDispose {
            playerA.removeListener(listenerA)
            playerA.stop()
            playerA.release()
            playerB?.let { secondary ->
                secondary.removeListener(listenerB)
                secondary.stop()
                secondary.release()
            }
        }
    }

    // Sync play/pause cleanly on lifecycle & Home visibility transitions
    LaunchedEffect(playerA, playerB, isPlaying, activeSlot, fallbackSinglePlayer) {
        val activePlayer = if (fallbackSinglePlayer || activeSlot == 0) playerA else (playerB ?: playerA)
        val standbyPlayer = if (fallbackSinglePlayer) null else if (activeSlot == 0) playerB else playerA

        if (isPlaying) {
            activePlayer.repeatMode = if (fallbackSinglePlayer) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            activePlayer.playWhenReady = true
            activePlayer.play()
        } else {
            activePlayer.playWhenReady = false
            activePlayer.pause()
            standbyPlayer?.playWhenReady = false
            standbyPlayer?.pause()
        }
    }

    // Smooth dual-surface live-motion loop dissolve monitor
    LaunchedEffect(playerA, playerB, isPlaying, fallbackSinglePlayer) {
        if (!isPlaying || fallbackSinglePlayer || playerB == null) return@LaunchedEffect

        val targetCrossfadeMs = 420L
        while (isActive) {
            val active = if (activeSlot == 0) playerA else playerB
            val standby = if (activeSlot == 0) playerB else playerA
            val duration = active.duration
            val pos = active.currentPosition

            // Ultra-short clips (< 2.4s) loop more cleanly via native REPEAT_MODE_ONE
            if (duration in 1L..2399L) {
                fallbackSinglePlayer = true
                activeSlot = 0
                isCrossfading = false
                incomingAlpha.snapTo(0f)
                standby.playWhenReady = false
                standby.pause()
                playerA.repeatMode = Player.REPEAT_MODE_ONE
                playerA.playWhenReady = true
                playerA.play()
                return@LaunchedEffect
            }

            if (duration >= 2400L && pos >= 0L) {
                val crossfadeWindow = minOf(targetCrossfadeMs, duration / 5L)
                val remaining = duration - pos
                val shouldDissolve = remaining <= crossfadeWindow ||
                    active.playbackState == Player.STATE_ENDED

                if (shouldDissolve) {
                    // Start live incoming motion from exact start while outgoing is still moving
                    if (standby.currentPosition > 60L || standby.playbackState == Player.STATE_ENDED) {
                        standby.seekTo(0L)
                    }
                    standby.playWhenReady = true
                    standby.play()

                    incomingAlpha.snapTo(0f)
                    isCrossfading = true

                    val dissolveDuration = remaining.coerceIn(180L, crossfadeWindow).toInt()
                    incomingAlpha.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = dissolveDuration,
                            easing = FastOutSlowInEasing,
                        ),
                    )

                    // Incoming is now 100% opaque — promote standby to active & reset outgoing silently
                    activeSlot = 1 - activeSlot
                    isCrossfading = false
                    incomingAlpha.snapTo(0f)

                    active.playWhenReady = false
                    active.pause()
                    active.seekTo(0L)
                    delay(120L)
                    continue
                }
            }

            delay(32L)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        val aIsActive = fallbackSinglePlayer || activeSlot == 0
        val aIsIncoming = !fallbackSinglePlayer && isCrossfading && activeSlot == 1
        val bIsIncoming = !fallbackSinglePlayer && isCrossfading && activeSlot == 0

        val aZ = if (aIsIncoming) 2f else if (aIsActive) 1f else 0f
        val bZ = if (bIsIncoming) 2f else if (!aIsActive) 1f else 0f

        val aAlpha = when {
            fallbackSinglePlayer -> 1f
            aIsActive -> 1f
            aIsIncoming -> incomingAlpha.value
            else -> 0f
        }
        val bAlpha = when {
            fallbackSinglePlayer -> 0f
            !aIsActive -> 1f
            bIsIncoming -> incomingAlpha.value
            else -> 0f
        }

        WallpaperVideoSurface(
            exoPlayer = playerA,
            contentScale = ContentScale.Crop,
            transform = transform,
            initialVideoWidth = initialVideoWidth,
            initialVideoHeight = initialVideoHeight,
            initialRotationDegrees = initialRotationDegrees,
            onVideoAspectResolved = { aspect -> latestOnAspect?.invoke(aspect) },
            onFirstFrameRendered = { latestOnFirstFrame?.invoke() },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(aZ)
                .graphicsLayer { alpha = aAlpha },
        )

        if (!fallbackSinglePlayer && playerB != null) {
            WallpaperVideoSurface(
                exoPlayer = playerB,
                contentScale = ContentScale.Crop,
                transform = transform,
                initialVideoWidth = initialVideoWidth,
                initialVideoHeight = initialVideoHeight,
                initialRotationDegrees = initialRotationDegrees,
                revealImmediately = true,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(bZ)
                    .graphicsLayer { alpha = bAlpha },
            )
        }
    }
}
