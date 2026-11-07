package app.harvester.companion.browser

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.harvester.companion.HistoryStore
import app.harvester.companion.SearchEnginePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.concept.engine.HitResult
import mozilla.components.concept.engine.EngineSession

class BrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    private val _isEditing = MutableStateFlow(false)
    val isEditing: StateFlow<Boolean> = _isEditing

    private val _editText = MutableStateFlow("")
    val editText: StateFlow<String> = _editText

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions

    private val _showMainMenu = MutableStateFlow(false)
    val showMainMenu: StateFlow<Boolean> = _showMainMenu

    private val _contextMenuState = MutableStateFlow<HitResult?>(null)
    val contextMenuState: StateFlow<HitResult?> = _contextMenuState

    private var sessionObserver: EngineSession.Observer? = null

    fun setShowMainMenu(show: Boolean) {
        _showMainMenu.value = show
    }

    fun showContextMenu(hitResult: HitResult) {
        _contextMenuState.value = hitResult
    }

    fun dismissContextMenu() {
        _contextMenuState.value = null
    }

    fun registerSessionObserver(engineSession: EngineSession) {
        unregisterSessionObserver()
        sessionObserver = object : EngineSession.Observer {
            override fun onContextMenu(session: EngineSession, screenX: Int, screenY: Int, displayContext: HitResult) {
                showContextMenu(displayContext)
            }
        }
        engineSession.register(sessionObserver!!)
    }

    fun unregisterSessionObserver() {
        sessionObserver = null
    }

    fun enterEditMode(currentUrl: String) {
        _isEditing.value = true
        _editText.value = currentUrl
        refreshSuggestions(currentUrl)
    }

    fun exitEditMode() {
        _isEditing.value = false
        _editText.value = ""
        _suggestions.value = emptyList()
    }

    fun onTextChanged(text: String) {
        _editText.value = text
        refreshSuggestions(text)
    }

    private fun refreshSuggestions(typed: String) {
        viewModelScope.launch {
            val isUrl = typed.contains(".") && !typed.contains(" ")
            val queries = if (isUrl || typed.isBlank()) emptyList() else withContext(Dispatchers.IO) { HistoryStore.get(context).searchSearchQueries(typed).map { it.query } }
            val pages = withContext(Dispatchers.IO) { HistoryStore.get(context).searchHistory(typed, 10).map { it.url } }
            _suggestions.value = (queries + pages).distinct().take(10)
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
