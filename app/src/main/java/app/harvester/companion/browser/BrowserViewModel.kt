package app.harvester.companion.browser

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.harvester.companion.SearchEnginePrefs
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.UHApp
import app.harvester.companion.browser.search.SearchSuggestionClient
import app.harvester.companion.components.appstate.AppAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val components = (application as UHApp).components
    private val suggestionClient = SearchSuggestionClient()

    private val _isEditing = MutableStateFlow(false)
    val isEditing: StateFlow<Boolean> = _isEditing

    private val _editText = MutableStateFlow("")
    val editText: StateFlow<String> = _editText

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions

    private var suggestJob: Job? = null

    fun enterEditMode(currentUrl: String) {
        _isEditing.value = true
        _editText.value = currentUrl
        components.appStore.dispatch(AppAction.SearchAction.SearchStarted())
        refreshSuggestions(currentUrl)
    }

    fun exitEditMode() {
        _isEditing.value = false
        _editText.value = ""
        _suggestions.value = emptyList()
        components.appStore.dispatch(AppAction.SearchAction.SearchEnded)
    }

    fun onTextChanged(text: String) {
        _editText.value = text
        refreshSuggestions(text)
    }

    private fun refreshSuggestions(typed: String) {
        suggestJob?.cancel()
        suggestJob = viewModelScope.launch {
            val showSuggestions = SettingsPrefs.getBool(context, SettingsPrefs.K_SEARCH_SUGGESTIONS, true)
            if (!showSuggestions) {
                _suggestions.value = emptyList()
                return@launch
            }

            val isUrl = typed.contains(".") && !typed.contains(" ")
            val showHistory = SettingsPrefs.getBool(context, SettingsPrefs.K_SEARCH_HISTORY, true)
            val showRemote = SettingsPrefs.getBool(context, SettingsPrefs.K_SEARCH_REMOTE, true)

            val queries = emptyList<String>()
            val pages = if (showHistory && typed.isNotBlank()) withContext(Dispatchers.IO) { 
                try {
                    val visits = components.placesHistoryStorage.getDetailedVisits(0L, System.currentTimeMillis())
                    visits.filter { it.url.contains(typed, ignoreCase = true) || (it.title?.contains(typed, ignoreCase = true) == true) }
                        .sortedByDescending { it.visitTime }
                        .take(10)
                        .map { it.url }
                } catch (e: Exception) {
                    app.harvester.companion.UHLog.throwable("browser", "Failed to fetch history suggestions", e)
                    emptyList()
                }
            } else emptyList()
            
            var combined = queries + pages

            if (!isUrl && typed.length >= 2 && showRemote) {
                try {
                    val engine = SearchEnginePrefs.currentEntry(context)
                    val remote = suggestionClient.getSuggestions(engine.id, engine.suggestionsUrl, typed)
                    if (remote != null) {
                        combined = remote + combined
                    }
                } catch (_: Exception) {}
            }
            
            _suggestions.value = combined.distinct().take(8)
            app.harvester.companion.UHLog.d("suggestions", "Loaded ${combined.size} suggestions for $typed")
        }
    }

    fun normalize(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return ""
        if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:") || t.startsWith("file:")) return t
        if (t.contains(".") && !t.contains(" ")) return "https://$t"
        return SearchEnginePrefs.getSearchUrl(context, t)
    }
}
