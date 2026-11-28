package app.harvester.companion.browser.reader

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import app.harvester.companion.GeckoWebExtensionBridge
import app.harvester.companion.UHLog
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class ReaderTheme(
    val id: String,
    val label: String,
    val bgColorInt: Int,
    val isDark: Boolean
) {
    LIGHT("light", "Paper", 0xFFFAF9F6.toInt(), false),
    SEPIA("sepia", "Sepia", 0xFFF5ECD8.toInt(), false),
    DARK("dark", "Dark", 0xFF17181C.toInt(), true),
    OLED("oled", "OLED", 0xFF000000.toInt(), true)
}

enum class ReaderFont(val id: String, val label: String) {
    SERIF("serif", "Book Serif"),
    EDITORIAL("editorial", "Classic"),
    SANS("sans", "Clean Sans"),
    MONO("mono", "Mono")
}

enum class ReaderLineSpacing(val value: Float, val label: String) {
    COMPACT(1.52f, "Compact"),
    COMFORTABLE(1.78f, "Book"),
    RELAXED(2.08f, "Wide")
}

enum class ReaderMargin(val px: Int, val label: String) {
    NARROW(14, "Narrow"),
    MEDIUM(24, "Book"),
    WIDE(38, "Wide")
}

enum class ReaderVoiceGender(val id: String, val label: String) {
    AUTO("auto", "Auto"),
    FEMALE("female", "Female"),
    MALE("male", "Male")
}

data class ReaderVoiceOption(
    val name: String,
    val label: String,
    val gender: ReaderVoiceGender,
    val langTag: String,
    val quality: Int,
    val isNeural: Boolean = false
)

data class ReaderPrefsState(
    val theme: ReaderTheme = ReaderTheme.SEPIA,
    val font: ReaderFont = ReaderFont.SERIF,
    val fontSizePx: Int = 19,
    val lineSpacing: ReaderLineSpacing = ReaderLineSpacing.COMFORTABLE,
    val margin: ReaderMargin = ReaderMargin.MEDIUM,
    val showImages: Boolean = true,
    val ttsEnabled: Boolean = true,
    val ttsSpeed: Float = 1.0f,
    val voiceGender: ReaderVoiceGender = ReaderVoiceGender.AUTO,
    val voiceName: String = "",
    val pageMode: Boolean = false,
    val isFullscreen: Boolean = false,
    // -1f = System Auto; 0.0f..0.20f = hardware min + extra dark dimmer overlay; 0.20f..1.0f = hardware 1%..100%
    val brightness: Float = -1f
) {
    fun toJson(savedProgress: ReaderProgressStore.ArticleProgress? = null): JSONObject = JSONObject().apply {
        put("theme", theme.id)
        put("fontFamily", font.id)
        put("fontSizePx", fontSizePx)
        put("lineHeight", lineSpacing.value.toDouble())
        put("marginPx", margin.px)
        put("showImages", showImages)
        put("ttsEnabled", ttsEnabled)
        put("pageMode", pageMode)
        put("isFullscreen", isFullscreen)
        if (savedProgress != null) {
            put("savedScrollRatio", savedProgress.scrollRatio.toDouble())
            put("savedPageIndex", savedProgress.pageIndex)
        }
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
    val detectedLang: String = "en",
    val sentences: List<String> = emptyList(),
    val availableVoices: List<ReaderVoiceOption> = emptyList(),
    val controlsSheetVisible: Boolean = false,
    val autoReaderHosts: Set<String> = emptySet(),
    val prefs: ReaderPrefsState = ReaderPrefsState(),
    val ttsPlaying: Boolean = false,
    val ttsIndex: Int = -1
) {
    val isAutoReaderForCurrentHost: Boolean
        get() = currentHost.isNotBlank() && currentHost in autoReaderHosts

    /** Extra night-dimmer black scrim alpha when Reader brightness is dragged below hardware minimum (0%..20%). */
    val dimmerOverlayAlpha: Float
        get() = ReaderModeManager.computeDimmerAlpha(prefs.brightness)
}

/**
 * Coordinates Book Reader Mode, persisted typography/theme/brightness/fullscreen preferences,
 * per-article reading progress memory, horizontal Book Pages mode, ML Kit multi-language detection,
 * free online Neural voices with seamless on-device TTS fallback, and word-level page turning.
 */
object ReaderModeManager {

    private const val PREFS_NAME = "uh_reader_mode_prefs"
    private const val K_THEME = "theme"
    private const val K_FONT = "font"
    private const val K_FONT_SIZE = "font_size"
    private const val K_LINE_SPACING = "line_spacing"
    private const val K_MARGIN = "margin"
    private const val K_SHOW_IMAGES = "show_images"
    private const val K_TTS_ENABLED = "tts_enabled"
    private const val K_TTS_SPEED = "tts_speed"
    private const val K_VOICE_GENDER = "voice_gender"
    private const val K_VOICE_NAME = "voice_name"
    private const val K_PAGE_MODE = "page_mode"
    private const val K_FULLSCREEN = "fullscreen"
    private const val K_BRIGHTNESS = "brightness"
    private const val K_AUTO_HOSTS = "auto_reader_hosts"

    private const val EXTRA_DIM_THRESHOLD = 0.20f
    private const val MAX_DIMMER_ALPHA = 0.78f
    private const val LOCAL_LOOKAHEAD_COUNT = 2

    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private val _openTranslateEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
    val openTranslateEvents: SharedFlow<Unit> = _openTranslateEvents.asSharedFlow()

    // Exact per-URL pause/resume sentence memory
    private val savedSentenceByUrl = ConcurrentHashMap<String, Int>()
    private val ttsSessionGen = AtomicLong(0L)

    // Bundled on-device ML Kit Language Identifier for 100+ languages (including Latin scripts)
    private val languageIdentifier by lazy {
        LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder()
                .setConfidenceThreshold(0.35f)
                .build()
        )
    }

    // Verified Google / Android TTS voice suffix -> gender maps (zero guessing!)
    private val knownFemaleVoiceTokens = setOf(
        // English US/GB/AU/IN
        "sfg", "tpf", "iol", "iob", "gba", "sfb", "afc", "gbf", "aud", "ena", "ene",
        // Hindi & Indic
        "hia", "hic", "hid", "cfn", "hif", "bna", "taa", "tea", "mra", "gua",
        // Spanish / French / German / Italian / Portuguese
        "eea", "eec", "eef", "esf", "fra", "frc", "vlf", "frf", "deb", "nfh", "def",
        "ita", "itb", "ptb", "pte", "ptf",
        // CJK & others
        "jab", "jad", "jpf", "kob", "kod", "kof", "ccd", "cce", "yzu", "ruc", "rue",
        "tra", "trc", "ida", "idd", "via", "vic", "tha", "nla", "nlc", "pla", "plc",
        "ara", "ard", "sva", "uka"
    )
    private val knownMaleVoiceTokens = setOf(
        // English US/GB/AU/IN
        "tpd", "iom", "gbb", "gbd", "rjs", "fis", "afm", "audm", "enb", "enc",
        // Hindi & Indic
        "hie", "hib", "him", "bnb", "tab", "teb", "mrb", "gub",
        // Spanish / French / German / Italian / Portuguese
        "eeb", "eed", "esm", "frb", "frd", "frm", "deg", "dem", "itc", "itd", "ptd", "ptm",
        // CJK & others
        "jac", "htm", "jpm", "ism", "koc", "kom", "ccf", "ccg", "rud", "ruf",
        "trb", "trd", "idb", "idc", "vib", "vid", "thb", "nlb", "nld", "plb", "pld",
        "arb", "arc", "svb", "ukb"
    )

    private val urlRegex = Regex("https?://\\S+|www\\.\\S+", RegexOption.IGNORE_CASE)
    private val citationRegex = Regex("\\[(?:\\d+|edit|citation needed|note \\d+|clarification needed)\\]", RegexOption.IGNORE_CASE)
    private val repeatedPunctRegex = Regex("[—–_=~*#|•·»«]{2,}|\\.{3,}")
    private val boilerplateSpeechRegex = Regex(
        "^(share|tweet|pin it|subscribe|sign up|read more|related|advertisement|sponsored|cookie|privacy policy|terms of service|skip to content|menu|search|table of contents|comments?)\\b.*",
        RegexOption.IGNORE_CASE
    )

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
    @Volatile
    private var lastAppliedLocalVoiceKey: String = ""
    @Volatile
    private var localQueuedUpToIndex: Int = -1
    @Volatile
    private var localSpeakingActiveGen: Long = -1L

    fun init(ctx: Context) {
        if (appContext != null) return
        val app = ctx.applicationContext
        appContext = app
        val sp = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPrefs = sp

        val theme = ReaderTheme.entries.firstOrNull { it.id == sp.getString(K_THEME, "sepia") } ?: ReaderTheme.SEPIA
        val savedFontId = sp.getString(K_FONT, null)
        val font = ReaderFont.entries.firstOrNull { it.id == savedFontId } ?: ReaderFont.SERIF
        val size = sp.getInt(K_FONT_SIZE, 19).coerceIn(14, 28)
        val lhVal = sp.getFloat(K_LINE_SPACING, 1.78f)
        val lineSpacing = ReaderLineSpacing.entries.minByOrNull { kotlin.math.abs(it.value - lhVal) }
            ?: ReaderLineSpacing.COMFORTABLE
        val marginPx = sp.getInt(K_MARGIN, 24)
        val margin = ReaderMargin.entries.minByOrNull { kotlin.math.abs(it.px - marginPx) } ?: ReaderMargin.MEDIUM
        val showImages = sp.getBoolean(K_SHOW_IMAGES, true)
        val ttsEnabled = sp.getBoolean(K_TTS_ENABLED, true)
        val ttsSpeed = sp.getFloat(K_TTS_SPEED, 1.0f).coerceIn(0.75f, 2.0f)
        val voiceGender = ReaderVoiceGender.entries.firstOrNull { it.id == sp.getString(K_VOICE_GENDER, "auto") }
            ?: ReaderVoiceGender.AUTO
        val voiceName = sp.getString(K_VOICE_NAME, "").orEmpty()
        val pageMode = sp.getBoolean(K_PAGE_MODE, false)
        val isFullscreen = sp.getBoolean(K_FULLSCREEN, false)
        val brightness = sp.getFloat(K_BRIGHTNESS, -1f)
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
                    ttsEnabled = ttsEnabled,
                    ttsSpeed = ttsSpeed,
                    voiceGender = voiceGender,
                    voiceName = voiceName,
                    pageMode = pageMode,
                    isFullscreen = isFullscreen,
                    brightness = brightness
                )
            )
        }
    }

    /**
     * Maps the Reader brightness slider value (`-1f` Auto, or `0f..1f`) to the Android Window
     * hardware `screenBrightness` attribute (`-1f` Auto, or `0.01f..1.0f`).
     */
    fun computeWindowBrightness(sliderValue: Float): Float {
        if (sliderValue < 0f) return -1f
        if (sliderValue <= EXTRA_DIM_THRESHOLD) return 0.01f
        val hwFrac = (sliderValue - EXTRA_DIM_THRESHOLD) / (1.0f - EXTRA_DIM_THRESHOLD)
        return hwFrac.coerceIn(0.01f, 1.0f)
    }

    /**
     * Computes the extra-dim dark overlay alpha (`0f..0.78f`) when slider is in `0.0f..0.20f`
     * (below device minimum hardware brightness).
     */
    fun computeDimmerAlpha(sliderValue: Float): Float {
        if (sliderValue < 0f || sliderValue >= EXTRA_DIM_THRESHOLD) return 0f
        val dimFrac = (EXTRA_DIM_THRESHOLD - sliderValue) / EXTRA_DIM_THRESHOLD
        return (dimFrac * MAX_DIMMER_ALPHA).coerceIn(0f, MAX_DIMMER_ALPHA)
    }

    /**
     * Reads the device's current system screen brightness and maps it onto the `0.20f..1.0f`
     * hardware band of our slider so dragging LEFT from Auto immediately dims and dragging RIGHT brightens.
     */
    fun readSystemSliderBrightness(ctx: Context): Float {
        return try {
            val raw = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            val maxVal = when {
                raw > 4095 -> 65535f
                raw > 1023 -> 4095f
                raw > 255 -> 1023f
                else -> 255f
            }
            val sysFrac = (raw.toFloat() / maxVal).coerceIn(0.02f, 1.0f)
            (EXTRA_DIM_THRESHOLD + sysFrac * (1.0f - EXTRA_DIM_THRESHOLD)).coerceIn(EXTRA_DIM_THRESHOLD, 1.0f)
        } catch (_: Exception) {
            0.55f
        }
    }

    fun onActiveTabChanged(url: String) {
        pauseTts(keepHighlight = false)
        val host = extractCleanHost(url)
        val isHttp = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
        _state.update {
            it.copy(
                currentUrl = url,
                currentHost = host,
                isEligible = false,
                isActive = false,
                controlsSheetVisible = false,
                sentences = emptyList(),
                ttsIndex = savedSentenceByUrl[url] ?: -1,
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
        if (eligible && !active && cleanHost.isNotBlank() && cleanHost in current.autoReaderHosts && url != autoEnteredUrl) {
            autoEnteredUrl = url
            mainHandler.post { enterReader() }
        }
    }

    fun onReaderEnteredFromPage(
        url: String,
        host: String,
        title: String,
        readMins: Int,
        wordCount: Int,
        lang: String,
        sentencesArray: JSONArray?
    ) {
        val sentences = parseSentences(sentencesArray)
        val initialLang = NeuralTtsClient.normalizeBaseLang(lang.trim().ifBlank { "en" })
        val resolvedUrl = url.ifBlank { _state.value.currentUrl }
        val restoredIdx = savedSentenceByUrl[resolvedUrl]?.takeIf { it in sentences.indices } ?: -1

        _state.update {
            it.copy(
                currentUrl = resolvedUrl,
                currentHost = host.lowercase().removePrefix("www.").ifBlank { it.currentHost },
                isEligible = true,
                isActive = true,
                title = title,
                readMins = readMins,
                wordCount = wordCount,
                detectedLang = initialLang,
                sentences = sentences,
                ttsIndex = restoredIdx,
                ttsPlaying = false
            )
        }
        appContext?.let { ctx ->
            ensureTtsReadyAndCatalogVoices(ctx, initialLang)
            identifyArticleLanguageAsync(ctx, title, sentences)
        }
    }

    fun onReaderContentRefreshed(
        url: String,
        host: String,
        title: String,
        readMins: Int,
        wordCount: Int,
        lang: String,
        sentencesArray: JSONArray?
    ) {
        val sentences = parseSentences(sentencesArray)
        val initialLang = NeuralTtsClient.normalizeBaseLang(lang.trim().ifBlank { _state.value.detectedLang })
        val wasPlaying = _state.value.ttsPlaying
        val prevIdx = _state.value.ttsIndex.coerceIn(0, (sentences.lastIndex).coerceAtLeast(0))
        if (wasPlaying) {
            stopAllEnginesInternal()
        }
        _state.update {
            it.copy(
                currentUrl = url.ifBlank { it.currentUrl },
                currentHost = host.lowercase().removePrefix("www.").ifBlank { it.currentHost },
                title = title.ifBlank { it.title },
                readMins = readMins,
                wordCount = wordCount,
                detectedLang = initialLang,
                sentences = sentences,
                ttsIndex = if (sentences.isNotEmpty() && it.ttsIndex >= 0) prevIdx else -1,
                ttsPlaying = false
            )
        }
        appContext?.let { ctx ->
            ensureTtsReadyAndCatalogVoices(ctx, initialLang)
            identifyArticleLanguageAsync(ctx, title, sentences)
            if (wasPlaying && sentences.isNotEmpty()) {
                speakFromIndex(ctx, prevIdx)
            }
        }
    }

    fun onReaderProgressUpdate(url: String, scrollRatio: Float, pageIndex: Int) {
        val ctx = appContext ?: return
        val targetUrl = url.ifBlank { _state.value.currentUrl }
        if (targetUrl.isNotBlank()) {
            ReaderProgressStore.save(ctx, targetUrl, scrollRatio, pageIndex)
        }
    }

    /**
     * Uses bundled on-device ML Kit LanguageIdentification to accurately identify 100+ languages
     * (including all Latin-script languages like Spanish, French, German, Turkish, Indonesian, etc.).
     */
    private fun identifyArticleLanguageAsync(ctx: Context, title: String, sentences: List<String>) {
        val sample = buildString {
            if (title.isNotBlank()) append(title).append(". ")
            var chars = 0
            for (s in sentences) {
                if (s.length < 18) continue
                append(s).append(' ')
                chars += s.length
                if (chars >= 1100) break
            }
        }.trim()
        if (sample.length < 24) return

        val expectedUrl = _state.value.currentUrl
        try {
            languageIdentifier.identifyLanguage(sample)
                .addOnSuccessListener { code ->
                    if (!code.isNullOrBlank() && code != "und") {
                        val normalized = NeuralTtsClient.normalizeBaseLang(code)
                        if (_state.value.currentUrl == expectedUrl && _state.value.detectedLang != normalized) {
                            _state.update { it.copy(detectedLang = normalized) }
                            ensureTtsReadyAndCatalogVoices(ctx, normalized)
                        }
                    }
                }
        } catch (e: Exception) {
            UHLog.w("reader", "ML Kit lang-id skipped: ${e.message}")
        }
    }

    fun onPageTranslationChanged(targetLangCode: String? = null) {
        if (!_state.value.isActive) return
        if (!targetLangCode.isNullOrBlank()) {
            val norm = NeuralTtsClient.normalizeBaseLang(targetLangCode)
            _state.update { it.copy(detectedLang = norm) }
            appContext?.let { ensureTtsReadyAndCatalogVoices(it, norm) }
        }
        mainHandler.postDelayed({
            if (_state.value.isActive) {
                GeckoWebExtensionBridge.sendReaderCommand("READER_REFRESH_CONTENT")
            }
        }, 280L)
        mainHandler.postDelayed({
            if (_state.value.isActive) {
                GeckoWebExtensionBridge.sendReaderCommand("READER_REFRESH_CONTENT")
            }
        }, 950L)
    }

    fun onReaderExitedFromPage() {
        pauseTts(keepHighlight = false)
        _state.update {
            it.copy(
                isActive = false,
                controlsSheetVisible = false,
                ttsPlaying = false
            )
        }
    }

    fun requestOpenTranslateSheet() {
        _openTranslateEvents.tryEmit(Unit)
    }

    fun openControlsSheet() {
        appContext?.let { ensureTtsReadyAndCatalogVoices(it, _state.value.detectedLang) }
        _state.update { it.copy(controlsSheetVisible = true) }
    }

    fun closeControlsSheet() {
        _state.update { it.copy(controlsSheetVisible = false) }
    }

    fun toggleReader() {
        if (_state.value.isActive) exitReader() else enterReader()
    }

    fun enterReader() {
        val ctx = appContext
        val saved = if (ctx != null && _state.value.currentUrl.isNotBlank()) {
            ReaderProgressStore.get(ctx, _state.value.currentUrl)
        } else null
        val prefsJson = _state.value.prefs.toJson(savedProgress = saved)
        GeckoWebExtensionBridge.sendReaderCommand("READER_ENTER", prefsJson)
    }

    fun exitReader() {
        pauseTts(keepHighlight = false)
        GeckoWebExtensionBridge.sendReaderCommand("READER_EXIT")
        _state.update { it.copy(isActive = false, controlsSheetVisible = false) }
    }

    fun togglePageMode() {
        updatePrefs { it.copy(pageMode = !it.pageMode) }
    }

    fun toggleFullscreen() {
        updatePrefs { it.copy(isFullscreen = !it.isFullscreen) }
    }

    fun setBrightness(value: Float) {
        val clamped = if (value < 0f) -1f else value.coerceIn(0.0f, 1.0f)
        updatePrefs { it.copy(brightness = clamped) }
    }

    fun updatePrefs(transform: (ReaderPrefsState) -> ReaderPrefsState) {
        val updated = transform(_state.value.prefs)
        _state.update { it.copy(prefs = updated) }
        persistPrefs(updated)
        if (_state.value.isActive) {
            GeckoWebExtensionBridge.sendReaderCommand("READER_UPDATE_STYLE", updated.toJson())
        }
    }

    fun toggleAutoReaderForCurrentHost(enabled: Boolean) {
        val host = _state.value.currentHost
        if (host.isBlank()) return
        val next = if (enabled) _state.value.autoReaderHosts + host else _state.value.autoReaderHosts - host
        _state.update { it.copy(autoReaderHosts = next) }
        sharedPrefs?.edit()?.putStringSet(K_AUTO_HOSTS, next)?.apply()
    }

    // ---- Free Online Neural TTS Primary + Seamless Upgraded Local TTS Fallback ----

    fun setTtsEnabled(enabled: Boolean) {
        if (!enabled) stopTts()
        updatePrefs { it.copy(ttsEnabled = enabled) }
    }

    fun setVoiceGender(gender: ReaderVoiceGender) {
        updatePrefs { it.copy(voiceGender = gender, voiceName = "") }
        if (_state.value.ttsPlaying) {
            val ctx = appContext ?: return
            val idx = _state.value.ttsIndex.coerceAtLeast(0)
            speakFromIndex(ctx, idx)
        }
    }

    fun selectVoiceOption(option: ReaderVoiceOption) {
        updatePrefs { it.copy(voiceGender = option.gender, voiceName = option.name) }
        if (_state.value.ttsPlaying) {
            val ctx = appContext ?: return
            val idx = _state.value.ttsIndex.coerceAtLeast(0)
            speakFromIndex(ctx, idx)
        }
    }

    fun togglePlayPauseTts(ctx: Context) {
        init(ctx)
        val s = _state.value
        if (!s.prefs.ttsEnabled) return
        if (s.ttsPlaying) {
            pauseTts(keepHighlight = true)
            return
        }
        if (s.sentences.isEmpty()) return
        val saved = savedSentenceByUrl[s.currentUrl]
        val startIdx = when {
            s.ttsIndex in s.sentences.indices -> s.ttsIndex
            saved != null && saved in s.sentences.indices -> saved
            else -> 0
        }
        speakFromIndex(ctx, startIdx)
    }

    fun togglePlayPauseFromPage() {
        val ctx = appContext ?: return
        togglePlayPauseTts(ctx)
    }

    fun playFromSentenceIndex(index: Int) {
        val ctx = appContext ?: return
        val s = _state.value
        if (!s.prefs.ttsEnabled || s.sentences.isEmpty()) return
        val clamped = index.coerceIn(0, s.sentences.lastIndex)
        speakFromIndex(ctx, clamped)
    }

    fun skipTts(ctx: Context, delta: Int) {
        val s = _state.value
        if (s.sentences.isEmpty()) return
        val base = if (s.ttsIndex >= 0) s.ttsIndex else (savedSentenceByUrl[s.currentUrl] ?: 0)
        val nextIdx = (base + delta).coerceIn(0, s.sentences.lastIndex)
        speakFromIndex(ctx, nextIdx)
    }

    fun setTtsSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.75f, 2.0f)
        updatePrefs { it.copy(ttsSpeed = clamped) }
        if (_state.value.ttsPlaying && _state.value.ttsIndex in _state.value.sentences.indices) {
            val ctx = appContext ?: return
            speakFromIndex(ctx, _state.value.ttsIndex)
        }
    }

    fun pauseTts(keepHighlight: Boolean = true) {
        stopAllEnginesInternal()
        val st = _state.value
        if (st.currentUrl.isNotBlank() && st.ttsIndex in st.sentences.indices) {
            savedSentenceByUrl[st.currentUrl] = st.ttsIndex
        }
        if (st.isActive) {
            GeckoWebExtensionBridge.sendReaderTtsHighlight(
                index = if (keepHighlight) st.ttsIndex else -1,
                playing = false
            )
        }
        _state.update { it.copy(ttsPlaying = false) }
    }

    fun stopTts() {
        stopAllEnginesInternal()
        val st = _state.value
        if (st.currentUrl.isNotBlank()) {
            savedSentenceByUrl.remove(st.currentUrl)
        }
        if (st.isActive) {
            GeckoWebExtensionBridge.sendReaderTtsHighlight(-1, playing = false)
        }
        _state.update { it.copy(ttsPlaying = false, ttsIndex = -1) }
    }

    private fun stopAllEnginesInternal() {
        ttsSessionGen.incrementAndGet()
        localSpeakingActiveGen = -1L
        localQueuedUpToIndex = -1
        NeuralTtsClient.stop()
        try { tts?.stop() } catch (_: Exception) {}
    }

    private fun ensureTtsReadyAndCatalogVoices(ctx: Context, langTag: String) {
        // Immediately expose Neural voices even before local Android TTS finishes binding
        updateVoiceCatalog(tts?.takeIf { ttsReady }, langTag)

        val existing = tts
        if (existing != null && ttsReady) return

        try {
            val app = ctx.applicationContext
            tts = TextToSpeech(app) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    attachUtteranceListener()
                    mainHandler.post {
                        updateVoiceCatalog(tts, _state.value.detectedLang)
                    }
                } else {
                    ttsReady = false
                }
            }
        } catch (e: Exception) {
            UHLog.w("reader", "Local TTS prewarm failed: ${e.message}")
        }
    }

    private fun attachUtteranceListener() {
        try {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    val (gen, idx) = parseUtteranceId(utteranceId) ?: return
                    mainHandler.post {
                        if (localSpeakingActiveGen != gen) return@post
                        val st = _state.value
                        if (!st.ttsPlaying || !st.isActive) return@post
                        if (st.currentUrl.isNotBlank()) {
                            savedSentenceByUrl[st.currentUrl] = idx
                        }
                        _state.update { it.copy(ttsIndex = idx) }
                        GeckoWebExtensionBridge.sendReaderTtsHighlight(idx, playing = true)
                        // Keep 2 sentences pre-buffered in QUEUE_ADD to eliminate pauses at full stops
                        enqueueLocalLookahead(gen, idx)
                    }
                }

                override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                    val (gen, idx) = parseUtteranceId(utteranceId) ?: return
                    if (localSpeakingActiveGen != gen) return
                    val len = (end - start).coerceAtLeast(1)
                    GeckoWebExtensionBridge.sendReaderTtsWord(idx, start, len)
                }

                override fun onDone(utteranceId: String?) {
                    val (gen, doneIdx) = parseUtteranceId(utteranceId) ?: return
                    mainHandler.post {
                        if (localSpeakingActiveGen != gen) return@post
                        val st = _state.value
                        if (!st.ttsPlaying || !st.isActive) return@post
                        val nextPlayable = findNextSpeakableIndex(st.sentences, doneIdx + 1)
                        if (nextPlayable < 0) {
                            stopTts()
                        } else if (nextPlayable > localQueuedUpToIndex) {
                            enqueueLocalLookahead(gen, doneIdx)
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    val (gen, _) = parseUtteranceId(utteranceId) ?: return
                    mainHandler.post {
                        if (localSpeakingActiveGen == gen) {
                            pauseTts(keepHighlight = true)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            UHLog.w("reader", "TTS listener setup failed: ${e.message}")
        }
    }

    private fun speakFromIndex(ctx: Context, requestedIndex: Int) {
        val st = _state.value
        val startIdx = findNextSpeakableIndex(st.sentences, requestedIndex)
        if (startIdx < 0) {
            stopTts()
            return
        }

        val gen = ttsSessionGen.incrementAndGet()
        NeuralTtsClient.stop()
        try { tts?.stop() } catch (_: Exception) {}
        localSpeakingActiveGen = -1L
        localQueuedUpToIndex = -1

        val forceLocal = st.prefs.voiceName.startsWith("local:")
        if (!forceLocal) {
            speakNeuralAt(ctx, gen, startIdx)
        } else {
            speakLocalFrom(ctx, gen, startIdx)
        }
    }

    private fun speakNeuralAt(ctx: Context, gen: Long, index: Int) {
        if (ttsSessionGen.get() != gen) return
        val st = _state.value
        if (!st.isActive) return

        val actualIdx = findNextSpeakableIndex(st.sentences, index)
        if (actualIdx < 0) {
            stopTts()
            return
        }
        val cleanText = cleanSentenceForSpeech(st.sentences[actualIdx])
        val nextIdx = findNextSpeakableIndex(st.sentences, actualIdx + 1)
        val nextCleanText = if (nextIdx >= 0) cleanSentenceForSpeech(st.sentences[nextIdx]) else null

        if (st.currentUrl.isNotBlank()) {
            savedSentenceByUrl[st.currentUrl] = actualIdx
        }
        _state.update { it.copy(ttsPlaying = true, ttsIndex = actualIdx) }
        GeckoWebExtensionBridge.sendReaderTtsHighlight(actualIdx, playing = true)

        NeuralTtsClient.speakSentence(
            context = ctx,
            sentence = cleanText,
            nextSentence = nextCleanText,
            langTag = st.detectedLang,
            prefs = st.prefs,
            onWordProgress = { charOffset, charLen ->
                if (ttsSessionGen.get() == gen && _state.value.ttsPlaying) {
                    GeckoWebExtensionBridge.sendReaderTtsWord(actualIdx, charOffset, charLen)
                }
            },
            onCompleted = {
                if (ttsSessionGen.get() != gen || !_state.value.ttsPlaying) return@speakSentence
                if (nextIdx >= 0) {
                    speakNeuralAt(ctx, gen, nextIdx)
                } else {
                    stopTts()
                }
            },
            onFallbackNeeded = {
                if (ttsSessionGen.get() == gen && _state.value.isActive) {
                    UHLog.d("reader", "Neural TTS unavailable, switching seamlessly to device TTS")
                    speakLocalFrom(ctx, gen, actualIdx)
                }
            }
        )
    }

    private fun speakLocalFrom(ctx: Context, gen: Long, startIdx: Int) {
        if (ttsSessionGen.get() != gen) return
        val engine = tts
        if (engine != null && ttsReady) {
            startLocalQueue(engine, gen, startIdx)
            return
        }

        try {
            val app = ctx.applicationContext
            tts = TextToSpeech(app) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    attachUtteranceListener()
                    mainHandler.post {
                        if (ttsSessionGen.get() != gen) return@post
                        tts?.let {
                            updateVoiceCatalog(it, _state.value.detectedLang)
                            startLocalQueue(it, gen, startIdx)
                        }
                    }
                } else {
                    ttsReady = false
                    mainHandler.post { pauseTts(keepHighlight = true) }
                }
            }
        } catch (e: Exception) {
            UHLog.w("reader", "Local TTS init failed: ${e.message}")
            pauseTts(keepHighlight = true)
        }
    }

    private fun startLocalQueue(engine: TextToSpeech, gen: Long, startIdx: Int) {
        if (ttsSessionGen.get() != gen) return
        val st = _state.value
        val actualIdx = findNextSpeakableIndex(st.sentences, startIdx)
        if (actualIdx < 0) {
            stopTts()
            return
        }

        localSpeakingActiveGen = gen
        localQueuedUpToIndex = actualIdx
        if (st.currentUrl.isNotBlank()) {
            savedSentenceByUrl[st.currentUrl] = actualIdx
        }
        _state.update { it.copy(ttsPlaying = true, ttsIndex = actualIdx) }
        GeckoWebExtensionBridge.sendReaderTtsHighlight(actualIdx, playing = true)

        try {
            applyLocalVoiceOnce(engine, st.detectedLang, st.prefs)
            engine.setSpeechRate(st.prefs.ttsSpeed)
            val text = cleanSentenceForSpeech(st.sentences[actualIdx])
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "uh_tts_${gen}_$actualIdx")
            enqueueLocalLookahead(gen, actualIdx)
        } catch (e: Exception) {
            UHLog.w("reader", "Local TTS speak failed: ${e.message}")
            pauseTts(keepHighlight = true)
        }
    }

    private fun enqueueLocalLookahead(gen: Long, currentIndex: Int) {
        val engine = tts ?: return
        if (!ttsReady || localSpeakingActiveGen != gen) return
        val sentences = _state.value.sentences
        val targetUpTo = (currentIndex + LOCAL_LOOKAHEAD_COUNT).coerceAtMost(sentences.lastIndex)
        var cursor = localQueuedUpToIndex + 1
        while (cursor <= targetUpTo) {
            val nextIdx = findNextSpeakableIndex(sentences, cursor)
            if (nextIdx < 0 || nextIdx > targetUpTo) break
            val cleanText = cleanSentenceForSpeech(sentences[nextIdx])
            try {
                engine.speak(cleanText, TextToSpeech.QUEUE_ADD, Bundle(), "uh_tts_${gen}_$nextIdx")
                localQueuedUpToIndex = nextIdx
            } catch (_: Exception) {
                break
            }
            cursor = nextIdx + 1
        }
    }

    private fun applyLocalVoiceOnce(engine: TextToSpeech, langTag: String, prefs: ReaderPrefsState) {
        val voiceKey = "${langTag}|${prefs.voiceGender.id}|${prefs.voiceName}"
        if (voiceKey == lastAppliedLocalVoiceKey) return

        val locale = Locale.forLanguageTag(langTag.ifBlank { Locale.getDefault().toLanguageTag() })
        try {
            val allVoices = engine.voices.orEmpty()
            val baseLang = NeuralTtsClient.normalizeBaseLang(locale.language)
            val matching = allVoices
                .filter { v ->
                    v.locale != null &&
                        NeuralTtsClient.normalizeBaseLang(v.locale.language) == baseLang
                }
                .sortedWith(
                    compareByDescending<Voice> { it.quality }
                        .thenByDescending { !it.isNetworkConnectionRequired }
                        .thenBy { it.latency }
                )

            val wantedLocalName = prefs.voiceName.removePrefix("local:")
            val exactVoice = if (prefs.voiceName.startsWith("local:") && wantedLocalName.isNotBlank()) {
                matching.firstOrNull { it.name == wantedLocalName }
            } else null

            val chosenVoice = exactVoice ?: when (prefs.voiceGender) {
                ReaderVoiceGender.FEMALE -> matching.firstOrNull { inferLocalVoiceGender(it) == ReaderVoiceGender.FEMALE }
                    ?: matching.firstOrNull()
                ReaderVoiceGender.MALE -> matching.firstOrNull { inferLocalVoiceGender(it) == ReaderVoiceGender.MALE }
                    ?: matching.firstOrNull()
                ReaderVoiceGender.AUTO -> matching.firstOrNull()
            }

            if (chosenVoice != null) {
                engine.voice = chosenVoice
            } else {
                val res = engine.setLanguage(locale)
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.language = Locale.getDefault()
                }
            }
            lastAppliedLocalVoiceKey = voiceKey
        } catch (e: Exception) {
            UHLog.w("reader", "Local voice selection fallback: ${e.message}")
            try { engine.language = locale } catch (_: Exception) {}
        }
    }

    private fun updateVoiceCatalog(engine: TextToSpeech?, langTag: String) {
        val options = mutableListOf<ReaderVoiceOption>()
        // 1. Free Online Neural Voices first (highest realism, verified gender)
        options.addAll(NeuralTtsClient.voicesForLanguage(langTag))

        // 2. Verified On-Device Offline Voices second
        if (engine != null) {
            try {
                val baseLang = NeuralTtsClient.normalizeBaseLang(langTag)
                val voices = engine.voices.orEmpty()
                    .filter { v ->
                        v.locale != null &&
                            NeuralTtsClient.normalizeBaseLang(v.locale.language) == baseLang &&
                            !v.isNetworkConnectionRequired
                    }
                    .sortedWith(
                        compareByDescending<Voice> { it.quality }
                            .thenBy { it.latency }
                    )

                var fCount = 0
                var mCount = 0
                var aCount = 0
                for (v in voices) {
                    val gender = inferLocalVoiceGender(v)
                    val label = when (gender) {
                        ReaderVoiceGender.FEMALE -> {
                            fCount++
                            if (fCount > 2) continue
                            "Offline Female $fCount"
                        }
                        ReaderVoiceGender.MALE -> {
                            mCount++
                            if (mCount > 2) continue
                            "Offline Male $mCount"
                        }
                        ReaderVoiceGender.AUTO -> {
                            aCount++
                            if (aCount > 2) continue
                            "Offline Voice $aCount"
                        }
                    }
                    options.add(
                        ReaderVoiceOption(
                            name = "local:${v.name}",
                            label = label,
                            gender = gender,
                            langTag = v.locale.toLanguageTag(),
                            quality = v.quality,
                            isNeural = false
                        )
                    )
                }
            } catch (_: Exception) {}
        }

        _state.update { it.copy(availableVoices = options) }
    }

    /**
     * Strictly verified gender classification for local Android TTS voices.
     * Unlabeled voices return AUTO rather than being misfiled into Male or Female.
     */
    private fun inferLocalVoiceGender(voice: Voice): ReaderVoiceGender {
        val n = voice.name.lowercase(Locale.ROOT)
        val features = voice.features?.joinToString(" ")?.lowercase(Locale.ROOT).orEmpty()
        val combined = "$n $features"

        if (combined.contains("female") || n.endsWith("f-local") || n.endsWith("f-network")) {
            return ReaderVoiceGender.FEMALE
        }
        if (Regex("\\bmale\\b").containsMatchIn(combined) || n.endsWith("m-local") || n.endsWith("m-network")) {
            return ReaderVoiceGender.MALE
        }

        val tokens = n.split('-', '_', '#', '.').filter { it.isNotBlank() }
        for (tok in tokens) {
            if (tok in knownFemaleVoiceTokens) return ReaderVoiceGender.FEMALE
            if (tok in knownMaleVoiceTokens) return ReaderVoiceGender.MALE
        }
        return ReaderVoiceGender.AUTO
    }

    private fun findNextSpeakableIndex(sentences: List<String>, start: Int): Int {
        for (i in start.coerceAtLeast(0) until sentences.size) {
            val cleaned = cleanSentenceForSpeech(sentences[i])
            if (cleaned.length >= 2 && !isUnnecessaryBoilerplate(cleaned)) {
                return i
            }
        }
        return -1
    }

    /**
     * Cleans raw DOM text before speaking so Read Aloud skips URLs, citation brackets,
     * symbol clutter, and navigation noise.
     */
    private fun cleanSentenceForSpeech(raw: String): String {
        var s = raw
            .replace(urlRegex, "")
            .replace(citationRegex, "")
            .replace(repeatedPunctRegex, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        // Drop leading list bullets or stray punctuation
        s = s.trimStart('•', '·', '-', '–', '—', '|', '»', '›', '*', ' ')
        return s.trim()
    }

    private fun isUnnecessaryBoilerplate(text: String): Boolean {
        if (text.length < 3) return true
        // Skip strings with almost no letters/digits (pure symbols/dividers)
        val alphaNum = text.count { it.isLetterOrDigit() }
        if (alphaNum < 2) return true
        if (text.length <= 42 && boilerplateSpeechRegex.matches(text)) return true
        return false
    }

    private fun parseUtteranceId(id: String?): Pair<Long, Int>? {
        if (id == null || !id.startsWith("uh_tts_")) return null
        val parts = id.removePrefix("uh_tts_").split("_")
        if (parts.size != 2) return null
        val gen = parts[0].toLongOrNull() ?: return null
        val idx = parts[1].toIntOrNull() ?: return null
        return gen to idx
    }

    private fun parseSentences(arr: JSONArray?): List<String> {
        val list = mutableListOf<String>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).trim()
                if (s.isNotEmpty()) list.add(s)
            }
        }
        return list
    }

    private fun persistPrefs(p: ReaderPrefsState) {
        sharedPrefs?.edit()
            ?.putString(K_THEME, p.theme.id)
            ?.putString(K_FONT, p.font.id)
            ?.putInt(K_FONT_SIZE, p.fontSizePx)
            ?.putFloat(K_LINE_SPACING, p.lineSpacing.value)
            ?.putInt(K_MARGIN, p.margin.px)
            ?.putBoolean(K_SHOW_IMAGES, p.showImages)
            ?.putBoolean(K_TTS_ENABLED, p.ttsEnabled)
            ?.putFloat(K_TTS_SPEED, p.ttsSpeed)
            ?.putString(K_VOICE_GENDER, p.voiceGender.id)
            ?.putString(K_VOICE_NAME, p.voiceName)
            ?.putBoolean(K_PAGE_MODE, p.pageMode)
            ?.putBoolean(K_FULLSCREEN, p.isFullscreen)
            ?.putFloat(K_BRIGHTNESS, p.brightness)
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
