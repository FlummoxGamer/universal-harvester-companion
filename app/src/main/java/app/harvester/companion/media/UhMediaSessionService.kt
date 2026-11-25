package app.harvester.companion.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import app.harvester.companion.HomeActivity
import app.harvester.companion.R
import app.harvester.companion.UHLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Rich Media Playback Foreground Service (Firefox / Brave grade).
 *
 * Features:
 * - Full [MediaSessionCompat] with live seekbar scrubbing (`ACTION_SEEK_TO`), duration & position sync
 * - High-res album/video thumbnail artwork (`setLargeIcon`), cleanly redacted in Private Browsing
 * - Clean title & channel/site origin (never raw URLs)
 * - 5 crisp media controls: Previous, Seek -10s, Play/Pause, Seek +10s, Next
 * - Tap notification to return directly to the playing tab in [HomeActivity]
 * - Automatic pause on headphone / Bluetooth audio disconnect (`ACTION_AUDIO_BECOMING_NOISY`)
 */
class UhMediaSessionService : Service() {

    companion object {
        const val CHANNEL_ID = "uh_media_playback"
        const val NOTIFICATION_ID = 4092

        const val ACTION_SYNC_STATE = "app.harvester.companion.media.SYNC_STATE"
        const val ACTION_PREV = "app.harvester.companion.media.PREV"
        const val ACTION_REPLAY_10 = "app.harvester.companion.media.REPLAY_10"
        const val ACTION_PLAY_PAUSE = "app.harvester.companion.media.PLAY_PAUSE"
        const val ACTION_FORWARD_10 = "app.harvester.companion.media.FORWARD_10"
        const val ACTION_NEXT = "app.harvester.companion.media.NEXT"
        const val ACTION_STOP = "app.harvester.companion.media.STOP"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var mediaSession: MediaSessionCompat
    private var noisyReceiverRegistered = false
    private var isForeground = false

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                UhMediaController.pause()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()

        mediaSession = MediaSessionCompat(this, "UHBrowserMedia").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { UhMediaController.play() }
                override fun onPause() { UhMediaController.pause() }
                override fun onSkipToNext() { UhMediaController.nextTrack() }
                override fun onSkipToPrevious() { UhMediaController.previousTrack() }
                override fun onFastForward() { UhMediaController.seekRelative(10L) }
                override fun onRewind() { UhMediaController.seekRelative(-10L) }
                override fun onSeekTo(pos: Long) { UhMediaController.seekTo(pos) }
                override fun onStop() {
                    UhMediaController.stopAndDismiss()
                    stopSelf()
                }
            })
            isActive = true
        }

        try {
            ContextCompat.registerReceiver(
                this,
                becomingNoisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            noisyReceiverRegistered = true
        } catch (e: Exception) {
            UHLog.w("media", "Failed registering AUDIO_BECOMING_NOISY receiver: ${e.message}")
        }

        serviceScope.launch {
            UhMediaController.state.collect { snapshot ->
                if (!snapshot.isActive) {
                    teardownNotificationAndStop()
                } else {
                    renderSnapshot(snapshot)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREV -> UhMediaController.previousTrack()
            ACTION_REPLAY_10 -> UhMediaController.seekRelative(-10L)
            ACTION_PLAY_PAUSE -> UhMediaController.togglePlayPause()
            ACTION_FORWARD_10 -> UhMediaController.seekRelative(10L)
            ACTION_NEXT -> UhMediaController.nextTrack()
            ACTION_STOP -> {
                UhMediaController.stopAndDismiss()
                teardownNotificationAndStop()
                return START_NOT_STICKY
            }
        }

        val snap = UhMediaController.state.value
        if (snap.isActive) {
            renderSnapshot(snap)
        } else {
            teardownNotificationAndStop()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!UhMediaController.state.value.isPlaying) {
            teardownNotificationAndStop()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        if (noisyReceiverRegistered) {
            try { unregisterReceiver(becomingNoisyReceiver) } catch (_: Exception) {}
            noisyReceiverRegistered = false
        }
        try {
            mediaSession.isActive = false
            mediaSession.release()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun renderSnapshot(snapshot: UhMediaSnapshot) {
        updateMediaSession(snapshot)
        val notification = buildNotification(snapshot)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        try {
            if (snapshot.isPlaying) {
                if (!isForeground) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                    isForeground = true
                } else {
                    nm.notify(NOTIFICATION_ID, notification)
                }
            } else {
                if (isForeground) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                    isForeground = false
                }
                nm.notify(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            UHLog.w("media", "Failed updating media notification: ${e.message}")
        }
    }

    private fun teardownNotificationAndStop() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        try {
            if (isForeground) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                isForeground = false
            }
            nm?.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
        stopSelf()
    }

    private fun updateMediaSession(snapshot: UhMediaSnapshot) {
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, snapshot.title.ifBlank { "Playing Media" })
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, snapshot.artist.ifBlank { "UH Browser" })
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, snapshot.title.ifBlank { "Playing Media" })
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, snapshot.artist.ifBlank { "UH Browser" })

        if (snapshot.durationMs > 0L) {
            metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, snapshot.durationMs)
        }
        val art = snapshot.artworkBitmap
        if (!snapshot.isPrivate && art != null && !art.isRecycled) {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, art)
        }
        mediaSession.setMetadata(metadataBuilder.build())

        val supportedActions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_REWIND or
            PlaybackStateCompat.ACTION_FAST_FORWARD or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_STOP

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(supportedActions)
            .setState(
                if (snapshot.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                snapshot.positionMs.coerceAtLeast(0L),
                if (snapshot.isPlaying) snapshot.playbackRate.coerceAtLeast(0.5f) else 0f,
            )
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    private fun buildNotification(snapshot: UhMediaSnapshot): Notification {
        val openTabIntent = Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (snapshot.tabId.isNotBlank()) {
                putExtra(HomeActivity.EXTRA_SELECT_TAB_ID, snapshot.tabId)
            }
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            100,
            openTabIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val deletePendingIntent = serviceActionIntent(ACTION_STOP, 106)

        val prevAction = NotificationCompat.Action(
            R.drawable.uh_ic_media_prev_24,
            "Previous",
            serviceActionIntent(ACTION_PREV, 101),
        )
        val replay10Action = NotificationCompat.Action(
            R.drawable.uh_ic_media_replay10_24,
            "Rewind 10 seconds",
            serviceActionIntent(ACTION_REPLAY_10, 102),
        )
        val playPauseAction = NotificationCompat.Action(
            if (snapshot.isPlaying) R.drawable.uh_ic_media_pause_24 else R.drawable.uh_ic_media_play_24,
            if (snapshot.isPlaying) "Pause" else "Play",
            serviceActionIntent(ACTION_PLAY_PAUSE, 103),
        )
        val forward10Action = NotificationCompat.Action(
            R.drawable.uh_ic_media_forward10_24,
            "Forward 10 seconds",
            serviceActionIntent(ACTION_FORWARD_10, 104),
        )
        val nextAction = NotificationCompat.Action(
            R.drawable.uh_ic_media_next_24,
            "Next",
            serviceActionIntent(ACTION_NEXT, 105),
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(
                if (snapshot.isPlaying) R.drawable.uh_ic_media_play_24
                else R.drawable.uh_ic_media_pause_24
            )
            .setContentTitle(snapshot.title.ifBlank { "Playing Media" })
            .setContentText(snapshot.artist.ifBlank { "UH Browser" })
            .setContentIntent(contentPendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .setVisibility(
                if (snapshot.isPrivate) NotificationCompat.VISIBILITY_PRIVATE
                else NotificationCompat.VISIBILITY_PUBLIC
            )
            .setOngoing(snapshot.isPlaying)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(prevAction)
            .addAction(replay10Action)
            .addAction(playPauseAction)
            .addAction(forward10Action)
            .addAction(nextAction)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(1, 2, 3)
            )

        val art = snapshot.artworkBitmap
        if (!snapshot.isPrivate && art != null && !art.isRecycled) {
            builder.setLargeIcon(art)
        }

        return builder.build()
    }

    private fun serviceActionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, UhMediaSessionService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Media Playback",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Playback controls for audio and video playing in UH Browser"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(ch)
        }
    }
}
