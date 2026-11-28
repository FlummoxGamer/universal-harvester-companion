package app.harvester.companion.browser.translate

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.harvester.companion.GeckoWebExtensionBridge
import app.harvester.companion.UHLog
import app.harvester.companion.browser.reader.ReaderModeManager
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
 * Unified Browser Translation Manager supporting:
 * 1. Fast Online Google Translate (default, zero downloads, 55+ languages, Brave-style floating bar).
 * 2. Private Offline Mode powered by GeckoView's on-device translation stack with persistent Offline Packs.
 * 3. True Auto-detect source state (picking any explicit language turns off Auto-detect before translating).
 * 4. Fast viewport-only Image OCR Translation (online batched primary + ML Kit offline fallback).
 * 5. "Offer to translate" OFF by default; when enabled, shows a tiny corner translate icon button.
 */
object UhTranslationManager {

    private const val PREFS_NAME = "uh_translation_prefs"
    private const val KEY_PREFERRED_TARGET_LANG = "preferred_target_lang"
    private const val KEY_AUTO_OFFER_PILL = "auto_offer_translate_pill_v2"
    private const val KEY_USE_OFFLINE_ENGINE = "use_offline_translate_engine"

    val ONLINE_LANGUAGES: List<Language> = listOf(
        Language("af", "Afrikaans"),
        Language("ar", "Arabic"),
        Language("az", "Azerbaijani"),
        Language("bg", "Bulgarian"),
        Language("bn", "Bengali"),
        Language("ca", "Catalan"),
        Language("cs", "Czech"),
        Language("da", "Danish"),
        Language("de", "German"),
        Language("el", "Greek"),
        Language("en", "English"),
        Language("es", "Spanish"),
        Language("et", "Estonian"),
        Language("fa", "Persian"),
        Language("fi", "Finnish"),
        Language("fil", "Filipino"),
        Language("fr", "French"),
        Language("gu", "Gujarati"),
        Language("he", "Hebrew"),
        Language("hi", "Hindi"),
        Language("hr", "Croatian"),
        Language("hu", "Hungarian"),
        Language("id", "Indonesian"),
        Language("it", "Italian"),
        Language("ja", "Japanese"),
        Language("kn", "Kannada"),
        Language("ko", "Korean"),
        Language("lt", "Lithuanian"),
        Language("lv", "Latvian"),
        Language("ml", "Malayalam"),
        Language("mr", "Marathi"),
        Language("ms", "Malay"),
        Language("nb", "Norwegian"),
        Language("ne", "Nepali"),
        Language("nl", "Dutch"),
        Language("pa", "Punjabi"),
        Language("pl", "Polish"),
        Language("pt", "Portuguese"),
        Language("ro", "Romanian"),
        Language("ru", "Russian"),
        Language("sk", "Slovak"),
        Language("sl", "Slovenian"),
        Language("sq", "Albanian"),
        Language("sr", "Serbian"),
        Language("sv", "Swedish"),
        Language("sw", "Swahili"),
        Language("ta", "Tamil"),
        Language("te", "Telugu"),
        Language("th", "Thai"),
        Language("tr", "Turkish"),
        Language("uk", "Ukrainian"),
        Language("ur", "Urdu"),
        Language("vi", "Vietnamese"),
        Language("zh-Hans", "Chinese (Simplified)"),
        Language("zh-Hant", "Chinese (Traditional)"),
    ).sortedBy { it.localizedDisplayName ?: it.code }

    data class State(
        val isEngineSupported: Boolean = true,
        val isLoadingLanguages: Boolean = false,
        val useOfflineEngine: Boolean = false,
        val fromLanguages: List<Language> = ONLINE_LANGUAGES,
        val toLanguages: List<Language> = ONLINE_LANGUAGES,
        val languageModels: List<LanguageModel> = emptyList(),
        val languageSettings: Map<String, LanguageSetting> = emptyMap(),
        val neverTranslateSites: List<String> = emptyList(),
        val currentSiteNeverTranslate: Boolean = false,
        val isAutoDetect: Boolean = true,
        val manualFromLang: String? = null,
        val detectedDocLang: String? = null,
        val preferredTargetLang: String = Locale.getDefault().language.ifBlank { "en" },
        val autoOfferEnabled: Boolean = false,
        val barVisible: Boolean = false,
        val onlineTranslatedTabId: String? = null,
        val onlineTranslatedUrl: String? = null,
        val onlineTranslatedToLang: String? = null,
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
    ) {
        fun effectiveFromCode(): String = if (isAutoDetect) {
            "auto"
        } else {
            manualFromLang?.ifBlank { null } ?: detectedDocLang?.ifBlank { null } ?: "auto"
        }

        fun resolvedSourceLangForRules(): String {
            return if (!isAutoDetect && !manualFromLang.isNullOrBlank()) {
                manualFromLang
            } else {
                detectedDocLang?.ifBlank { null } ?: "es"
            }
        }

        fun sourceTabDisplayLabel(): String {
            return if (isAutoDetect) {
                val det = detectedDocLang?.ifBlank { null }
                if (det != null) "${UhTranslationManager.languageDisplayName(det)} (Auto)"
                else "Auto-detect"
            } else {
                val code = manualFromLang?.ifBlank { null } ?: detectedDocLang ?: "auto"
                if (code == "auto") "Auto-detect" else UhTranslationManager.languageDisplayName(code)
            }
        }

        fun targetTabDisplayLabel(): String {
            val code = preferredTargetLang.ifBlank { "en" }
            return UhTranslationManager.languageDisplayName(code)
        }

        fun activeFromLanguageList(): List<Language> =
            if (useOfflineEngine) fromLanguages else ONLINE_LANGUAGES

        fun activeToLanguageList(): List<Language> =
            if (useOfflineEngine) toLanguages else ONLINE_LANGUAGES

        fun isCurrentTabTranslated(tabId: String?, tabUrl: String?, geckoTranslated: Boolean): Boolean {
            if (geckoTranslated) return true
            return !tabId.isNullOrBlank() &&
                tabId == onlineTranslatedTabId &&
                !tabUrl.isNullOrBlank() &&
                tabUrl == onlineTranslatedUrl
        }
    }

    private val initialized = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var engineRef: Engine? = null
    @Volatile
    private var storeRef: BrowserStore? = null
    @Volatile
    private var lastRequestedFrom: String = "auto"
    @Volatile
    private var lastRequestedTo: String = "en"
    @Volatile
    private var pendingTabId: String? = null
    @Volatile
    private var pendingOnlineTabId: String? = null
    @Volatile
    private var pendingOnlineTabUrl: String? = null
    @Volatile
    private var translateStartedAtMs: Long = 0L

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
        // Offer to translate is OFF by default per user preference
        val autoOffer = prefs.getBoolean(KEY_AUTO_OFFER_PILL, false)
        val offlineEngine = prefs.getBoolean(KEY_USE_OFFLINE_ENGINE, false)

        _state.update {
            it.copy(
                preferredTargetLang = savedTarget,
                autoOfferEnabled = autoOffer,
                useOfflineEngine = offlineEngine,
            )
        }

        GeckoWebExtensionBridge.onImageTranslateProgress = { done, total ->
            if (_state.value.isTranslatingImages && total > 0) {
                val frac = (done.toFloat() / total.toFloat()).coerceIn(0.10f, 0.95f)
                scheduleImageWatchdog(18000L)
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
                imageCount == 0 -> "No visible images on screen"
                else -> "No readable text found in visible images"
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
            schedulePillAutoHide(4500L)
        }

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
                pendingOnlineTabId = null
                val msg = if (st.useOfflineEngine) {
                    "Offline translation timed out — check pack download"
                } else {
                    "Translation timed out — check connection or try Offline Mode"
                }
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
                schedulePillAutoHide(4000L)
            }
        }
        imageWatchdogRunnable = r
        mainHandler.postDelayed(r, timeoutMs)
    }

    fun setUseOfflineEngine(offline: Boolean) {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_USE_OFFLINE_ENGINE, offline)?.apply()
        _state.update { it.copy(useOfflineEngine = offline, errorMessage = null) }
        if (offline) {
            refreshEngineCapabilities()
        }
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

    fun setSourceAutoDetect() {
        _state.update {
            it.copy(
                isAutoDetect = true,
                manualFromLang = null,
                errorMessage = null
            )
        }
    }

    fun setManualSourceLanguage(langCode: String) {
        val clean = langCode.trim()
        if (clean.isBlank() || clean.equals("auto", ignoreCase = true)) {
            setSourceAutoDetect()
            return
        }
        _state.update {
            it.copy(
                isAutoDetect = false,
                manualFromLang = clean,
                errorMessage = null
            )
        }
    }

    fun setPreferredTargetLanguage(langCode: String) {
        val clean = langCode.trim()
        if (clean.isBlank()) return
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_PREFERRED_TARGET_LANG, clean)?.apply()
        _state.update { it.copy(preferredTargetLang = clean, errorMessage = null) }
    }

    fun swapLanguages() {
        val cur = _state.value
        val resolvedFrom = if (cur.isAutoDetect) {
            cur.detectedDocLang?.ifBlank { null } ?: "en"
        } else {
            cur.manualFromLang?.ifBlank { null } ?: cur.detectedDocLang?.ifBlank { null } ?: "en"
        }
        val currentTo = cur.preferredTargetLang.ifBlank { "en" }
        setManualSourceLanguage(currentTo)
        setPreferredTargetLanguage(resolvedFrom)
    }

    fun showTranslateBar() {
        _state.update { it.copy(barVisible = true) }
    }

    fun hideTranslateBar() {
        cancelPillAutoHide()
        _state.update {
            it.copy(
                barVisible = false,
                pillPhase = if (it.pillPhase == TranslatePillPhase.OFFER) it.pillPhase else TranslatePillPhase.HIDDEN,
                pillMessage = "",
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
                barVisible = false,
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
        val to = cur.offerToLang?.ifBlank { null } ?: cur.preferredTargetLang.ifBlank { "en" }
        val from = if (cur.isAutoDetect) "auto" else (cur.offerFromLang?.ifBlank { null } ?: "auto")
        _state.update { it.copy(barVisible = true) }
        translateActiveTab(from, to)
    }

    fun retryLastTranslation() {
        translateActiveTab(lastRequestedFrom, lastRequestedTo)
    }

    fun syncFromGeckoTabState(tabId: String?, tabUrl: String?, tabState: TranslationsState?) {
        val cur = _state.value

        // Clear online-translated marker & bar when user navigates to a different page/tab
        if (cur.onlineTranslatedTabId != null &&
            (tabId != cur.onlineTranslatedTabId || tabUrl != cur.onlineTranslatedUrl)
        ) {
            _state.update {
                it.copy(
                    onlineTranslatedTabId = null,
                    onlineTranslatedUrl = null,
                    onlineTranslatedToLang = null,
                    barVisible = false
                )
            }
        }

        // Track detected language from GeckoView cleanly without clobbering user's manual selection!
        val rawDetected = tabState?.translationEngineState?.detectedLanguages?.documentLangTag
            ?.trim()?.ifBlank { null }
        if (rawDetected != null && rawDetected != cur.detectedDocLang) {
            _state.update { it.copy(detectedDocLang = rawDetected) }
        }

        // 1. Track active offline GeckoView translation lifecycle
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
                    val fromCode = tabState.translationEngineState?.requestedTranslationPair?.fromLanguage
                        ?.ifBlank { null } ?: lastRequestedFrom
                    val targetShort = languageShortLabel(targetCode)
                    val msg = "Translated offline to $targetShort · ${formatDurationShort(elapsed)}"
                    _state.update {
                        it.copy(
                            isBusy = false,
                            barVisible = true,
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
                    schedulePillAutoHide(3200L)
                    ReaderModeManager.onPageTranslationChanged(targetCode)
                    // Persist downloaded packs in GeckoView Offline Packs store so next time is instant!
                    persistOfflinePacksForPair(fromCode, targetCode)
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
                    val msg = "Offline translation failed for this page"
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

        val alreadyTranslated = _state.value.isCurrentTabTranslated(tabId, tabUrl, tabState.isTranslated)
        if (alreadyTranslated || tabState.isTranslateProcessing || tabState.isRestoreProcessing) {
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

        if (cur.isBusy || cur.isTranslatingImages ||
            cur.pillPhase == TranslatePillPhase.DOWNLOADING_MODEL ||
            cur.pillPhase == TranslatePillPhase.TRANSLATING ||
            cur.pillPhase == TranslatePillPhase.TRANSLATING_IMAGES ||
            cur.pillPhase == TranslatePillPhase.COMPLETED ||
            cur.pillPhase == TranslatePillPhase.ERROR
        ) {
            return
        }

        val resolvedFrom = rawDetected?.let { resolveSupportedFromCode(it) ?: it }
        val targetLang = cur.preferredTargetLang.ifBlank { "en" }
        val host = extractHost(tabUrl)
        val sameLanguage = resolvedFrom == null ||
            baseLang(resolvedFrom).equals(baseLang(targetLang), ignoreCase = true)

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
                translateActiveTab("auto", targetLang)
            }
            return
        }

        // Tiny corner icon offer — strictly gated behind user-enabled autoOfferEnabled (OFF by default)
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

        val toShort = languageShortLabel(targetLang)
        if (cur.pillPhase != TranslatePillPhase.OFFER ||
            cur.offerTabId != tabId ||
            cur.offerFromLang != resolvedFrom ||
            cur.offerHost != host
        ) {
            _state.update {
                it.copy(
                    pillPhase = TranslatePillPhase.OFFER,
                    pillMessage = "Translate",
                    pillProgress = 0f,
                    pillTargetLang = toShort,
                    offerTabId = tabId,
                    offerFromLang = resolvedFrom,
                    offerToLang = targetLang,
                    offerHost = host,
                )
            }
        }
    }

    /**
     * Ensures that when Offline Mode downloads a language pack on-demand, GeckoView registers &
     * persists it in the Offline Language Packs catalog so it's saved for future offline use.
     */
    private fun persistOfflinePacksForPair(fromCode: String, toCode: String) {
        val engine = engineRef ?: return
        val candidates = listOf(fromCode, toCode)
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals("auto", ignoreCase = true) && !it.equals("en", ignoreCase = true) }
            .distinct()

        for (code in candidates) {
            if (!isModelDownloaded(code)) {
                mainHandler.post {
                    try {
                        engine.manageTranslationsLanguageModel(
                            options = ModelManagementOptions(
                                languageToManage = code,
                                operation = ModelOperation.DOWNLOAD,
                                operationLevel = OperationLevel.LANGUAGE,
                            ),
                            onSuccess = { fetchLanguageModels(engine) },
                            onError = { fetchLanguageModels(engine) }
                        )
                    } catch (_: Exception) {
                        fetchLanguageModels(engine)
                    }
                }
            }
        }
        mainHandler.postDelayed({ fetchLanguageModels(engine) }, 800L)
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
        if (!_state.value.useOfflineEngine) {
            _state.update { it.copy(pairDownloadSizeBytes = null) }
            return
        }
        val engine = engineRef ?: return
        val effectiveFrom = if (fromLang.equals("auto", ignoreCase = true)) {
            _state.value.detectedDocLang ?: return
        } else fromLang
        val resolvedFrom = resolveSupportedFromCode(effectiveFrom) ?: effectiveFrom
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

    fun translateActiveTab(fromLang: String? = null, toLang: String? = null) {
        val cur = _state.value
        val tab = storeRef?.state?.selectedTab
        val session = tab?.engineState?.engineSession
        if (tab == null || session == null) {
            _state.update {
                it.copy(
                    errorMessage = "No active webpage to translate.",
                    pillPhase = TranslatePillPhase.ERROR,
                    pillMessage = "No active webpage",
                )
            }
            schedulePillAutoHide(3500L)
            return
        }

        val resolvedTo = (toLang ?: cur.preferredTargetLang).trim().ifBlank { "en" }
        val resolvedFrom = (fromLang ?: cur.effectiveFromCode()).trim().ifBlank { "auto" }

        if (!resolvedFrom.equals("auto", ignoreCase = true) &&
            baseLang(resolvedFrom).equals(baseLang(resolvedTo), ignoreCase = true)
        ) {
            _state.update { it.copy(errorMessage = "Source and target languages are the same.") }
            return
        }

        lastRequestedFrom = resolvedFrom
        lastRequestedTo = resolvedTo
        setPreferredTargetLanguage(resolvedTo)
        cancelPillAutoHide()

        if (!cur.useOfflineEngine) {
            // Fast Online Google Translate mode!
            translateActiveTabOnline(tab.id, tab.content.url, resolvedFrom, resolvedTo)
        } else {
            // Private Offline GeckoView engine mode
            translateActiveTabOffline(tab.id, session, tab.translationsState, resolvedFrom, resolvedTo)
        }
    }

    private fun translateActiveTabOnline(tabId: String, tabUrl: String, fromLang: String, toLang: String) {
        // Restore any prior GeckoView offline translation first so DOM stays clean
        val tab = storeRef?.state?.selectedTab
        if (tab != null && tab.translationsState.isTranslated) {
            mainHandler.post {
                runCatching { tab.engineState.engineSession?.requestTranslationRestore() }
            }
        }

        pendingTabId = null
        pendingOnlineTabId = tabId
        pendingOnlineTabUrl = tabUrl
        translateStartedAtMs = System.currentTimeMillis()
        val toLabel = languageDisplayName(toLang)

        _state.update {
            it.copy(
                isBusy = true,
                barVisible = true,
                statusMessage = "Translating to $toLabel…",
                errorMessage = null,
                pillPhase = TranslatePillPhase.TRANSLATING,
                pillMessage = "Translating to $toLabel…",
                pillProgress = 0f,
                pillTargetLang = languageShortLabel(toLang),
                pillCanRestore = false,
                pillCanClearImages = false,
                pillCanRetry = false,
                offerTabId = null,
                offerFromLang = null,
                offerToLang = null,
                offerHost = null,
            )
        }
        scheduleTranslateWatchdog(22000L)

        val dispatched = GeckoWebExtensionBridge.triggerOnlinePageTranslation(fromLang, toLang)
        if (!dispatched) {
            pendingOnlineTabId = null
            cancelTranslateWatchdog()
            _state.update {
                it.copy(
                    isBusy = false,
                    errorMessage = "Translator not ready yet — try again.",
                    pillPhase = TranslatePillPhase.ERROR,
                    pillMessage = "Translator not ready",
                    pillCanRetry = true,
                )
            }
            schedulePillAutoHide(4500L)
        }
    }

    fun onOnlinePageTranslationDone(
        ok: Boolean,
        restored: Boolean,
        @Suppress("UNUSED_PARAMETER") translatedCount: Int,
        detectedLang: String?,
        toLang: String?,
        error: String?
    ) {
        cancelTranslateWatchdog()
        val tabId = pendingOnlineTabId ?: storeRef?.state?.selectedTab?.id
        val tabUrl = pendingOnlineTabUrl ?: storeRef?.state?.selectedTab?.content?.url
        pendingOnlineTabId = null
        pendingOnlineTabUrl = null

        if (restored) {
            _state.update {
                it.copy(
                    isBusy = false,
                    onlineTranslatedTabId = null,
                    onlineTranslatedUrl = null,
                    onlineTranslatedToLang = null,
                    statusMessage = "Showing original page",
                    errorMessage = null,
                    pillPhase = TranslatePillPhase.COMPLETED,
                    pillMessage = "",
                )
            }
            ReaderModeManager.onPageTranslationChanged(null)
            return
        }

        if (!ok) {
            val msg = error?.ifBlank { null } ?: "Online translation failed — try Offline Mode"
            _state.update {
                it.copy(
                    isBusy = false,
                    statusMessage = null,
                    errorMessage = msg,
                    pillPhase = TranslatePillPhase.ERROR,
                    pillMessage = msg,
                    pillCanRetry = true,
                )
            }
            schedulePillAutoHide(5500L)
            return
        }

        val elapsed = (System.currentTimeMillis() - translateStartedAtMs).coerceAtLeast(80L)
        val finalTo = toLang?.ifBlank { null } ?: lastRequestedTo
        val normalizedDetected = detectedLang?.trim()?.ifBlank { null }
        val msg = "Translated to ${languageShortLabel(finalTo)} · ${formatDurationShort(elapsed)}"

        _state.update {
            it.copy(
                isBusy = false,
                barVisible = true,
                onlineTranslatedTabId = tabId,
                onlineTranslatedUrl = tabUrl,
                onlineTranslatedToLang = finalTo,
                detectedDocLang = normalizedDetected ?: it.detectedDocLang,
                statusMessage = msg,
                errorMessage = null,
                lastTranslateMs = elapsed,
                pillPhase = TranslatePillPhase.COMPLETED,
                pillMessage = msg,
                pillProgress = 1f,
                pillTargetLang = languageShortLabel(finalTo),
                pillCanRestore = true,
                pillCanRetry = false,
            )
        }
        schedulePillAutoHide(2600L)
        ReaderModeManager.onPageTranslationChanged(finalTo)
    }

    private fun translateActiveTabOffline(
        tabId: String,
        session: mozilla.components.concept.engine.EngineSession,
        tabTranslationsState: TranslationsState,
        fromLang: String,
        toLang: String
    ) {
        // Clear any online translation first
        if (_state.value.onlineTranslatedTabId == tabId) {
            GeckoWebExtensionBridge.restoreOnlinePageTranslation()
            _state.update {
                it.copy(onlineTranslatedTabId = null, onlineTranslatedUrl = null, onlineTranslatedToLang = null)
            }
        }

        val cleanTo = resolveSupportedToCode(toLang) ?: toLang
        val detected = tabTranslationsState.translationEngineState?.detectedLanguages?.documentLangTag
            ?.trim()?.ifBlank { null } ?: _state.value.detectedDocLang
        val cleanFromRaw = if (fromLang.isBlank() || fromLang.equals("auto", ignoreCase = true)) {
            detected ?: _state.value.fromLanguages.firstOrNull { !baseLang(it.code).equals(baseLang(cleanTo), ignoreCase = true) }?.code ?: "es"
        } else {
            fromLang
        }
        val cleanFrom = resolveSupportedFromCode(cleanFromRaw) ?: cleanFromRaw

        if (baseLang(cleanFrom).equals(baseLang(cleanTo), ignoreCase = true)) {
            _state.update { it.copy(errorMessage = "Source and target languages are the same.") }
            return
        }

        lastRequestedFrom = cleanFrom
        lastRequestedTo = cleanTo

        val needsDownload = !isModelDownloaded(cleanFrom) || !isModelDownloaded(cleanTo)
        val phase = if (needsDownload) TranslatePillPhase.DOWNLOADING_MODEL else TranslatePillPhase.TRANSLATING
        val statusMsg = if (needsDownload) {
            "Preparing ${languageDisplayName(cleanFrom)} offline pack…"
        } else {
            "Translating offline to ${languageShortLabel(cleanTo)}…"
        }

        pendingTabId = tabId
        translateStartedAtMs = System.currentTimeMillis()
        _state.update {
            it.copy(
                isBusy = true,
                barVisible = true,
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
                val errMsg = e.message ?: "Offline translation failed."
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
        pendingOnlineTabId = null
        pendingOnlineTabUrl = null

        val wasOnline = _state.value.onlineTranslatedTabId != null
        if (wasOnline) {
            GeckoWebExtensionBridge.restoreOnlinePageTranslation()
        }

        _state.update {
            it.copy(
                isBusy = false,
                onlineTranslatedTabId = null,
                onlineTranslatedUrl = null,
                onlineTranslatedToLang = null,
                lastTranslateMs = null,
                statusMessage = "Showing original page",
                errorMessage = null,
                pillPhase = TranslatePillPhase.COMPLETED,
                pillMessage = "",
                pillProgress = 1f,
                pillCanRestore = false,
                pillCanClearImages = false,
                pillCanRetry = false,
            )
        }
        schedulePillAutoHide(1800L)

        if (session != null && tab.translationsState.isTranslated) {
            mainHandler.post {
                try {
                    session.requestTranslationRestore()
                } catch (e: Exception) {
                    UHLog.w("translate", "requestTranslationRestore failed: ${e.message}")
                }
            }
        }
        ReaderModeManager.onPageTranslationChanged(null)
    }

    fun translateVisibleImages(fromLang: String? = null, toLang: String? = null) {
        val cur = _state.value
        val cleanTo = (toLang ?: cur.preferredTargetLang).trim().ifBlank { "en" }
        val cleanFrom = (fromLang ?: cur.effectiveFromCode()).trim().ifBlank { "auto" }
        val preferOnline = !cur.useOfflineEngine

        cancelPillAutoHide()
        _state.update {
            it.copy(
                isTranslatingImages = true,
                barVisible = true,
                statusMessage = "Scanning visible images…",
                errorMessage = null,
                pillPhase = TranslatePillPhase.TRANSLATING_IMAGES,
                pillMessage = "Scanning visible images…",
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
        val dispatched = GeckoWebExtensionBridge.triggerImageTranslation(cleanFrom, cleanTo, preferOnline)
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
            schedulePillAutoHide(4000L)
            return
        }
        scheduleImageWatchdog(20000L)
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
                    .ifEmpty { ONLINE_LANGUAGES }
                val toList = support.toLanguages.orEmpty()
                    .sortedBy { it.localizedDisplayName ?: it.code }
                    .ifEmpty { ONLINE_LANGUAGES }
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
                        fromLanguages = it.fromLanguages.ifEmpty { ONLINE_LANGUAGES },
                        toLanguages = it.toLanguages.ifEmpty { ONLINE_LANGUAGES },
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

    fun languageDisplayName(langCode: String): String {
        if (langCode.equals("auto", ignoreCase = true)) return "Auto-detect"
        return ONLINE_LANGUAGES.firstOrNull { it.code.equals(langCode, ignoreCase = true) }?.localizedDisplayName
            ?: _state.value.fromLanguages.firstOrNull { it.code.equals(langCode, ignoreCase = true) }?.localizedDisplayName
            ?: _state.value.toLanguages.firstOrNull { it.code.equals(langCode, ignoreCase = true) }?.localizedDisplayName
            ?: ONLINE_LANGUAGES.firstOrNull { baseLang(it.code) == baseLang(langCode) }?.localizedDisplayName
            ?: Locale.forLanguageTag(langCode).getDisplayLanguage(Locale.ENGLISH)
                .replaceFirstChar { it.uppercase() }
                .ifBlank { langCode.uppercase(Locale.ROOT) }
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
