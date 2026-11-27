package app.harvester.companion.browser.reader

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import app.harvester.companion.GeckoWebExtensionBridge
import app.harvester.companion.UHLog
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

enum class ReaderTheme(val id: String, val label: String) {
    LIGHT("light", "Paper"),
    SEPIA("sepia", "Sepia"),
    DARK("dark", "Dark"),
    OLED("oled", "OLED")
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
    val quality: Int
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
    val brightness: Float = -1f
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("theme", theme.id)
        put("fontFamily", font.id)
        put("fontSizePx", fontSizePx)
        put("lineHeight", lineSpacing.value.toDouble())
        put("marginPx", margin.px)
        put("showImages", showImages)
        put("ttsEnabled", ttsEnabled)
        put("pageMode", pageMode)
        put("isFullscreen", isFullscreen)
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
}

/**
 * Coordinates Kindle-class Book Reader Mode, persisted typography/theme/brightness/fullscreen
 * preferences, horizontal Book Pages mode, live translation re-sync, multi-language auto-detection,
 * and sentence-level Android TextToSpeech with Male/Female human voice picker & exact pause/resume.
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private val _openTranslateEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
    val openTranslateEvents: SharedFlow<Unit> = _openTranslateEvents.asSharedFlow()

    // Exact per-URL pause/resume sentence memory
    private val savedSentenceByUrl = ConcurrentHashMap<String, Int>()

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

        val theme = ReaderTheme.entries.firstOrNull { it.id == sp.getString(K_THEME, "sepia") } ?: ReaderTheme.SEPIA
        val savedFontId = sp.getString(K_FONT, null)
        // Upgrade older default ("sans" if never customized or missing) cleanly to bookish SERIF
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
        val cleanLang = lang.trim().ifBlank { "en" }
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
                detectedLang = cleanLang,
                sentences = sentences,
                ttsIndex = restoredIdx,
                ttsPlaying = false
            )
        }
        appContext?.let { ensureTtsReadyAndCatalogVoices(it, cleanLang) }
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
        val cleanLang = lang.trim().ifBlank { _state.value.detectedLang }
        val wasPlaying = _state.value.ttsPlaying
        val prevIdx = _state.value.ttsIndex.coerceIn(0, (sentences.lastIndex).coerceAtLeast(0))
        if (wasPlaying) {
            try { tts?.stop() } catch (_: Exception) {}
        }
        _state.update {
            it.copy(
                currentUrl = url.ifBlank { it.currentUrl },
                currentHost = host.lowercase().removePrefix("www.").ifBlank { it.currentHost },
                title = title.ifBlank { it.title },
                readMins = readMins,
                wordCount = wordCount,
                detectedLang = cleanLang,
                sentences = sentences,
                ttsIndex = if (sentences.isNotEmpty() && it.ttsIndex >= 0) prevIdx else -1,
                ttsPlaying = false
            )
        }
        appContext?.let { ctx ->
            ensureTtsReadyAndCatalogVoices(ctx, cleanLang)
            if (wasPlaying && sentences.isNotEmpty()) {
                speakFromIndex(ctx, prevIdx)
            }
        }
    }

    /**
     * Called by UhTranslationManager whenever on-device page translation finishes or restores.
     * Re-syncs Reader Mode content immediately in place without closing or reopening Reader.
     */
    fun onPageTranslationChanged(targetLangCode: String? = null) {
        if (!_state.value.isActive) return
        if (!targetLangCode.isNullOrBlank()) {
            _state.update { it.copy(detectedLang = targetLangCode) }
        }
        mainHandler.postDelayed({
            if (_state.value.isActive) {
                GeckoWebExtensionBridge.sendReaderCommand("READER_REFRESH_CONTENT")
            }
        }, 260L)
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
        val prefsJson = _state.value.prefs.toJson()
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
        val clamped = if (value < 0f) -1f else value.coerceIn(0.05f, 1.0f)
        updatePrefs { it.copy(brightness = clamped) }
    }

    fun updatePrefs(transform: (ReaderPrefsState) -> ReaderPrefsState) {
        val updated = transform(_state.value.prefs)
        _state.update { it.copy(prefs = updated) }
        persistPrefs(updated)
        if (_state.value.isActive) {
            GeckoWebExtensionBridge.sendReaderCommand("READER_UPDATE_STYLE", updated.toJson())
        }
        if (_state.value.ttsPlaying) {
            try { tts?.setSpeechRate(updated.ttsSpeed) } catch (_: Exception) {}
        }
    }

    fun toggleAutoReaderForCurrentHost(enabled: Boolean) {
        val host = _state.value.currentHost
        if (host.isBlank()) return
        val next = if (enabled) _state.value.autoReaderHosts + host else _state.value.autoReaderHosts - host
        _state.update { it.copy(autoReaderHosts = next) }
        sharedPrefs?.edit()?.putStringSet(K_AUTO_HOSTS, next)?.apply()
    }

    // ---- Multi-Language On-Device Android TTS with Male & Female Voices + Exact Resume ----

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
        try { tts?.stop() } catch (_: Exception) {}
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
        try { tts?.stop() } catch (_: Exception) {}
        val st = _state.value
        if (st.currentUrl.isNotBlank()) {
            savedSentenceByUrl.remove(st.currentUrl)
        }
        if (st.isActive) {
            GeckoWebExtensionBridge.sendReaderTtsHighlight(-1, playing = false)
        }
        _state.update { it.copy(ttsPlaying = false, ttsIndex = -1) }
    }

    private fun ensureTtsReadyAndCatalogVoices(ctx: Context, langTag: String) {
        val existing = tts
        if (existing != null && ttsReady) {
            catalogVoicesForLang(existing, langTag)
            return
        }
        try {
            val app = ctx.applicationContext
            tts = TextToSpeech(app) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    attachUtteranceListener()
                    mainHandler.post {
                        tts?.let { catalogVoicesForLang(it, _state.value.detectedLang) }
                    }
                } else {
                    ttsReady = false
                }
            }
        } catch (e: Exception) {
            UHLog.w("reader", "TTS prewarm failed: ${e.message}")
        }
    }

    private fun attachUtteranceListener() {
        try {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    val doneIdx = utteranceId?.removePrefix("uh_tts_")?.toIntOrNull() ?: return
                    mainHandler.post {
                        val st = _state.value
                        if (!st.ttsPlaying || !st.isActive) return@post
                        val next = doneIdx + 1
                        if (next in st.sentences.indices) {
                            tts?.let { startUtterance(it, next) }
                        } else {
                            stopTts()
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    mainHandler.post { pauseTts(keepHighlight = true) }
                }
            })
        } catch (e: Exception) {
            UHLog.w("reader", "TTS listener setup failed: ${e.message}")
        }
    }

    private fun speakFromIndex(ctx: Context, index: Int) {
        val sentences = _state.value.sentences
        if (index !in sentences.indices) {
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
                    attachUtteranceListener()
                    mainHandler.post {
                        tts?.let {
                            catalogVoicesForLang(it, _state.value.detectedLang)
                            startUtterance(it, index)
                        }
                    }
                } else {
                    ttsReady = false
                }
            }
        } catch (e: Exception) {
            UHLog.w("reader", "TTS initialization failed: ${e.message}")
        }
    }

    private fun applyVoiceAndLocale(engine: TextToSpeech, langTag: String, prefs: ReaderPrefsState) {
        val locale = Locale.forLanguageTag(langTag.ifBlank { Locale.getDefault().toLanguageTag() })
        try {
            val allVoices = engine.voices.orEmpty()
            val baseLang = locale.language.lowercase(Locale.ROOT)
            val matching = allVoices
                .filter { v -> v.locale != null && v.locale.language.equals(baseLang, ignoreCase = true) }
                .sortedWith(
                    compareByDescending<Voice> { !vIsNetworkOnly(it) }
                        .thenByDescending { it.quality }
                        .thenBy { it.latency }
                )

            val exactVoice = if (prefs.voiceName.isNotBlank()) {
                matching.firstOrNull { it.name == prefs.voiceName }
            } else null

            val chosenVoice = exactVoice ?: when (prefs.voiceGender) {
                ReaderVoiceGender.FEMALE -> matching.firstOrNull { inferVoiceGender(it) == ReaderVoiceGender.FEMALE }
                ReaderVoiceGender.MALE -> matching.firstOrNull { inferVoiceGender(it) == ReaderVoiceGender.MALE }
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
        } catch (e: Exception) {
            UHLog.w("reader", "Voice selection fallback: ${e.message}")
            try { engine.language = locale } catch (_: Exception) {}
        }
    }

    private fun catalogVoicesForLang(engine: TextToSpeech, langTag: String) {
        try {
            val locale = Locale.forLanguageTag(langTag.ifBlank { Locale.getDefault().toLanguageTag() })
            val baseLang = locale.language.lowercase(Locale.ROOT)
            val voices = engine.voices.orEmpty()
                .filter { it.locale != null && it.locale.language.equals(baseLang, ignoreCase = true) }
                .sortedWith(
                    compareByDescending<Voice> { !vIsNetworkOnly(it) }
                        .thenByDescending { it.quality }
                        .thenBy { it.latency }
                )

            var femaleIdx = 0
            var maleIdx = 0
            val options = mutableListOf<ReaderVoiceOption>()
            for (v in voices) {
                val gender = inferVoiceGender(v)
                val label = when (gender) {
                    ReaderVoiceGender.FEMALE -> {
                        femaleIdx++
                        if (femaleIdx > 4) continue
                        "Female $femaleIdx"
                    }
                    ReaderVoiceGender.MALE -> {
                        maleIdx++
                        if (maleIdx > 4) continue
                        "Male $maleIdx"
                    }
                    ReaderVoiceGender.AUTO -> {
                        if (options.size >= 6) continue
                        "Natural ${options.size + 1}"
                    }
                }
                options.add(
                    ReaderVoiceOption(
                        name = v.name,
                        label = label,
                        gender = gender,
                        langTag = v.locale.toLanguageTag(),
                        quality = v.quality
                    )
                )
            }
            _state.update { it.copy(availableVoices = options) }
        } catch (_: Exception) {}
    }

    private fun vIsNetworkOnly(voice: Voice): Boolean = voice.isNetworkConnectionRequired

    private fun inferVoiceGender(voice: Voice): ReaderVoiceGender {
        val n = voice.name.lowercase(Locale.ROOT)
        val features = voice.features?.joinToString(" ")?.lowercase(Locale.ROOT).orEmpty()
        val combined = "$n $features"
        // Standard Android/Google TTS voice identifiers (e.g., sfg/sfb/tpf/iob/iol/x-iom/male/female)
        return when {
            combined.contains("female") ||
                Regex("[-_#](f|sfg|tpf|iol|iob|gba|sfb|afc|esf|frf|def|hif|jpf)[-_0-9]?").containsMatchIn(n) ||
                n.endsWith("f-local") || n.endsWith("f-network") ||
                n.contains("-x-sfg") || n.contains("-x-tpf") || n.contains("-x-iob") || n.contains("-x-iol") ->
                ReaderVoiceGender.FEMALE
            combined.contains("male") ||
                Regex("[-_#](m|tpd|iom|gbb|gbd|afm|esm|frm|dem|him|jpm)[-_0-9]?").containsMatchIn(n) ||
                n.endsWith("m-local") || n.endsWith("m-network") ||
                n.contains("-x-tpd") || n.contains("-x-iom") || n.contains("-x-gbd") ->
                ReaderVoiceGender.MALE
            else -> {
                // Deterministic balanced fallback for unlabeled voices so both Female & Male pools populate cleanly
                if ((voice.name.hashCode() and 1) == 0) ReaderVoiceGender.FEMALE else ReaderVoiceGender.MALE
            }
        }
    }

    private fun startUtterance(engine: TextToSpeech, index: Int) {
        val st = _state.value
        val text = st.sentences.getOrNull(index) ?: return
        if (st.currentUrl.isNotBlank()) {
            savedSentenceByUrl[st.currentUrl] = index
        }
        _state.update { it.copy(ttsPlaying = true, ttsIndex = index) }
        GeckoWebExtensionBridge.sendReaderTtsHighlight(index, playing = true)
        try {
            applyVoiceAndLocale(engine, st.detectedLang, st.prefs)
            engine.setSpeechRate(st.prefs.ttsSpeed)
            val params = Bundle()
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "uh_tts_$index")
        } catch (e: Exception) {
            UHLog.w("reader", "TTS speak failed: ${e.message}")
            pauseTts(keepHighlight = true)
        }
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
