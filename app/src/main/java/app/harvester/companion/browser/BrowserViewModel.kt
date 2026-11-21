package app.harvester.companion.browser

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.harvester.companion.GeckoWebExtensionBridge
import app.harvester.companion.SearchEnginePrefs
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.Shortcut
import app.harvester.companion.ShortcutStore
import app.harvester.companion.UHApp
import app.harvester.companion.UHLog
import app.harvester.companion.browser.browsingmode.BrowsingMode
import app.harvester.companion.browser.search.SearchSuggestionClient
import app.harvester.companion.browser.tools.PdfPrintHelper
import app.harvester.companion.components.appstate.AppAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.appservices.places.BookmarkRoot
import mozilla.components.browser.engine.gecko.GeckoEngineSession
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.selector.selectedTab
import org.mozilla.geckoview.GeckoSession
import java.net.URI

data class ExternalAppPrompt(
    val appName: String,
    val packageName: String,
    val activityName: String,
    val appIcon: Bitmap?,
    val targetUrl: String,
    val host: String,
)

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

    private val _findMatchCase = MutableStateFlow(false)
    val findMatchCase: StateFlow<Boolean> = _findMatchCase.asStateFlow()

    private val _findWholeWord = MutableStateFlow(false)
    val findWholeWord: StateFlow<Boolean> = _findWholeWord.asStateFlow()

    // Global Page Zoom state (50% - 300%, applied smoothly via CSS zoom across all tabs)
    private val _pageZoomPercent = MutableStateFlow(
        SettingsPrefs.getInt(context, SettingsPrefs.K_TEXT_SCALE, 100).coerceIn(50, 300)
    )
    val pageZoomPercent: StateFlow<Int> = _pageZoomPercent

    private val _showZoomBar = MutableStateFlow(false)
    val showZoomBar: StateFlow<Boolean> = _showZoomBar

    // Bookmark state for current URL
    private val _isBookmarked = MutableStateFlow(false)
    val isBookmarked: StateFlow<Boolean> = _isBookmarked

    // DevTools active tabs tracking
    private val _devToolsActiveTabIds = MutableStateFlow<Set<String>>(emptySet())
    val devToolsActiveTabIds: StateFlow<Set<String>> = _devToolsActiveTabIds.asStateFlow()

    // Overlay sheets & dialogs
    private val _showScriptsSheet = MutableStateFlow(false)
    val showScriptsSheet: StateFlow<Boolean> = _showScriptsSheet

    private val _showCaptureQuickSheet = MutableStateFlow(false)
    val showCaptureQuickSheet: StateFlow<Boolean> = _showCaptureQuickSheet

    private val _showAddToHomeDialog = MutableStateFlow(false)
    val showAddToHomeDialog: StateFlow<Boolean> = _showAddToHomeDialog

    private val _showExtensionsSheet = MutableStateFlow(false)
    val showExtensionsSheet: StateFlow<Boolean> = _showExtensionsSheet

    private val _showTranslateSheet = MutableStateFlow(false)
    val showTranslateSheet: StateFlow<Boolean> = _showTranslateSheet.asStateFlow()

    private val _showSavePdfSheet = MutableStateFlow(false)
    val showSavePdfSheet: StateFlow<Boolean> = _showSavePdfSheet.asStateFlow()

    private val _pendingExternalAppPrompt = MutableStateFlow<ExternalAppPrompt?>(null)
    val pendingExternalAppPrompt: StateFlow<ExternalAppPrompt?> = _pendingExternalAppPrompt.asStateFlow()

    private var suggestJob: Job? = null
    private var lastBookmarkUrl: String = ""

    init {
        viewModelScope.launch {
            GeckoWebExtensionBridge.devToolsVisible.collect { visible ->
                val tabId = components.store.state.selectedTab?.id ?: return@collect
                _devToolsActiveTabIds.update { set ->
                    if (visible) set + tabId else set - tabId
                }
            }
        }
    }

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

    fun toggleFindMatchCase(tabId: String? = null) {
        _findMatchCase.value = !_findMatchCase.value
        val q = _findQuery.value
        if (q.isNotBlank()) {
            runFindInPage(q, forward = true, tabId = tabId)
        }
    }

    fun toggleFindWholeWord(tabId: String? = null) {
        _findWholeWord.value = !_findWholeWord.value
        val q = _findQuery.value
        if (q.isNotBlank()) {
            runFindInPage(q, forward = true, tabId = tabId)
        }
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
        var flags = if (forward) GeckoSession.FINDER_FIND_FORWARD else GeckoSession.FINDER_FIND_BACKWARDS
        if (_findMatchCase.value) {
            flags = flags or GeckoSession.FINDER_FIND_MATCH_CASE
        }
        if (_findWholeWord.value) {
            flags = flags or GeckoSession.FINDER_FIND_WHOLE_WORD
        }
        try {
            session.finder.displayFlags =
                GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL or GeckoSession.FINDER_DISPLAY_DIM_PAGE
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

    // ---- Global Full-Page Zoom (50% - 300%) ----

    fun openZoomBar() {
        _showFindInPage.value = false
        _showZoomBar.value = true
    }

    fun closeZoomBar() {
        _showZoomBar.value = false
    }

    fun setPageZoom(percent: Int) {
        val clamped = percent.coerceIn(50, 300)
        _pageZoomPercent.value = clamped
        SettingsPrefs.setInt(context, SettingsPrefs.K_TEXT_SCALE, clamped)
        try {
            components.engine.settings.forceUserScalableContent = true
            components.engine.settings.fontInflationEnabled = false
            components.engine.settings.automaticFontSizeAdjustment = false
        } catch (_: Exception) {}
        // Broadcast live CSS full-page zoom to all open tabs immediately via uh_bridge
        GeckoWebExtensionBridge.applyGlobalZoom(clamped)
    }

    // ---- DevTools ----

    fun toggleDevTools(tabId: String?, darkTheme: Boolean = true) {
        GeckoWebExtensionBridge.toggleDevTools(darkTheme)
        if (!tabId.isNullOrBlank()) {
            _devToolsActiveTabIds.update { set ->
                if (tabId in set) set - tabId else set + tabId
            }
        }
    }

    // ---- Save PDF / Print / Copy / Open in External App / New Private Tab ----

    fun saveCurrentPageAsPdf(activityContext: Context, title: String) {
        PdfPrintHelper.saveSessionAsPdf(activityContext, geckoSessionForTab(null), title)
    }

    fun printCurrentPage(activityContext: Context, title: String = "UH Page") {
        PdfPrintHelper.printSession(activityContext, geckoSessionForTab(null), title)
    }

    fun copyLink(url: String) {
        if (url.isBlank() || url == "about:blank") return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("URL", url))
        Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
    }

    fun openInExternalApp(activityContext: Context, url: String) {
        if (url.isBlank() || url == "about:blank") return
        try {
            val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            val pm = activityContext.packageManager
            val rawResolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(viewIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(viewIntent, 0)
            }
            val resolved = rawResolved
                .filter { it.activityInfo.packageName != activityContext.packageName }
                .distinctBy { it.activityInfo.packageName }

            if (resolved.isEmpty()) {
                Toast.makeText(activityContext, "No external app found for this link", Toast.LENGTH_SHORT).show()
                return
            }

            if (resolved.size == 1) {
                val ri = resolved.first()
                val appName = try {
                    ri.loadLabel(pm).toString().ifBlank { ri.activityInfo.packageName }
                } catch (_: Exception) {
                    ri.activityInfo.packageName
                }
                val iconBitmap = try {
                    drawableToBitmap(ri.loadIcon(pm))
                } catch (_: Exception) {
                    null
                }
                val host = try {
                    URI(url).host?.removePrefix("www.").orEmpty().ifBlank { url }
                } catch (_: Exception) {
                    url
                }
                _pendingExternalAppPrompt.value = ExternalAppPrompt(
                    appName = appName,
                    packageName = ri.activityInfo.packageName,
                    activityName = ri.activityInfo.name,
                    appIcon = iconBitmap,
                    targetUrl = url,
                    host = host,
                )
            } else {
                val targets = resolved.map { ri ->
                    Intent(viewIntent).apply {
                        setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                val chooser = Intent.createChooser(targets.first(), "Open in app").apply {
                    if (targets.size > 1) {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, targets.drop(1).toTypedArray())
                    }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activityContext.startActivity(chooser)
            }
        } catch (e: Exception) {
            UHLog.w("external_app", "Failed opening URL in external app: ${e.message}")
            Toast.makeText(activityContext, "Could not open in external app", Toast.LENGTH_SHORT).show()
        }
    }

    fun confirmOpenExternalApp(activityContext: Context) {
        val prompt = _pendingExternalAppPrompt.value ?: return
        _pendingExternalAppPrompt.value = null
        try {
            val direct = Intent(Intent.ACTION_VIEW, Uri.parse(prompt.targetUrl)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                setClassName(prompt.packageName, prompt.activityName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activityContext.startActivity(direct)
        } catch (e: Exception) {
            UHLog.w("external_app", "Failed launching confirmed external app: ${e.message}")
            Toast.makeText(activityContext, "Could not open ${prompt.appName}", Toast.LENGTH_SHORT).show()
        }
    }

    fun dismissExternalAppPrompt() {
        _pendingExternalAppPrompt.value = null
    }

    private fun drawableToBitmap(drawable: Drawable?): Bitmap? {
        if (drawable == null) return null
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bmp
    }

    fun openNewPrivateTab() {
        browsingModeManager.mode = BrowsingMode.Private
        components.useCases.tabsUseCases.addTab("about:blank", selectTab = true, private = true)
        enterEditMode("")
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

    // ---- Overlay Sheets ----

    fun openScriptsSheet() { _showScriptsSheet.value = true }
    fun closeScriptsSheet() { _showScriptsSheet.value = false }

    fun openCaptureQuickSheet() { _showCaptureQuickSheet.value = true }
    fun closeCaptureQuickSheet() { _showCaptureQuickSheet.value = false }

    fun openAddToHomeDialog() { _showAddToHomeDialog.value = true }
    fun closeAddToHomeDialog() { _showAddToHomeDialog.value = false }

    fun openExtensionsSheet() { _showExtensionsSheet.value = true }
    fun closeExtensionsSheet() { _showExtensionsSheet.value = false }

    fun openTranslateSheet() { _showTranslateSheet.value = true }
    fun closeTranslateSheet() { _showTranslateSheet.value = false }

    fun openSavePdfSheet() { _showSavePdfSheet.value = true }
    fun closeSavePdfSheet() { _showSavePdfSheet.value = false }

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
