package app.harvester.companion

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URL
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.WebResponse
import org.mozilla.geckoview.WebExtension

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tabs: TabManager
    private lateinit var shortcutAdapter: ShortcutAdapter
    private lateinit var suggestionsAdapter: SuggestionAdapter
    private lateinit var mainGeckoView: NestedGeckoView
    private var currentSession: GeckoSession? = null
    private var currentUrl: String = ""

    private val popupTabs = mutableSetOf<Long>()
    private val page3p = mutableMapOf<Long, MutableSet<String>>()
    private val recentUrls = LinkedHashMap<String, Long>()
    private var lastBackPress = 0L
    private var engineIconCache: Bitmap? = null
    private var currentEngineId: String = ""
    private var suggestJob: Job? = null
    private var urlTextWatcher: android.text.TextWatcher? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_UHCompanion)
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        val safePrefs = getSharedPreferences("uh_safe_mode", MODE_PRIVATE)
        if (safePrefs.getBoolean("force_safe_mode", false)) {
            startActivity(Intent(this, SafeModeActivity::class.java))
            finish()
            return
        }

UHLog.init(this)
SessionRecorder.init(this)
UHLog.section("MAIN")

try {
    GeckoEngine.getRuntime(this)
} catch (e: Throwable) {
    UHLog.e("gecko", "GeckoRuntime init failed: ${e.message}")
    UHLog.throwable("gecko", "GeckoRuntime init failed", e)
    try { UHLog.mirrorToPublic(this) } catch (_: Exception) {}
    getSharedPreferences("uh_safe_mode", MODE_PRIVATE).edit().putBoolean("force_safe_mode", true).apply()
    startActivity(Intent(this, SafeModeActivity::class.java))
    finish()
    return
}

if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
    if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
    }
}

binding = ActivityMainBinding.inflate(layoutInflater)
setContentView(binding.root)

androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
    val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
    val imeVisible = ime.bottom > 0
    val position = SettingsPrefs.getInt(this, SettingsPrefs.K_ADDRESS_BAR_POSITION, 1)

    val params = binding.bottomBar.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
    if (position == 0) {
        params.topMargin = systemBars.top
        binding.contentContainer.setPadding(0, 0, 0, 0)
        params.bottomMargin = 0
    } else {
        params.topMargin = 0
        binding.contentContainer.setPadding(0, systemBars.top, 0, 0)
        params.bottomMargin = if (imeVisible) ime.bottom else systemBars.bottom
    }
    binding.bottomBar.layoutParams = params
    binding.bottomBar.setPadding(0, 0, 0, 0)
    binding.suggestionsPanel.setPadding(0, systemBars.top, 0, 0)
    try {
        val barH = binding.bottomBar.height
        if (barH > 0) {
            binding.webContainer.geckoView.updateVerticalClipping(barH, if (imeVisible) ime.bottom else 0)
        }
    } catch (e: Throwable) {
        UHLog.e("insets", "clip update failed: ${e.message}")
    }

    insets
}
tabs = TabManager.get(this)
       setupWebExtensionBridge()

        mainGeckoView = binding.webContainer.geckoView

        MenuCommandStore.listener = { runOnUiThread { updateUi() } }

        setupHomeGrid()
        setupBottomBar()
        setupSuggestions()
        setupSwipeRefresh()
        setupBackDispatcher()

        val existing = tabs.list()
        if (existing.isEmpty()) {
            tabs.openNew("about:blank")
        } else {
            tabs.acquireSession(existing.first().id)
        }
        attachCurrent()
        applyAddressBarPosition()

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ hideSplash() }, 1200)

    UHLog.d("main", "onCreate complete")
    }
    private fun setupWebExtensionBridge() {
        val runtime = GeckoEngine.getRuntime(this) ?: return
            runtime.webExtensionController.ensureBuiltIn(
                "resource://android/assets/web_extensions/uh_bridge/",
                "uh-bridge@harvester.app"
            ).accept({ ext ->
                if (ext != null) {
                    ext.setMessageDelegate(object : WebExtension.MessageDelegate {
                    override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                        val json = message as? JSONObject ?: return null
                        val type = json.optString("type")
                        when (type) {
                            "GET_SCRIPTS" -> {
                                val url = json.optString("url")
                                val code = UserscriptManager.buildInjectionScript(this@MainActivity, url)
                                val res = JSONObject()
                                res.put("scripts", code)
                                return GeckoResult.fromValue(res)
                            }
                            "SAVE_BLOB" -> {
                                val filename = json.optString("filename")
                                val mime = json.optString("mime")
                                val base64 = json.optString("base64")
                                DownloadEngine.startDownload(this@MainActivity, "", filename, mime, base64, null, currentUrl)
                                return GeckoResult.fromValue("ok")
                            }
                            "GM_setValue" -> {
                                val script = json.optString("script")
                                val key = json.optString("key")
                                val value = json.optString("value")
                                getSharedPreferences("uh_gm", Context.MODE_PRIVATE).edit().putString("$script::$key", value).apply()
                                return GeckoResult.fromValue("ok")
                            }
                            "GM_deleteValue" -> {
                                val script = json.optString("script")
                                val key = json.optString("key")
                                getSharedPreferences("uh_gm", Context.MODE_PRIVATE).edit().remove("$script::$key").apply()
                                return GeckoResult.fromValue("ok")
                            }
                            "GM_log" -> {
                                UHLog.d("userscript", json.optString("message"))
                                return GeckoResult.fromValue("ok")
                            }
                            "GM_setClipboard" -> {
                                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("userscript", json.optString("text")))
                                return GeckoResult.fromValue("ok")
                            }
                            "GM_notification" -> {
                                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                                val chId = "uh_userscript"
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    if (nm.getNotificationChannel(chId) == null) {
                                        nm.createNotificationChannel(android.app.NotificationChannel(chId, "Userscripts", android.app.NotificationManager.IMPORTANCE_DEFAULT))
                                    }
                                }
                                val n = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                                    android.app.Notification.Builder(this@MainActivity, chId)
                                else @Suppress("DEPRECATION") android.app.Notification.Builder(this@MainActivity)
                                n.setContentTitle(json.optString("title", "Harvester"))
                                n.setContentText(json.optString("text"))
                                n.setSmallIcon(android.R.drawable.ic_dialog_info)
                                nm.notify(System.currentTimeMillis().toInt(), n.build())
                                return GeckoResult.fromValue("ok")
                            }
                            "OPEN_IMAGE" -> {
                                try {
                                    val intent = Intent(this@MainActivity, ImagePreviewActivity::class.java).apply {
                                        putExtra("url", json.optString("url"))
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    startActivity(intent)
                                } catch (_: Exception) {}
                                return GeckoResult.fromValue("ok")
                            }
            "OVERLAY_STATE" -> {
        val active = json.optBoolean("active")
        val bridge = mainGeckoView.tag as? UserscriptBridge
        bridge?.overlayActive = active
        return GeckoResult.fromValue("ok")
    }
"GM_xmlhttpRequest" -> {
    val geckoResult = GeckoResult<Any>()
    val method = json.optString("method")
    val url = json.optString("url")
    val headersStr = json.optString("headers")
    val data = json.optString("data")
    lifecycleScope.launch(Dispatchers.IO) {
        try {
            val body = if (method in listOf("POST", "PUT", "PATCH")) data.toRequestBody(null) else null
            val builder = okhttp3.Request.Builder().url(url).method(method, body)
            if (headersStr.isNotBlank()) {
                val hObj = JSONObject(headersStr)
                for (key in hObj.keys()) builder.addHeader(key, hObj.getString(key))
            }
            val client = okhttp3.OkHttpClient.Builder().addInterceptor(SecurityInterceptor()).build()
            val resp = client.newCall(builder.build()).execute()
            val text = resp.body?.string() ?: ""
            val respHeaders = resp.headers.toMultimap().entries.joinToString("") { "${it.key}: ${it.value.joinToString(", ")}\r\n" }
            
            val resObj = JSONObject()
            resObj.put("status", resp.code)
            resObj.put("responseText", text)
            resObj.put("responseHeaders", respHeaders)
            
            geckoResult.complete(resObj.toString())
        } catch (e: Exception) {
            val errObj = JSONObject()
            errObj.put("status", 0)
            errObj.put("responseText", e.message ?: "Error")
            errObj.put("responseHeaders", "")
            geckoResult.complete(errObj.toString())
        }
    }
    return geckoResult
}
    "GM_download" -> {
        val url = json.optString("url")
        val name = json.optString("name")
        DownloadEngine.startDownload(this@MainActivity, url, name, "application/octet-stream", null, null, currentUrl)
        return GeckoResult.fromValue("ok")
    }
    "GM_openInTab" -> {
        val url = json.optString("url")
        tabs.openNew(url)
        attachCurrent()
        updateUi()
        return GeckoResult.fromValue("ok")
    }
    "GM_registerMenuCommand" -> {
        val script = json.optString("script")
        val id = json.optString("id")
        val name = json.optString("name")
        MenuCommandStore.register(script, id, name, "")
        return GeckoResult.fromValue("ok")
    }
    "GM_unregisterMenuCommand" -> {
        val script = json.optString("script")
        val id = json.optString("id")
        MenuCommandStore.unregister(script, id)
        return GeckoResult.fromValue("ok")
    }
                        }
                        return GeckoResult.fromValue("ok")
                    }
                }, "uhApp")
            }
        }, { error ->
            UHLog.e("main", "Failed to attach MessageDelegate: ${error?.message}")
        })
    }

private var isSplashHidden = false
private fun hideSplash() {
    if (isSplashHidden) return
    isSplashHidden = true
    
    binding.splashLogo.alpha = 0f
    binding.splashLogo.scaleX = 0.9f
    binding.splashLogo.scaleY = 0.9f
    binding.splashLogo.animate()
        .alpha(1f)
        .scaleX(1f)
        .scaleY(1f)
        .setDuration(600)
        .setInterpolator(android.view.animation.DecelerateInterpolator())
        .start()

    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        binding.splashScreen.animate()
            .alpha(0f)
            .setDuration(400)
            .withEndAction { binding.splashScreen.visibility = View.GONE }
            .start()
    }, 1200)
}

private fun updateGridColumns() {
    val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val screenWidthDp = resources.configuration.screenWidthDp
    val cols = if (isLandscape) {
        when {
            screenWidthDp >= 1280 -> 9
            screenWidthDp >= 917 -> 8
            screenWidthDp >= 600 -> 7
            else -> 6
        }
    } else 4
    (binding.shortcutsGrid.layoutManager as? GridLayoutManager)?.spanCount = cols

    val nsv = binding.shortcutsGrid.parent as? androidx.core.widget.NestedScrollView
    if (nsv != null) {
        val lp = nsv.layoutParams as android.widget.LinearLayout.LayoutParams
        if (isLandscape) {
            lp.height = (170 * resources.displayMetrics.density).toInt()
            lp.weight = 0f
        } else {
            lp.height = 0
            lp.weight = 1f
        }
        nsv.layoutParams = lp
        nsv.requestLayout()
   }
}

override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
    super.onConfigurationChanged(newConfig)
    updateGridColumns()
    applyAddressBarPosition()
}

private fun setupHomeGrid() {
    shortcutAdapter = ShortcutAdapter(
        onOpen = { s -> loadUrlFromHome(s.url) },
        onAdd = { showAddShortcutDialog() },
        onLongPress = { }
    )
    binding.shortcutsGrid.layoutManager = GridLayoutManager(this, 4)
    binding.shortcutsGrid.adapter = shortcutAdapter
    updateGridColumns()
    shortcutAdapter.submit(ShortcutStore.list(this))

    val callback = object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
        androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN or androidx.recyclerview.widget.ItemTouchHelper.START or androidx.recyclerview.widget.ItemTouchHelper.END, 0
    ) {
        override fun onMove(recyclerView: androidx.recyclerview.widget.RecyclerView, viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, target: androidx.recyclerview.widget.RecyclerView.ViewHolder): Boolean {
            val fromPos = viewHolder.adapterPosition
            val toPos = target.adapterPosition
            if (fromPos < 0 || toPos < 0) return false
            if (fromPos == shortcutAdapter.itemCount - 1 || toPos == shortcutAdapter.itemCount - 1) return false
            shortcutAdapter.moveItem(fromPos, toPos)
            return true
        }
        override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {}
        override fun isLongPressDragEnabled(): Boolean = false
        
        override fun clearView(recyclerView: androidx.recyclerview.widget.RecyclerView, viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            ShortcutStore.save(this@MainActivity, shortcutAdapter.getItems())
        }
    }
val touchHelper = androidx.recyclerview.widget.ItemTouchHelper(callback)
touchHelper.attachToRecyclerView(binding.shortcutsGrid)

binding.shortcutsGrid.addOnItemTouchListener(object : androidx.recyclerview.widget.RecyclerView.SimpleOnItemTouchListener() {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var dragRunnable: Runnable? = null
    private var editRunnable: Runnable? = null
    private var downX = 0f
    private var downY = 0f
    private var isDragReady = false
    private var isDragging = false
    private var isPopupShowing = false

    override fun onInterceptTouchEvent(rv: androidx.recyclerview.widget.RecyclerView, e: android.view.MotionEvent): Boolean {
        when (e.action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                isDragReady = false
                isDragging = false
                isPopupShowing = false
                val child = rv.findChildViewUnder(e.x, e.y)
                if (child != null) {
                    val pos = rv.getChildAdapterPosition(child)
                    if (pos in 0 until shortcutAdapter.itemCount - 1) {
                        dragRunnable = Runnable { isDragReady = true }
                        handler.postDelayed(dragRunnable!!, 150)

                        editRunnable = Runnable {
                            if (!isDragging && !isPopupShowing) {
                                isPopupShowing = true
                                val item = shortcutAdapter.getItemAt(pos)
                                showWidgetMenu(item, child)
                            }
                        }
                        handler.postDelayed(editRunnable!!, 500)
                    }
                }
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (isPopupShowing) return false
                val dx = Math.abs(e.x - downX)
                val dy = Math.abs(e.y - downY)
                if (dx > 20 || dy > 20) {
                    editRunnable?.let { handler.removeCallbacks(it) }
                    editRunnable = null
                    
                    if (isDragReady && !isDragging) {
                        isDragging = true
                        val child = rv.findChildViewUnder(downX, downY)
                        if (child != null) {
                            val pos = rv.getChildAdapterPosition(child)
                            if (pos in 0 until shortcutAdapter.itemCount - 1) {
                                touchHelper.startDrag(rv.findViewHolderForAdapterPosition(pos)!!)
                            }
                        }
                    } else if (!isDragReady) {
                        dragRunnable?.let { handler.removeCallbacks(it) }
                        dragRunnable = null
                    }
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                dragRunnable?.let { handler.removeCallbacks(it) }
                dragRunnable = null
                editRunnable?.let { handler.removeCallbacks(it) }
                editRunnable = null
            }
        }
        return false
    }
})
}

private fun showWidgetMenu(s: Shortcut, anchor: android.view.View) {
    val popup = android.widget.PopupMenu(this, anchor)
    popup.menu.add(0, 1, 0, "Edit")
    popup.menu.add(0, 2, 1, "Remove")
    popup.setOnMenuItemClickListener { item ->
        when (item.itemId) {
            1 -> showShortcutEditor(s)
            2 -> {
                ShortcutStore.remove(this, s.url)
                shortcutAdapter.submit(ShortcutStore.list(this))
            }
        }
        true
    }
    popup.show()
}
    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            currentSession?.reload()
        }
        binding.swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.accent)
        )
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(this, R.color.surface)
        )
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ -> mainGeckoView.canScrollUp() }
    
        binding.btnEditHome.setOnClickListener {
            startActivity(Intent(this, CustomizeHomeActivity::class.java))
        }
    }

    private fun setupSuggestions() {
        suggestionsAdapter = SuggestionAdapter(
            onClick = { text ->
                val trimmed = text.trim()
                if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                    HistoryStore.get(this).record(trimmed, null)
                    if (tabs.list().isEmpty()) { tabs.openNew(trimmed); attachCurrent() } else { currentSession?.loadUri(trimmed) }
                } else {
                    HistoryStore.get(this).recordSearch(trimmed)
                    val target = SearchEnginePrefs.getSearchUrl(this, trimmed)
                    if (tabs.list().isEmpty()) { tabs.openNew(target); attachCurrent() } else { currentSession?.loadUri(target) }
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
        val loadedUrl = currentUrl

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

    val topUrl = pages.firstOrNull()?.url ?: if (queries.isNotEmpty() && !isUrl) SearchEnginePrefs.getSearchUrl(this@MainActivity, queries.first().query) else null
    if (topUrl != null) {
        GeckoEngine.speculativeConnect(topUrl)
    }
    
    if (!isUrl && typed.length >= 2) {
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
            val isBlank = currentUrl.isBlank() || currentUrl == "about:blank"
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
    
    binding.loadingBar.visibility = View.VISIBLE
    binding.loadingBar.progress = 0
    binding.homeScreen.visibility = View.GONE
    
    var session = currentSession
        if (session == null || tabs.list().isEmpty() || tabs.currentId() < 0L) {
            tabs.openNew(target)
            attachCurrent()
            session = currentSession
        } else {
            currentUrl = target
            binding.urlText.text = target
            updateHomeVisibility()
            session.loadUri(target)
        }
        exitEditMode()
        return true
    }

private fun setupBackDispatcher() {
    onBackPressedDispatcher.addCallback(this) {
        if (binding.urlEdit.visibility == View.VISIBLE) {
            exitEditMode()
            return@addCallback
        }

        val v = currentSession
        val id = if (v != null) tabs.idForSession(v) else -1L
        val url = currentUrl

        if (url == "about:blank" || url.isNullOrBlank()) {
            val all = tabs.list()
            if (all.size > 1) {
                popupTabs.remove(id)
                tabs.close(id)
                val remaining = tabs.list()
                if (remaining.isNotEmpty()) {
                    tabs.acquireSession(remaining.first().id)
                    attachCurrent(); updateUi()
                }
            } else {
                val now = System.currentTimeMillis()
                if (now - lastBackPress < 2000L) finish()
                else {
                    lastBackPress = now
                    Toast.makeText(this@MainActivity, "Press back again to exit", Toast.LENGTH_SHORT).show()
                }
            }
            return@addCallback
        }

        if (v != null) {
            if (tabs.canGoBack(v)) {
                v.goBack()
                return@addCallback
            }
        }

        val currentEntity = tabs.list().firstOrNull { it.id == id }
        if (currentEntity != null && currentEntity.parentId != 0L) {
            val parentId = currentEntity.parentId
            popupTabs.remove(id)
            tabs.close(id)
            tabs.acquireSession(parentId)
            attachCurrent()
            updateUi()
            return@addCallback
        }

        if (v != null) {
            v.loadUri("about:blank")
            return@addCallback
        }

        finish()
    }
}
private fun updateStatusBarAppearance(isHome: Boolean) {
    val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
    val isDarkTheme = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    val hasWallpaper = WallpaperStore.getCurrentWallpaper(this) != null && WallpaperStore.getCurrentWallpaper(this) != "default"

    if (isHome && hasWallpaper) {
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        val textColor = WallpaperStore.getTextColor(this)
        if (textColor == 0) {
            controller.isAppearanceLightStatusBars = !isDarkTheme
        } else {
            val isDarkText = androidx.core.graphics.ColorUtils.calculateLuminance(textColor) < 0.5
            controller.isAppearanceLightStatusBars = isDarkText
        }
    } else {
        window.statusBarColor = ContextCompat.getColor(this, R.color.bg)
        controller.isAppearanceLightStatusBars = !isDarkTheme
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
    cs.connect(wcId, ConstraintSet.TOP, chipId, ConstraintSet.BOTTOM)
    cs.connect(wcId, ConstraintSet.BOTTOM, parent, ConstraintSet.BOTTOM)
} else {
    cs.connect(barId, ConstraintSet.BOTTOM, parent, ConstraintSet.BOTTOM)
    cs.connect(lbId, ConstraintSet.BOTTOM, barId, ConstraintSet.TOP)
    cs.connect(chipId, ConstraintSet.BOTTOM, lbId, ConstraintSet.TOP)
    cs.connect(spId, ConstraintSet.BOTTOM, chipId, ConstraintSet.TOP)
    cs.connect(spId, ConstraintSet.TOP, parent, ConstraintSet.TOP)
    cs.connect(wcId, ConstraintSet.TOP, parent, ConstraintSet.TOP)
    cs.connect(wcId, ConstraintSet.BOTTOM, chipId, ConstraintSet.TOP)
}
    cs.applyTo(root)
    androidx.core.view.ViewCompat.requestApplyInsets(root)
}
    private fun enterEditMode() {
        val url = currentUrl
        val isBlank = url.isBlank() || url == "about:blank"

        updateStatusBarAppearance(false)

        binding.bottomBar.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        binding.suggestionsPanel.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        binding.urlChipStrip.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        binding.urlChipBar.setCardBackgroundColor(ContextCompat.getColor(this, R.color.surface_2))

        binding.urlChipText.text = if (isBlank) "" else url
        binding.urlChipStrip.visibility = if (isBlank) View.GONE else View.VISIBLE
        if (isBlank) {
            binding.urlChipStrip.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        } else {
            binding.urlChipStrip.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        }

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
        updateHomeVisibility()
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
        val sheet = BottomSheetDialog(this, R.style.Theme_UHCompanion_BottomSheet)
        val v = layoutInflater.inflate(R.layout.dialog_search_engine_picker, null)
        v.setBackgroundResource(R.drawable.bg_bottom_sheet)
        sheet.setContentView(v)
        sheet.behavior.skipCollapsed = true
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        
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
    val url = currentUrl
    val isBlank = url.isBlank() || url == "about:blank"

    if (isBlank || binding.urlEdit.visibility == View.VISIBLE) {
        binding.searchEngineIcon.visibility = View.VISIBLE
        binding.searchEngineArrow.visibility = View.VISIBLE
        binding.searchEngineIcon.isClickable = true
        binding.searchEngineIcon.clearColorFilter()
        if (currentEngineId != entry.id || engineIconCache == null) {
            currentEngineId = entry.id
            binding.searchEngineIcon.setImageResource(R.drawable.ic_search)
            loadIconInto(binding.searchEngineIcon, entry.iconUrl, R.drawable.ic_search)
        } else {
            binding.searchEngineIcon.setImageBitmap(engineIconCache)
        }
        binding.searchEngineIcon.setPadding(4, 4, 4, 4)
    } else if (url.startsWith("https")) {
        binding.searchEngineIcon.setImageResource(R.drawable.ic_lock)
        binding.searchEngineArrow.visibility = View.GONE
        binding.searchEngineIcon.setColorFilter(ContextCompat.getColor(this, R.color.accent))
        binding.searchEngineIcon.isClickable = false
    } else {
        binding.searchEngineIcon.setImageResource(R.drawable.ic_lock_open)
        binding.searchEngineArrow.visibility = View.GONE
        binding.searchEngineIcon.setColorFilter(ContextCompat.getColor(this, R.color.red))
        binding.searchEngineIcon.isClickable = false
    }
}

    private fun showAddShortcutDialog() { showShortcutEditor(null) }

private fun showShortcutEditor(existing: Shortcut?) {
    val view = layoutInflater.inflate(R.layout.dialog_add_shortcut_custom, null)
    val dialog = AlertDialog.Builder(this)
        .setView(view)
        .setCancelable(true)
        .create()

    view.findViewById<android.widget.TextView>(R.id.dialogTitle).text = if (existing == null) "Add Shortcut" else "Edit Shortcut"
    
    val urlInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inputUrl)
    val nameInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inputName)
    
    if (existing != null) {
        urlInput.setText(existing.url)
        nameInput.setText(existing.name)
    }

    view.findViewById<View>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
    
    view.findViewById<View>(R.id.btnSave).setOnClickListener {
        val raw = urlInput.text.toString().trim()
        if (raw.isBlank()) {
            urlInput.error = "URL is required"
            return@setOnClickListener
        }
        val fixed = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
        val manual = nameInput.text.toString().trim()
        val name = if (manual.isNotBlank()) manual else ShortcutStore.shortNameFor(fixed)
        
        if (existing != null) ShortcutStore.remove(this, existing.url)
        ShortcutStore.add(this, Shortcut(name, fixed, ShortcutStore.iconUrlFor(fixed)))
        shortcutAdapter.submit(ShortcutStore.list(this))
        dialog.dismiss()
    }

    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    dialog.show()
    
    val width = (resources.displayMetrics.widthPixels * 0.90).toInt()
    dialog.window?.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    dialog.window?.setGravity(android.view.Gravity.CENTER)
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
        var session = currentSession
        if (session == null || currentUrl == "about:blank" || tabs.currentId() < 0L) {
            tabs.openNew(url); attachCurrent(); session = currentSession
        }
        session?.loadUri(url)
        updateHomeVisibility()
    }

    private fun attachCurrent() {
        val session = tabs.currentSession() ?: return
        if (session === currentSession) { updateHomeVisibility(); return }
        
        tabs.pushHistory(tabs.currentId())
        
        if (currentSession != null) {
            currentSession?.setActive(false)
        }
        
        mainGeckoView.attachSession(session)
        currentSession = session
        session.setActive(true)
        
        currentUrl = tabs.list().firstOrNull { it.id == tabs.currentId() }?.url ?: ""
        
        if (tabs.markAttached(session)) {
            installDelegates(session)
        }
        updateUi()
    }

private fun updateHomeVisibility() {
    val id = tabs.currentId()
    if (id in popupTabs) {
        binding.homeScreen.visibility = View.GONE
        binding.swipeRefresh.visibility = View.VISIBLE
        binding.btnEditHome.visibility = View.GONE
        binding.bottomBar.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        return
    }
    val entity = tabs.list().firstOrNull { it.id == id }
    val url = entity?.url
    val isBlank = url.isNullOrBlank() || url == "about:blank"
    
    val hasSavedState = try {
        id > 0L && tabs.loadState(id) != null
    } catch (_: Exception) { false }
    
if (hasSavedState && binding.loadingBar.visibility == View.VISIBLE) {
    binding.homeScreen.visibility = View.GONE
    binding.swipeRefresh.visibility = View.VISIBLE
    binding.btnEditHome.visibility = View.GONE
    binding.bottomBar.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
    updateStatusBarAppearance(false)
} else {
    val hasWallpaper = WallpaperStore.getCurrentWallpaper(this) != null && WallpaperStore.getCurrentWallpaper(this) != "default"
    
    val isEditing = binding.urlEdit.visibility == View.VISIBLE
    if (isBlank) {
        binding.homeScreen.visibility = View.VISIBLE
        binding.swipeRefresh.visibility = View.GONE
        binding.btnEditHome.visibility = View.VISIBLE
   
        if (isEditing) {
            binding.bottomBar.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
            updateStatusBarAppearance(false)
        } else {
            binding.bottomBar.setBackgroundColor(if (hasWallpaper) android.graphics.Color.TRANSPARENT else ContextCompat.getColor(this, R.color.bg))
            updateStatusBarAppearance(true)
        }
    } else {
        binding.homeScreen.visibility = View.GONE
        binding.swipeRefresh.visibility = View.VISIBLE
        binding.btnEditHome.visibility = View.GONE
        binding.bottomBar.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        updateStatusBarAppearance(false)
    }
}
}

    private fun prunePopupTabs() {
        val live = tabs.list().map { it.id }.toSet()
        popupTabs.retainAll(live)
    }

    private fun installDelegates(session: GeckoSession) {
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                UHLog.d("web", "start ${url.take(140)}")
                val id = tabs.idForSession(session)
                if (id > 0L) tabs.updateUrl(id, url, null)
                if (session === currentSession) {
                    val isEmpty = url.isBlank() || url == "about:blank"
                    binding.urlText.text = if (isEmpty) "Search or type URL" else url
                    binding.urlText.setTextColor(ContextCompat.getColor(
                        this@MainActivity, if (isEmpty) R.color.subtext else R.color.text))
                    updateSearchEngineIcon()
                    binding.loadingBar.visibility = View.VISIBLE
                    binding.loadingBar.progress = 0
                }
                if (id in popupTabs) UHLog.netPopupLoad(url)
                if (session === currentSession) updateHomeVisibility()
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                val url = currentUrl
                UHLog.d("web", "finish ${url.take(140)}")
                hideSplash()
                binding.loadingBar.visibility = View.GONE
                val id = tabs.idForSession(session)
                if (id > 0L) tabs.updateUrl(id, url, null)
                binding.swipeRefresh.isRefreshing = false
                if (url != "about:blank") {
                    val entity = tabs.list().firstOrNull { it.id == id }
                    if (entity?.incognito != true) {
                        HistoryStore.get(this@MainActivity).record(url, entity?.title ?: "")
                    }
                }
                if (session === currentSession) updateUi()
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                if (session !== currentSession) return
                binding.loadingBar.visibility =
                    if (progress in 1..99) View.VISIBLE else View.GONE
                binding.loadingBar.progress = progress
            }

override fun onSecurityChange(session: GeckoSession, securityInfo: GeckoSession.ProgressDelegate.SecurityInformation) {
}

override fun onSessionStateChange(session: GeckoSession, sessionState: GeckoSession.SessionState) {
    val id = tabs.idForSession(session)
    if (id > 0L) {
        tabs.saveState(id, sessionState.toString())
    }
}
}

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                if (title.isNullOrBlank()) return
                val id = tabs.idForSession(session)
                if (id <= 0L) return
                val entity = tabs.list().firstOrNull { it.id == id }
                val url = entity?.url ?: ""
                tabs.updateUrl(id, url, title)
            }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                val headers = response.headers
                val disposition = headers["Content-Disposition"] ?: headers["content-disposition"]
                val contentType = headers["Content-Type"] ?: headers["content-type"]
                val cleanContentType = contentType?.lowercase()?.trim() ?: ""

                val isHtml = cleanContentType.contains("text/html") || cleanContentType.contains("application/xhtml+xml")
                val isAttachment = disposition?.contains("attachment", ignoreCase = true) == true
                val hasFilename = disposition?.let { Regex("""filename\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(it) } ?: false
                if (isHtml && !isAttachment && !hasFilename) {
                    return
                }

                val filename = extractFilename(disposition) ?: "download_${System.currentTimeMillis()}"
                val userAgent = headers["User-Agent"] ?: headers["user-agent"]
                val body = response.body
                val contentLength = headers["Content-Length"]?.toLongOrNull() ?: headers["content-length"]?.toLongOrNull() ?: -1L

                if (body != null) {
                    if (SettingsPrefs.getBool(this@MainActivity, SettingsPrefs.K_ASK_DL, true)) {
                        showStreamDownloadConfirmationSheet(response.uri, filename, contentType, userAgent, body, contentLength)
                    } else {
                        DownloadEngine.startStreamDownload(this@MainActivity, filename, contentType ?: "application/octet-stream", body, contentLength, userAgent, currentUrl, response.uri)
                    }
                    } else {
                    if (SettingsPrefs.getBool(this@MainActivity, SettingsPrefs.K_ASK_DL, true)) {
                        showDownloadConfirmationSheet(response.uri, filename, contentType, userAgent)
                    } else {
                        executeDownload(response.uri, filename, contentType, userAgent)
                    }
                }
            }

            override fun onCrash(session: GeckoSession) {
                UHLog.e("gecko", "Tab crashed. Reloading.")
                session.reload()
            }

            override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, displayContext: GeckoSession.ContentDelegate.ContextElement) {
                if (displayContext.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE) {
                    val imageUrl = displayContext.srcUri
                    if (!imageUrl.isNullOrBlank()) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Image Options")
                            .setItems(arrayOf("Download Image", "Share Image", "Copy Image Link")) { _, which ->
                                when (which) {
                                    0 -> {
                                        val filename = "image_${System.currentTimeMillis()}.jpg"
                                        if (imageUrl.startsWith("blob:") || imageUrl.startsWith("data:")) {
                                            currentSession?.loadUri("javascript:fetch('$imageUrl').then(r=>r.blob()).then(b=>{var reader=new FileReader();reader.onload=function(){window.postMessage({type:'UH_NATIVE_CALL',payload:{type:'SAVE_BLOB',filename:'$filename',mime:b.type,base64:reader.result.split(',')[1]}},'*')};reader.readAsDataURL(b);})")
                                        } else if (SettingsPrefs.getBool(this@MainActivity, SettingsPrefs.K_ASK_DL, true)) {
                                            showDownloadConfirmationSheet(imageUrl, filename, "image/jpeg", null)
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
                                        Toast.makeText(this@MainActivity, "Link copied", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .show()
                    }
                }
            }
        }

        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onChoicePrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.ChoicePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                val choices = prompt.choices
                if (choices.isEmpty()) {
                    result.complete(prompt.dismiss())
                    return result
                }
                val labels = choices.map { it.label }.toTypedArray()
                runOnUiThread {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(prompt.message ?: "Select an option")
                        .setItems(labels) { _, which ->
                            result.complete(prompt.confirm(choices[which]))
                        }
                        .setOnCancelListener {
                            result.complete(prompt.dismiss())
                        }
                        .show()
                }
                return result
            }
        }

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                tabs.setNav(session, canGoBack, tabs.canGoForward(session))
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                tabs.setNav(session, tabs.canGoBack(session), canGoForward)
            }

            override fun onLocationChange(session: GeckoSession, url: String?, permissions: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                if (url != null) {
                    currentUrl = url
                }
            }

            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
                if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW && !request.hasUserGesture) {
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                val url = request.uri
                val scheme = Uri.parse(url).scheme ?: ""
                if (scheme == "intent") { 
                    handleIntentUrl(url)
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                if (scheme != "http" && scheme != "https" && scheme != "about" && scheme != "data" && scheme != "blob" && scheme != "file" && scheme != "javascript" && scheme != "moz-extension") {
                    UHLog.netScheme(url, "dialog")
                    askOpenExternal(session, url)
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                val lower = uri.lowercase()
                if (lower.contains(".jpg") || lower.contains(".png") || lower.contains(".jpeg") || lower.contains(".webp")) {
                    val filename = "image_${System.currentTimeMillis()}.jpg"
                    if (SettingsPrefs.getBool(this@MainActivity, SettingsPrefs.K_ASK_DL, true)) {
                        showDownloadConfirmationSheet(uri, filename, "image/jpeg", null)
                    } else {
                        executeDownload(uri, filename, "image/jpeg", null)
                    }
                    return GeckoResult.fromValue(null)
                }

                val parentId = tabs.idForSession(session).takeIf { it > 0L } ?: 0L
                val (id, newSession) = tabs.createUnopenedSession(parentId = parentId)
                popupTabs.add(id)
                UHLog.netPopupCreated(id)
                if (tabs.markAttached(newSession)) {
                    installDelegates(newSession)
                }
                
                tabs.setCurrentId(id)
                
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    currentSession?.setActive(false)
                    mainGeckoView.attachSession(newSession)
                    currentSession = newSession
                    currentUrl = "about:blank"
                    binding.homeScreen.visibility = View.GONE
                    updateUi()
                }
                
                return GeckoResult.fromValue(newSession)
            }
        }
    }

private fun showStreamDownloadConfirmationSheet(url: String, filename: String, mimetype: String?, userAgent: String?, stream: java.io.InputStream, contentLength: Long) {
    val sheet = BottomSheetDialog(this)
    val v = layoutInflater.inflate(R.layout.dialog_download_confirmation, null)
    v.setBackgroundResource(R.drawable.bg_bottom_sheet)
    sheet.setContentView(v)

    v.findViewById<android.widget.TextView>(R.id.dlFileName).text = filename
    
val sizeText = v.findViewById<android.widget.TextView>(R.id.dlFileSize)
if (contentLength > 0) {
    sizeText.text = android.text.format.Formatter.formatShortFileSize(this, contentLength)
} else {
    sizeText.visibility = View.GONE
    if (url.isNotBlank() && url != "stream") {
        Thread {
            try {
                val client = okhttp3.OkHttpClient.Builder().connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build()
                val reqBuilder = okhttp3.Request.Builder().url(url)
                    .header("User-Agent", userAgent ?: "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
                    .header("Range", "bytes=0-0")
                    .header("Accept-Encoding", "identity")
                val referer = currentUrl
                if (!referer.isNullOrBlank()) reqBuilder.addHeader("Referer", referer)
                val cookie = android.webkit.CookieManager.getInstance().getCookie(url)
                if (!cookie.isNullOrBlank()) reqBuilder.addHeader("Cookie", cookie)
                
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    var size = -1L
                    val contentRange = resp.header("Content-Range")
                    if (contentRange != null && contentRange.contains("/")) {
                        size = contentRange.substringAfterLast("/").toLongOrNull() ?: -1L
                    } else {
                        size = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                    }
                    runOnUiThread {
                        if (size > 0) {
                            sizeText.text = android.text.format.Formatter.formatShortFileSize(this@MainActivity, size)
                        } else {
                            sizeText.visibility = View.GONE
                        }
                    }
                }
            } catch (_: Exception) {
                runOnUiThread { sizeText.visibility = View.GONE }
            }
        }.start()
    } else {
        sizeText.visibility = View.GONE
    }
}
    
    v.findViewById<android.widget.TextView>(R.id.dlFileUrl).text = if (url == "stream") "Stream Download" else url
    
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

        v.findViewById<View>(R.id.dlStartBtn).setOnClickListener {
            sheet.dismiss()
            val finalName = v.findViewById<android.widget.TextView>(R.id.dlFileName).text.toString()
            DownloadEngine.startStreamDownload(this, finalName, mimetype ?: "application/octet-stream", stream, contentLength, userAgent, currentUrl, url)
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

        sheet.setOnCancelListener {
            try { stream.close() } catch (_: Exception) {}
        }

        sheet.show()
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
            val finalName = v.findViewById<android.widget.TextView>(R.id.dlFileName).text.toString()
            executeDownload(url, finalName, mimetype, userAgent)
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
                val client = okhttp3.OkHttpClient.Builder().connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build()
                val reqBuilder = okhttp3.Request.Builder().url(url)
                    .header("User-Agent", userAgent ?: "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
                    .header("Range", "bytes=0-0")
                    .header("Accept-Encoding", "identity")
                val referer = currentUrl
                if (!referer.isNullOrBlank()) reqBuilder.addHeader("Referer", referer)
                
                val cookie = android.webkit.CookieManager.getInstance().getCookie(url)
                if (!cookie.isNullOrBlank()) reqBuilder.addHeader("Cookie", cookie)
                
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    var size = -1L
                    val contentRange = resp.header("Content-Range")
                    if (contentRange != null && contentRange.contains("/")) {
                        size = contentRange.substringAfterLast("/").toLongOrNull() ?: -1L
                    } else {
                        size = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                    }
                    
                    runOnUiThread {
                        if (size > 0) {
                            v.findViewById<android.widget.TextView>(R.id.dlFileSize).text = android.text.format.Formatter.formatShortFileSize(this@MainActivity, size)
                        } else {
                            v.findViewById<android.widget.TextView>(R.id.dlFileSize).text = "Unknown size"
                        }
                    }
                }
            } catch (_: Exception) {
                runOnUiThread { v.findViewById<android.widget.TextView>(R.id.dlFileSize).text = "Unknown size" }
            }
        }.start()

        sheet.show()
    }

    private fun executeDownload(url: String, filename: String, mimetype: String?, userAgent: String?) {
        val referer = currentUrl
        DownloadEngine.startDownload(this, url, filename, mimetype ?: "application/octet-stream", null, userAgent, referer)
    }

    private fun extractFilename(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null
        val rx = Regex("""filename[^;=\n]*=((['"]).*?\2|[^;\n]*)""", RegexOption.IGNORE_CASE)
        val m = rx.find(contentDisposition) ?: return null
        return m.groupValues[1].trim().trim('"', '\'')
    }

    private fun switchTab(id: Long) {
        tabs.saveCurrentState()
        tabs.acquireSession(id)
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
        val sheet = BottomSheetDialog(this, R.style.Theme_UHCompanion_BottomSheet)
        val v = layoutInflater.inflate(R.layout.dialog_script_menu, null)
        v.setBackgroundResource(R.drawable.bg_bottom_sheet)
        sheet.setContentView(v)
        sheet.behavior.skipCollapsed = true
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        

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
        val url = currentUrl
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

val iconView = row.findViewById<android.widget.ImageView>(R.id.smsIcon)
iconView.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.accent))
if (script.iconUrl.isNotBlank()) {
    Thread {
        try {
            val bmp = java.net.URL(script.iconUrl).openStream().use { android.graphics.BitmapFactory.decodeStream(it) }
            if (bmp != null) {
                runOnUiThread {
                    iconView.setImageBitmap(bmp)
                    iconView.clearColorFilter()
                }
            }
        } catch (_: Exception) {}
    }.start()
}

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
                        currentSession?.loadUri("javascript:try{window.__uh_runMenuCommand('${cmd.id}')}catch(e){}")
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
            onNew = { exitEditMode(); attachCurrent(); updateUi() }
        ).apply {
            setOnDismissListener {
                val live = tabs.currentSession()
                if (live != null && live !== currentSession) { attachCurrent(); updateUi() }
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
    val sheet = BottomSheetDialog(this, R.style.Theme_UHCompanion_BottomSheet)
    val v = layoutInflater.inflate(R.layout.dialog_main_menu, null)
    v.setBackgroundResource(R.drawable.bg_bottom_sheet)
    sheet.setContentView(v)
    sheet.behavior.skipCollapsed = true
    sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
    
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
            currentSession?.reload()
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
                if (!target.isNullOrBlank()) { UHLog.netScheme(url, "unwrapped→$target"); currentSession?.loadUri(target); return }
            }
            val resolved = packageManager.queryIntentActivities(intent, 0)
            if (resolved.isNotEmpty()) { UHLog.netScheme(url, "external"); askOpenExternalIntent(intent, currentSession) }
            else {
                val fallback = intent.getStringExtra("browser_fallback_url")
                if (!fallback.isNullOrBlank()) { UHLog.netScheme(url, "fallback→$fallback"); currentSession?.loadUri(fallback) }
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

    private fun askOpenExternal(session: GeckoSession, url: String) {
        val isFresh = currentUrl.isNullOrBlank() || currentUrl == "about:blank"
        UHLog.netExtDialog(url)
        AlertDialog.Builder(this)
            .setTitle("Open in another app?").setMessage(url)
            .setPositiveButton("Open") { _, _ ->
                UHLog.netExtOpen(url)
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (_: Exception) { toast("No app can handle this link") }
                if (isFresh) closeBlankPopup(session)
            }
            .setNegativeButton("Stay") { _, _ ->
                UHLog.netExtStay(url)
                if (isFresh) closeBlankPopup(session)
            }
            .show()
    }

    private fun askOpenExternalIntent(intent: Intent, session: GeckoSession?) {
        val isFresh = session == null || currentUrl.isNullOrBlank() || currentUrl == "about:blank"
        val urlString = intent.data?.toString() ?: "External app link"
        UHLog.netExtDialog(urlString)
        AlertDialog.Builder(this)
            .setTitle("Open in another app?").setMessage(urlString)
            .setPositiveButton("Open") { _, _ ->
                UHLog.netExtOpen(urlString)
                try { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(intent) }
                catch (_: Exception) { toast("Failed to open app") }
                if (isFresh && session != null) closeBlankPopup(session)
            }
            .setNegativeButton("Stay") { _, _ ->
                UHLog.netExtStay(urlString)
                if (isFresh && session != null) closeBlankPopup(session)
            }
            .show()
    }

    private fun closeBlankPopup(session: GeckoSession) {
        val id = tabs.idForSession(session)
        if (id <= 0L) return
        popupTabs.remove(id)
        tabs.close(id)
        val remaining = tabs.list()
        if (remaining.isEmpty()) tabs.openNew("about:blank")
        else tabs.acquireSession(remaining.first().id)
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
        exitEditMode()
        attachCurrent(); updateUi()
    }

override fun onResume() {
    super.onResume()
    UpdateChecker.resumePendingInstallIfPossible(this)
    applyAddressBarPosition()
    currentSession?.setActive(true)
    currentSession?.setFocused(true)
    applyWallpaper()
    shortcutAdapter.submit(ShortcutStore.list(this))
    updateUi()
}

private fun applyWallpaper() {
    val wpPath = WallpaperStore.getCurrentWallpaper(this)
    val textColor = WallpaperStore.getTextColor(this)
    
if (wpPath == null || wpPath == "default") {
    binding.wallpaperBackground.visibility = View.GONE
    binding.homeScreen.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
    binding.homeTitle.setTextColor(ContextCompat.getColor(this, R.color.accent))
    binding.btnEditHome.setColorFilter(ContextCompat.getColor(this, R.color.text))
    shortcutAdapter.setTextColor(ContextCompat.getColor(this, R.color.text), 0)
} else {
    try {
        val bitmap = android.graphics.BitmapFactory.decodeFile(wpPath)
        if (bitmap != null) {
            binding.wallpaperBackground.setImageBitmap(bitmap)
            binding.wallpaperBackground.visibility = View.VISIBLE
            binding.homeScreen.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            
            val safeColor = if (textColor == 0) ContextCompat.getColor(this, R.color.text) else textColor
            val luminance = androidx.core.graphics.ColorUtils.calculateLuminance(safeColor)
            val strictBW = if (luminance > 0.5) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            val shadowColor = if (strictBW == android.graphics.Color.BLACK) android.graphics.Color.WHITE else android.graphics.Color.BLACK

            binding.homeTitle.setTextColor(strictBW)
            binding.homeTitle.setShadowLayer(8f, 0f, 2f, shadowColor)

val pencilDrawable = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_pencil)?.mutate()
pencilDrawable?.setTint(strictBW)
pencilDrawable?.setBounds(0, 0, pencilDrawable.intrinsicWidth, pencilDrawable.intrinsicHeight)

val padding = 16
val bmp = android.graphics.Bitmap.createBitmap(
    (pencilDrawable?.intrinsicWidth ?: 48) + padding * 2, 
    (pencilDrawable?.intrinsicHeight ?: 48) + padding * 2, 
    android.graphics.Bitmap.Config.ARGB_8888
)
val canvas = android.graphics.Canvas(bmp)

val mask = android.graphics.Bitmap.createBitmap(
    pencilDrawable?.intrinsicWidth ?: 48, 
    pencilDrawable?.intrinsicHeight ?: 48, 
    android.graphics.Bitmap.Config.ALPHA_8
)
val maskCanvas = android.graphics.Canvas(mask)
pencilDrawable?.draw(maskCanvas)

val shadowPaint = android.graphics.Paint()
shadowPaint.setShadowLayer(8f, 0f, 2f, shadowColor)
canvas.drawBitmap(mask, padding.toFloat(), padding.toFloat(), shadowPaint)

pencilDrawable?.setBounds(padding, padding, padding + (pencilDrawable.intrinsicWidth), padding + (pencilDrawable.intrinsicHeight))
pencilDrawable?.draw(canvas)

binding.btnEditHome.setImageBitmap(bmp)
binding.btnEditHome.background = null
binding.btnEditHome.setPadding(0, 0, 0, 0)
binding.btnEditHome.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE

shortcutAdapter.setTextColor(strictBW, shadowColor)
        } else {
                binding.wallpaperBackground.visibility = View.GONE
                binding.homeScreen.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
            }
        } catch (_: Exception) {
            binding.wallpaperBackground.visibility = View.GONE
            binding.homeScreen.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        }
    }
}
override fun onPause() {
    super.onPause()
    currentSession?.setActive(false)
    currentSession?.setFocused(false)
    if (::tabs.isInitialized) {
        tabs.saveAll()
    }
    UHLog.onActivityPause(this)
}

    override fun onDestroy() {
        if (::tabs.isInitialized) {
            tabs.saveAll()
        }
        MenuCommandStore.listener = null
        suggestJob?.cancel()
        super.onDestroy()
    }
}
