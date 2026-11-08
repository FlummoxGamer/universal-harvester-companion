package app.harvester.companion.browser

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.harvester.companion.HistoryStore
import app.harvester.companion.SearchEnginePrefs
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
            val isUrl = typed.contains(".") && !typed.contains(" ")
            val queries = if (isUrl || typed.isBlank()) emptyList() else withContext(Dispatchers.IO) { 
                HistoryStore.get(context).searchSearchQueries(typed).map { it.query } 
            }
            val pages = withContext(Dispatchers.IO) { 
                HistoryStore.get(context).searchHistory(typed, 10).map { it.url } 
            }
            
            var combined = queries + pages

            if (!isUrl && typed.length >= 2) {
                try {
                    val engine = SearchEnginePrefs.currentEntry(context)
                    val remote = suggestionClient.getSuggestions(engine.id, engine.suggestionsUrl, typed)
                    if (remote != null) {
                        combined = remote + combined
                    }
                } catch (_: Exception) {}
            }
            
            _suggestions.value = combined.distinct().take(8)
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
