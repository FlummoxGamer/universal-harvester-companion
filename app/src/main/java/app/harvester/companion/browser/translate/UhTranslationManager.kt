package app.harvester.companion.browser.translate

import android.content.Context
import android.os.Handler
import android.os.Looper
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

    data class State(
        val isEngineSupported: Boolean = true,
        val isLoadingLanguages: Boolean = false,
        val fromLanguages: List<Language> = emptyList(),
        val toLanguages: List<Language> = emptyList(),
        val languageModels: List<LanguageModel> = emptyList(),
        val languageSettings: Map<String, LanguageSetting> = emptyMap(),
        val neverTranslateSites: List<String> = emptyList(),
        val currentSiteNeverTranslate: Boolean = false,
        val preferredTargetLang: String = Locale.getDefault().language.ifBlank { "en" },
        val pairDownloadSizeBytes: Long? = null,
        val isBusy: Boolean = false,
        val statusMessage: String? = null,
        val errorMessage: String? = null,
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

        val savedTarget = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PREFERRED_TARGET_LANG, null)
            ?.ifBlank { null }
            ?: Locale.getDefault().language.ifBlank { "en" }

        _state.update { it.copy(preferredTargetLang = savedTarget) }
        refreshEngineCapabilities()
    }

    fun refreshEngineCapabilities() {
        val engine = engineRef ?: return
        mainHandler.post {
            _state.update { it.copy(isLoadingLanguages = true, errorMessage = null) }
            engine.isTranslationsEngineSupported(
                onSuccess = { supported ->
                    _state.update { it.copy(isEngineSupported = supported) }
                    if (supported) {
                        fetchSupportedLanguages(engine)
                        fetchLanguageModels(engine)
                        fetchLanguageSettings(engine)
                        fetchNeverTranslateSites(engine)
                    } else {
                        _state.update {
                            it.copy(
                                isLoadingLanguages = false,
                                errorMessage = "On-device translation is not supported on this device architecture."
                            )
                        }
                    }
                },
                onError = { err ->
                    UHLog.w("translate", "isTranslationsEngineSupported error: ${err.message}")
                    _state.update { it.copy(isLoadingLanguages = false) }
                    // Still attempt fetching languages in case runtime warms up asynchronously
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
        if (fromLang.isBlank() || toLang.isBlank() || fromLang == toLang) {
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
        val session = storeRef?.state?.selectedTab?.engineState?.engineSession
        if (session == null) {
            _state.update { it.copy(errorMessage = "No active webpage to translate.") }
            return
        }
        if (fromLang.isBlank() || toLang.isBlank()) {
            _state.update { it.copy(errorMessage = "Choose both source and target languages.") }
            return
        }
        if (fromLang == toLang) {
            _state.update { it.copy(errorMessage = "Source and target languages are the same.") }
            return
        }
        setPreferredTargetLanguage(toLang)
        _state.update {
            it.copy(
                isBusy = true,
                statusMessage = "Translating page on-device…",
                errorMessage = null,
            )
        }
        mainHandler.post {
            try {
                session.requestTranslate(
                    fromLanguage = fromLang,
                    toLanguage = toLang,
                    options = TranslationOptions(downloadModel = true),
                )
                // GeckoView updates BrowserStore tab.translationsState asynchronously; clear local busy fallback shortly
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
        val session = storeRef?.state?.selectedTab?.engineState?.engineSession ?: return
        _state.update { it.copy(isBusy = true, statusMessage = "Restoring original page…", errorMessage = null) }
        mainHandler.post {
            try {
                session.requestTranslationRestore()
                mainHandler.postDelayed({
                    _state.update { it.copy(isBusy = false, statusMessage = null) }
                }, 500L)
            } catch (e: Exception) {
                UHLog.w("translate", "requestTranslationRestore failed: ${e.message}")
                _state.update { it.copy(isBusy = false, statusMessage = null, errorMessage = e.message) }
            }
        }
    }

    fun setLanguageRule(languageCode: String, setting: LanguageSetting) {
        val engine = engineRef ?: return
        mainHandler.post {
            engine.setLanguageSetting(
                languageCode = languageCode,
                languageSetting = setting,
                onSuccess = {
                    fetchLanguageSettings(engine)
                },
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
                onSuccess = {
                    fetchLanguageModels(engine)
                },
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
                val fromList = support.fromLanguages.orEmpty().sortedBy { it.localizedDisplayName ?: it.code }
                val toList = support.toLanguages.orEmpty().sortedBy { it.localizedDisplayName ?: it.code }
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
                        errorMessage = "Could not load supported languages. Check internet connection."
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
