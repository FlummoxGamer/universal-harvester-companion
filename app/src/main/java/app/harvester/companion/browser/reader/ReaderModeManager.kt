package app.harvester.companion.browser.reader

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.harvester.companion.GeckoWebExtensionBridge
import app.harvester.companion.UHLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class ReaderTheme(val id: String, val label: String) {
    LIGHT("light", "Light"),
    SEPIA("sepia", "Sepia"),
    DARK("dark", "Dark"),
    OLED("oled", "OLED")
}

enum class ReaderFont(val id: String, val label: String) {
    SANS("sans", "Sans"),
    SERIF("serif", "Serif"),
    MONO("mono", "Mono")
}

enum class ReaderLineSpacing(val value: Float, val label: String) {
    COMPACT(1.45f, "Compact"),
    COMFORTABLE(1.72f, "Normal"),
    RELAXED(2.05f, "Wide")
}

enum class ReaderMargin(val px: Int, val label: String) {
    NARROW(12, "Narrow"),
    MEDIUM(22, "Medium"),
    WIDE(36, "Wide")
}

data class ReaderPrefsState(
    val theme: ReaderTheme = ReaderTheme.DARK,
    val font: ReaderFont = ReaderFont.SANS,
    val fontSizePx: Int = 18,
    val lineSpacing: ReaderLineSpacing = ReaderLineSpacing.COMFORTABLE,
    val margin: ReaderMargin = ReaderMargin.MEDIUM,
    val showImages: Boolean = true,
    val ttsSpeed: Float = 1.0f
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("theme", theme.id)
        put("fontFamily", font.id)
        put("fontSizePx", fontSizePx)
        put("lineHeight", lineSpacing.value.toDouble())
        put("marginPx", margin.px)
        put("showImages", showImages)
    }
}

data class ReaderUiState(
    val currentUrl: String = "",
    val currentHost: String = "",
    val isEligible: Boolean = false,
    val isActive: Boolean = false,
    val title: String = "",
    val readMins: Int = 0,
    val wordCount: Int = 0,
    val paragraphs: List<String> = emptyList(),
    val controlsSheetVisible: Boolean = false,
    val autoReaderHosts: Set<String> = emptySet(),
    val prefs: ReaderPrefsState = ReaderPrefsState(),
    val ttsPlaying: Boolean = false,
    val ttsIndex: Int = -1
) {
    val isAutoReaderForCurrentHost: Boolean
        get() = currentHost.isNotBlank() && currentHost in autoReaderHosts
}

/**
 * Coordinates in-page Readability Reader Mode, persisted typography/theme preferences,
 * per-domain Auto-Reader memory, and native Android TextToSpeech Read Aloud with live highlighting.
 */
object ReaderModeManager {

    private const val PREFS_NAME = "uh_reader_mode_prefs"
    private const val K_THEME = "theme"
    private const val K_FONT = "font"
    private const val K_FONT_SIZE = "font_size"
    private const val K_LINE_SPACING = "line_spacing"
    private const val K_MARGIN = "margin"
    private const val K_SHOW_IMAGES = "show_images"
    private const val K_TTS_SPEED = "tts_speed"
    private const val K_AUTO_HOSTS = "auto_reader_hosts"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var sharedPrefs: SharedPreferences? = null
    @Volatile
    private var tts: TextToSpeech? = null
    @Volatile
    private var ttsReady = false
    @Volatile
    private var autoEnteredUrl: String = ""

    fun init(ctx: Context) {
        if (appContext != null) return
        val app = ctx.applicationContext
        appContext = app
        val sp = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPrefs = sp

        val theme = ReaderTheme.entries.firstOrNull { it.id == sp.getString(K_THEME, "dark") } ?: ReaderTheme.DARK
        val font = ReaderFont.entries.firstOrNull { it.id == sp.getString(K_FONT, "sans") } ?: ReaderFont.SANS
        val size = sp.getInt(K_FONT_SIZE, 18).coerceIn(14, 28)
        val lhVal = sp.getFloat(K_LINE_SPACING, 1.72f)
        val lineSpacing = ReaderLineSpacing.entries.minByOrNull { kotlin.math.abs(it.value - lhVal) }
            ?: ReaderLineSpacing.COMFORTABLE
        val marginPx = sp.getInt(K_MARGIN, 22)
        val margin = ReaderMargin.entries.minByOrNull { kotlin.math.abs(it.px - marginPx) } ?: ReaderMargin.MEDIUM
        val showImages = sp.getBoolean(K_SHOW_IMAGES, true)
        val ttsSpeed = sp.getFloat(K_TTS_SPEED, 1.0f).coerceIn(0.75f, 2.0f)
        val autoHosts = sp.getStringSet(K_AUTO_HOSTS, emptySet())?.toSet() ?: emptySet()

        _state.update {
            it.copy(
                autoReaderHosts = autoHosts,
                prefs = ReaderPrefsState(
                    theme = theme,
                    font = font,
                    fontSizePx = size,
                    lineSpacing = lineSpacing,
                    margin = margin,
                    showImages = showImages,
                    ttsSpeed = ttsSpeed
                )
            )
        }
    }

    fun onActiveTabChanged(url: String) {
        stopTts()
        val host = extractCleanHost(url)
        val isHttp = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
        _state.update {
            it.copy(
                currentUrl = url,
                currentHost = host,
                isEligible = false,
                isActive = false,
                controlsSheetVisible = false,
                paragraphs = emptyList(),
                ttsIndex = -1,
                ttsPlaying = false
            )
        }
        if (isHttp) {
            GeckoWebExtensionBridge.sendReaderCommand("READER_CHECK")
        }
    }

    fun onEligibilityFromPage(url: String, host: String, eligible: Boolean, active: Boolean) {
        val cleanHost = host.lowercase().removePrefix("www.")
        val current = _state.value
        _state.update {
            it.copy(
                currentUrl = url.ifBlank { it.currentUrl },
                currentHost = cleanHost.ifBlank { it.currentHost },
                isEligible = eligible,
                isActive = active
            )
        }
        // Per-domain Auto-Reader: automatically engage Reader Mode once per URL
        if (eligible && !active && cleanHost.isNotBlank() && cleanHost in current.autoReaderHosts && url != autoEnteredUrl) {
            autoEnteredUrl = url
            mainHandler.post {
                enterReader()
            }
        }
    }

    fun onReaderEnteredFromPage(
        url: String,
        host: String,
        title: String,
        readMins: Int,
        wordCount: Int,
        paragraphsArray: JSONArray?
    ) {
        val paragraphs = mutableListOf<String>()
        if (paragraphsArray != null) {
            for (i in 0 until paragraphsArray.length()) {
                val p = paragraphsArray.optString(i).trim()
                if (p.isNotEmpty()) paragraphs.add(p)
            }
        }
        _state.update {
            it.copy(
                currentUrl = url.ifBlank { it.currentUrl },
                currentHost = host.lowercase().removePrefix("www.").ifBlank { it.currentHost },
                isEligible = true,
                isActive = true,
                title = title,
                readMins = readMins,
                wordCount = wordCount,
                paragraphs = paragraphs,
                ttsIndex = -1,
                ttsPlaying = false
            )
        }
    }

    fun onReaderExitedFromPage() {
        stopTts()
        _state.update {
            it.copy(
                isActive = false,
                controlsSheetVisible = false,
                ttsPlaying = false,
                ttsIndex = -1
            )
        }
    }

    fun openControlsSheet() {
        _state.update { it.copy(controlsSheetVisible = true) }
    }

    fun closeControlsSheet() {
        _state.update { it.copy(controlsSheetVisible = false) }
    }

    fun toggleReader() {
        val s = _state.value
        if (s.isActive) {
            exitReader()
        } else {
            enterReader()
        }
    }

    fun enterReader() {
        val prefsJson = _state.value.prefs.toJson()
        GeckoWebExtensionBridge.sendReaderCommand("READER_ENTER", prefsJson)
    }

    fun exitReader() {
        stopTts()
        GeckoWebExtensionBridge.sendReaderCommand("READER_EXIT")
        _state.update { it.copy(isActive = false, controlsSheetVisible = false) }
    }

    fun updatePrefs(transform: (ReaderPrefsState) -> ReaderPrefsState) {
        val updated = transform(_state.value.prefs)
        _state.update { it.copy(prefs = updated) }
        persistPrefs(updated)
        if (_state.value.isActive) {
            GeckoWebExtensionBridge.sendReaderCommand("READER_UPDATE_STYLE", updated.toJson())
        }
        if (_state.value.ttsPlaying) {
            try {
                tts?.setSpeechRate(updated.ttsSpeed)
            } catch (_: Exception) {}
        }
    }

    fun toggleAutoReaderForCurrentHost(enabled: Boolean) {
        val host = _state.value.currentHost
        if (host.isBlank()) return
        val next = if (enabled) _state.value.autoReaderHosts + host else _state.value.autoReaderHosts - host
        _state.update { it.copy(autoReaderHosts = next) }
        sharedPrefs?.edit()?.putStringSet(K_AUTO_HOSTS, next)?.apply()
    }

    // ---- Android TextToSpeech Read Aloud Engine ----

    fun togglePlayPauseTts(ctx: Context) {
        init(ctx)
        val s = _state.value
        if (s.ttsPlaying) {
            pauseTts()
            return
        }
        if (s.paragraphs.isEmpty()) return
        val startIdx = if (s.ttsIndex in s.paragraphs.indices) s.ttsIndex else 0
        speakFromIndex(ctx, startIdx)
    }

    fun skipTts(ctx: Context, delta: Int) {
        val s = _state.value
        if (s.paragraphs.isEmpty()) return
        val base = if (s.ttsIndex >= 0) s.ttsIndex else 0
        val nextIdx = (base + delta).coerceIn(0, s.paragraphs.lastIndex)
        speakFromIndex(ctx, nextIdx)
    }

    fun setTtsSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.75f, 2.0f)
        updatePrefs { it.copy(ttsSpeed = clamped) }
        if (_state.value.ttsPlaying && _state.value.ttsIndex in _state.value.paragraphs.indices) {
            val ctx = appContext ?: return
            speakFromIndex(ctx, _state.value.ttsIndex)
        }
    }

    fun pauseTts() {
        try {
            tts?.stop()
        } catch (_: Exception) {}
        _state.update { it.copy(ttsPlaying = false) }
    }

    fun stopTts() {
        try {
            tts?.stop()
        } catch (_: Exception) {}
        if (_state.value.isActive && _state.value.ttsIndex >= 0) {
            GeckoWebExtensionBridge.sendReaderTtsHighlight(-1)
        }
        _state.update { it.copy(ttsPlaying = false, ttsIndex = -1) }
    }

    private fun speakFromIndex(ctx: Context, index: Int) {
        val paragraphs = _state.value.paragraphs
        if (index !in paragraphs.indices) {
            stopTts()
            return
        }

        val engine = tts
        if (engine != null && ttsReady) {
            startUtterance(engine, index)
            return
        }

        try {
            val app = ctx.applicationContext
            tts = TextToSpeech(app) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    try {
                        tts?.language = Locale.getDefault()
                        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}

                            override fun onDone(utteranceId: String?) {
                                val doneIdx = utteranceId?.removePrefix("uh_tts_")?.toIntOrNull() ?: return
                                mainHandler.post {
                                    val st = _state.value
                                    if (!st.ttsPlaying || !st.isActive) return@post
                                    val next = doneIdx + 1
                                    if (next in st.paragraphs.indices) {
                                        tts?.let { startUtterance(it, next) }
                                    } else {
                                        stopTts()
                                    }
                                }
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) {
                                mainHandler.post { pauseTts() }
                            }
                        })
                    } catch (e: Exception) {
                        UHLog.w("reader", "TTS listener setup failed: ${e.message}")
                    }
                    mainHandler.post {
                        tts?.let { startUtterance(it, index) }
                    }
                } else {
                    ttsReady = false
                }
            }
        } catch (e: Exception) {
            UHLog.w("reader", "TTS initialization failed: ${e.message}")
        }
    }

    private fun startUtterance(engine: TextToSpeech, index: Int) {
        val st = _state.value
        val text = st.paragraphs.getOrNull(index) ?: return
        _state.update { it.copy(ttsPlaying = true, ttsIndex = index) }
        GeckoWebExtensionBridge.sendReaderTtsHighlight(index)
        try {
            engine.setSpeechRate(st.prefs.ttsSpeed)
            val params = Bundle()
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "uh_tts_$index")
        } catch (e: Exception) {
            UHLog.w("reader", "TTS speak failed: ${e.message}")
            pauseTts()
        }
    }

    private fun persistPrefs(p: ReaderPrefsState) {
        sharedPrefs?.edit()
            ?.putString(K_THEME, p.theme.id)
            ?.putString(K_FONT, p.font.id)
            ?.putInt(K_FONT_SIZE, p.fontSizePx)
            ?.putFloat(K_LINE_SPACING, p.lineSpacing.value)
            ?.putInt(K_MARGIN, p.margin.px)
            ?.putBoolean(K_SHOW_IMAGES, p.showImages)
            ?.putFloat(K_TTS_SPEED, p.ttsSpeed)
            ?.apply()
    }

    private fun extractCleanHost(url: String): String {
        return try {
            android.net.Uri.parse(url).host?.lowercase()?.removePrefix("www.").orEmpty()
        } catch (_: Exception) {
            ""
        }
    }
}
