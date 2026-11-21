package app.harvester.companion.browser.translate

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

enum class TranslatePillPhase {
    HIDDEN,
    TRANSLATING_ONLINE,
    DOWNLOADING_MODEL,
    TRANSLATING_OFFLINE,
    TRANSLATING_IMAGES,
    COMPLETED,
    ERROR,
}

object UhTranslationManager {

    private const val PREFS_NAME = "uh_translation_prefs"
    private const val KEY_PREFERRED_TARGET_LANG = "preferred_target_lang"
    private const val KEY_PREFER_OFFLINE = "prefer_offline_translation"

    private val FALLBACK_LANGUAGES: List<Language> = listOf(
        Language("en", "English"),
        Language("es", "Spanish"),
        Language("fr", "French"),
        Language("de", "German"),
        Language("hi", "Hindi"),
        Language("ja", "Japanese"),
        Language("ko", "Korean"),
        Language("zh-CN", "Chinese (Simplified)"),
        Language("zh-TW", "Chinese (Traditional)"),
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
        Language("ta", "Tamil"),
        Language("te", "Telugu"),
        Language("mr", "Marathi"),
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
        val preferOffline: Boolean = false,
        val pairDownloadSizeBytes: Long? = null,
        val isBusy: Boolean = false,
        val statusMessage: String? = null,
        val errorMessage: String? = null,
        val fastTranslatedTabId: String? = null,
        val fastTranslatedUrl: String? = null,
        val fastTranslatedFrom: String? = null,
        val fastTranslatedTo: String? = null,
        val lastTranslateMs: Long? = null,
        val isTranslatingImages: Boolean = false,
        val imageOverlayCount: Int = 0,
        val imageOverlayTabId: String? = null,
        val pillPhase: TranslatePillPhase = TranslatePillPhase.HIDDEN,
        val pillMessage: String = "",
        val pillProgress: Float = 0f,
        val pillTargetLang: String? = null,
        val pillCanRestore: Boolean = false,
        val pillCanRetryOffline: Boolean = false,
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
    private var lastRequestedFrom: String = "auto"

    @Volatile
    private var lastRequestedTo: String = "en"

    @Volatile
    private var offlinePendingTabId: String? = null

    @Volatile
    private var offlineStartedAtMs: Long = 0L

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
        val preferOffline = prefs.getBoolean(KEY_PREFER_OFFLINE, false)

        _state.update {
            it.copy(
                preferredTargetLang = savedTarget,
                preferOffline = preferOffline,
            )
        }

        GeckoWebExtensionBridge.onFastTranslateProgress = { done, total, _ ->
            if (_state.value.pillPhase == TranslatePillPhase.TRANSLATING_ONLINE) {
                val frac = if (total > 0) (done.toFloat() / total.toFloat()).coerceIn(0.08f, 0.96f) else 0.5f
                val pct = (frac * 100f).roundToInt().coerceIn(1, 99)
                scheduleOnlineWatchdog(14000L)
                _state.update {
                    it.copy(
                        pillProgress = frac,
                        pillMessage = "Translating… $pct%",
                        statusMessage = "Translating… $pct%",
                    )
                }
            }
        }

        GeckoWebExtensionBridge.onFastTranslateCompleted = { success, count, durationMs, fromLang, toLang, pageUrl, error ->
            cancelOnlineWatchdog()
            val activeTab = storeRef?.state?.selectedTab
            val targetShort = languageShortLabel(toLang)
            if (success) {
                val speedStr = formatDurationShort(durationMs)
                val pillMsg = if (count > 0) {
                    "Translated to $targetShort · $speedStr"
                } else {
                    "Page already in $targetShort"
                }
                _state.update {
                    it.copy(
                        isBusy = false,
                        errorMessage = null,
                        statusMessage = pillMsg,
                        fastTranslatedTabId = activeTab?.id,
                        fastTranslatedUrl = pageUrl.ifBlank { activeTab?.content?.url },
                        fastTranslatedFrom = fromLang,
                        fastTranslatedTo = toLang,
                        lastTranslateMs = durationMs,
                        pillPhase = TranslatePillPhase.COMPLETED,
                        pillMessage = pillMsg,
                        pillProgress = 1f,
                        pillTargetLang = targetShort,
                        pillCanRestore = count > 0,
                        pillCanRetryOffline = false,
                    )
                }
                schedulePillAutoHide(5500L)
            } else {
                val errMsg = error?.ifBlank { null } ?: "Online translation unavailable"
                _state.update {
                    it.copy(
                        isBusy = false,
                        statusMessage = null,
                        errorMessage = errMsg,
                        pillPhase = TranslatePillPhase.ERROR,
                        pillMessage = errMsg,
                        pillProgress = 0f,
                        pillCanRestore = false,
                        pillCanRetryOffline = true,
                    )
                }
                schedulePillAutoHide(8000L)
            }
        }

        GeckoWebExtensionBridge.onImageTranslateCompleted = { imageCount, overlayCount, _ ->
            cancelImageWatchdog()
            val activeTabId = storeRef?.state?.selectedTab?.id
            val msg = when {
                overlayCount > 0 -> "Translated $overlayCount image text block${if (overlayCount == 1) "" else "s"}"
                imageCount == 0 -> "No visible images found on screen"
                else -> "No readable text found in images"
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
                    pillCanRetryOffline = false,
                )
            }
            schedulePillAutoHide(4500L)
        }

        // Pre-warm GeckoView's on-device translation engine registry immediately at app start
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
                        pillCanRetryOffline = false,
                    )
                } else {
                    current
                }
            }
        }
        pillAutoHideRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun cancelOnlineWatchdog() {
        translateWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        translateWatchdogRunnable = null
    }

    private fun scheduleOnlineWatchdog(timeoutMs: Long) {
        cancelOnlineWatchdog()
        val r = Runnable {
            val st = _state.value
            if (st.isBusy && (st.pillPhase == TranslatePillPhase.TRANSLATING_ONLINE ||
                    st.pillPhase == TranslatePillPhase.DOWNLOADING_MODEL ||
                    st.pillPhase == TranslatePillPhase.TRANSLATING_OFFLINE)
            ) {
                val wasOnline = st.pillPhase == TranslatePillPhase.TRANSLATING_ONLINE
                offlinePendingTabId = null
                val msg = if (wasOnline) "Translation timed out" else "Offline translation timed out"
                _state.update {
                    it.copy(
                        isBusy = false,
                        statusMessage = null,
                        errorMessage = msg,
                        pillPhase = TranslatePillPhase.ERROR,
                        pillMessage = msg,
                        pillProgress = 0f,
                        pillCanRestore = false,
                        pillCanRetryOffline = wasOnline,
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

    fun dismissPill() {
        cancelPillAutoHide()
        _state.update {
            it.copy(
                pillPhase = TranslatePillPhase.HIDDEN,
                pillMessage = "",
                pillProgress = 0f,
                pillCanRestore = false,
                pillCanRetryOffline = false,
            )
        }
    }

    fun syncFromGeckoTabState(tabId: String?, tabState: TranslationsState?) {
        val pendingId = offlinePendingTabId ?: return
        if (tabId == null || tabId != pendingId) {
            // User switched away from the pending offline tab
            offlinePendingTabId = null
            cancelOnlineWatchdog()
            _state.update {
                if (it.pillPhase == TranslatePillPhase.DOWNLOADING_MODEL || it.pillPhase == TranslatePillPhase.TRANSLATING_OFFLINE) {
                    it.copy(isBusy = false, statusMessage = null, pillPhase = TranslatePillPhase.HIDDEN, pillMessage = "")
                } else it
            }
            return
        }
        if (tabState == null) return

        if (tabState.isTranslated && !tabState.isTranslateProcessing) {
            offlinePendingTabId = null
            cancelOnlineWatchdog()
            val elapsed = (System.currentTimeMillis() - offlineStartedAtMs).coerceAtLeast(100L)
            val targetCode = tabState.translationEngineState?.requestedTranslationPair?.toLanguage
                ?.ifBlank { null } ?: lastRequestedTo
            val targetShort = languageShortLabel(targetCode)
            val msg = "Translated offline to $targetShort · ${formatDurationShort(elapsed)}"
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
                    pillCanRetryOffline = false,
                )
            }
            schedulePillAutoHide(5500L)
            engineRef?.let { fetchLanguageModels(it) }
        } else if (tabState.isTranslateProcessing && _state.value.pillPhase == TranslatePillPhase.DOWNLOADING_MODEL) {
            // Keep clear feedback while GeckoView downloads/runs the local neural model
            val elapsed = System.currentTimeMillis() - offlineStartedAtMs
            if (elapsed > 2500L) {
                val targetName = languageDisplayName(lastRequestedTo)
                _state.update {
                    it.copy(
                        pillMessage = "Downloading $targetName pack…",
                        statusMessage = "Downloading $targetName pack…",
                    )
                }
            }
        }
    }

    fun isFastTranslatedForTab(tabId: String?, tabUrl: String?): Boolean {
        val st = _state.value
        if (tabId.isNullOrBlank() || st.fastTranslatedTabId != tabId) return false
        if (!tabUrl.isNullOrBlank() && !st.fastTranslatedUrl.isNullOrBlank() && st.fastTranslatedUrl != tabUrl) {
            return false
        }
        return true
    }

    fun isModelDownloaded(langCode: String): Boolean {
        if (langCode.equals("en", ignoreCase = true)) return true
        val norm = langCode.lowercase(Locale.ROOT)
        return _state.value.languageModels.any { model ->
            val code = model.language?.code?.lowercase(Locale.ROOT) ?: return@any false
            (code == norm || code.substringBefore("-") == norm.substringBefore("-")) &&
                model.status == ModelState.DOWNLOADED
        }
    }

    fun setPreferOffline(preferOffline: Boolean) {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_PREFER_OFFLINE, preferOffline)?.apply()
        _state.update { it.copy(preferOffline = preferOffline, errorMessage = null, statusMessage = null) }
    }

    private fun isNetworkAvailable(): Boolean {
        val ctx = appContext ?: return true
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
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
        if (fromLang.isBlank() || fromLang == "auto" || toLang.isBlank() || fromLang == toLang) {
            _state.update { it.copy(pairDownloadSizeBytes = null) }
            return
        }
        mainHandler.post {
            engine.getTranslationsPairDownloadSize(
                fromLanguage = fromLang,
                toLanguage = toLang,
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

    fun retryOfflineActiveTab() {
        translateActiveTab(lastRequestedFrom, lastRequestedTo, forceOffline = true)
    }

    fun translateActiveTab(fromLang: String, toLang: String, forceOffline: Boolean = false) {
        val tab = storeRef?.state?.selectedTab
        val session = tab?.engineState?.engineSession
        if (session == null) {
            _state.update {
                it.copy(
                    errorMessage = "No active webpage to translate.",
                    pillPhase = TranslatePillPhase.ERROR,
                    pillMessage = "No active webpage to translate",
                    pillCanRestore = false,
                    pillCanRetryOffline = false,
                )
            }
            schedulePillAutoHide(4000L)
            return
        }
        val cleanFrom = fromLang.trim().ifBlank { "auto" }
        val cleanTo = toLang.trim()
        if (cleanTo.isBlank()) {
            _state.update { it.copy(errorMessage = "Choose a target language.") }
            return
        }
        if (cleanFrom != "auto" && cleanFrom.equals(cleanTo, ignoreCase = true)) {
            _state.update { it.copy(errorMessage = "Source and target languages are the same.") }
            return
        }
        lastRequestedFrom = cleanFrom
        lastRequestedTo = cleanTo
        setPreferredTargetLanguage(cleanTo)
        cancelPillAutoHide()

        val useOnlineFast = !forceOffline && !_state.value.preferOffline && isNetworkAvailable()
        if (useOnlineFast) {
            offlinePendingTabId = null
            _state.update {
                it.copy(
                    isBusy = true,
                    statusMessage = "Translating page…",
                    errorMessage = null,
                    pillPhase = TranslatePillPhase.TRANSLATING_ONLINE,
                    pillMessage = "Translating to ${languageShortLabel(cleanTo)}…",
                    pillProgress = 0f,
                    pillTargetLang = languageShortLabel(cleanTo),
                    pillCanRestore = false,
                    pillCanRetryOffline = false,
                )
            }
            val dispatched = GeckoWebExtensionBridge.triggerFastTranslate(cleanFrom, cleanTo)
            if (dispatched) {
                scheduleOnlineWatchdog(14000L)
                return
            }
        }

        // On-Device GeckoView neural translation
        val geckoFrom = if (cleanFrom == "auto") {
            tab.translationsState.translationEngineState?.detectedLanguages?.documentLangTag
                ?.ifBlank { null }
                ?: _state.value.fromLanguages.firstOrNull { it.code != cleanTo }?.code
                ?: "es"
        } else {
            cleanFrom
        }

        val needsDownload = !isModelDownloaded(geckoFrom) || !isModelDownloaded(cleanTo)
        val phase = if (needsDownload) TranslatePillPhase.DOWNLOADING_MODEL else TranslatePillPhase.TRANSLATING_OFFLINE
        val statusMsg = if (needsDownload) {
            "Preparing ${languageDisplayName(cleanTo)} offline pack…"
        } else {
            "Translating on-device…"
        }

        offlinePendingTabId = tab.id
        offlineStartedAtMs = System.currentTimeMillis()
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
                pillCanRetryOffline = false,
            )
        }
        scheduleOnlineWatchdog(if (needsDownload) 45000L else 18000L)

        mainHandler.post {
            try {
                session.requestTranslate(
                    fromLanguage = geckoFrom,
                    toLanguage = cleanTo,
                    options = TranslationOptions(downloadModel = true),
                )
            } catch (e: Exception) {
                offlinePendingTabId = null
                cancelOnlineWatchdog()
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
                        pillCanRetryOffline = false,
                    )
                }
                schedulePillAutoHide(6000L)
            }
        }
    }

    fun restoreActiveTab() {
        val tab = storeRef?.state?.selectedTab
        val session = tab?.engineState?.engineSession

        cancelOnlineWatchdog()
        offlinePendingTabId = null

        // Instant 0ms DOM restore for fast-translated pages
        GeckoWebExtensionBridge.restoreFastTranslate()
        _state.update {
            it.copy(
                isBusy = false,
                fastTranslatedTabId = null,
                fastTranslatedUrl = null,
                fastTranslatedFrom = null,
                fastTranslatedTo = null,
                lastTranslateMs = null,
                statusMessage = "Restored original page",
                errorMessage = null,
                pillPhase = TranslatePillPhase.COMPLETED,
                pillMessage = "Restored original page",
                pillProgress = 1f,
                pillCanRestore = false,
                pillCanRetryOffline = false,
            )
        }
        schedulePillAutoHide(2400L)

        // Also restore GeckoView on-device translation if active
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
                pillCanRetryOffline = false,
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
                    pillCanRetryOffline = false,
                )
            }
            schedulePillAutoHide(4500L)
            return
        }
        cancelImageWatchdog()
        val r = Runnable {
            if (_state.value.isTranslatingImages) {
                _state.update {
                    it.copy(
                        isTranslatingImages = false,
                        statusMessage = null,
                        pillPhase = TranslatePillPhase.ERROR,
                        pillMessage = "Image scan timed out",
                        pillCanRestore = false,
                        pillCanRetryOffline = false,
                    )
                }
                schedulePillAutoHide(4500L)
            }
        }
        imageWatchdogRunnable = r
        mainHandler.postDelayed(r, 20000L)
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
            )
        }
    }

    fun setLanguageRule(languageCode: String, setting: LanguageSetting) {
        val engine = engineRef ?: return
        if (languageCode == "auto") return
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
                    _state.update { it.copy(errorMessage = "Model ${operation.operation} failed. Check connection.") }
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

    private fun languageShortLabel(langCode: String): String {
        val clean = langCode.trim()
        if (clean.isBlank()) return "EN"
        return clean.substringBefore("-").uppercase(Locale.ROOT)
    }

    private fun languageDisplayName(langCode: String): String {
        return _state.value.toLanguages.firstOrNull { it.code.equals(langCode, ignoreCase = true) }
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
