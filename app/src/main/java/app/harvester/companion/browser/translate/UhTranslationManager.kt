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
    )

    private val initialized = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var engineRef: Engine? = null

    @Volatile
    private var storeRef: BrowserStore? = null

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

        GeckoWebExtensionBridge.onFastTranslateCompleted = { success, count, durationMs, fromLang, toLang, pageUrl ->
            val activeTab = storeRef?.state?.selectedTab
            if (success) {
                _state.update {
                    it.copy(
                        isBusy = false,
                        errorMessage = null,
                        statusMessage = if (count > 0) {
                            "Translated $count blocks in ${durationMs}ms"
                        } else {
                            "Page translated"
                        },
                        fastTranslatedTabId = activeTab?.id,
                        fastTranslatedUrl = pageUrl.ifBlank { activeTab?.content?.url },
                        fastTranslatedFrom = fromLang,
                        fastTranslatedTo = toLang,
                        lastTranslateMs = durationMs,
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        isBusy = false,
                        statusMessage = null,
                        errorMessage = "Fast translation could not reach service. Try On-Device Offline mode.",
                    )
                }
            }
        }

        GeckoWebExtensionBridge.onImageTranslateCompleted = { imageCount, overlayCount, _ ->
            val activeTabId = storeRef?.state?.selectedTab?.id
            _state.update {
                it.copy(
                    isTranslatingImages = false,
                    imageOverlayCount = overlayCount,
                    imageOverlayTabId = if (overlayCount > 0) activeTabId else null,
                    statusMessage = when {
                        overlayCount > 0 -> "Translated $overlayCount text block${if (overlayCount == 1) "" else "s"} across images"
                        imageCount == 0 -> "No visible images with text found on screen"
                        else -> "No readable text detected in visible images"
                    },
                    errorMessage = null,
                )
            }
        }

        refreshEngineCapabilities()
    }

    fun isFastTranslatedForTab(tabId: String?, tabUrl: String?): Boolean {
        val st = _state.value
        if (tabId.isNullOrBlank() || st.fastTranslatedTabId != tabId) return false
        if (!tabUrl.isNullOrBlank() && !st.fastTranslatedUrl.isNullOrBlank() && st.fastTranslatedUrl != tabUrl) {
            return false
        }
        return true
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

    fun translateActiveTab(fromLang: String, toLang: String) {
        val tab = storeRef?.state?.selectedTab
        val session = tab?.engineState?.engineSession
        if (session == null) {
            _state.update { it.copy(errorMessage = "No active webpage to translate.") }
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
        setPreferredTargetLanguage(cleanTo)

        val useOnlineFast = !_state.value.preferOffline && isNetworkAvailable()
        if (useOnlineFast) {
            _state.update {
                it.copy(
                    isBusy = true,
                    statusMessage = "Translating page…",
                    errorMessage = null,
                )
            }
            val dispatched = GeckoWebExtensionBridge.triggerFastTranslate(cleanFrom, cleanTo)
            if (dispatched) {
                // Safety fallback timer in case page does not respond (e.g. restricted internal page)
                mainHandler.postDelayed({
                    if (_state.value.isBusy) {
                        _state.update { it.copy(isBusy = false) }
                    }
                }, 8000L)
                return
            }
        }

        // Seamless fallback to GeckoView On-Device neural translation
        val geckoFrom = if (cleanFrom == "auto") {
            tab.translationsState.translationEngineState?.detectedLanguages?.documentLangTag
                ?.ifBlank { null }
                ?: _state.value.fromLanguages.firstOrNull { it.code != cleanTo }?.code
                ?: "es"
        } else {
            cleanFrom
        }

        _state.update {
            it.copy(
                isBusy = true,
                statusMessage = "Translating on-device…",
                errorMessage = null,
            )
        }
        mainHandler.post {
            try {
                session.requestTranslate(
                    fromLanguage = geckoFrom,
                    toLanguage = cleanTo,
                    options = TranslationOptions(downloadModel = true),
                )
                mainHandler.postDelayed({
                    _state.update { it.copy(isBusy = false, statusMessage = null) }
                    engineRef?.let { fetchLanguageModels(it) }
                }, 1200L)
            } catch (e: Exception) {
                UHLog.w("translate", "requestTranslate failed: ${e.message}")
                _state.update {
                    it.copy(
                        isBusy = false,
                        statusMessage = null,
                        errorMessage = e.message ?: "Translation failed."
                    )
                }
            }
        }
    }

    fun restoreActiveTab() {
        val tab = storeRef?.state?.selectedTab
        val session = tab?.engineState?.engineSession

        // Instant 0ms DOM restore for fast-translated pages
        GeckoWebExtensionBridge.restoreFastTranslate()
        _state.update {
            it.copy(
                fastTranslatedTabId = null,
                fastTranslatedUrl = null,
                fastTranslatedFrom = null,
                fastTranslatedTo = null,
                lastTranslateMs = null,
                statusMessage = "Restored original page",
                errorMessage = null,
            )
        }

        // Also restore GeckoView on-device translation if active
        if (session != null && tab.translationsState.isTranslated) {
            _state.update { it.copy(isBusy = true, statusMessage = "Restoring original page…") }
            mainHandler.post {
                try {
                    session.requestTranslationRestore()
                    mainHandler.postDelayed({
                        _state.update { it.copy(isBusy = false, statusMessage = null) }
                    }, 400L)
                } catch (e: Exception) {
                    UHLog.w("translate", "requestTranslationRestore failed: ${e.message}")
                    _state.update { it.copy(isBusy = false, statusMessage = null, errorMessage = e.message) }
                }
            }
        }
    }

    fun translateVisibleImages(fromLang: String, toLang: String) {
        val cleanTo = toLang.trim().ifBlank { _state.value.preferredTargetLang.ifBlank { "en" } }
        val cleanFrom = fromLang.trim().ifBlank { "auto" }
        _state.update {
            it.copy(
                isTranslatingImages = true,
                statusMessage = "Scanning visible images with ML Kit OCR…",
                errorMessage = null,
            )
        }
        val dispatched = GeckoWebExtensionBridge.triggerImageTranslation(cleanFrom, cleanTo)
        if (!dispatched) {
            _state.update {
                it.copy(
                    isTranslatingImages = false,
                    statusMessage = null,
                    errorMessage = "Image scanner not ready on this page.",
                )
            }
            return
        }
        mainHandler.postDelayed({
            if (_state.value.isTranslatingImages) {
                _state.update { it.copy(isTranslatingImages = false, statusMessage = null) }
            }
        }, 18000L)
    }

    fun clearImageTranslations() {
        GeckoWebExtensionBridge.clearImageTranslations()
        _state.update {
            it.copy(
                isTranslatingImages = false,
                imageOverlayCount = 0,
                imageOverlayTabId = null,
                statusMessage = null,
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

    fun formatBytes(bytes: Long?): String {
        if (bytes == null || bytes <= 0L) return ""
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1.0) String.format(Locale.US, "%.1f MB", mb)
        else "${(bytes / 1024L).coerceAtLeast(1L)} KB"
    }
}
