package app.harvester.companion.media

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Rational
import androidx.core.content.ContextCompat
import app.harvester.companion.GeckoWebExtensionBridge
import app.harvester.companion.UHLog
import app.harvester.companion.core.network.UhHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.mediasession.MediaSession
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

data class UhMediaSnapshot(
    val tabId: String = "",
    val title: String = "",
    val artist: String = "",
    val pageUrl: String = "",
    val artworkUrl: String = "",
    val artworkBitmap: Bitmap? = null,
    val isPrivate: Boolean = false,
    val isPlaying: Boolean = false,
    val isActive: Boolean = false,
    val hasVideo: Boolean = false,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val playbackRate: Float = 1f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val updatedAtMs: Long = 0L,
)

/**
 * Unified Media Playback State & Control Hub for UH Browser.
 *
 * Merges GeckoView native [MediaSession] state with DOM-level metadata/video dimensions
 * from `uh_bridge/content.js`, formats clean non-URL titles, caches high-res artwork bitmaps,
 * drives [UhMediaSessionService], and supplies accurate video aspect ratios for Video-Only PiP.
 */
object UhMediaController {

    private val initialized = AtomicBoolean(false)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var storeRef: BrowserStore? = null

    @Volatile
    private var activeTabId: String = ""

    @Volatile
    private var webTitle: String = ""
    @Volatile
    private var webArtist: String = ""
    @Volatile
    private var webArtworkUrl: String = ""
    @Volatile
    private var webPageUrl: String = ""
    @Volatile
    private var webPlaying: Boolean = false
    @Volatile
    private var webHasVideo: Boolean = false
    @Volatile
    private var webDurationMs: Long = 0L
    @Volatile
    private var webPositionMs: Long = 0L
    @Volatile
    private var webVideoWidth: Int = 0
    @Volatile
    private var webVideoHeight: Int = 0
    @Volatile
    private var webLastUpdateMs: Long = 0L

    @Volatile
    private var geckoArtworkBitmap: Bitmap? = null
    @Volatile
    private var geckoArtworkKey: String = ""

    private val artworkCache = object : LruCache<String, Bitmap>(12) {}

    private val _state = MutableStateFlow(UhMediaSnapshot())
    val state: StateFlow<UhMediaSnapshot> = _state.asStateFlow()

    fun init(ctx: Context, store: BrowserStore) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = ctx.applicationContext
        storeRef = store

        ioScope.launch {
            store.stateFlow.collect { browserState ->
                val tabs = browserState.tabs
                val playingTab = tabs.firstOrNull {
                    it.mediaSessionState?.playbackState == MediaSession.PlaybackState.PLAYING
                }
                val pausedTab = tabs.firstOrNull {
                    it.id == activeTabId &&
                        it.mediaSessionState?.playbackState == MediaSession.PlaybackState.PAUSED
                } ?: tabs.firstOrNull {
                    it.mediaSessionState?.playbackState == MediaSession.PlaybackState.PAUSED
                }

                val targetTab = playingTab ?: pausedTab
                if (targetTab != null) {
                    activeTabId = targetTab.id
                    reconcileFromTab(targetTab)
                } else {
                    // Check if web content still reports active recent media on the selected tab
                    val sel = browserState.selectedTab
                    val webFresh = (System.currentTimeMillis() - webLastUpdateMs) < 12_000L
                    if (sel != null && webFresh && (webPlaying || _state.value.isActive)) {
                        activeTabId = sel.id
                        reconcileFromWebOnly(sel)
                    } else if (_state.value.isActive) {
                        clearMediaState()
                    }
                }
            }
        }
    }

    fun onWebMediaState(json: JSONObject) {
        val active = json.optBoolean("active", false)
        if (!active) {
            webPlaying = false
            webHasVideo = false
            webLastUpdateMs = 0L
            val tab = findActiveTab()
            val geckoPlaying = tab?.mediaSessionState?.playbackState == MediaSession.PlaybackState.PLAYING
            val geckoPaused = tab?.mediaSessionState?.playbackState == MediaSession.PlaybackState.PAUSED
            if (!geckoPlaying && !geckoPaused) {
                clearMediaState()
            } else if (tab != null) {
                reconcileFromTab(tab)
            }
            return
        }

        webTitle = json.optString("title", "").trim()
        webArtist = json.optString("artist", "").trim()
        webArtworkUrl = json.optString("artworkUrl", "").trim()
        webPageUrl = json.optString("pageUrl", "").trim()
        webPlaying = json.optBoolean("playing", false)
        webHasVideo = json.optBoolean("hasVideo", false)
        val durSec = json.optDouble("durationSec", 0.0)
        val posSec = json.optDouble("positionSec", 0.0)
        webDurationMs = if (durSec.isFinite() && durSec > 0.0) (durSec * 1000.0).toLong() else 0L
        webPositionMs = if (posSec.isFinite() && posSec >= 0.0) (posSec * 1000.0).toLong() else 0L
        webVideoWidth = json.optInt("videoWidth", 0).coerceAtLeast(0)
        webVideoHeight = json.optInt("videoHeight", 0).coerceAtLeast(0)
        webLastUpdateMs = System.currentTimeMillis()

        val tab = findActiveTab() ?: storeRef?.state?.selectedTab
        if (tab != null) {
            activeTabId = tab.id
            if (tab.mediaSessionState != null) {
                reconcileFromTab(tab)
            } else {
                reconcileFromWebOnly(tab)
            }
        }
    }

    private fun reconcileFromTab(tab: TabSessionState) {
        val ms = tab.mediaSessionState ?: return
        val isPrivate = tab.content.private
        val pageUrl = tab.content.url.ifBlank { webPageUrl }
        val isPlaying = ms.playbackState == MediaSession.PlaybackState.PLAYING
        val isPaused = ms.playbackState == MediaSession.PlaybackState.PAUSED
        if (!isPlaying && !isPaused) {
            clearMediaState()
            return
        }

        val rawTitle = pickCleanTitle(
            ms.metadata.title,
            webTitle,
            tab.content.title,
            pageUrl,
        )
        val rawArtist = pickCleanArtist(
            ms.metadata.artist,
            ms.metadata.album,
            webArtist,
            pageUrl,
        )

        val geckoDur = ms.positionState?.duration ?: 0.0
        val geckoPos = ms.positionState?.position ?: 0.0
        val durationMs = when {
            geckoDur.isFinite() && geckoDur > 0.0 -> (geckoDur * 1000.0).toLong()
            webDurationMs > 0L -> webDurationMs
            else -> 0L
        }
        val positionMs = when {
            geckoPos.isFinite() && geckoPos >= 0.0 -> (geckoPos * 1000.0).toLong()
            webPositionMs > 0L -> webPositionMs
            else -> 0L
        }
        val rate = (ms.positionState?.playbackRate ?: 1.0).toFloat().coerceIn(0.25f, 4f)

        val artKey = "${tab.id}|$rawTitle|$pageUrl"
        if (!isPrivate && geckoArtworkKey != artKey) {
            geckoArtworkKey = artKey
            geckoArtworkBitmap = null
            val getter = ms.metadata.getArtwork
            if (getter != null) {
                ioScope.launch {
                    try {
                        val bmp = getter.invoke()
                        if (bmp != null && !bmp.isRecycled && geckoArtworkKey == artKey) {
                            geckoArtworkBitmap = bmp
                            updateArtworkInState(bmp)
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        val cachedArt = if (isPrivate) {
            null
        } else {
            geckoArtworkBitmap ?: webArtworkUrl.takeIf { it.isNotBlank() }?.let { artworkCache.get(it) }
        }

        if (!isPrivate && cachedArt == null && webArtworkUrl.isNotBlank()) {
            fetchArtworkAsync(webArtworkUrl)
        }

        val snapshot = UhMediaSnapshot(
            tabId = tab.id,
            title = if (isPrivate) "Private Media" else rawTitle,
            artist = if (isPrivate) "Private Browsing" else rawArtist,
            pageUrl = pageUrl,
            artworkUrl = if (isPrivate) "" else webArtworkUrl,
            artworkBitmap = cachedArt,
            isPrivate = isPrivate,
            isPlaying = isPlaying,
            isActive = true,
            hasVideo = webHasVideo || tab.content.fullScreen || ms.fullscreen,
            durationMs = durationMs,
            positionMs = positionMs,
            playbackRate = if (isPlaying) rate else 0f,
            videoWidth = webVideoWidth,
            videoHeight = webVideoHeight,
            updatedAtMs = System.currentTimeMillis(),
        )
        publishSnapshot(snapshot)
    }

    private fun reconcileFromWebOnly(tab: TabSessionState) {
        val isPrivate = tab.content.private
        val pageUrl = tab.content.url.ifBlank { webPageUrl }
        val rawTitle = pickCleanTitle(webTitle, tab.content.title, null, pageUrl)
        val rawArtist = pickCleanArtist(webArtist, null, null, pageUrl)
        val cachedArt = if (isPrivate) {
            null
        } else {
            webArtworkUrl.takeIf { it.isNotBlank() }?.let { artworkCache.get(it) }
        }
        if (!isPrivate && cachedArt == null && webArtworkUrl.isNotBlank()) {
            fetchArtworkAsync(webArtworkUrl)
        }

        val snapshot = UhMediaSnapshot(
            tabId = tab.id,
            title = if (isPrivate) "Private Media" else rawTitle,
            artist = if (isPrivate) "Private Browsing" else rawArtist,
            pageUrl = pageUrl,
            artworkUrl = if (isPrivate) "" else webArtworkUrl,
            artworkBitmap = cachedArt,
            isPrivate = isPrivate,
            isPlaying = webPlaying,
            isActive = true,
            hasVideo = webHasVideo || tab.content.fullScreen,
            durationMs = webDurationMs,
            positionMs = webPositionMs,
            playbackRate = if (webPlaying) 1f else 0f,
            videoWidth = webVideoWidth,
            videoHeight = webVideoHeight,
            updatedAtMs = System.currentTimeMillis(),
        )
        publishSnapshot(snapshot)
    }

    private fun publishSnapshot(snapshot: UhMediaSnapshot) {
        val prev = _state.value
        _state.value = snapshot
        val ctx = appContext ?: return
        try {
            val intent = Intent(ctx, UhMediaSessionService::class.java).apply {
                action = UhMediaSessionService.ACTION_SYNC_STATE
            }
            if (snapshot.isPlaying && !prev.isPlaying) {
                ContextCompat.startForegroundService(ctx, intent)
            } else {
                ctx.startService(intent)
            }
        } catch (e: Exception) {
            UHLog.w("media", "Failed notifying UhMediaSessionService: ${e.message}")
        }
    }

    private fun updateArtworkInState(bitmap: Bitmap) {
        val cur = _state.value
        if (!cur.isActive || cur.isPrivate) return
        _state.update { it.copy(artworkBitmap = bitmap, updatedAtMs = System.currentTimeMillis()) }
        val ctx = appContext ?: return
        try {
            ctx.startService(Intent(ctx, UhMediaSessionService::class.java).apply {
                action = UhMediaSessionService.ACTION_SYNC_STATE
            })
        } catch (_: Exception) {}
    }

    private fun fetchArtworkAsync(url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        if (artworkCache.get(url) != null) return
        ioScope.launch {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", UhHttpClient.DEFAULT_USER_AGENT)
                    .build()
                UhHttpClient.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val bytes = resp.body?.bytes() ?: return@use
                    if (bytes.isEmpty() || bytes.size > 4 * 1024 * 1024) return@use

                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) {
                        sample *= 2
                    }
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@use
                    artworkCache.put(url, bmp)
                    if (_state.value.artworkUrl == url) {
                        updateArtworkInState(bmp)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun clearMediaState() {
        val wasActive = _state.value.isActive
        _state.value = UhMediaSnapshot()
        activeTabId = ""
        geckoArtworkBitmap = null
        geckoArtworkKey = ""
        if (wasActive) {
            val ctx = appContext ?: return
            try {
                ctx.stopService(Intent(ctx, UhMediaSessionService::class.java))
            } catch (_: Exception) {}
        }
    }

    // ─── Playback Controls (Dual GeckoView + WebExtension) ───────────────────

    fun play() {
        mainHandler.post {
            try { findActiveTab()?.mediaSessionState?.controller?.play() } catch (_: Exception) {}
        }
        GeckoWebExtensionBridge.sendMediaControlAction("PLAY")
        _state.update { if (it.isActive) it.copy(isPlaying = true, playbackRate = 1f) else it }
    }

    fun pause() {
        mainHandler.post {
            try { findActiveTab()?.mediaSessionState?.controller?.pause() } catch (_: Exception) {}
        }
        GeckoWebExtensionBridge.sendMediaControlAction("PAUSE")
        _state.update { if (it.isActive) it.copy(isPlaying = false, playbackRate = 0f) else it }
    }

    fun togglePlayPause() {
        if (_state.value.isPlaying) pause() else play()
    }

    fun seekRelative(deltaSec: Long) {
        val cur = _state.value
        val curSec = (cur.positionMs / 1000.0).coerceAtLeast(0.0)
        val durSec = if (cur.durationMs > 0L) cur.durationMs / 1000.0 else Double.MAX_VALUE
        val targetSec = (curSec + deltaSec).coerceIn(0.0, durSec)

        mainHandler.post {
            try {
                findActiveTab()?.mediaSessionState?.controller?.seekTo(targetSec, fast = false)
            } catch (_: Exception) {}
        }
        GeckoWebExtensionBridge.sendMediaControlAction("SEEK_RELATIVE", deltaSec = deltaSec.toDouble())
        _state.update { it.copy(positionMs = (targetSec * 1000.0).toLong(), updatedAtMs = System.currentTimeMillis()) }
    }

    fun seekTo(positionMs: Long) {
        val safeMs = positionMs.coerceAtLeast(0L)
        val targetSec = safeMs / 1000.0
        mainHandler.post {
            try {
                findActiveTab()?.mediaSessionState?.controller?.seekTo(targetSec, fast = false)
            } catch (_: Exception) {}
        }
        GeckoWebExtensionBridge.sendMediaControlAction("SEEK_TO", positionSec = targetSec)
        _state.update { it.copy(positionMs = safeMs, updatedAtMs = System.currentTimeMillis()) }
    }

    fun nextTrack() {
        mainHandler.post {
            try { findActiveTab()?.mediaSessionState?.controller?.nextTrack() } catch (_: Exception) {}
        }
        GeckoWebExtensionBridge.sendMediaControlAction("NEXT")
    }

    fun previousTrack() {
        mainHandler.post {
            try { findActiveTab()?.mediaSessionState?.controller?.previousTrack() } catch (_: Exception) {}
        }
        GeckoWebExtensionBridge.sendMediaControlAction("PREV")
    }

    fun stopAndDismiss() {
        mainHandler.post {
            try {
                val ctrl = findActiveTab()?.mediaSessionState?.controller
                ctrl?.pause()
                ctrl?.stop()
            } catch (_: Exception) {}
        }
        GeckoWebExtensionBridge.sendMediaControlAction("PAUSE")
        clearMediaState()
    }

    // ─── Video-Only PiP Helpers ──────────────────────────────────────────────

    /**
     * Returns true when the currently selected browser tab has an active playing video
     * (works seamlessly for BOTH inline and fullscreen playback).
     */
    fun hasActivePlayingVideo(selectedTab: TabSessionState?): Boolean {
        if (selectedTab == null) return false
        if (selectedTab.content.fullScreen) return true
        val ms = selectedTab.mediaSessionState
        if (ms != null && ms.playbackState == MediaSession.PlaybackState.PLAYING) {
            // Confirm it's video (or video metadata hasn't explicitly said audio-only)
            val snap = _state.value
            if (snap.tabId == selectedTab.id && snap.updatedAtMs > 0L) {
                return snap.hasVideo || ms.fullscreen
            }
            return true
        }
        val snap = _state.value
        val fresh = (System.currentTimeMillis() - snap.updatedAtMs) < 15_000L
        return fresh && snap.isPlaying && snap.hasVideo && (snap.tabId.isBlank() || snap.tabId == selectedTab.id)
    }

    /**
     * Resolves a clamped Android-legal Picture-in-Picture aspect ratio [0.42f..2.39f]
     * from the real playing `<video>` dimensions, defaulting to 16:9.
     */
    fun resolveActiveVideoAspectRatio(): Rational {
        val snap = _state.value
        val w = snap.videoWidth
        val h = snap.videoHeight
        if (w <= 0 || h <= 0) return Rational(16, 9)
        val rawRatio = w.toFloat() / h.toFloat()
        val clamped = rawRatio.coerceIn(0.42f, 2.39f)
        val scaledW = (clamped * 1000f).roundToInt().coerceAtLeast(420)
        return Rational(scaledW, 1000)
    }

    private fun findActiveTab(): TabSessionState? {
        val st = storeRef?.state ?: return null
        if (activeTabId.isNotBlank()) {
            st.tabs.firstOrNull { it.id == activeTabId }?.let { return it }
        }
        return st.tabs.firstOrNull {
            it.mediaSessionState?.playbackState == MediaSession.PlaybackState.PLAYING
        } ?: st.selectedTab
    }

    // ─── Clean Title & Artist Formatting (Never Raw URLs) ────────────────────

    private fun looksLikeUrl(value: String?): Boolean {
        val t = value?.trim().orEmpty()
        if (t.isBlank()) return true
        if (t.startsWith("http://", ignoreCase = true) ||
            t.startsWith("https://", ignoreCase = true) ||
            t.startsWith("about:", ignoreCase = true) ||
            t.startsWith("blob:", ignoreCase = true)
        ) return true
        // Reject raw host/path strings like "m.youtube.com/watch?v=..."
        if (!t.contains(' ') && t.contains('.') && t.contains('/')) return true
        return false
    }

    private fun cleanHost(url: String): String {
        if (url.isBlank()) return ""
        return try {
            val h = URI(url).host.orEmpty().lowercase(Locale.ROOT)
            h.removePrefix("www.").removePrefix("m.")
        } catch (_: Exception) {
            url.removePrefix("https://").removePrefix("http://")
                .substringBefore('/')
                .lowercase(Locale.ROOT)
                .removePrefix("www.")
                .removePrefix("m.")
        }
    }

    private fun friendlySiteName(url: String): String {
        val host = cleanHost(url)
        if (host.isBlank()) return "Playing Media"
        return when {
            host == "youtube.com" || host == "youtu.be" || host.endsWith(".youtube.com") -> "YouTube"
            host == "music.youtube.com" -> "YouTube Music"
            host == "twitch.tv" || host.endsWith(".twitch.tv") -> "Twitch"
            host == "vimeo.com" -> "Vimeo"
            host == "spotify.com" || host.endsWith(".spotify.com") -> "Spotify"
            host == "soundcloud.com" -> "SoundCloud"
            host == "dailymotion.com" -> "Dailymotion"
            else -> host.substringBefore('.').replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
            }.ifBlank { host }
        }
    }

    private fun pickCleanTitle(vararg candidates: String?): String {
        // Last arg is pageUrl
        val url = candidates.lastOrNull().orEmpty()
        for (i in 0 until candidates.size - 1) {
            val raw = candidates[i]?.trim().orEmpty()
            if (raw.isNotBlank() && !looksLikeUrl(raw)) {
                return raw
                    .replace(Regex("\\s*-\\s*YouTube\\s*$", RegexOption.IGNORE_CASE), "")
                    .trim()
                    .ifBlank { raw }
            }
        }
        return friendlySiteName(url)
    }

    private fun pickCleanArtist(vararg candidates: String?): String {
        val url = candidates.lastOrNull().orEmpty()
        for (i in 0 until candidates.size - 1) {
            val raw = candidates[i]?.trim().orEmpty()
            if (raw.isNotBlank() && !looksLikeUrl(raw)) {
                return raw
            }
        }
        return cleanHost(url).ifBlank { "UH Browser" }
    }
}
