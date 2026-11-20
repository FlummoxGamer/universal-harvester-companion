package app.harvester.companion.browser

import android.app.Application
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.harvester.companion.HomeActivity
import app.harvester.companion.R
import app.harvester.companion.SearchEnginePrefs
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.Shortcut
import app.harvester.companion.ShortcutStore
import app.harvester.companion.UHApp
import app.harvester.companion.UHLog
import app.harvester.companion.browser.search.SearchSuggestionClient
import app.harvester.companion.components.appstate.AppAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.appservices.places.BookmarkRoot
import mozilla.components.browser.engine.gecko.GeckoEngineSession
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.selector.selectedTab
import org.mozilla.geckoview.GeckoSession

class BrowserViewModel(application: Application) : AndroidViewModel(application) {
    val context: Context = application.applicationContext
    val components = (application as UHApp).components
    private val suggestionClient = SearchSuggestionClient()

    val browsingModeManager = app.harvester.companion.browser.browsingmode.UhBrowsingModeManager()

    private val _isEditing = MutableStateFlow(false)
    val isEditing: StateFlow<Boolean> = _isEditing

    val isBarBottom: StateFlow<Boolean> = SettingsPrefs.addressBarBottomFlow(context)
    val searchEngine: StateFlow<SearchEnginePrefs.Entry> = SearchEnginePrefs.activeEngineFlow(context)

    private val _editText = MutableStateFlow("")
    val editText: StateFlow<String> = _editText

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions

    // Find-in-page state
    private val _showFindInPage = MutableStateFlow(false)
    val showFindInPage: StateFlow<Boolean> = _showFindInPage

    private val _findQuery = MutableStateFlow("")
    val findQuery: StateFlow<String> = _findQuery

    private val _findCurrent = MutableStateFlow(0)
    val findCurrent: StateFlow<Int> = _findCurrent

    private val _findTotal = MutableStateFlow(0)
    val findTotal: StateFlow<Int> = _findTotal

    // Page zoom state
    private val _pageZoomPercent = MutableStateFlow(
        SettingsPrefs.getInt(context, SettingsPrefs.K_TEXT_SCALE, 100).coerceIn(50, 200)
    )
    val pageZoomPercent: StateFlow<Int> = _pageZoomPercent

    private val _showZoomBar = MutableStateFlow(false)
    val showZoomBar: StateFlow<Boolean> = _showZoomBar

    // Bookmark state for current URL
    private val _isBookmarked = MutableStateFlow(false)
    val isBookmarked: StateFlow<Boolean> = _isBookmarked

    // Overlay sheets & dialogs
    private val _showScriptsSheet = MutableStateFlow(false)
    val showScriptsSheet: StateFlow<Boolean> = _showScriptsSheet

    private val _showCaptureQuickSheet = MutableStateFlow(false)
    val showCaptureQuickSheet: StateFlow<Boolean> = _showCaptureQuickSheet

    private val _showAddToHomeDialog = MutableStateFlow(false)
    val showAddToHomeDialog: StateFlow<Boolean> = _showAddToHomeDialog

    private var suggestJob: Job? = null
    private var lastBookmarkUrl: String = ""

    fun enterEditMode(currentUrl: String) {
        _showFindInPage.value = false
        _showZoomBar.value = false
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

            val pages = if (showHistory && typed.isNotBlank()) withContext(Dispatchers.IO) {
                try {
                    val now = System.currentTimeMillis()
                    val visits = components.placesHistoryStorage.getDetailedVisits(
                        now - 30L * 24 * 60 * 60 * 1000L,
                        now
                    )
                    visits.filter {
                        it.url.contains(typed, ignoreCase = true) ||
                            (it.title?.contains(typed, ignoreCase = true) == true)
                    }
                        .sortedByDescending { it.visitTime }
                        .take(10)
                        .map { it.url }
                } catch (e: Exception) {
                    UHLog.w("search_warn", "Failed querying local history suggestions: ${e.message}")
                    emptyList()
                }
            } else emptyList()

            var combined = pages

            if (!isUrl && typed.length >= 2 && showRemote) {
                try {
                    val engine = SearchEnginePrefs.currentEntry(context)
                    val remote = suggestionClient.getSuggestions(engine.id, engine.suggestionsUrl, typed)
                    if (remote != null) {
                        combined = remote + combined
                    }
                } catch (e: Exception) {
                    UHLog.w("search_warn", "Remote suggestion fetch failed: ${e.message}")
                }
            }

            _suggestions.value = combined.distinct().take(8)
        }
    }

    fun updateBarPosition(isBottom: Boolean) {
        val target = if (isBottom) 1 else 0
        if (SettingsPrefs.getInt(context, SettingsPrefs.K_ADDRESS_BAR_POSITION, 1) != target) {
            SettingsPrefs.setInt(context, SettingsPrefs.K_ADDRESS_BAR_POSITION, target)
        }
    }

    fun normalize(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return ""
        if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:") || t.startsWith("file:")) return t
        if (t.contains(".") && !t.contains(" ")) return "https://$t"
        return SearchEnginePrefs.getSearchUrl(context, t)
    }

    // ---- Find in Page ----

    fun openFindInPage() {
        _showZoomBar.value = false
        _showFindInPage.value = true
    }

    fun closeFindInPage(tabId: String? = null) {
        _showFindInPage.value = false
        _findQuery.value = ""
        _findCurrent.value = 0
        _findTotal.value = 0
        geckoSessionForTab(tabId)?.finder?.clear()
    }

    fun runFindInPage(query: String, forward: Boolean = true, tabId: String? = null) {
        _findQuery.value = query
        val session = geckoSessionForTab(tabId) ?: return
        if (query.isBlank()) {
            session.finder.clear()
            _findCurrent.value = 0
            _findTotal.value = 0
            return
        }
        val flags = if (forward) GeckoSession.FINDER_FIND_FORWARD else GeckoSession.FINDER_FIND_BACKWARDS
        try {
            session.finder.displayFlags = GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL
            session.finder.find(query, flags).accept({ res ->
                if (res != null) {
                    _findCurrent.value = if (res.total > 0) res.current else 0
                    _findTotal.value = res.total
                }
            }, { _ ->
                _findCurrent.value = 0
                _findTotal.value = 0
            })
        } catch (_: Exception) {}
    }

    // ---- Page Zoom ----

    fun openZoomBar() {
        _showFindInPage.value = false
        _showZoomBar.value = true
    }

    fun closeZoomBar() {
        _showZoomBar.value = false
    }

    fun setPageZoom(percent: Int) {
        val clamped = percent.coerceIn(50, 200)
        _pageZoomPercent.value = clamped
        SettingsPrefs.setInt(context, SettingsPrefs.K_TEXT_SCALE, clamped)
        val factor = clamped / 100f
        try {
            components.engine.settings.fontSizeFactor = factor
            components.engine.settings.fontInflationEnabled = clamped != 100
        } catch (_: Exception) {}
        try {
            val activeSession = components.store.state.selectedTab?.engineState?.engineSession
            if (activeSession != null) {
                activeSession.settings.fontSizeFactor = factor
                activeSession.settings.fontInflationEnabled = clamped != 100
            }
        } catch (_: Exception) {}
    }

    // ---- Bookmarks ----

    fun refreshBookmarkState(url: String?) {
        val clean = url?.trim().orEmpty()
        if (clean.isBlank() || clean == "about:blank") {
            lastBookmarkUrl = ""
            _isBookmarked.value = false
            return
        }
        if (clean == lastBookmarkUrl) return
        lastBookmarkUrl = clean
        viewModelScope.launch(Dispatchers.IO) {
            val saved = try {
                components.placesBookmarksStorage.getBookmarksWithUrl(clean).getOrNull()?.isNotEmpty() == true
            } catch (_: Exception) {
                false
            }
            _isBookmarked.value = saved
        }
    }

    fun toggleBookmark(url: String, title: String) {
        if (url.isBlank() || url == "about:blank") return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val existing = components.placesBookmarksStorage.getBookmarksWithUrl(url).getOrNull().orEmpty()
                if (existing.isNotEmpty()) {
                    existing.forEach { node ->
                        components.placesBookmarksStorage.deleteNode(node.guid)
                    }
                    _isBookmarked.value = false
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Bookmark removed", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    components.placesBookmarksStorage.addItem(
                        BookmarkRoot.Mobile.id,
                        url,
                        title.ifBlank { url },
                        null
                    )
                    _isBookmarked.value = true
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Bookmarked", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                UHLog.w("bookmark", "Toggle bookmark failed: ${e.message}")
            }
        }
    }

    // ---- Sheets & Add to Home ----

    fun openScriptsSheet() { _showScriptsSheet.value = true }
    fun closeScriptsSheet() { _showScriptsSheet.value = false }

    fun openCaptureQuickSheet() { _showCaptureQuickSheet.value = true }
    fun closeCaptureQuickSheet() { _showCaptureQuickSheet.value = false }

    fun openAddToHomeDialog() { _showAddToHomeDialog.value = true }
    fun closeAddToHomeDialog() { _showAddToHomeDialog.value = false }

    fun addToUhHomeGrid(url: String, title: String): Boolean {
        if (url.isBlank() || url == "about:blank") return false
        val name = title.ifBlank { ShortcutStore.shortNameFor(url) }.take(24)
        ShortcutStore.add(
            context,
            Shortcut(
                name = name,
                url = url,
                iconUrl = ShortcutStore.iconUrlFor(url)
            )
        )
        Toast.makeText(context, "Added to UH Home", Toast.LENGTH_SHORT).show()
        return true
    }

    fun pinToAndroidHomeScreen(activityContext: Context, url: String, title: String) {
        if (url.isBlank() || url == "about:blank") return
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(activityContext)) {
            Toast.makeText(activityContext, "Launcher pinning unsupported; added to UH Home", Toast.LENGTH_SHORT).show()
            addToUhHomeGrid(url, title)
            return
        }
        val label = title.ifBlank { ShortcutStore.shortNameFor(url) }.take(32)
        val launchIntent = Intent(activityContext, HomeActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse(url)
        }
        val shortcutInfo = ShortcutInfoCompat.Builder(activityContext, "uh_pin_${url.hashCode()}")
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(activityContext, R.mipmap.ic_launcher))
            .setIntent(launchIntent)
            .build()
        ShortcutManagerCompat.requestPinShortcut(activityContext, shortcutInfo, null)
    }

    private fun geckoSessionForTab(tabId: String?): GeckoSession? {
        val tab = if (tabId != null) {
            components.store.state.findTab(tabId)
        } else {
            components.store.state.selectedTab
        } ?: return null
        val engineSession = tab.engineState.engineSession as? GeckoEngineSession ?: return null
        return try {
            val field = GeckoEngineSession::class.java.getDeclaredField("geckoSession")
            field.isAccessible = true
            field.get(engineSession) as? GeckoSession
        } catch (_: Exception) {
            null
        }
    }
}
