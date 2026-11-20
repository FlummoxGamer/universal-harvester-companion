package app.harvester.companion.settings.appearance

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import app.harvester.companion.R
import app.harvester.companion.UHLog
import app.harvester.companion.WallpaperStore
import app.harvester.companion.theme.UHTheme
import app.harvester.companion.wallpaper.WallpaperPlaybackController
import app.harvester.companion.wallpaper.WallpaperVideoSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

class TrimVideoActivity : AppCompatActivity() {

    private companion object {
        const val MAX_CLIP_DURATION_SEC = 30f
    }

    private data class VideoMeta(
        val durationSec: Float,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT

        val uriString = intent.getStringExtra("videoUri")
        if (uriString == null) {
            finish()
            return
        }
        val videoUri = Uri.parse(uriString)
        val meta = readVideoMeta(videoUri)
        val durationSec = meta.durationSec

        setContent {
            UHTheme(
                lightStatusBarOverride = false,
                lightNavigationBarOverride = false,
            ) {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

                // Always keep the fine trim bar at most 30 seconds wide so handles never squeeze
                // on long (e.g., 1-hour) videos; show a separate timeline window scrubber when > 30s.
                val isLongVideo = durationSec > MAX_CLIP_DURATION_SEC + 0.05f
                val windowSpanSec = min(durationSec, MAX_CLIP_DURATION_SEC)
                val maxWindowStartSec = (durationSec - windowSpanSec).coerceAtLeast(0f)

                var windowStartSec by remember { mutableFloatStateOf(0f) }
                var localRange by remember { mutableStateOf(0f..min(windowSpanSec, 15f)) }

                val absStartSec = (windowStartSec + localRange.start).coerceIn(0f, durationSec)
                val absEndSec = (windowStartSec + localRange.endInclusive).coerceIn(
                    min(durationSec, absStartSec + 1f),
                    durationSec,
                )

                var frames by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
                var isPlaying by remember { mutableStateOf(true) }
                var currentPlayheadSec by remember { mutableFloatStateOf(0f) }
                var isProcessing by remember { mutableStateOf(false) }
                var processingLabel by remember { mutableStateOf("Cutting lossless clip...") }
                var progressFraction by remember { mutableFloatStateOf(0f) }
                val animatedProgress by animateFloatAsState(targetValue = progressFraction, label = "trim_progress")

                val exoPlayer = remember(videoUri) {
                    WallpaperPlaybackController.createWallpaperExoPlayer(
                        context = context,
                        scalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT,
                    ).apply {
                        addListener(object : Player.Listener {
                            override fun onPlayerError(error: PlaybackException) {
                                UHLog.e("video", "Trim preview error: ${error.message}")
                                Toast.makeText(
                                    context,
                                    "Could not preview this video format",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        })
                        setMediaItem(MediaItem.fromUri(videoUri))
                        prepare()
                        play()
                    }
                }

                // Loop playback strictly inside the selected [absStartSec..absEndSec] slice
                LaunchedEffect(exoPlayer, absStartSec, absEndSec, isPlaying) {
                    while (isActive) {
                        val posSec = exoPlayer.currentPosition / 1000f
                        currentPlayheadSec = posSec
                        if (isPlaying && (posSec >= absEndSec || posSec < absStartSec - 0.5f)) {
                            exoPlayer.seekTo((absStartSec * 1000f).toLong())
                        }
                        delay(50)
                    }
                }

                DisposableEffect(exoPlayer) {
                    onDispose {
                        exoPlayer.stop()
                        exoPlayer.release()
                    }
                }

                // Extract filmstrip frames specifically for the active 30s window (debounced while scrubbing)
                LaunchedEffect(videoUri, windowStartSec, windowSpanSec) {
                    if (frames.isNotEmpty()) {
                        delay(180)
                    }
                    frames = withContext(Dispatchers.IO) {
                        extractWindowFrames(videoUri, windowStartSec, windowSpanSec)
                    }
                }

                fun processAndOpenPreview(useFullShortVideo: Boolean) {
                    if (isProcessing) return
                    val allowFullVerbatim = !isLongVideo && (
                        useFullShortVideo ||
                            (absStartSec <= 0.15f && (durationSec - absEndSec) <= 0.25f)
                        )

                    val startSec = if (allowFullVerbatim) null else absStartSec.coerceAtLeast(0f)
                    val clipDuration = if (allowFullVerbatim) {
                        null
                    } else {
                        (absEndSec - absStartSec).coerceIn(1f, MAX_CLIP_DURATION_SEC)
                    }

                    processingLabel = if (allowFullVerbatim) {
                        "Saving untouched original..."
                    } else {
                        "Saving lossless ${"%.1f".format(Locale.US, clipDuration ?: 0f)}s clip..."
                    }
                    isProcessing = true
                    progressFraction = 0.10f
                    exoPlayer.pause()

                    scope.launch(Dispatchers.IO) {
                        try {
                            val finalPath = WallpaperStore.saveLocalVideo(
                                ctx = this@TrimVideoActivity,
                                uri = videoUri,
                                startSec = startSec,
                                durationSec = clipDuration,
                                onProgress = { pct -> progressFraction = pct },
                            )

                            withContext(Dispatchers.Main) {
                                isProcessing = false
                                if (finalPath != null) {
                                    startActivity(
                                        Intent(this@TrimVideoActivity, WallpaperPreviewActivity::class.java).apply {
                                            putExtra("imagePath", finalPath)
                                            putExtra("isCustom", true)
                                            putExtra("isVideo", true)
                                        },
                                    )
                                    finish()
                                } else {
                                    Toast.makeText(
                                        this@TrimVideoActivity,
                                        "Could not trim this video",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        } catch (e: Exception) {
                            UHLog.e("video", "Trim import exception: ${e.message}")
                            withContext(Dispatchers.Main) {
                                isProcessing = false
                                Toast.makeText(
                                    this@TrimVideoActivity,
                                    "Error trimming video",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0A0A0E)),
                ) {
                    if (isLandscape) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1.3f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color.Black)
                                    .clickable {
                                        isPlaying = !isPlaying
                                        if (isPlaying) exoPlayer.play() else exoPlayer.pause()
                                    },
                            ) {
                                WallpaperVideoSurface(
                                    exoPlayer = exoPlayer,
                                    contentScale = ContentScale.Fit,
                                    initialVideoWidth = meta.width,
                                    initialVideoHeight = meta.height,
                                    initialRotationDegrees = meta.rotationDegrees,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                PlayPauseBadge(isPlaying = isPlaying, modifier = Modifier.align(Alignment.Center))
                                TopBarControls(
                                    onClose = { finish() },
                                    selectedDurationSec = (absEndSec - absStartSec).coerceAtLeast(1f),
                                    srcMeta = if (meta.width > 0) "${meta.width}x${meta.height}" else "Video",
                                    onTrimAndUse = { processAndOpenPreview(useFullShortVideo = false) },
                                    isProcessing = isProcessing,
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                )
                            }

                            TrimControlsPanel(
                                localRange = localRange,
                                windowStartSec = windowStartSec,
                                windowSpanSec = windowSpanSec,
                                totalDurationSec = durationSec,
                                maxWindowStartSec = maxWindowStartSec,
                                isLongVideo = isLongVideo,
                                currentPlayheadSec = currentPlayheadSec,
                                frames = frames,
                                isPlaying = isPlaying,
                                onTogglePlay = {
                                    isPlaying = !isPlaying
                                    if (isPlaying) exoPlayer.play() else exoPlayer.pause()
                                },
                                onWindowStartChange = { newStart ->
                                    windowStartSec = newStart.coerceIn(0f, maxWindowStartSec)
                                    exoPlayer.seekTo(((windowStartSec + localRange.start) * 1000f).toLong())
                                },
                                onLocalRangeChange = { updated ->
                                    localRange = updated
                                    exoPlayer.seekTo(((windowStartSec + updated.start) * 1000f).toLong())
                                },
                                onPresetDuration = { sec ->
                                    val targetLen = min(windowSpanSec, sec)
                                    val end = min(windowSpanSec, localRange.start + targetLen)
                                    val start = (end - targetLen).coerceAtLeast(0f)
                                    localRange = start..end
                                    exoPlayer.seekTo(((windowStartSec + start) * 1000f).toLong())
                                },
                                onUseFullVideo = { processAndOpenPreview(useFullShortVideo = true) },
                                isProcessing = isProcessing,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                .navigationBarsPadding(),
                        ) {
                            TopBarControls(
                                onClose = { finish() },
                                selectedDurationSec = (absEndSec - absStartSec).coerceAtLeast(1f),
                                srcMeta = if (meta.width > 0) "${meta.width}x${meta.height}" else "Video",
                                onTrimAndUse = { processAndOpenPreview(useFullShortVideo = false) },
                                isProcessing = isProcessing,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                            )

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(Color.Black)
                                    .clickable {
                                        isPlaying = !isPlaying
                                        if (isPlaying) exoPlayer.play() else exoPlayer.pause()
                                    },
                            ) {
                                WallpaperVideoSurface(
                                    exoPlayer = exoPlayer,
                                    contentScale = ContentScale.Fit,
                                    initialVideoWidth = meta.width,
                                    initialVideoHeight = meta.height,
                                    initialRotationDegrees = meta.rotationDegrees,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                PlayPauseBadge(isPlaying = isPlaying, modifier = Modifier.align(Alignment.Center))
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            TrimControlsPanel(
                                localRange = localRange,
                                windowStartSec = windowStartSec,
                                windowSpanSec = windowSpanSec,
                                totalDurationSec = durationSec,
                                maxWindowStartSec = maxWindowStartSec,
                                isLongVideo = isLongVideo,
                                currentPlayheadSec = currentPlayheadSec,
                                frames = frames,
                                isPlaying = isPlaying,
                                onTogglePlay = {
                                    isPlaying = !isPlaying
                                    if (isPlaying) exoPlayer.play() else exoPlayer.pause()
                                },
                                onWindowStartChange = { newStart ->
                                    windowStartSec = newStart.coerceIn(0f, maxWindowStartSec)
                                    exoPlayer.seekTo(((windowStartSec + localRange.start) * 1000f).toLong())
                                },
                                onLocalRangeChange = { updated ->
                                    localRange = updated
                                    exoPlayer.seekTo(((windowStartSec + updated.start) * 1000f).toLong())
                                },
                                onPresetDuration = { sec ->
                                    val targetLen = min(windowSpanSec, sec)
                                    val end = min(windowSpanSec, localRange.start + targetLen)
                                    val start = (end - targetLen).coerceAtLeast(0f)
                                    localRange = start..end
                                    exoPlayer.seekTo(((windowStartSec + start) * 1000f).toLong())
                                },
                                onUseFullVideo = { processAndOpenPreview(useFullShortVideo = true) },
                                isProcessing = isProcessing,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }

                    // Processing Progress Overlay
                    if (isProcessing) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.72f))
                                .clickable(enabled = false) {},
                            contentAlignment = Alignment.Center,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(22.dp),
                                color = Color(0xFF1A1A24),
                                tonalElevation = 8.dp,
                                modifier = Modifier
                                    .padding(24.dp)
                                    .fillMaxWidth(if (isLandscape) 0.48f else 0.86f),
                            ) {
                                Column(
                                    modifier = Modifier.padding(22.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    CircularProgressIndicator(
                                        progress = { animatedProgress.coerceIn(0.08f, 1f) },
                                        modifier = Modifier.size(44.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Text(
                                        text = "Saving Motion Wallpaper",
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "$processingLabel (${(animatedProgress * 100).toInt()}%)",
                                        color = Color.White.copy(alpha = 0.72f),
                                        fontSize = 13.sp,
                                    )
                                    Spacer(modifier = Modifier.height(14.dp))
                                    LinearProgressIndicator(
                                        progress = { animatedProgress.coerceIn(0.08f, 1f) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp)),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = Color.White.copy(alpha = 0.14f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun readVideoMeta(uri: Uri): VideoMeta {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            VideoMeta((durMs / 1000f).coerceAtLeast(1f), w, h, rot)
        } catch (_: Exception) {
            VideoMeta(15f, 0, 0, 0)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun extractWindowFrames(uri: Uri, windowStartSec: Float, windowSpanSec: Float): List<Bitmap> {
        val frames = mutableListOf<Bitmap>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            val numFrames = 12
            val interval = (windowSpanSec / numFrames).coerceAtLeast(0.25f)
            for (i in 0 until numFrames) {
                val timeUs = ((windowStartSec + i * interval) * 1_000_000f).toLong().coerceAtLeast(0L)
                val bmp = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 144, 144)
                if (bmp != null) frames.add(bmp)
            }
        } catch (_: Exception) {
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        return frames
    }
}

@Composable
private fun PlayPauseBadge(isPlaying: Boolean, modifier: Modifier = Modifier) {
    if (!isPlaying) {
        Box(
            modifier = modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.58f))
                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = R.drawable.fx_ic_play_24),
                contentDescription = "Play",
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun TopBarControls(
    onClose: () -> Unit,
    selectedDurationSec: Float,
    srcMeta: String,
    onTrimAndUse: () -> Unit,
    isProcessing: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.52f)),
        ) {
            Icon(
                painter = painterResource(id = R.drawable.fx_ic_close_24),
                contentDescription = "Close",
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.Black.copy(alpha = 0.52f),
        ) {
            Text(
                text = "${"%.1f".format(Locale.US, selectedDurationSec)}s • $srcMeta",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        Button(
            enabled = !isProcessing,
            onClick = onTrimAndUse,
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
            ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Icon(
                painter = painterResource(id = R.drawable.fx_ic_check_24),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Trim & Use", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun TrimControlsPanel(
    localRange: ClosedFloatingPointRange<Float>,
    windowStartSec: Float,
    windowSpanSec: Float,
    totalDurationSec: Float,
    maxWindowStartSec: Float,
    isLongVideo: Boolean,
    currentPlayheadSec: Float,
    frames: List<Bitmap>,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onWindowStartChange: (Float) -> Unit,
    onLocalRangeChange: (ClosedFloatingPointRange<Float>) -> Unit,
    onPresetDuration: (Float) -> Unit,
    onUseFullVideo: () -> Unit,
    isProcessing: Boolean,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val absStart = windowStartSec + localRange.start
    val absEnd = (windowStartSec + localRange.endInclusive).coerceAtMost(totalDurationSec)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF15151E),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(
                        onClick = onTogglePlay,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.1f)),
                    ) {
                        Icon(
                            painter = painterResource(
                                id = if (isPlaying) R.drawable.fx_ic_pause_24 else R.drawable.fx_ic_play_24,
                            ),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Column {
                        Text(
                            text = "${formatTime(absStart)}  —  ${formatTime(absEnd)}",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Saves trimmed clip only • Lossless original quality",
                            color = Color.White.copy(alpha = 0.58f),
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            // Separate full-video timeline window scrubber ONLY for videos longer than 30s
            if (isLongVideo && maxWindowStartSec > 0.1f) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "30s Window: ${formatTime(windowStartSec, showTenths = false)} – ${formatTime(windowStartSec + windowSpanSec, showTenths = false)}",
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Total ${formatTime(totalDurationSec, showTenths = false)}",
                            color = Color.White.copy(alpha = 0.52f),
                            fontSize = 11.sp,
                        )
                    }
                    Slider(
                        value = windowStartSec.coerceIn(0f, maxWindowStartSec),
                        onValueChange = onWindowStartChange,
                        valueRange = 0f..maxWindowStartSec,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White.copy(alpha = 0.55f),
                            inactiveTrackColor = Color.White.copy(alpha = 0.18f),
                        ),
                        modifier = Modifier.height(28.dp),
                    )
                }
            }

            // Full-width 30s (or exact duration if <= 30s) Frame Strip + Active Trim Overlay
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF232330)),
            ) {
                if (frames.isNotEmpty()) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        frames.forEach { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }
                    }
                }

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val safeSpan = windowSpanSec.coerceAtLeast(1f)
                    val startFrac = (localRange.start / safeSpan).coerceIn(0f, 1f)
                    val endFrac = (localRange.endInclusive / safeSpan).coerceIn(startFrac, 1f)
                    val playFrac = ((currentPlayheadSec - windowStartSec) / safeSpan).coerceIn(0f, 1f)

                    val startX = size.width * startFrac
                    val endX = size.width * endFrac
                    val playX = size.width * playFrac

                    if (startX > 0f) {
                        drawRect(Color.Black.copy(alpha = 0.62f), Offset.Zero, Size(startX, size.height))
                    }
                    if (endX < size.width) {
                        drawRect(
                            Color.Black.copy(alpha = 0.62f),
                            Offset(endX, 0f),
                            Size(size.width - endX, size.height),
                        )
                    }

                    drawRect(
                        color = primary,
                        topLeft = Offset(startX, 0f),
                        size = Size((endX - startX).coerceAtLeast(4f), size.height),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()),
                    )

                    drawLine(
                        color = Color.White,
                        start = Offset(playX, 0f),
                        end = Offset(playX, size.height),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            }

            // Zoomed Trim RangeSlider strictly bounded to 0..windowSpanSec (<= 30s)
            RangeSlider(
                value = localRange,
                onValueChange = { updated ->
                    var s = updated.start.coerceIn(0f, windowSpanSec)
                    var e = updated.endInclusive.coerceIn(0f, windowSpanSec)
                    if (e - s < 1f) {
                        e = min(windowSpanSec, s + 1f)
                        s = (e - 1f).coerceAtLeast(0f)
                    }
                    onLocalRangeChange(s..e)
                },
                valueRange = 0f..windowSpanSec,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = primary,
                    inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                ),
            )

            // Quick Loop Length Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(5f to "5s", 10f to "10s", 15f to "15s", 30f to "30s").forEach { (sec, label) ->
                    val enabled = windowSpanSec >= sec - 0.5f || label == "5s"
                    val isSelected = abs((localRange.endInclusive - localRange.start) - min(windowSpanSec, sec)) < 0.8f
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) primary.copy(alpha = 0.26f) else Color.White.copy(alpha = 0.08f),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = enabled) { onPresetDuration(sec) },
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) primary else Color.White.copy(alpha = if (enabled) 0.9f else 0.35f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            // Only allow Use Full Video when total duration is <= 30s (enforces 30s max saved clip)
            if (!isLongVideo) {
                OutlinedButton(
                    enabled = !isProcessing,
                    onClick = onUseFullVideo,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Use Full Video (${formatTime(totalDurationSec)} • Instant)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

private fun formatTime(sec: Float, showTenths: Boolean = true): String {
    val totalTenths = (sec * 10).toInt().coerceAtLeast(0)
    val totalWholeSec = totalTenths / 10
    val hours = totalWholeSec / 3600
    val mins = (totalWholeSec % 3600) / 60
    val remSec = totalWholeSec % 60
    val tenths = totalTenths % 10
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, mins, remSec)
    } else if (showTenths) {
        String.format(Locale.US, "%d:%02d.%d", mins, remSec, tenths)
    } else {
        String.format(Locale.US, "%d:%02d", mins, remSec)
    }
}
