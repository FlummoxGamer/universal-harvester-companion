package app.harvester.companion.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer

/**
 * Playback & power-management utilities for motion wallpapers:
 * - Always silent with audio & text tracks disabled in TrackSelectionParameters (zero audio-clock
 *   sync jitter and zero audio-focus stealing so music/podcasts keep playing).
 * - Native frame cadence without artificial 30fps pulldown override so playback stays butter-smooth
 *   across 60Hz/90Hz/120Hz displays.
 * - Non-destructive MediaItem.ClippingConfiguration support for trimmed clips.
 * - Real-time Battery Saver observer (`PowerManager.isPowerSaveMode`).
 */
object WallpaperPlaybackController {

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun createWallpaperExoPlayer(
        context: Context,
        scalingMode: Int = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING,
        repeatOne: Boolean = true,
    ): ExoPlayer {
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 4000,
                /* maxBufferMs = */ 15000,
                /* bufferForPlaybackMs = */ 250,
                /* bufferForPlaybackAfterRebufferMs = */ 500,
            )
            .build()

        return ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ false)
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
            .build()
            .apply {
                volume = 0f
                repeatMode = if (repeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                videoScalingMode = scalingMode
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            }
    }

    /**
     * Builds an ExoPlayer [MediaItem], applying non-destructive [clipBounds] when trimmed.
     */
    fun buildMediaItem(uri: Uri, clipBounds: VideoClipBounds? = null): MediaItem {
        val builder = MediaItem.Builder().setUri(uri)
        if (clipBounds != null && clipBounds.isValid) {
            builder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clipBounds.startMs.coerceAtLeast(0L))
                    .setEndPositionMs(clipBounds.endMs)
                    .build()
            )
        }
        return builder.build()
    }

    fun isPowerSaveMode(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isPowerSaveMode == true
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * Observes Android Battery Saver state in real time so motion wallpapers pause seamlessly
 * into their crisp static poster frame while Power Save mode is active.
 */
@Composable
fun rememberPowerSaveMode(): State<Boolean> {
    val context = LocalContext.current
    val state = remember(context) {
        mutableStateOf(WallpaperPlaybackController.isPowerSaveMode(context))
    }

    DisposableEffect(context) {
        state.value = WallpaperPlaybackController.isPowerSaveMode(context)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                state.value = WallpaperPlaybackController.isPowerSaveMode(context)
            }
        }
        val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } catch (_: Exception) {
        }
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
    }

    return state
}
