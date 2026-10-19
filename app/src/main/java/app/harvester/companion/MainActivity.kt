package app.harvester.companion

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import app.harvester.companion.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tabs: TabManager
    private lateinit var shortcutAdapter: ShortcutAdapter
    private lateinit var suggestionsAdapter: SuggestionAdapter
    private var currentView: WebView? = null

    private val popupTabs = mutableSetOf<Long>()
    private val page3p = mutableMapOf<Long, MutableSet<String>>()
    private val recentUrls = LinkedHashMap<String, Long>()
    private var lastBackPress = 0L
    private var engineIconCache: Bitmap? = null
    private var currentEngineId: String = ""
    private var suggestJob: Job? = null
    private var urlTextWatcher: android.text.TextWatcher? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UHLog.init(this)
        SessionRecorder.init(this)
        UHLog.section("MAIN")

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        tabs = TabManager.get(this)

        MenuCommandStore.listener = { runOnUiThread { updateUi() } }

        setupHomeGrid()
        setupBottomBar()
        setupSuggestions()
        setupSwipeRefresh()
        setupBackDispatcher()

        val existing = tabs.list()
        if (existing.isEmpty()) tabs.openNew("about:blank")
        else tabs.acquireView(existing.first().id)
        attachCurrent()
        applyAddressBarPosition()
        UHLog.d("main", "onCreate complete")
    }

    private fun setupHomeGrid() {
        shortcutAdapter = ShortcutAdapter(
            onOpen = { s -> loadUrlFromHome(s.url) },
            onAdd = { showAddShortcutDialog() },
            onLongPress = { s -> confirmRemoveShortcut(s) }
        )
        binding.shortcutsGrid.layoutManager = GridLayoutManager(this, 4)
        binding.shortcutsGrid.adapter = shortcutAdapter
        shortcutAdapter.submit(ShortcutStore.list(this))
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            currentView?.reload()
        }
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            val webView = currentView ?: return@setOnChildScrollUpCallback false
            val bridge = webView.tag as? UserscriptBridge
            webView.scrollY > 0 || bridge?.overlayActive == true
        }
        binding.swipeRefresh.viewTreeObserver.addOnScrollChangedListener {
            val webView = currentView ?: return@addOnScrollChangedListener
            val bridge = webView.tag as? UserscriptBridge
            binding.swipeRefresh.isEnabled = webView.scrollY == 0 && bridge?.overlayActive != true
        }
        binding.swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.accent)
        )
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(this, R.color.surface)
        )
    }

    private fun setupSuggestions() {
        suggestionsAdapter = SuggestionAdapter(
            onClick = { text ->
                val trimmed = text.trim()
                if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                    HistoryStore.get(this).record(trimmed, null)
                    currentView?.loadUrl(trimmed)
                } else {
                    HistoryStore.get(this).recordSearch(trimmed)
                    val target = SearchEnginePrefs.getSearchUrl(this, trimmed)
                    currentView?.loadUrl(target)
                }
                exitEditMode()
                updateHomeVisibility()
            },
            onDeleteQuery = { id ->
                HistoryStore.get(this).deleteSearchQuery(id)
                refreshSuggestions(binding.urlEdit.text?.isNotEmpty() == true)
            },
            onDeletePage = { id ->
                HistoryStore.get(this).delete(id)
                refreshSuggestions(binding.urlEdit.text?.isNotEmpty() == true)
            },
            onCopy = { url ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("URL", url))
                Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
            },
            onEdit = { url ->
                binding.urlEdit.setText(url)
                binding.urlEdit.setSelection(binding.urlEdit.text.length)
            }
        )
        binding.suggestionsList.layoutManager = LinearLayoutManager(this)
        binding.suggestionsList.adapter = suggestionsAdapter
        binding.suggestionsClearAll.setOnClickListener {
            HistoryStore.get(this).clearSearchHistory()
            refreshSuggestions(binding.urlEdit.text?.isNotEmpty() == true)
        }
        binding.urlChipCopy.setOnClickListener {
            val url = binding.urlChipText.text.toString()
            if (url.isNotBlank()) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("URL", url))
                Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
            }
        }
        binding.urlChipEdit.setOnClickListener {
            val url = binding.urlChipText.text.toString()
            if (url.isNotBlank()) {
                enterEditMode()
                binding.urlEdit.setText(url)
                binding.urlEdit.setSelection(url.length)
            }
        }
    }

    private fun refreshSuggestions(hasText: Boolean) {
        val typed = binding.urlEdit.text.toString().trim()
        val isUrl = typed.contains(".") && !typed.contains(" ")
        val loadedUrl = currentView?.url ?: ""

        val showLoaded = !hasText && loadedUrl.isNotBlank() && loadedUrl != "about:blank"
        if (showLoaded) {
            binding.urlChipTitle.text = tabs.list().firstOrNull { it.id == tabs.currentId() }?.title ?: "Website"
            binding.urlChipText.text = loadedUrl
            binding.urlChipStrip.visibility = View.VISIBLE
        } else {
            binding.urlChipStrip.visibility = View.GONE
        }

        suggestJob?.cancel()
        suggestJob = lifecycleScope.launch {
            if (!hasText) {
                val queries = withContext(Dispatchers.IO) { HistoryStore.get(this@MainActivity).searchSearchQueries("", 10) }
                val pages = withContext(Dispatchers.IO) { HistoryStore.get(this@MainActivity).searchHistory("", 5) }
                suggestionsAdapter.submitAll(queries, pages)
                binding.suggestionsClearAll.visibility = if (queries.isNotEmpty()) View.VISIBLE else View.GONE
                return@launch
            }

            val queries = if (isUrl) emptyList() else withContext(Dispatchers.IO) { HistoryStore.get(this@MainActivity).searchSearchQueries(typed) }
            val pages = withContext(Dispatchers.IO) { HistoryStore.get(this@MainActivity).searchHistory(typed, 10) }
            
            suggestionsAdapter.submitAll(queries, pages)
            binding.suggestionsClearAll.visibility = View.GONE
            
            if (!isUrl && typed.length >= 2) {
                delay(250)
                val engine = SearchEnginePrefs.getEngine(this@MainActivity)
                val preds = SearchPredictions.fetch(engine, typed)
                if (preds.isNotEmpty() && binding.urlEdit.text.toString().trim() == typed) {
                    val fake = preds.mapIndexed { i, p -> HistoryStore.SearchQuery(-1L - i, p, System.currentTimeMillis()) }
                    val merged = (fake + queries).distinctBy { it.query.lowercase() }.take(8)
                    suggestionsAdapter.submitAll(merged, pages)
                }
            }
        }
    }

    private fun setupBottomBar() {
        binding.urlText.setOnClickListener { enterEditMode() }
        binding.urlClear.setOnClickListener {
            binding.urlEdit.setText("")
            refreshSuggestions(false)
        }
        binding.urlEdit.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                val typed = binding.urlEdit.text.toString().trim()
                if (typed.isNotBlank()) {
                    navigateInput(typed)
                }
                true
            } else false
        }
        binding.searchEngineIcon.setOnClickListener {
            val isBlank = currentView?.url.isNullOrBlank() || currentView?.url == "about:blank"
            if (isBlank || binding.urlEdit.visibility == View.VISIBLE) {
                showSearchEnginePicker()
            }
        }
        binding.btnMenu.setOnClickListener { showMenu() }
        binding.tabCount.setOnClickListener { openTabGrid() }
        binding.btnScriptMenu.setOnClickListener { showScriptMenuSheet() }
    }

    private fun navigateInput(typed: String): Boolean {
        val target = normalize(typed)
        if (target.isBlank()) return false
        val isSearch = !target.startsWith("http")
        if (isSearch) HistoryStore.get(this).recordSearch(typed)
        var view = currentView
        if (view == null || tabs.currentId() < 0L) {
            tabs.openNew(target)
            attachCurrent()
            view = currentView
        } else {
            view.loadUrl(target)
        }
        exitEditMode()
        updateHomeVisibility()
        return true
    }

    private fun setupBackDispatcher() {
        onBackPressedDispatcher.addCallback(this) {
            if (binding.urlEdit.visibility == View.VISIBLE) {
                exitEditMode()
                return@addCallback
            }
            val v = currentView
            val id = if (v != null) tabs.idForView(v) else -1L
            if (v != null && id in popupTabs) { closeBlankPopup(v); return@addCallback }
            if (v != null) {
                val history = v.copyBackForwardList()
                if (history.currentIndex > 0) {
                    v.goBack()
                    return@addCallback
                }
            }
            val url = v?.url
            val isOnSite = !url.isNullOrBlank() && url != "about:blank" &&
                !url.startsWith("chrome-error://")
            if (isOnSite) {
                tabs.openNew("about:blank")
                attachCurrent(); updateUi(); return@addCallback
            }
            val previousId = tabs.popHistory()
            if (previousId != null) {
                tabs.acquireView(previousId)
                attachCurrent(); updateUi()
                return@addCallback
            }
            val now = System.currentTimeMillis()
            if (now - lastBackPress < 2000L) finish()
            else {
                lastBackPress = now
                Toast.makeText(this@MainActivity, "Press back again to exit", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyAddressBarPosition() {
        val position = SettingsPrefs.getInt(this, SettingsPrefs.K_ADDRESS_BAR_POSITION, 1)
        val root = binding.root as ConstraintLayout
        val cs = ConstraintSet()
        cs.clone(root)

        val barId = binding.bottomBar.id
        val chipId = binding.urlChipStrip.id
        val lbId = binding.loadingBar.id
        val wcId = binding.contentContainer.id
        val spId = binding.suggestionsPanel.id
        val parent = ConstraintSet.PARENT_ID

        listOf(barId, chipId, lbId, wcId, spId).forEach { id ->
            cs.clear(id, ConstraintSet.TOP)
            cs.clear(id, ConstraintSet.BOTTOM)
        }

        if (position == 0) {
            cs.connect(barId, ConstraintSet.TOP, parent, ConstraintSet.TOP)
            cs.connect(lbId, ConstraintSet.TOP, barId, ConstraintSet.BOTTOM)
            cs.connect(chipId, ConstraintSet.TOP, lbId, ConstraintSet.BOTTOM)
            cs.connect(spId, ConstraintSet.TOP, chipId, ConstraintSet.BOTTOM)
            cs.connect(spId, ConstraintSet.BOTTOM, parent, ConstraintSet.BOTTOM)
            cs.connect(wcId, ConstraintSet.TOP, lbId, ConstraintSet.BOTTOM)
            cs.connect(wcId, ConstraintSet.BOTTOM, parent, ConstraintSet.BOTTOM)
        } else {
            cs.connect(barId, ConstraintSet.BOTTOM, parent, ConstraintSet.BOTTOM)
            cs.connect(lbId, ConstraintSet.BOTTOM, barId, ConstraintSet.TOP)
            cs.connect(chipId, ConstraintSet.BOTTOM, lbId, ConstraintSet.TOP)
            cs.connect(spId, ConstraintSet.BOTTOM, chipId, ConstraintSet.TOP)
            cs.connect(spId, ConstraintSet.TOP, parent, ConstraintSet.TOP)
            cs.connect(wcId, ConstraintSet.TOP, parent, ConstraintSet.TOP)
            cs.connect(wcId, ConstraintSet.BOTTOM, lbId, ConstraintSet.TOP)
        }
        cs.applyTo(root)
    }

    private fun enterEditMode() {
        val url = currentView?.url ?: ""
        val isBlank = url.isBlank() || url == "about:blank"

        binding.urlChipText.text = if (isBlank) "" else url
        binding.urlChipStrip.visibility = if (isBlank) View.GONE else View.VISIBLE

        binding.urlText.visibility = View.GONE
        binding.urlEdit.visibility = View.VISIBLE
        binding.urlEdit.setText("")
        binding.urlEdit.requestFocus()

        if (urlTextWatcher != null) {
            binding.urlEdit.removeTextChangedListener(urlTextWatcher)
        }
        urlTextWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                val hasText = !s.isNullOrEmpty()
                binding.urlClear.visibility = if (hasText) View.VISIBLE else View.GONE
                binding.urlChipStrip.visibility = if (hasText) View.GONE else View.VISIBLE
                refreshSuggestions(hasText)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        binding.urlEdit.addTextChangedListener(urlTextWatcher)

        showSuggestionsPanel()
        refreshSuggestions(false)
        binding.urlEdit.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.urlEdit, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun exitEditMode() {
        binding.urlEdit.setText("")
        binding.urlEdit.visibility = View.GONE
        binding.urlText.visibility = View.VISIBLE
        binding.urlClear.visibility = View.GONE
        binding.urlChipStrip.visibility = View.GONE
        urlTextWatcher?.let { binding.urlEdit.removeTextChangedListener(it) }
        urlTextWatcher = null
        suggestJob?.cancel()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.urlEdit.windowToken, 0)
        binding.urlEdit.clearFocus()
        hideSuggestionsPanel()
        updateUi()
    }

    private fun showSuggestionsPanel() {
        binding.suggestionsPanel.visibility = View.VISIBLE
        refreshSuggestions(binding.urlEdit.text?.isNotEmpty() == true)
    }

    private fun hideSuggestionsPanel() {
        binding.suggestionsPanel.visibility = View.GONE
    }

    private fun showSearchEnginePicker() {
        val sheet = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.dialog_search_engine_picker, null)
        v.setBackgroundResource(R.drawable.bg_bottom_sheet)
        sheet.setContentView(v)
        sheet.behavior.isDraggable = false

        val list = v.findViewById<android.widget.LinearLayout>(R.id.engineList)
        val current = SearchEnginePrefs.getEngine(this)
        for (entry in SearchEnginePrefs.all(this)) {
            val row = layoutInflater.inflate(R.layout.item_search_engine_picker, list, false)
            row.findViewById<android.widget.TextView>(R.id.engineName).text = entry.name
            row.findViewById<android.widget.TextView>(R.id.engineHost).text = entry.host
            row.findViewById<View>(R.id.engineCheck).visibility =
                if (entry.id == current) View.VISIBLE else View.INVISIBLE
            val iconView = row.findViewById<ImageView>(R.id.engineIcon)
            loadIconInto(iconView, entry.iconUrl, R.drawable.ic_search)
            row.setOnClickListener {
                SearchEnginePrefs.setEngine(this, entry.id)
                sheet.dismiss()
                currentEngineId = ""
                engineIconCache = null
                updateSearchEngineIcon()
                Toast.makeText(this, entry.name, Toast.LENGTH_SHORT).show()
            }
            list.addView(row)
        }

        v.findViewById<android.widget.Button>(R.id.engineAddCustom).setOnClickListener {
            sheet.dismiss()
            startActivity(Intent(this, SearchEngineActivity::class.java))
        }

        sheet.show()
    }

    private fun loadIconInto(view: ImageView, url: String, fallbackRes: Int) {
        Thread {
            val bmp = try {
                URL(url).openStream().use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) { null }
            runOnUiThread {
                if (bmp != null) view.setImageBitmap(bmp)
                else view.setImageResource(fallbackRes)
            }
        }.start()
    }

    private fun updateSearchEngineIcon() {
        val entry = SearchEnginePrefs.currentEntry(this)
        val url = currentView?.url ?: ""
        val isBlank = url.isBlank() || url == "about:blank"

        if (isBlank || binding.urlEdit.visibility == View.VISIBLE) {
            binding.searchEngineIcon.visibility = View.VISIBLE
            binding.searchEngineIcon.isClickable = true
            binding.searchEngineIcon.clearColorFilter()
            if (currentEngineId != entry.id || engineIconCache == null) {
                currentEngineId = entry.id
                binding.searchEngineIcon.setImageResource(R.drawable.ic_search)
                loadIconInto(binding.searchEngineIcon, entry.iconUrl, R.drawable.ic_search)
            } else {
                binding.searchEngineIcon.setImageBitmap(engineIconCache)
            }
        } else if (url.startsWith("https")) {
            binding.searchEngineIcon.setImageResource(R.drawable.ic_lock)
            binding.searchEngineIcon.setColorFilter(ContextCompat.getColor(this, R.color.accent))
            binding.searchEngineIcon.isClickable = false
        } else {
            binding.searchEngineIcon.setImageResource(R.drawable.ic_lock_open)
            binding.searchEngineIcon.setColorFilter(ContextCompat.getColor(this, R.color.red))
            binding.searchEngineIcon.isClickable = false
        }
    }

    private fun showAddShortcutDialog() { showShortcutEditor(null) }

    private fun showShortcutEditor(existing: Shortcut?) {
        val urlInput = EditText(this).apply {
            setHint("https://example.com"); setSingleLine()
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.subtext))
            if (existing != null) setText(existing.url)
        }
        val nameInput = EditText(this).apply {
            setHint("Name (optional)"); setSingleLine()
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.subtext))
            if (existing != null) setText(existing.name)
        }
        val wrap = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 16, 40, 16)
            addView(urlInput); addView(nameInput)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add shortcut" else "Edit shortcut")
            .setView(wrap)
            .setPositiveButton(if (existing == null) "Add" else "Save") { _, _ ->
                val raw = urlInput.text.toString().trim()
                if (raw.isBlank()) return@setPositiveButton
                val fixed = if (raw.startsWith("http")) raw else "https://$raw"
                val manual = nameInput.text.toString().trim()
                val name = if (manual.isNotBlank()) manual else ShortcutStore.shortNameFor(fixed)
                if (existing != null) ShortcutStore.remove(this, existing.url)
                ShortcutStore.add(this, Shortcut(name, fixed, ShortcutStore.iconUrlFor(fixed)))
                shortcutAdapter.submit(ShortcutStore.list(this))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRemoveShortcut(s: Shortcut) {
        AlertDialog.Builder(this)
            .setTitle(s.name)
            .setItems(arrayOf("Edit", "Remove")) { _, which ->
                when (which) {
                    0 -> showShortcutEditor(s)
                    1 -> {
                        ShortcutStore.remove(this, s.url)
                        shortcutAdapter.submit(ShortcutStore.list(this))
                    }
                }
            }
            .show()
    }

    private fun loadUrlFromHome(url: String) {
        var view = currentView
        if (view == null || view.url == "about:blank" || tabs.currentId() < 0L) {
            tabs.openNew(url); attachCurrent(); view = currentView
        }
        view?.loadUrl(url)
        updateHomeVisibility()
    }

private fun attachCurrent() {
    val view = tabs.currentView() ?: return
    if (view === currentView) { updateHomeVisibility(); return }
    
    tabs.pushHistory(tabs.currentId())
    
    binding.webContainer.removeAllViews()
    (view.parent as? ViewGroup)?.removeView(view)
    binding.webContainer.addView(view)
    currentView = view
        if (view.getTag(R.id.webContainer) == null) {
            installClients(view)
            view.setTag(R.id.webContainer, true)
        }
        updateUi()
        tabs.drainPendingDestroy()
    }

    private fun updateHomeVisibility() {
        val id = tabs.currentId()
        if (id in popupTabs) {
            binding.homeScreen.visibility = View.GONE
            binding.contentContainer.visibility = View.VISIBLE
            return
        }
        val url = tabs.list().firstOrNull { it.id == id }?.url
        val isBlank = url.isNullOrBlank() || url == "about:blank"
        binding.homeScreen.visibility = if (isBlank) View.VISIBLE else View.GONE
    }

    private fun prunePopupTabs() {
        val live = tabs.list().map { it.id }.toSet()
        popupTabs.retainAll(live)
    }

    private fun isStaticAsset(url: String, headers: Map<String, String>?): Boolean {
        val accept = headers?.entries?.firstOrNull { it.key.equals("Accept", true) }?.value ?: ""
        if (accept.contains("image/") || accept.contains("text/css") || accept.contains("font/")) return true
        
        val path = url.substringBefore('?').substringBefore('#')
        val dot = path.lastIndexOf('.')
        if (dot == -1 || path.length - dot > 6) return false
        val ext = path.substring(dot + 1)
        if (ext.length < 2) return false
        val c0 = ext[0].lowercaseChar()
        if (c0 == 'j') return ext.equals("js", true) || ext.equals("jpg", true) || ext.equals("jpeg", true)
        if (c0 == 'c') return ext.equals("css", true)
        if (c0 == 'p') return ext.equals("png", true)
        if (c0 == 'w') return ext.equals("woff", true) || ext.equals("woff2", true) || ext.equals("webp", true)
        if (c0 == 't') return ext.equals("ttf", true)
        if (c0 == 'o') return ext.equals("otf", true)
        if (c0 == 'e') return ext.equals("eot", true)
        if (c0 == 's') return ext.equals("svg", true)
        if (c0 == 'g') return ext.equals("gif", true)
        if (c0 == 'i') return ext.equals("ico", true)
        if (c0 == 'm') return ext.equals("mjs", true) || ext.equals("map", true)
        if (c0 == 'a') return ext.equals("avif", true)
        if (c0 == 'b') return ext.equals("bmp", true)
        return false
    }

    private fun isBeaconHost(url: String): Boolean {
        val host = try { java.net.URI(url).host?.lowercase() ?: return false } catch (_: Exception) { return false }
        if (host.endsWith(".googletagmanager.com") || host == "googletagmanager.com") return true
        if (host.endsWith(".google-analytics.com") || host == "google-analytics.com") return true
        if (host == "improving.duckduckgo.com") return true
        if (host == "my.rtmark.net") return true
        if (host.endsWith(".cloudflareinsights.com")) return true
        if (host.endsWith(".sentry.io")) return true
        return false
    }

    private fun recordUrlOnce(url: String): Boolean {
        val key = url.substringBefore('?').substringBefore('#')
        val now = System.currentTimeMillis()
        synchronized(recentUrls) {
            val it = recentUrls.entries.iterator()
            while (it.hasNext()) if (now - it.next().value > 2000L) it.remove()
            val last = recentUrls[key]
            recentUrls[key] = now
            return !(last != null && now - last < 1000L)
        }
    }

    private fun collect3p(v: WebView, url: String) {
        val host = try { java.net.URI(url).host?.lowercase() ?: return } catch (_: Exception) { return }
        val viewUrl = v.url ?: return
        val viewHost = try { java.net.URI(viewUrl).host?.lowercase() ?: return } catch (_: Exception) { return }
        if (host == viewHost) return
        val id = tabs.idForView(v)
        if (id <= 0L) return
        synchronized(page3p) { page3p.getOrPut(id) { mutableSetOf() }.add(host) }
    }

    private fun flush3p(v: WebView, pageUrl: String) {
        val id = tabs.idForView(v)
        if (id <= 0L) return
        val hosts = synchronized(page3p) { page3p.remove(id)?.toList() } ?: return
        if (hosts.isNotEmpty()) UHLog.net3p(pageUrl, hosts)
    }

    private fun clear3p(v: WebView) {
        val id = tabs.idForView(v)
        if (id <= 0L) return
        synchronized(page3p) { page3p.remove(id) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun installClients(view: WebView) {
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                try {
                    val url = request.url.toString()
                    val method = request.method ?: "GET"
                    val headers = request.requestHeaders
                    if (request.isForMainFrame && url.isNotBlank() && url != "about:blank") {
                        val id = tabs.idForView(v)
                        if (id > 0L) tabs.updateUrl(id, url, null)
                        val ref = headers?.entries?.firstOrNull { it.key.equals("Referer", true) }?.value
                        val hasCookie = headers?.keys?.any { it.equals("Cookie", true) } == true
                        val hasAuth = headers?.keys?.any { it.equals("Authorization", true) } == true
                        UHLog.netNav(method, url, ref, hasCookie, hasAuth)
                        if (SessionRecorder.isActive() && SessionRecorder.isEnabled("header")) {
                            SessionRecorder.record("header") {
                                put("url", url); put("method", method)
                                val h = JSONObject()
                                for ((k, value) in headers ?: emptyMap()) h.put(k, value)
                                put("headers", h)
                            }
                        }
                        return null
                    }
                    if (isStaticAsset(url, headers)) return null
                    if (isBeaconHost(url)) return null
                    if (!recordUrlOnce(url)) return null
                    val accept = headers?.entries?.firstOrNull { it.key.equals("Accept", true) }?.value ?: ""
                    val isApi = accept.contains("json", true) || url.contains("/api/") ||
                        headers?.keys?.any { it.equals("X-Requested-With", true) } == true
                    when {
                        method == "POST" -> {
                            val ct = headers?.entries?.firstOrNull { it.key.equals("Content-Type", true) }?.value
                            val cl = headers?.entries?.firstOrNull { it.key.equals("Content-Length", true) }?.value?.toLongOrNull() ?: -1L
                            UHLog.netPost(url, ct, cl)
                        }
                        isApi -> UHLog.netApi(method, url)
                    }
                    collect3p(v, url)
                } catch (_: Exception) {}
                return null
            }

            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                val scheme = request.url.scheme ?: ""
                if (scheme == "intent") { handleIntentUrl(url); return true }
                if (scheme == "http" || scheme == "https") return false
                if (scheme == "about" || scheme == "data" || scheme == "blob" ||
                    scheme == "file" || scheme == "javascript") return false
                UHLog.netScheme(url, "dialog")
                askOpenExternal(v, url)
                return true
            }

            override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                UHLog.d("web", "start ${url.take(140)}")
                val id = tabs.idForView(v)
                if (id > 0L) tabs.updateUrl(id, url, null)
                clear3p(v)
                if (v === currentView) {
                    val isEmpty = url.isBlank() || url == "about:blank"
                    binding.urlText.text = if (isEmpty) "Search or type URL" else url
                    binding.urlText.setTextColor(ContextCompat.getColor(
                        this@MainActivity, if (isEmpty) R.color.subtext else R.color.text))
                    updateSearchEngineIcon()
                }
                if (id in popupTabs) UHLog.netPopupLoad(url)
                if (url != "about:blank" && !url.startsWith("chrome-error://")) {
                    UserscriptManager.injectOnPageStart(v, this@MainActivity, url)
                }
                if (v === currentView) updateHomeVisibility()
            }

            override fun onPageFinished(v: WebView, url: String) {
                UHLog.d("web", "finish ${url.take(140)}")
                val id = tabs.idForView(v)
                if (id > 0L) tabs.updateUrl(id, url, null)
                flush3p(v, url)
                binding.swipeRefresh.isRefreshing = false
                if (url != "about:blank") {
                    UserscriptManager.injectOnPageFinished(this@MainActivity, v, url)
                    val entity = tabs.list().firstOrNull { it.id == id }
                    if (entity?.incognito != true) {
                        HistoryStore.get(this@MainActivity).record(url, entity?.title ?: "")
                    }
                }
                if (v === currentView) updateUi()
            }

            override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
                tabs.setNav(v, v.canGoBack(), v.canGoForward())
            }

            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    UHLog.w("web", "error ${error.errorCode} ${request.url}")
                    UHLog.netError(request.url.toString(), error.errorCode)
                    binding.swipeRefresh.isRefreshing = false
                }
            }

            override fun onReceivedSslError(v: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, newProgress: Int) {
                if (v !== currentView) return
                binding.loadingBar.visibility =
                    if (newProgress in 1..99) View.VISIBLE else View.GONE
                binding.loadingBar.progress = newProgress
            }

            override fun onReceivedTitle(v: WebView, title: String?) {
                if (title.isNullOrBlank()) return
                val id = tabs.idForView(v)
                if (id <= 0L) return
                val entity = tabs.list().firstOrNull { it.id == id }
                val url = entity?.url ?: ""
                tabs.updateUrl(id, url, title)
            }

            override fun onCreateWindow(v: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val parentId = tabs.idForView(v)
                val parentUrl = tabs.list().firstOrNull { it.id == parentId }?.url ?: v.url ?: ""
                UHLog.netPopupOpen(parentUrl, isUserGesture, isDialog)
                val id = tabs.openBlankTab()
                val newView = tabs.acquireView(id) ?: return false
                popupTabs.add(id)
                UHLog.netPopupCreated(id)
                if (newView.getTag(R.id.webContainer) == null) {
                    installClients(newView)
                    newView.setTag(R.id.webContainer, true)
                }
                binding.webContainer.removeAllViews()
                (newView.parent as? ViewGroup)?.removeView(newView)
                binding.webContainer.addView(newView)
                binding.homeScreen.visibility = View.GONE
                currentView = newView
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = newView
                resultMsg.sendToTarget()
                updateUi()
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        }

        view.setOnLongClickListener {
            val result = view.hitTestResult
            if (result.type == WebView.HitTestResult.IMAGE_TYPE || result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                val imageUrl = result.extra
                if (!imageUrl.isNullOrBlank()) {
                    val options = arrayOf("Download Image", "Share Image", "Copy Image Link", "Open Image")
                    AlertDialog.Builder(this)
                        .setTitle("Image Options")
                        .setItems(options) { _, which ->
                            when (which) {
                                0 -> {
                                    val filename = "image_${System.currentTimeMillis()}.jpg"
                                    if (imageUrl.startsWith("blob:") || imageUrl.startsWith("data:")) {
                                        currentView?.evaluateJavascript(
                                            "fetch('$imageUrl').then(r=>r.blob()).then(b=>{var reader=new FileReader();reader.onload=function(){window.UH_BRIDGE.saveBlob('$filename',b.type,reader.result.split(',')[1])};reader.readAsDataURL(b);})", null
                                        )
                                    } else {
                                        executeDownload(imageUrl, filename, "image/jpeg", null)
                                    }
                                }
                                1 -> {
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, imageUrl)
                                    }
                                    startActivity(Intent.createChooser(intent, "Share Image Link"))
                                }
                                2 -> {
                                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Image URL", imageUrl))
                                    Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
                                }
                                3 -> currentView?.loadUrl(imageUrl)
                            }
                        }
                        .show()
                }
                true
            } else false
        }

        view.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val filename = extractFilename(contentDisposition) ?: "download_${System.currentTimeMillis()}"
            
            if (SettingsPrefs.getBool(this, SettingsPrefs.K_ASK_DL, true)) {
                showDownloadConfirmationSheet(url, filename, mimetype, userAgent)
            } else {
                executeDownload(url, filename, mimetype, userAgent)
            }
        }
    }

    private fun showDownloadConfirmationSheet(url: String, filename: String, mimetype: String?, userAgent: String?) {
        val sheet = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.dialog_download_confirmation, null)
        v.setBackgroundResource(R.drawable.bg_bottom_sheet)
        sheet.setContentView(v)

        v.findViewById<android.widget.TextView>(R.id.dlFileName).text = filename
        v.findViewById<android.widget.TextView>(R.id.dlFileSize).text = "Calculating..."
        v.findViewById<android.widget.TextView>(R.id.dlFileUrl).text = url
        
        val customUri = SettingsPrefs.getString(this, "custom_download_uri", "")
        val pathStr = if (customUri.isNotBlank()) {
             customUri.replace("content://com.android.externalstorage.documents/tree/primary%3A", "/storage/emulated/0/").replace("%2F", "/")
        } else {
            "/storage/emulated/0/Download"
        }
        v.findViewById<android.widget.TextView>(R.id.dlLocationPath).text = pathStr

        v.findViewById<android.view.View>(R.id.dlLocationRow).setOnClickListener {
            startActivity(Intent(this, DownloadSettingsActivity::class.java))
            sheet.dismiss()
        }

        v.findViewById<android.view.View>(R.id.dlStartBtn).setOnClickListener {
            sheet.dismiss()
            executeDownload(url, filename, mimetype, userAgent)
        }

        v.findViewById<android.view.View>(R.id.dlEditName).setOnClickListener {
            val input = EditText(this).apply { setText(filename) }
            AlertDialog.Builder(this)
                .setTitle("Rename")
                .setView(input)
                .setPositiveButton("OK") { _, _ ->
                    val newName = input.text.toString().trim()
                    if (newName.isNotBlank()) {
                        v.findViewById<android.widget.TextView>(R.id.dlFileName).text = newName
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        Thread {
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "HEAD"
                conn.connectTimeout = 3000
                val size = conn.contentLengthLong
                runOnUiThread {
                    if (size > 0) {
                        v.findViewById<android.widget.TextView>(R.id.dlFileSize).text = android.text.format.Formatter.formatShortFileSize(this, size)
                    } else {
                        v.findViewById<android.widget.TextView>(R.id.dlFileSize).text = "Unknown size"
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {
                runOnUiThread { v.findViewById<android.widget.TextView>(R.id.dlFileSize).text = "Unknown size" }
            }
        }.start()

        sheet.show()
    }

private fun executeDownload(url: String, filename: String, mimetype: String?, userAgent: String?) {
    val id = DownloadHelper.saveFromUrlWithCookies(this, url, filename, mimetype, userAgent)
    if (id != -1L) {
        DownloadService.startTracking(this, id, filename, url)
    } else {
        Toast.makeText(this, "Failed to start download", Toast.LENGTH_SHORT).show()
    }
}

    private fun extractFilename(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null
        val rx = Regex("""filename[^;=\n]*=((['"]).*?\2|[^;\n]*)""", RegexOption.IGNORE_CASE)
        val m = rx.find(contentDisposition) ?: return null
        return m.groupValues[1].trim().trim('"', '\'')
    }

    private fun switchTab(id: Long) {
        tabs.saveCurrentState()
        tabs.acquireView(id)
        attachCurrent()
        updateUi()
    }

    private fun updateUi() {
        prunePopupTabs()
        val id = tabs.currentId()
        val url = tabs.list().firstOrNull { it.id == id }?.url ?: ""
        val isEmpty = url.isBlank() || url == "about:blank"
        if (binding.urlEdit.visibility != View.VISIBLE) {
            binding.urlText.text = if (isEmpty) "Search or type URL" else url
            binding.urlText.setTextColor(ContextCompat.getColor(
                this, if (isEmpty) R.color.subtext else R.color.text))
        }
        binding.tabCount.text = tabs.list().size.toString()
        binding.btnScriptMenu.visibility = View.VISIBLE
        updateSearchEngineIcon()
        updateHomeVisibility()
    }

    private fun matchingScriptsForPage(url: String): List<UserscriptManager.Script> {
        if (url.isBlank() || url == "about:blank") return emptyList()
        return UserscriptManager.loadScripts(this).filter { s ->
            UserscriptStore.isEnabled(this, s.name) && matchesAny(s, url)
        }
    }

    private fun matchesAny(script: UserscriptManager.Script, url: String): Boolean {
        for (p in script.patterns) if (globMatch(p, url)) return true
        return false
    }

    private fun globMatch(pattern: String, url: String): Boolean {
        var pi = 0; var ui = 0; var star = -1; var mark = 0
        while (ui < url.length) {
            when {
                pi < pattern.length && pattern[pi] == url[ui] -> { pi++; ui++ }
                pi < pattern.length && pattern[pi] == '*' -> { star = pi++; mark = ui }
                star >= 0 -> { pi = star + 1; ui = ++mark }
                else -> return false
            }
        }
        while (pi < pattern.length && pattern[pi] == '*') pi++
        return pi == pattern.length
    }

    private fun showScriptMenuSheet() {
        val sheet = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.dialog_script_menu, null)
        v.setBackgroundResource(R.drawable.bg_bottom_sheet)
        sheet.setContentView(v)
        sheet.behavior.isDraggable = false

        val list = v.findViewById<android.widget.LinearLayout>(R.id.scriptMenuList)
        populateScriptSheet(list, sheet)

        v.findViewById<android.widget.Button>(R.id.scriptMenuAll)?.setOnClickListener {
            sheet.dismiss()
            startActivity(Intent(this, ScriptsActivity::class.java))
        }
        sheet.show()
    }

    private fun populateScriptSheet(list: android.widget.LinearLayout, sheet: BottomSheetDialog) {
        list.removeAllViews()
        val url = currentView?.url ?: ""
        val allScripts = UserscriptManager.loadScripts(this)
            .filter { UserscriptStore.isEnabled(this, it.name) }

        if (allScripts.isEmpty()) {
            val empty = android.widget.TextView(this).apply {
                text = "No scripts installed."
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.subtext))
                textSize = 12f
                setPadding(0, 20, 0, 20)
            }
            list.addView(empty)
            return
        }

        val matching = allScripts.filter { matchesAny(it, url) }
        val nonMatching = allScripts.filter { !matchesAny(it, url) }
        val ordered = matching + nonMatching

        for (script in ordered) {
            val isActive = script in matching
            val row = layoutInflater.inflate(R.layout.item_script_menu_script, list, false)

            row.findViewById<android.widget.TextView>(R.id.smsName).text =
                script.displayName.ifBlank { script.name }

            val badge = row.findViewById<android.widget.TextView>(R.id.smsActiveBadge)
            badge.visibility = if (isActive) View.VISIBLE else View.GONE

            val sw = row.findViewById<android.widget.Switch>(R.id.smsEnabled)
            sw.isChecked = true
            sw.setOnCheckedChangeListener { _, checked ->
                UserscriptStore.setEnabled(this, script.name, checked)
                if (!checked) sheet.dismiss()
            }

            val cmds = row.findViewById<android.widget.LinearLayout>(R.id.smsCommands)
            val arrow = row.findViewById<ImageView>(R.id.smsExpand)
            val scriptCommands = MenuCommandStore.forScript(script.name)

            if (scriptCommands.isEmpty()) {
                val empty = android.widget.TextView(this).apply {
                    text = "No menu commands yet"
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.subtext))
                    textSize = 11f
                    setPadding(12, 4, 12, 8)
                }
                cmds.addView(empty)
            } else {
                for (cmd in scriptCommands) {
                    val cv = layoutInflater.inflate(R.layout.item_script_menu_command, cmds, false)
                    cv.findViewById<android.widget.TextView>(R.id.smcLabel).text = cmd.name
                    cv.setOnClickListener {
                        currentView?.evaluateJavascript(
                            "try{window.__uh_runMenuCommand('${cmd.id}')}catch(e){}", null)
                        list.postDelayed({ populateScriptSheet(list, sheet) }, 400)
                    }
                    cmds.addView(cv)
                }
            }

            arrow.setOnClickListener {
                if (cmds.visibility == View.VISIBLE) {
                    cmds.visibility = View.GONE
                    arrow.animate().rotation(90f).setDuration(150).start()
                } else {
                    cmds.visibility = View.VISIBLE
                    arrow.animate().rotation(-90f).setDuration(150).start()
                }
            }

            list.addView(row)
        }
    }

    private fun openTabGrid() {
        TabGridDialog(this, tabs,
            onSwitch = { id -> switchTab(id) },
            onNew = { attachCurrent(); updateUi() }
        ).apply {
            setOnDismissListener {
                val live = tabs.currentView()
                if (live != null && live !== currentView) { attachCurrent(); updateUi() }
                else updateUi()
            }
        }.show()
    }

    private fun normalize(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return ""
        if (t.startsWith("http://") || t.startsWith("https://") ||
            t.startsWith("about:") || t.startsWith("file:")) return t
        if (t.contains(".") && !t.contains(" ")) return "https://$t"
        return SearchEnginePrefs.getSearchUrl(this, t)
    }

    private fun showMenu() {
        val sheet = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.dialog_main_menu, null)
        v.setBackgroundResource(R.drawable.bg_bottom_sheet)
        sheet.setContentView(v)

        v.findViewById<View>(R.id.menuFavorites)?.setOnClickListener { sheet.dismiss(); toast("Favorites coming soon") }
        v.findViewById<View>(R.id.menuHistory)?.setOnClickListener {
            sheet.dismiss()
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        v.findViewById<View>(R.id.menuDownloads)?.setOnClickListener {
            sheet.dismiss()
            startActivity(Intent(this, DownloadsActivity::class.java))
        }
        v.findViewById<View>(R.id.menuPlaylist)?.setOnClickListener { sheet.dismiss(); toast("Playlist coming soon") }
        v.findViewById<View>(R.id.menuSettings)?.setOnClickListener {
            sheet.dismiss(); startActivity(Intent(this, SettingsActivity::class.java))
        }
        v.findViewById<View>(R.id.menuScripts)?.setOnClickListener {
            sheet.dismiss(); startActivity(Intent(this, ScriptsActivity::class.java))
        }
        v.findViewById<View>(R.id.menuTools)?.setOnClickListener {
            sheet.dismiss(); startActivity(Intent(this, ToolsActivity::class.java))
        }
        v.findViewById<View>(R.id.menuLogs)?.setOnClickListener {
            sheet.dismiss(); startActivity(Intent(this, LogsActivity::class.java))
        }
        
        v.findViewById<View>(R.id.menuBottomHome)?.setOnClickListener {
            sheet.dismiss()
            tabs.openNew("about:blank"); attachCurrent(); updateUi()
        }
        v.findViewById<View>(R.id.menuBottomFavorite)?.setOnClickListener { sheet.dismiss(); toast("Favorites coming soon") }
        v.findViewById<View>(R.id.menuBottomClose)?.setOnClickListener { sheet.dismiss(); finish() }
        v.findViewById<View>(R.id.menuBottomScripts)?.setOnClickListener {
            sheet.dismiss(); startActivity(Intent(this, ScriptsActivity::class.java))
        }
        v.findViewById<View>(R.id.menuBottomReload)?.setOnClickListener {
            sheet.dismiss()
            currentView?.reload()
        }
        sheet.behavior.isDraggable = false
        sheet.show()
    }

    private fun pickOrCreateGroup() {
        val groups = TabGroupStore.list(this)
        if (groups.isEmpty()) { showAddGroupDialog(); return }
        val names = groups.map { it.name }.toMutableList()
        names.add("+ New group")
        AlertDialog.Builder(this)
            .setTitle("Add tab to group")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == groups.size) {
                    showAddGroupDialog()
                } else {
                    val g = groups[which]
                    tabs.updateGroup(tabs.currentId(), g.id)
                    Toast.makeText(this, "Added to ${g.name}", Toast.LENGTH_SHORT).show()
                    updateUi()
                }
            }
            .show()
    }

    private fun showAddGroupDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_create_group, null)
        val input = view.findViewById<EditText>(R.id.groupNameInput)
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()
        view.findViewById<View>(R.id.groupCancel)?.setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.groupCreate)?.setOnClickListener {
            val name = input.text.toString().trim().ifBlank { "Group" }
            TabGroupStore.create(this, name)
            Toast.makeText(this, "Group '$name' created", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun handleIntentUrl(url: String) {
        try {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            val dataScheme = intent.data?.scheme ?: ""
            val schemeExtra = intent.scheme ?: ""
            val isWeb = dataScheme == "http" || dataScheme == "https" ||
                schemeExtra == "http" || schemeExtra == "https"
            if (isWeb) {
                val target = intent.data?.toString()
                if (!target.isNullOrBlank()) { UHLog.netScheme(url, "unwrapped→$target"); currentView?.loadUrl(target); return }
            }
            val resolved = packageManager.queryIntentActivities(intent, 0)
            if (resolved.isNotEmpty()) { UHLog.netScheme(url, "external"); askOpenExternalIntent(intent, currentView) }
            else {
                val fallback = intent.getStringExtra("browser_fallback_url")
                if (!fallback.isNullOrBlank()) { UHLog.netScheme(url, "fallback→$fallback"); currentView?.loadUrl(fallback) }
                else {
                    val pkg = intent.`package`
                    if (!pkg.isNullOrBlank()) {
                        UHLog.netScheme(url, "market→$pkg")
                        try { startActivity(Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse("market://details?id=$pkg") }) }
                        catch (_: Exception) { toast("App not installed") }
                    } else { UHLog.netScheme(url, "unresolved"); toast("App not installed") }
                }
            }
        } catch (e: Exception) { UHLog.throwable("nav", "intent parse failed: $url", e) }
    }

    private fun askOpenExternal(view: WebView, url: String) {
        val isFresh = view.url.isNullOrBlank() || view.url == "about:blank"
        UHLog.netExtDialog(url)
        AlertDialog.Builder(this)
            .setTitle("Open in another app?").setMessage(url)
            .setPositiveButton("Open") { _, _ ->
                UHLog.netExtOpen(url)
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (_: Exception) { toast("No app can handle this link") }
                if (isFresh) closeBlankPopup(view)
            }
            .setNegativeButton("Stay") { _, _ ->
                UHLog.netExtStay(url)
                if (isFresh) closeBlankPopup(view)
            }
            .show()
    }

    private fun askOpenExternalIntent(intent: Intent, view: WebView?) {
        val isFresh = view?.url.isNullOrBlank() || view?.url == "about:blank"
        val urlString = intent.data?.toString() ?: "External app link"
        UHLog.netExtDialog(urlString)
        AlertDialog.Builder(this)
            .setTitle("Open in another app?").setMessage(urlString)
            .setPositiveButton("Open") { _, _ ->
                UHLog.netExtOpen(urlString)
                try { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(intent) }
                catch (_: Exception) { toast("Failed to open app") }
                if (isFresh) view?.let { closeBlankPopup(it) }
            }
            .setNegativeButton("Stay") { _, _ ->
                UHLog.netExtStay(urlString)
                if (isFresh) view?.let { closeBlankPopup(it) }
            }
            .show()
    }

    private fun closeBlankPopup(view: WebView) {
        val id = tabs.idForView(view)
        if (id <= 0L) return
        popupTabs.remove(id)
        tabs.close(id)
        val remaining = tabs.list()
        if (remaining.isEmpty()) tabs.openNew("about:blank")
        else tabs.acquireView(remaining.first().id)
        attachCurrent(); updateUi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val incoming = intent.data?.toString() ?: return
        val already = tabs.list().firstOrNull { it.url == incoming }
        if (already != null) { switchTab(already.id); return }
        if (tabs.list().isEmpty()) tabs.openNew(incoming)
        else tabs.openNew(incoming)
        attachCurrent(); updateUi()
    }

    override fun onResume() {
        super.onResume()
        UpdateChecker.resumePendingInstallIfPossible(this)
        applyAddressBarPosition()
        currentView?.onResume()
        currentView?.resumeTimers()
        val dark = when (ThemePrefs.getMode(this)) {
            ThemePrefs.MODE_DARK -> true
            ThemePrefs.MODE_LIGHT -> false
            else -> (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        currentView?.let { WebViewEngine.applyDarkMode(it, dark) }
        if (UserscriptStore.consumeDirty(this)) {
            val v = currentView
            val u = v?.url
            if (v != null && !u.isNullOrBlank()) UserscriptManager.injectOnPageStart(v, this, u)
        }
        updateUi()
    }

    override fun onPause() {
        super.onPause()
        currentView?.onPause()
        currentView?.pauseTimers()
        tabs.saveAll()
        UHLog.onActivityPause(this)
    }

    override fun onDestroy() {
        tabs.saveAll()
        MenuCommandStore.listener = null
        suggestJob?.cancel()
        super.onDestroy()
    }
}
