package app.harvester.companion.browser.translate

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.harvester.companion.GeckoWebExtensionBridge
import app.harvester.companion.UHLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.TranslationsState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.translate.Language
import mozilla.components.concept.engine.translate.LanguageModel
import mozilla.components.concept.engine.translate.LanguageSetting
import mozilla.components.concept.engine.translate.ModelManagementOptions
import mozilla.components.concept.engine.translate.ModelOperation
import mozilla.components.concept.engine.translate.ModelState
import mozilla.components.concept.engine.translate.OperationLevel
import mozilla.components.concept.engine.translate.TranslationOptions
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class TranslatePillPhase {
    HIDDEN,
    OFFER,
    DOWNLOADING_MODEL,
    TRANSLATING,
    TRANSLATING_IMAGES,
    COMPLETED,
    ERROR,
}

/**
 * UH Private On-Device Translation Manager.
 * Runs exclusively on the browser engine's built-in neural translation stack
 * (100% private, on-device, zero external scraping timeouts) with automatic
 * foreign-page detection, subtle floating Translate offer pill, language/site
 * rules, offline language pack management, and multi-script Image OCR overlays.
 */
object UhTranslationManager {

    private const val PREFS_NAME = "uh_translation_prefs"
    private const val KEY_PREFERRED_TARGET_LANG = "preferred_target_lang"
    private const val KEY_AUTO_OFFER_PILL = "auto_offer_translate_pill"

    private val FALLBACK_LANGUAGES: List<Language> = listOf(
        Language("en", "English"),
        Language("es", "Spanish"),
        Language("fr", "French"),
        Language("de", "German"),
        Language("hi", "Hindi"),
        Language("ja", "Japanese"),
        Language("ko", "Korean"),
        Language("zh-Hans", "Chinese (Simplified)"),
        Language("zh-Hant", "Chinese (Traditional)"),
        Language("ar", "Arabic"),
        Language("pt", "Portuguese"),
        Language("ru", "Russian"),
        Language("it", "Italian"),
        Language("nl", "Dutch"),
        Language("tr", "Turkish"),
        Language("pl", "Polish"),
        Language("id", "Indonesian"),
        Language("vi", "Vietnamese"),
        Language("th", "Thai"),
        Language("uk", "Ukrainian"),
        Language("bn", "Bengali"),
        Language("ur", "Urdu"),
        Language("sv", "Swedish"),
        Language("da", "Danish"),
        Language("fi", "Finnish"),
        Language("el", "Greek"),
        Language("cs", "Czech"),
        Language("ro", "Romanian"),
    )

    data class State(
        val isEngineSupported: Boolean = true,
        val isLoadingLanguages: Boolean = false,
        val fromLanguages: List<Language> = FALLBACK_LANGUAGES,
        val toLanguages: List<Language> = FALLBACK_LANGUAGES,
        val languageModels: List<LanguageModel> = emptyList(),
        val languageSettings: Map<String, LanguageSetting> = emptyMap(),
        val neverTranslateSites: List<String> = emptyList(),
        val currentSiteNeverTranslate: Boolean = false,
        val preferredTargetLang: String = Locale.getDefault().language.ifBlank { "en" },
        val autoOfferEnabled: Boolean = true,
        val pairDownloadSizeBytes: Long? = null,
        val isBusy: Boolean = false,
        val statusMessage: String? = null,
        val errorMessage: String? = null,
        val lastTranslateMs: Long? = null,
        val isTranslatingImages: Boolean = false,
        val imageOverlayCount: Int = 0,
        val imageOverlayTabId: String? = null,
        val offerTabId: String? = null,
        val offerFromLang: String? = null,
        val offerToLang: String? = null,
        val offerHost: String? = null,
        val pillPhase: TranslatePillPhase = TranslatePillPhase.HIDDEN,
        val pillMessage: String = "",
        val pillProgress: Float = 0f,
        val pillTargetLang: String? = null,
        val pillCanRestore: Boolean = false,
        val pillCanClearImages: Boolean = false,
        val pillCanRetry: Boolean = false,
    )

    private val initialized = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var engineRef: Engine? = null

    @Volatile
    private var storeRef: BrowserStore? = null

    @Volatile
    private var lastRequestedFrom: String = "es"

    @Volatile
    private var lastRequestedTo: String = "en"

    @Volatile
    private var pendingTabId: String? = null

    @Volatile
    private var translateStartedAtMs: Long = 0L

    // Session-scoped sets so dismissed offers and ALWAYS auto-translates don't loop
    private val dismissedOfferKeys = ConcurrentHashMap.newKeySet<String>()
    private val autoTranslatedPageKeys = ConcurrentHashMap.newKeySet<String>()

    private var pillAutoHideRunnable: Runnable? = null
    private var translateWatchdogRunnable: Runnable? = null
    private var imageWatchdogRunnable: Runnable? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun init(context: Context, engine: Engine, store: BrowserStore) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        engineRef = engine
        storeRef = store

        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedTarget = prefs.getString(KEY_PREFERRED_TARGET_LANG, null)
            ?.ifBlank { null }
            ?: Locale.getDefault().language.ifBlank { "en" }
        val autoOffer = prefs.getBoolean(KEY_AUTO_OFFER_PILL, true)

        _state.update {
            it.copy(
                preferredTargetLang = savedTarget,
                autoOfferEnabled = autoOffer,
            )
        }

        GeckoWebExtensionBridge.onImageTranslateProgress = { done, total ->
            if (_state.value.isTranslatingImages && total > 0) {
                val frac = (done.toFloat() / total.toFloat()).coerceIn(0.10f, 0.95f)
                scheduleImageWatchdog(25000L)
                _state.update {
                    it.copy(
                        pillPhase = TranslatePillPhase.TRANSLATING_IMAGES,
                        pillMessage = "Scanning images ($done/$total)…",
                        pillProgress = frac,
                        statusMessage = "Scanning images ($done/$total)…",
                    )
                }
            }
        }

        GeckoWebExtensionBridge.onImageTranslateCompleted = { imageCount, overlayCount, _ ->
            cancelImageWatchdog()
            val activeTabId = storeRef?.state?.selectedTab?.id
            val msg = when {
                overlayCount > 0 -> "Translated $overlayCount image text block${if (overlayCount == 1) "" else "s"}"
                imageCount == 0 -> "No visible images found on screen"
                else -> "No translatable text found in images"
            }
            _state.update {
                it.copy(
                    isTranslatingImages = false,
                    imageOverlayCount = overlayCount,
                    imageOverlayTabId = if (overlayCount > 0) activeTabId else null,
                    statusMessage = msg,
                    errorMessage = null,
                    pillPhase = TranslatePillPhase.COMPLETED,
                    pillMessage = msg,
                    pillProgress = 1f,
                    pillCanRestore = false,
                    pillCanClearImages = overlayCount > 0,
                    pillCanRetry = false,
                )
            }
            schedulePillAutoHide(5500L)
        }

        // Pre-warm the built-in on-device translation engine immediately at app launch
        refreshEngineCapabilities()
    }

    private fun cancelPillAutoHide() {
        pillAutoHideRunnable?.let { mainHandler.removeCallbacks(it) }
        pillAutoHideRunnable = null
    }

    private fun schedulePillAutoHide(delayMs: Long) {
        cancelPillAutoHide()
        val r = Runnable {
            _state.update { current ->
                if (current.pillPhase == TranslatePillPhase.COMPLETED || current.pillPhase == TranslatePillPhase.ERROR) {
                    current.copy(
                        pillPhase = TranslatePillPhase.HIDDEN,
                        pillMessage = "",
                        pillProgress = 0f,
                        pillCanRestore = false,
                        pillCanClearImages = false,
                        pillCanRetry = false,
                    )
                } else {
                    current
                }
            }
        }
        pillAutoHideRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun cancelTranslateWatchdog() {
        translateWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        translateWatchdogRunnable = null
    }

    private fun scheduleTranslateWatchdog(timeoutMs: Long) {
        cancelTranslateWatchdog()
        val r = Runnable {
            val st = _state.value
            if (st.isBusy && (st.pillPhase == TranslatePillPhase.DOWNLOADING_MODEL ||
                    st.pillPhase == TranslatePillPhase.TRANSLATING)
            ) {
                pendingTabId = null
                val msg = "Translation timed out — check network for first-time pack"
                _state.update {
                    it.copy(
                        isBusy = false,
                        statusMessage = null,
                        errorMessage = msg,
                        pillPhase = TranslatePillPhase.ERROR,
                        pillMessage = msg,
                        pillProgress = 0f,
                        pillCanRestore = false,
                        pillCanClearImages = false,
                        pillCanRetry = true,
                    )
                }
                schedulePillAutoHide(7000L)
            }
        }
        translateWatchdogRunnable = r
        mainHandler.postDelayed(r, timeoutMs)
    }

    private fun cancelImageWatchdog() {
        imageWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        imageWatchdogRunnable = null
    }

    private fun scheduleImageWatchdog(timeoutMs: Long) {
        cancelImageWatchdog()
        val r = Runnable {
            if (_state.value.isTranslatingImages) {
                _state.update {
                    it.copy(
                        isTranslatingImages = false,
                        statusMessage = null,
                        pillPhase = TranslatePillPhase.ERROR,
                        pillMessage = "Image scan timed out",
                        pillProgress = 0f,
                        pillCanRestore = false,
                        pillCanClearImages = false,
                        pillCanRetry = false,
                    )
                }
                schedulePillAutoHide(4500L)
            }
        }
        imageWatchdogRunnable = r
        mainHandler.postDelayed(r, timeoutMs)
    }

    fun setAutoOfferEnabled(enabled: Boolean) {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_AUTO_OFFER_PILL, enabled)?.apply()
        _state.update { cur ->
            val hideOffer = !enabled && cur.pillPhase == TranslatePillPhase.OFFER
            cur.copy(
                autoOfferEnabled = enabled,
                pillPhase = if (hideOffer) TranslatePillPhase.HIDDEN else cur.pillPhase,
                pillMessage = if (hideOffer) "" else cur.pillMessage,
                offerTabId = if (hideOffer) null else cur.offerTabId,
                offerFromLang = if (hideOffer) null else cur.offerFromLang,
                offerToLang = if (hideOffer) null else cur.offerToLang,
            )
        }
    }

    fun dismissPill() {
        cancelPillAutoHide()
        val cur = _state.value
        if (cur.pillPhase == TranslatePillPhase.OFFER && !cur.offerTabId.isNullOrBlank()) {
            dismissedOfferKeys.add("${cur.offerTabId}|${cur.offerHost.orEmpty()}")
        }
        _state.update {
            it.copy(
                pillPhase = TranslatePillPhase.HIDDEN,
                pillMessage = "",
                pillProgress = 0f,
                pillCanRestore = false,
                pillCanClearImages = false,
                pillCanRetry = false,
                offerTabId = null,
                offerFromLang = null,
                offerToLang = null,
                offerHost = null,
            )
        }
    }

    fun acceptTranslateOffer() {
        val cur = _state.value
        val from = cur.offerFromLang?.ifBlank { null } ?: return
        val to = cur.offerToLang?.ifBlank { null } ?: cur.preferredTargetLang.ifBlank { "en" }
        translateActiveTab(from, to)
    }

    fun retryLastTranslation() {
        translateActiveTab(lastRequestedFrom, lastRequestedTo)
    }

    /**
     * Observes native engine [TranslationsState] for the active tab to:
     * 1. Track live on-device model preparation & translation completion.
     * 2. Honor "Always translate [Language]" rules automatically.
     * 3. Surface a subtle floating Translate Offer Pill when a foreign page is detected.
     */
    fun syncFromGeckoTabState(tabId: String?, tabUrl: String?, tabState: TranslationsState?) {
        val cur = _state.value

        // 1. Track active translation lifecycle
        val pendingId = pendingTabId
        if (pendingId != null) {
            if (tabId == null || tabId != pendingId) {
                pendingTabId = null
                cancelTranslateWatchdog()
                _state.update {
                    if (it.pillPhase == TranslatePillPhase.DOWNLOADING_MODEL || it.pillPhase == TranslatePillPhase.TRANSLATING) {
                        it.copy(isBusy = false, statusMessage = null, pillPhase = TranslatePillPhase.HIDDEN, pillMessage = "")
                    } else it
                }
            } else if (tabState != null) {
                if (tabState.isTranslated && !tabState.isTranslateProcessing) {
                    pendingTabId = null
                    cancelTranslateWatchdog()
                    val elapsed = (System.currentTimeMillis() - translateStartedAtMs).coerceAtLeast(100L)
                    val targetCode = tabState.translationEngineState?.requestedTranslationPair?.toLanguage
                        ?.ifBlank { null } ?: lastRequestedTo
                    val targetShort = languageShortLabel(targetCode)
                    val msg = "Translated to $targetShort · ${formatDurationShort(elapsed)}"
                    _state.update {
                        it.copy(
                            isBusy = false,
                            statusMessage = msg,
                            errorMessage = null,
                            lastTranslateMs = elapsed,
                            pillPhase = TranslatePillPhase.COMPLETED,
                            pillMessage = msg,
                            pillProgress = 1f,
                            pillTargetLang = targetShort,
                            pillCanRestore = true,
                            pillCanClearImages = false,
                            pillCanRetry = false,
                            offerTabId = null,
                            offerFromLang = null,
                            offerToLang = null,
                            offerHost = null,
                        )
                    }
                    schedulePillAutoHide(5500L)
                    engineRef?.let { fetchLanguageModels(it) }
                    return
                } else if (tabState.isTranslateProcessing) {
                    val elapsed = System.currentTimeMillis() - translateStartedAtMs
                    if (elapsed > 2200L && cur.pillPhase == TranslatePillPhase.DOWNLOADING_MODEL) {
                        val fromName = languageDisplayName(lastRequestedFrom)
                        _state.update {
                            it.copy(
                                pillMessage = "Downloading $fromName pack…",
                                statusMessage = "Downloading $fromName pack…",
                            )
                        }
                    }
                    return
                } else if (tabState.translationError != null) {
                    pendingTabId = null
                    cancelTranslateWatchdog()
                    val msg = "Could not translate this page"
                    _state.update {
                        it.copy(
                            isBusy = false,
                            statusMessage = null,
                            errorMessage = msg,
                            pillPhase = TranslatePillPhase.ERROR,
                            pillMessage = msg,
                            pillProgress = 0f,
                            pillCanRestore = false,
                            pillCanClearImages = false,
                            pillCanRetry = true,
                        )
                    }
                    schedulePillAutoHide(6500L)
                    return
                }
            }
        }

        // Clear stale offer if user navigated away or no active HTTP(S) page
        if (tabId.isNullOrBlank() || tabState == null || tabUrl.isNullOrBlank() ||
            (!tabUrl.startsWith("http://") && !tabUrl.startsWith("https://"))
        ) {
            if (cur.pillPhase == TranslatePillPhase.OFFER) {
                _state.update {
                    it.copy(
                        pillPhase = TranslatePillPhase.HIDDEN,
                        pillMessage = "",
                        offerTabId = null,
                        offerFromLang = null,
                        offerToLang = null,
                        offerHost = null,
                    )
                }
            }
            return
        }

        // If tab is already translated or actively working, hide any lingering offer pill
        if (tabState.isTranslated || tabState.isTranslateProcessing || tabState.isRestoreProcessing) {
            if (cur.pillPhase == TranslatePillPhase.OFFER) {
                _state.update {
                    it.copy(
                        pillPhase = TranslatePillPhase.HIDDEN,
                        pillMessage = "",
                        offerTabId = null,
                        offerFromLang = null,
                        offerToLang = null,
                        offerHost = null,
                    )
                }
            }
            return
        }

        // Don't interrupt active progress/completion/error pills
        if (cur.isBusy || cur.isTranslatingImages ||
            cur.pillPhase == TranslatePillPhase.DOWNLOADING_MODEL ||
            cur.pillPhase == TranslatePillPhase.TRANSLATING ||
            cur.pillPhase == TranslatePillPhase.TRANSLATING_IMAGES ||
            cur.pillPhase == TranslatePillPhase.COMPLETED ||
            cur.pillPhase == TranslatePillPhase.ERROR
        ) {
            return
        }

        val rawDetected = tabState.translationEngineState?.detectedLanguages?.documentLangTag
            ?.trim()
            ?.ifBlank { null }
        val resolvedFrom = rawDetected?.let { resolveSupportedFromCode(it) }
        val targetLang = cur.preferredTargetLang.ifBlank { "en" }
        val resolvedTo = resolveSupportedToCode(targetLang) ?: targetLang

        val host = extractHost(tabUrl)
        val sameLanguage = resolvedFrom == null ||
            baseLang(resolvedFrom).equals(baseLang(resolvedTo), ignoreCase = true)

        if (sameLanguage || resolvedFrom == null) {
            if (cur.pillPhase == TranslatePillPhase.OFFER) {
                _state.update {
                    it.copy(
                        pillPhase = TranslatePillPhase.HIDDEN,
                        pillMessage = "",
                        offerTabId = null,
                        offerFromLang = null,
                        offerToLang = null,
                        offerHost = null,
                    )
                }
            }
            return
        }

        // Check site-level and language-level user rules
        if (isHostNeverTranslated(host, tabUrl, cur.neverTranslateSites)) {
            if (cur.pillPhase == TranslatePillPhase.OFFER) dismissPill()
            return
        }

        val langRule = cur.languageSettings[resolvedFrom]
            ?: cur.languageSettings[baseLang(resolvedFrom)]
            ?: LanguageSetting.OFFER

        if (langRule == LanguageSetting.NEVER) {
            if (cur.pillPhase == TranslatePillPhase.OFFER) dismissPill()
            return
        }

        if (langRule == LanguageSetting.ALWAYS) {
            val pageKey = "$tabId|$tabUrl"
            if (autoTranslatedPageKeys.add(pageKey)) {
                translateActiveTab(resolvedFrom, resolvedTo)
            }
            return
        }

        // Subtle floating offer pill (UH-branded)
        if (!cur.autoOfferEnabled) return
        val dismissKey = "$tabId|$host"
        if (dismissKey in dismissedOfferKeys) {
            if (cur.pillPhase == TranslatePillPhase.OFFER) {
                _state.update { it.copy(pillPhase = TranslatePillPhase.HIDDEN, pillMessage = "") }
            }
            return
        }

        val shouldOffer = tabState.isExpectedTranslate || !rawDetected.isNullOrBlank()
        if (!shouldOffer) return

        val fromName = languageDisplayName(resolvedFrom)
        val toShort = languageShortLabel(resolvedTo)
        val offerMsg = "Translate $fromName → $toShort?"

        if (cur.pillPhase != TranslatePillPhase.OFFER ||
            cur.offerTabId != tabId ||
            cur.offerFromLang != resolvedFrom ||
            cur.offerHost != host
        ) {
            _state.update {
                it.copy(
                    pillPhase = TranslatePillPhase.OFFER,
                    pillMessage = offerMsg,
                    pillProgress = 0f,
                    pillTargetLang = toShort,
                    pillCanRestore = false,
                    pillCanClearImages = false,
                    pillCanRetry = false,
                    offerTabId = tabId,
                    offerFromLang = resolvedFrom,
                    offerToLang = resolvedTo,
                    offerHost = host,
                )
            }
        }
    }

    fun isModelDownloaded(langCode: String): Boolean {
        if (langCode.equals("en", ignoreCase = true)) return true
        val norm = langCode.lowercase(Locale.ROOT)
        return _state.value.languageModels.any { model ->
            val code = model.language?.code?.lowercase(Locale.ROOT) ?: return@any false
            (code == norm || baseLang(code) == baseLang(norm)) &&
                model.status == ModelState.DOWNLOADED
        }
    }

    fun refreshEngineCapabilities() {
        val engine = engineRef ?: return
        mainHandler.post {
            _state.update { it.copy(isLoadingLanguages = it.fromLanguages.isEmpty(), errorMessage = null) }
            engine.isTranslationsEngineSupported(
                onSuccess = { supported ->
                    _state.update { it.copy(isEngineSupported = supported) }
                    if (supported) {
                        fetchSupportedLanguages(engine)
                        fetchLanguageModels(engine)
                        fetchLanguageSettings(engine)
                        fetchNeverTranslateSites(engine)
                    } else {
                        _state.update { it.copy(isLoadingLanguages = false) }
                    }
                },
                onError = { err ->
                    UHLog.w("translate", "isTranslationsEngineSupported error: ${err.message}")
                    _state.update { it.copy(isLoadingLanguages = false) }
                    fetchSupportedLanguages(engine)
                }
            )
        }
    }

    fun refreshCurrentTabSiteSetting() {
        val session = storeRef?.state?.selectedTab?.engineState?.engineSession ?: return
        mainHandler.post {
            session.getNeverTranslateSiteSetting(
                onResult = { neverTranslate ->
                    _state.update { it.copy(currentSiteNeverTranslate = neverTranslate) }
                },
                onException = { err ->
                    UHLog.w("translate", "getNeverTranslateSiteSetting error: ${err.message}")
                }
            )
        }
    }

    fun fetchPairDownloadSize(fromLang: String, toLang: String) {
        val engine = engineRef ?: return
        val resolvedFrom = resolveSupportedFromCode(fromLang) ?: fromLang
        val resolvedTo = resolveSupportedToCode(toLang) ?: toLang
        if (resolvedFrom.isBlank() || resolvedTo.isBlank() ||
            baseLang(resolvedFrom).equals(baseLang(resolvedTo), ignoreCase = true)
        ) {
            _state.update { it.copy(pairDownloadSizeBytes = null) }
            return
        }
        mainHandler.post {
            engine.getTranslationsPairDownloadSize(
                fromLanguage = resolvedFrom,
                toLanguage = resolvedTo,
                onSuccess = { sizeBytes ->
                    _state.update { it.copy(pairDownloadSizeBytes = sizeBytes) }
                },
                onError = {
                    _state.update { it.copy(pairDownloadSizeBytes = null) }
                }
            )
        }
    }

    fun setPreferredTargetLanguage(langCode: String) {
        val clean = langCode.trim()
        if (clean.isBlank()) return
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_PREFERRED_TARGET_LANG, clean)?.apply()
        _state.update { it.copy(preferredTargetLang = clean) }
    }

    fun translateActiveTab(fromLang: String, toLang: String) {
        val tab = storeRef?.state?.selectedTab
        val session = tab?.engineState?.engineSession
        if (session == null) {
            _state.update {
                it.copy(
                    errorMessage = "No active webpage to translate.",
                    pillPhase = TranslatePillPhase.ERROR,
                    pillMessage = "No active webpage to translate",
                    pillCanRestore = false,
                    pillCanClearImages = false,
                    pillCanRetry = false,
                )
            }
            schedulePillAutoHide(4000L)
            return
        }

        val cleanToRaw = toLang.trim().ifBlank { _state.value.preferredTargetLang.ifBlank { "en" } }
        val cleanTo = resolveSupportedToCode(cleanToRaw) ?: cleanToRaw

        val rawFrom = fromLang.trim()
        val detected = tab.translationsState.translationEngineState?.detectedLanguages?.documentLangTag
            ?.trim()?.ifBlank { null }
        val cleanFromRaw = if (rawFrom.isBlank() || rawFrom.equals("auto", ignoreCase = true)) {
            detected ?: _state.value.fromLanguages.firstOrNull { !baseLang(it.code).equals(baseLang(cleanTo), ignoreCase = true) }?.code ?: "es"
        } else {
            rawFrom
        }
        val cleanFrom = resolveSupportedFromCode(cleanFromRaw) ?: cleanFromRaw

        if (baseLang(cleanFrom).equals(baseLang(cleanTo), ignoreCase = true)) {
            _state.update { it.copy(errorMessage = "Source and target languages are the same.") }
            return
        }

        lastRequestedFrom = cleanFrom
        lastRequestedTo = cleanTo
        setPreferredTargetLanguage(cleanTo)
        cancelPillAutoHide()

        val needsDownload = !isModelDownloaded(cleanFrom) || !isModelDownloaded(cleanTo)
        val phase = if (needsDownload) TranslatePillPhase.DOWNLOADING_MODEL else TranslatePillPhase.TRANSLATING
        val statusMsg = if (needsDownload) {
            "Preparing ${languageDisplayName(cleanFrom)} pack…"
        } else {
            "Translating to ${languageShortLabel(cleanTo)}…"
        }

        pendingTabId = tab.id
        translateStartedAtMs = System.currentTimeMillis()
        _state.update {
            it.copy(
                isBusy = true,
                statusMessage = statusMsg,
                errorMessage = null,
                pillPhase = phase,
                pillMessage = statusMsg,
                pillProgress = 0f,
                pillTargetLang = languageShortLabel(cleanTo),
                pillCanRestore = false,
                pillCanClearImages = false,
                pillCanRetry = false,
                offerTabId = null,
                offerFromLang = null,
                offerToLang = null,
                offerHost = null,
            )
        }
        scheduleTranslateWatchdog(if (needsDownload) 45000L else 18000L)

        mainHandler.post {
            try {
                session.requestTranslate(
                    fromLanguage = cleanFrom,
                    toLanguage = cleanTo,
                    options = TranslationOptions(downloadModel = true),
                )
            } catch (e: Exception) {
                pendingTabId = null
                cancelTranslateWatchdog()
                UHLog.w("translate", "requestTranslate failed: ${e.message}")
                val errMsg = e.message ?: "On-device translation failed."
                _state.update {
                    it.copy(
                        isBusy = false,
                        statusMessage = null,
                        errorMessage = errMsg,
                        pillPhase = TranslatePillPhase.ERROR,
                        pillMessage = errMsg,
                        pillProgress = 0f,
                        pillCanRestore = false,
                        pillCanClearImages = false,
                        pillCanRetry = true,
                    )
                }
                schedulePillAutoHide(6000L)
            }
        }
    }

    fun restoreActiveTab() {
        val tab = storeRef?.state?.selectedTab
        val session = tab?.engineState?.engineSession

        cancelTranslateWatchdog()
        pendingTabId = null

        _state.update {
            it.copy(
                isBusy = false,
                lastTranslateMs = null,
                statusMessage = "Restored original page",
                errorMessage = null,
                pillPhase = TranslatePillPhase.COMPLETED,
                pillMessage = "Restored original page",
                pillProgress = 1f,
                pillCanRestore = false,
                pillCanClearImages = false,
                pillCanRetry = false,
            )
        }
        schedulePillAutoHide(2400L)

        if (session != null && tab.translationsState.isTranslated) {
            mainHandler.post {
                try {
                    session.requestTranslationRestore()
                } catch (e: Exception) {
                    UHLog.w("translate", "requestTranslationRestore failed: ${e.message}")
                }
            }
        }
    }

    fun translateVisibleImages(fromLang: String, toLang: String) {
        val cleanTo = toLang.trim().ifBlank { _state.value.preferredTargetLang.ifBlank { "en" } }
        val cleanFrom = fromLang.trim().ifBlank { "auto" }
        cancelPillAutoHide()
        _state.update {
            it.copy(
                isTranslatingImages = true,
                statusMessage = "Scanning images (OCR)…",
                errorMessage = null,
                pillPhase = TranslatePillPhase.TRANSLATING_IMAGES,
                pillMessage = "Scanning images (OCR)…",
                pillProgress = 0f,
                pillCanRestore = false,
                pillCanClearImages = false,
                pillCanRetry = false,
                offerTabId = null,
                offerFromLang = null,
                offerToLang = null,
                offerHost = null,
            )
        }
        val dispatched = GeckoWebExtensionBridge.triggerImageTranslation(cleanFrom, cleanTo)
        if (!dispatched) {
            _state.update {
                it.copy(
                    isTranslatingImages = false,
                    statusMessage = null,
                    errorMessage = "Image scanner not ready on this page.",
                    pillPhase = TranslatePillPhase.ERROR,
                    pillMessage = "Image scanner not ready",
                    pillCanRestore = false,
                    pillCanClearImages = false,
                    pillCanRetry = false,
                )
            }
            schedulePillAutoHide(4500L)
            return
        }
        scheduleImageWatchdog(30000L)
    }

    fun clearImageTranslations() {
        cancelImageWatchdog()
        GeckoWebExtensionBridge.clearImageTranslations()
        _state.update {
            it.copy(
                isTranslatingImages = false,
                imageOverlayCount = 0,
                imageOverlayTabId = null,
                statusMessage = null,
                pillPhase = TranslatePillPhase.HIDDEN,
                pillMessage = "",
                pillCanClearImages = false,
            )
        }
    }

    fun setLanguageRule(languageCode: String, setting: LanguageSetting) {
        val engine = engineRef ?: return
        if (languageCode.isBlank() || languageCode == "auto") return
        mainHandler.post {
            engine.setLanguageSetting(
                languageCode = languageCode,
                languageSetting = setting,
                onSuccess = { fetchLanguageSettings(engine) },
                onError = { err ->
                    UHLog.w("translate", "setLanguageSetting($languageCode, $setting) failed: ${err.message}")
                    _state.update { it.copy(errorMessage = "Could not update language preference.") }
                }
            )
        }
    }

    fun setNeverTranslateCurrentSite(neverTranslate: Boolean) {
        val session = storeRef?.state?.selectedTab?.engineState?.engineSession ?: return
        val engine = engineRef
        mainHandler.post {
            session.setNeverTranslateSiteSetting(
                setting = neverTranslate,
                onResult = {
                    _state.update { it.copy(currentSiteNeverTranslate = neverTranslate) }
                    if (engine != null) fetchNeverTranslateSites(engine)
                },
                onException = { err ->
                    UHLog.w("translate", "setNeverTranslateSiteSetting failed: ${err.message}")
                }
            )
        }
    }

    fun removeNeverTranslateSite(origin: String) {
        val engine = engineRef ?: return
        mainHandler.post {
            engine.setNeverTranslateSpecifiedSite(
                origin = origin,
                setting = false,
                onSuccess = {
                    fetchNeverTranslateSites(engine)
                    refreshCurrentTabSiteSetting()
                },
                onError = { err ->
                    UHLog.w("translate", "setNeverTranslateSpecifiedSite failed: ${err.message}")
                }
            )
        }
    }

    fun downloadLanguageModel(langCode: String) {
        manageLanguageModel(langCode, ModelOperation.DOWNLOAD)
    }

    fun deleteLanguageModel(langCode: String) {
        manageLanguageModel(langCode, ModelOperation.DELETE)
    }

    private fun manageLanguageModel(langCode: String, operation: ModelOperation) {
        val engine = engineRef ?: return
        val pendingState = if (operation == ModelOperation.DOWNLOAD) {
            ModelState.DOWNLOAD_IN_PROGRESS
        } else {
            ModelState.DELETION_IN_PROGRESS
        }
        _state.update { current ->
            current.copy(
                errorMessage = null,
                languageModels = current.languageModels.map { model ->
                    if (model.language?.code == langCode) model.copy(status = pendingState) else model
                }
            )
        }
        mainHandler.post {
            engine.manageTranslationsLanguageModel(
                options = ModelManagementOptions(
                    languageToManage = langCode,
                    operation = operation,
                    operationLevel = OperationLevel.LANGUAGE,
                ),
                onSuccess = { fetchLanguageModels(engine) },
                onError = { err ->
                    UHLog.w("translate", "manageTranslationsLanguageModel($langCode, $operation) failed: ${err.message}")
                    _state.update { it.copy(errorMessage = "Pack ${operation.operation} failed. Check connection.") }
                    fetchLanguageModels(engine)
                }
            )
        }
    }

    private fun fetchSupportedLanguages(engine: Engine) {
        engine.getSupportedTranslationLanguages(
            onSuccess = { support ->
                val fromList = support.fromLanguages.orEmpty()
                    .sortedBy { it.localizedDisplayName ?: it.code }
                    .ifEmpty { FALLBACK_LANGUAGES }
                val toList = support.toLanguages.orEmpty()
                    .sortedBy { it.localizedDisplayName ?: it.code }
                    .ifEmpty { FALLBACK_LANGUAGES }
                _state.update {
                    it.copy(
                        isLoadingLanguages = false,
                        fromLanguages = fromList,
                        toLanguages = toList,
                    )
                }
            },
            onError = { err ->
                UHLog.w("translate", "getSupportedTranslationLanguages failed: ${err.message}")
                _state.update {
                    it.copy(
                        isLoadingLanguages = false,
                        fromLanguages = it.fromLanguages.ifEmpty { FALLBACK_LANGUAGES },
                        toLanguages = it.toLanguages.ifEmpty { FALLBACK_LANGUAGES },
                    )
                }
            }
        )
    }

    private fun fetchLanguageModels(engine: Engine) {
        engine.getTranslationsModelDownloadStates(
            onSuccess = { models ->
                val sorted = models
                    .filter { it.language != null }
                    .sortedWith(
                        compareByDescending<LanguageModel> { it.status == ModelState.DOWNLOADED }
                            .thenBy { it.language?.localizedDisplayName ?: it.language?.code.orEmpty() }
                    )
                _state.update { it.copy(languageModels = sorted) }
            },
            onError = { err ->
                UHLog.w("translate", "getTranslationsModelDownloadStates failed: ${err.message}")
            }
        )
    }

    private fun fetchLanguageSettings(engine: Engine) {
        engine.getLanguageSettings(
            onSuccess = { map ->
                _state.update { it.copy(languageSettings = map) }
            },
            onError = { err ->
                UHLog.w("translate", "getLanguageSettings failed: ${err.message}")
            }
        )
    }

    private fun fetchNeverTranslateSites(engine: Engine) {
        engine.getNeverTranslateSiteList(
            onSuccess = { sites ->
                _state.update { it.copy(neverTranslateSites = sites) }
            },
            onError = { err ->
                UHLog.w("translate", "getNeverTranslateSiteList failed: ${err.message}")
            }
        )
    }

    private fun resolveSupportedFromCode(raw: String): String? {
        val list = _state.value.fromLanguages
        list.firstOrNull { it.code.equals(raw, ignoreCase = true) }?.let { return it.code }
        val base = baseLang(raw)
        return list.firstOrNull { baseLang(it.code).equals(base, ignoreCase = true) }?.code
    }

    private fun resolveSupportedToCode(raw: String): String? {
        val list = _state.value.toLanguages
        list.firstOrNull { it.code.equals(raw, ignoreCase = true) }?.let { return it.code }
        val base = baseLang(raw)
        return list.firstOrNull { baseLang(it.code).equals(base, ignoreCase = true) }?.code
    }

    private fun baseLang(code: String): String =
        code.trim().substringBefore("-").substringBefore("_").lowercase(Locale.ROOT)

    private fun extractHost(url: String): String = try {
        URI(url).host?.removePrefix("www.")?.lowercase(Locale.ROOT).orEmpty()
    } catch (_: Exception) {
        ""
    }

    private fun isHostNeverTranslated(host: String, url: String, neverList: List<String>): Boolean {
        if (neverList.isEmpty()) return false
        val cleanHost = host.lowercase(Locale.ROOT)
        return neverList.any { origin ->
            val cleanOrigin = origin.trim().lowercase(Locale.ROOT)
            val originHost = extractHost(cleanOrigin).ifBlank { cleanOrigin.removePrefix("www.") }
            (cleanHost.isNotBlank() && originHost == cleanHost) ||
                url.lowercase(Locale.ROOT).startsWith(cleanOrigin)
        }
    }

    private fun languageShortLabel(langCode: String): String {
        val clean = langCode.trim()
        if (clean.isBlank()) return "EN"
        return clean.substringBefore("-").uppercase(Locale.ROOT)
    }

    private fun languageDisplayName(langCode: String): String {
        return _state.value.fromLanguages.firstOrNull { it.code.equals(langCode, ignoreCase = true) }
            ?.localizedDisplayName
            ?: _state.value.toLanguages.firstOrNull { it.code.equals(langCode, ignoreCase = true) }
                ?.localizedDisplayName
            ?: FALLBACK_LANGUAGES.firstOrNull { it.code.equals(langCode, ignoreCase = true) }
                ?.localizedDisplayName
            ?: langCode.uppercase(Locale.ROOT)
    }

    private fun formatDurationShort(durationMs: Long): String {
        return if (durationMs < 1000L) {
            "${durationMs.coerceAtLeast(50L)}ms"
        } else {
            String.format(Locale.US, "%.1fs", durationMs / 1000.0)
        }
    }

    fun formatBytes(bytes: Long?): String {
        if (bytes == null || bytes <= 0L) return ""
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1.0) String.format(Locale.US, "%.1f MB", mb)
        else "${(bytes / 1024L).coerceAtLeast(1L)} KB"
    }
}
