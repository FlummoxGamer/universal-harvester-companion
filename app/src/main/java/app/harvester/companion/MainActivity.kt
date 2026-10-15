package app.harvester.companion

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import app.harvester.companion.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tabs: TabManager
    private lateinit var shortcutAdapter: ShortcutAdapter
    private var currentView: WebView? = null

    private val popupTabs = mutableSetOf<Long>()
    private val page3p = mutableMapOf<Long, MutableSet<String>>()
    private val recentUrls = LinkedHashMap<String, Long>()
    private var lastBackPress = 0L
    private var downloadSheet: BottomSheetDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UHLog.init(this)
        SessionRecorder.init(this)
        UHLog.section("MAIN")

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        tabs = TabManager.get(this)

        DownloadStore.ensureInstalled(this)
        DownloadStore.listener = { runOnUiThread { refreshDownloadSheet() } }
        
        setupHomeGrid()
        setupBottomBar()
        setupBackDispatcher()

        val existing = tabs.list()
        if (existing.isEmpty()) {
            tabs.openNew("about:blank")
        } else {
            tabs.acquireView(existing.first().id)
        }
        attachCurrent()
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

    private fun setupBottomBar() {
        binding.urlBar.setOnClickListener { openUrlDialog() }
        binding.btnMenu.setOnClickListener { showMenu() }
        binding.tabCount.setOnClickListener { openTabGrid() }
        binding.btnScriptMenu.setOnClickListener { showScriptMenuSheet() }
        
    }

    private fun setupBackDispatcher() {
        onBackPressedDispatcher.addCallback(this) {
            val v = currentView
            val id = if (v != null) tabs.idForView(v) else -1L

            if (v != null && id in popupTabs) {
                closeBlankPopup(v)
                return@addCallback
            }
            if (v != null && v.canGoBack()) {
                v.goBack()
                return@addCallback
            }
            val url = v?.url
            val isOnSite = !url.isNullOrBlank() && url != "about:blank" &&
                !url.startsWith("chrome-error://")
            if (isOnSite) {
                tabs.openNew("about:blank")
                attachCurrent()
                updateUi()
                return@addCallback
            }
            val all = tabs.list()
            if (all.size > 1) {
                val closeId = tabs.currentId()
                popupTabs.remove(closeId)
                tabs.close(closeId)
                val remaining = tabs.list()
                if (remaining.isNotEmpty()) {
                    tabs.acquireView(remaining.first().id)
                    attachCurrent()
                    updateUi()
                }
                return@addCallback
            }
            val now = System.currentTimeMillis()
            if (now - lastBackPress < 2000L) {
                finish()
            } else {
                lastBackPress = now
                Toast.makeText(this@MainActivity, "Press back again to exit", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAddShortcutDialog() {
        val input = EditText(this).apply {
            setHint("https://example.com")
            setTextColor(0xFFE6EDF5.toInt())
            setHintTextColor(0xFF8296AD.toInt())
            setPadding(32, 32, 32, 32)
            setSingleLine()
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("Add shortcut")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val raw = input.text.toString().trim()
                if (raw.isBlank()) return@setPositiveButton
                val fixedUrl = if (raw.startsWith("http")) raw else "https://$raw"
                val name = try {
                    java.net.URI(fixedUrl).host?.removePrefix("www.") ?: fixedUrl
                } catch (_: Exception) { fixedUrl }
                val s = Shortcut(name, fixedUrl, ShortcutStore.iconUrlFor(fixedUrl))
                ShortcutStore.add(this, s)
                shortcutAdapter.submit(ShortcutStore.list(this))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRemoveShortcut(s: Shortcut) {
        AlertDialog.Builder(this)
            .setTitle("Remove ${s.name}?")
            .setPositiveButton("Remove") { _, _ ->
                ShortcutStore.remove(this, s.url)
                shortcutAdapter.submit(ShortcutStore.list(this))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadUrlFromHome(url: String) {
        var view = currentView
        if (view == null || view.url == "about:blank") {
            tabs.openNew(url)
            attachCurrent()
            view = currentView
        }
        view?.loadUrl(url)
        updateHomeVisibility()
    }

    private fun attachCurrent() {
        val view = tabs.currentView() ?: return
        if (view === currentView) {
            updateHomeVisibility()
            return
        }
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
            binding.webContainer.visibility = View.VISIBLE
            return
        }
        val url = tabs.list().firstOrNull { it.id == id }?.url
        val isBlank = url.isNullOrBlank() || url == "about:blank"
        binding.homeScreen.visibility = if (isBlank) View.VISIBLE else View.GONE
        binding.webContainer.visibility = if (isBlank) View.GONE else View.VISIBLE
    }

    private fun prunePopupTabs() {
        val live = tabs.list().map { it.id }.toSet()
        popupTabs.retainAll(live)
    }

    private fun isStaticAsset(url: String, headers: Map<String, String>?): Boolean {
        val accept = headers?.entries?.firstOrNull { it.key.equals("Accept", true) }?.value ?: ""
        if (accept.contains("image/") || accept.contains("text/css") ||
            accept.contains("font/") || accept.contains("video/") || accept.contains("audio/")) {
            return true
        }
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val exts = listOf(
            ".js", ".mjs", ".css", ".map",
            ".woff", ".woff2", ".ttf", ".otf", ".eot",
            ".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".avif", ".ico", ".bmp"
        )
        return exts.any { path.endsWith(it) }
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
            while (it.hasNext()) {
                if (now - it.next().value > 2000L) it.remove()
            }
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
        val hosts = synchronized(page3p) {
            val set = page3p.remove(id) ?: return
            set.toList()
        }
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

            override fun shouldInterceptRequest(
                v: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                try {
                    val url = request.url.toString()
                    val method = request.method ?: "GET"
                    val headers = request.requestHeaders

                    if (request.isForMainFrame && url.isNotBlank() && url != "about:blank") {
                        val id = tabs.idForView(v)
                        if (id > 0L) {
                            tabs.updateUrl(id, url, null)
                            if (v === currentView) v.post { updateHomeVisibility() }
                        }
                        val ref = headers?.entries?.firstOrNull { it.key.equals("Referer", true) }?.value
                        val hasCookie = headers?.keys?.any { it.equals("Cookie", true) } == true
                        val hasAuth = headers?.keys?.any { it.equals("Authorization", true) } == true
                        UHLog.netNav(method, url, ref, hasCookie, hasAuth)
                        if (SessionRecorder.isActive() && SessionRecorder.isEnabled("header")) {
                            SessionRecorder.record("header") {
                                put("url", url)
                                put("method", method)
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
                    val isApi = accept.contains("json", true)
                            || url.contains("/api/")
                            || headers?.keys?.any { it.equals("X-Requested-With", true) } == true

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

            override fun shouldOverrideUrlLoading(
                v: WebView, request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                val scheme = request.url.scheme ?: ""

                if (scheme == "intent") {
                    handleIntentUrl(url)
                    return true
                }
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
                    binding.urlText.text = if (url == "about:blank") "" else url
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
                if (url != "about:blank") {
                    UserscriptManager.injectOnPageFinished(this@MainActivity, v, url)
                }
                if (v === currentView) updateUi()
            }

            override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
                tabs.setNav(v, v.canGoBack(), v.canGoForward())
            }

            override fun onReceivedError(
                v: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    UHLog.w("web", "error ${error.errorCode} ${request.url}")
                }
            }

            override fun onReceivedSslError(
                v: WebView, handler: SslErrorHandler, error: android.net.http.SslError
            ) {
                handler.cancel()
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, newProgress: Int) {
                if (v !== currentView) return
                binding.pageProgress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                binding.pageProgress.progress = newProgress
            }

            override fun onReceivedTitle(v: WebView, title: String?) {
                if (title.isNullOrBlank()) return
                val id = tabs.idForView(v)
                if (id <= 0L) return
                val url = tabs.list().firstOrNull { it.id == id }?.url ?: ""
                tabs.updateUrl(id, url, title)
            }

            override fun onCreateWindow(
                v: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
            ): Boolean {
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
                binding.webContainer.visibility = View.VISIBLE
                binding.homeScreen.visibility = View.GONE
                currentView = newView

                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = newView
                resultMsg.sendToTarget()

                updateUi()
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }
        }
    }

private fun updateUi() {
    prunePopupTabs()
    val id = tabs.currentId()
    val url = tabs.list().firstOrNull { it.id == id }?.url ?: ""
    val isEmpty = url.isBlank() || url == "about:blank"
    binding.urlText.text = if (isEmpty) "Search or type URL" else url
    binding.urlText.setTextColor(if (isEmpty) 0x8A96A8.toInt() else 0xFFE6E9EE.toInt())
    binding.urlIcon.setImageResource(
        if (url.startsWith("https")) R.drawable.ic_lock else R.drawable.ic_lock_open
    )
    binding.tabCount.text = tabs.list().size.toString()
    binding.btnScriptMenu.visibility = View.VISIBLE
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
        val url = currentView?.url ?: ""
        val host = try { java.net.URI(url).host ?: url } catch (_: Exception) { url.ifBlank { "No page" } }
        val scripts = matchingScriptsForPage(url)

        val sheet = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.dialog_script_menu, null)
        sheet.setContentView(v)

        v.findViewById<android.widget.TextView>(R.id.scriptMenuDomain)?.text = host
        val list = v.findViewById<android.widget.LinearLayout>(R.id.scriptMenuList)

        if (scripts.isEmpty()) {
            val empty = android.widget.TextView(this).apply {
                text = "No scripts match this page.\nInstall or enable scripts from Manage all scripts."
                setTextColor(0xFF8A96A8.toInt())
                textSize = 12f
                setPadding(0, 20, 0, 20)
            }
            list.addView(empty)
        } else {
            for (script in scripts) {
                val row = layoutInflater.inflate(R.layout.item_script_menu_script, list, false)
                row.findViewById<android.widget.TextView>(R.id.smsName).text =
                    script.displayName.ifBlank { script.name }
                val sw = row.findViewById<android.widget.Switch>(R.id.smsEnabled)
                sw.isChecked = true
                sw.setOnCheckedChangeListener { _, checked ->
                    UserscriptStore.setEnabled(this, script.name, checked)
                    if (!checked) row.findViewById<View>(R.id.smsCommands).visibility = View.GONE
                    else row.findViewById<View>(R.id.smsCommands).visibility = View.VISIBLE
                }

                val cmds = row.findViewById<android.widget.LinearLayout>(R.id.smsCommands)
                val scriptCommands = MenuCommandStore.forScript(script.name)
                if (scriptCommands.isEmpty()) {
                    val empty = android.widget.TextView(this).apply {
                        text = "No menu commands registered"
                        setTextColor(0xFF8A96A8.toInt())
                        textSize = 11f
                        setPadding(0, 4, 0, 4)
                    }
                    cmds.addView(empty)
                } else {
                    for (cmd in scriptCommands) {
                        val cv = layoutInflater.inflate(R.layout.item_script_menu_command, cmds, false)
                        cv.findViewById<android.widget.TextView>(R.id.smcLabel).text = cmd.name
                        cv.setOnClickListener {
                            currentView?.evaluateJavascript(
                                "try{window.__uh_runMenuCommand('${cmd.id}')}catch(e){}",
                                null
                            )
                            sheet.dismiss()
                        }
                        cmds.addView(cv)
                    }
                }
                list.addView(row)
            }
        }

        v.findViewById<android.widget.Button>(R.id.scriptMenuAll)?.setOnClickListener {
            sheet.dismiss()
            startActivity(Intent(this, ScriptsActivity::class.java))
        }

        sheet.show()
    }

    private fun openTabGrid() {
        TabGridDialog(
            this, tabs,
            onSwitch = { id -> switchTab(id) },
            onNew = {
                tabs.openNew("about:blank")
                attachCurrent()
                updateUi()
            }
        ).apply {
            setOnDismissListener {
                val live = tabs.currentView()
                if (live != null && live !== currentView) {
                    attachCurrent()
                    updateUi()
                } else {
                    updateUi()
                }
            }
        }.show()
    }

    private fun openUrlDialog() {
        val input = EditText(this).apply {
            setText("")
            setTextColor(0xFFE6EDF5.toInt())
            setHint("Search or type URL")
            setHintTextColor(0xFF8296AD.toInt())
            setPadding(32, 32, 32, 32)
            setSingleLine()
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("Go to")
            .setView(input)
            .setPositiveButton("Go") { _, _ ->
                val target = normalize(input.text.toString().trim())
                if (target.isNotBlank()) {
                    currentView?.loadUrl(target)
                    updateHomeVisibility()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
    sheet.setContentView(v)

    v.findViewById<View>(R.id.menuFavorites)?.setOnClickListener {
        sheet.dismiss()
        toast("Favorites coming soon")
    }
    v.findViewById<View>(R.id.menuHistory)?.setOnClickListener {
        sheet.dismiss()
        toast("History coming soon")
    }
    v.findViewById<View>(R.id.menuDownloads)?.setOnClickListener {
        sheet.dismiss()
        openSystemDownloads()
    }
    v.findViewById<View>(R.id.menuPlaylist)?.setOnClickListener {
        sheet.dismiss()
        toast("Playlist coming soon")
    }
    v.findViewById<View>(R.id.menuSettings)?.setOnClickListener {
        sheet.dismiss()
        startActivity(Intent(this, SettingsActivity::class.java))
    }
    v.findViewById<View>(R.id.menuScripts)?.setOnClickListener {
        sheet.dismiss()
        startActivity(Intent(this, ScriptsActivity::class.java))
    }
    v.findViewById<View>(R.id.menuTools)?.setOnClickListener {
        sheet.dismiss()
        startActivity(Intent(this, ToolsActivity::class.java))
    }
    v.findViewById<View>(R.id.menuLogs)?.setOnClickListener {
        sheet.dismiss()
        startActivity(Intent(this, LogsActivity::class.java))
    }
    sheet.show()
}

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun openSystemDownloads() {
        try {
            startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (_: Exception) {
            toast("Downloads unavailable")
        }
    }

    private fun openGoogleTranslate() {
        val url = currentView?.url ?: return
        val target = "https://translate.google.com/translate?sl=auto&tl=en&u=" +
            java.net.URLEncoder.encode(url, "UTF-8")
        currentView?.loadUrl(target)
    }

    private fun showFindOnPageDialog() {
        val input = EditText(this).apply {
            setHint("Find on page")
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Find on Page")
            .setView(input)
            .setPositiveButton("Find") { _, _ ->
                val q = input.text.toString()
                if (q.isNotBlank()) {
                    currentView?.findAllAsync(q)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openDevTools() {
        // Chromium WebView exposes devtools via chrome://inspect on desktop.
        // Placeholder: show a small info dialog.
        AlertDialog.Builder(this)
            .setTitle("Developer Tools")
            .setMessage("Attach Chrome DevTools from a desktop machine via chrome://inspect while the device is connected over ADB.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun refreshDownloadSheet() {
        val active = DownloadStore.active()
        if (active.isEmpty()) {
            downloadSheet?.dismiss()
            downloadSheet = null
            return
        }
        if (downloadSheet == null) {
            val sheet = BottomSheetDialog(this)
            val v = layoutInflater.inflate(R.layout.dialog_downloads, null)
            sheet.setContentView(v)
            sheet.setOnDismissListener { downloadSheet = null }
            downloadSheet = sheet
            sheet.show()
        }
        downloadSheet?.findViewById<android.widget.LinearLayout>(R.id.dlList)?.let { rebuildDownloadList(it) }
    }

    private fun rebuildDownloadList(container: android.widget.LinearLayout) {
        container.removeAllViews()
        val items = DownloadStore.active()
        if (items.isEmpty()) {
            downloadSheet?.dismiss()
            downloadSheet = null
            return
        }
        for (d in items) {
            val row = layoutInflater.inflate(R.layout.item_download, container, false)
            row.findViewById<android.widget.TextView>(R.id.dlName).text = d.filename
            val pb = row.findViewById<android.widget.ProgressBar>(R.id.dlProgress)
            pb.isIndeterminate = d.total <= 0
            if (d.total > 0) {
                pb.max = 100
                pb.progress = ((d.done * 100) / d.total).toInt()
            }
            val pct = if (d.total > 0) "${(d.done * 100) / d.total}%" else "…"
            row.findViewById<android.widget.TextView>(R.id.dlPct).text = pct
            container.addView(row)
        }
    }

    private fun handleIntentUrl(url: String) {
        try {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            val dataScheme = intent.data?.scheme ?: ""
            val schemeExtra = intent.scheme ?: ""
            val isWeb = dataScheme == "http" || dataScheme == "https" ||
                schemeExtra == "http" || schemeExtra == "https"
            if (isWeb) {
                val target = intent.data?.toString()
                if (!target.isNullOrBlank()) {
                    UHLog.netScheme(url, "unwrapped→$target")
                    currentView?.loadUrl(target)
                    return
                }
            }
            val resolved = packageManager.queryIntentActivities(intent, 0)
            if (resolved.isNotEmpty()) {
                UHLog.netScheme(url, "external")
                askOpenExternalIntent(intent, currentView)
            } else {
                val fallback = intent.getStringExtra("browser_fallback_url")
                if (!fallback.isNullOrBlank()) {
                    UHLog.netScheme(url, "fallback→$fallback")
                    currentView?.loadUrl(fallback)
                } else {
                    val pkg = intent.`package`
                    if (!pkg.isNullOrBlank()) {
                        UHLog.netScheme(url, "market→$pkg")
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW).apply {
                                data = Uri.parse("market://details?id=$pkg")
                            })
                        } catch (_: Exception) { toast("App not installed") }
                    } else {
                        UHLog.netScheme(url, "unresolved")
                        toast("App not installed")
                    }
                }
            }
        } catch (e: Exception) {
            UHLog.throwable("nav", "intent parse failed: $url", e)
        }
    }

    private fun askOpenExternal(view: WebView, url: String) {
        val isFreshPopup = view.url.isNullOrBlank() || view.url == "about:blank"
        UHLog.netExtDialog(url)
        AlertDialog.Builder(this)
            .setTitle("Open in another app?")
            .setMessage(url)
            .setPositiveButton("Open") { _, _ ->
                UHLog.netExtOpen(url)
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { toast("No app can handle this link") }
                if (isFreshPopup) closeBlankPopup(view)
            }
            .setNegativeButton("Stay") { _, _ ->
                UHLog.netExtStay(url)
                if (isFreshPopup) closeBlankPopup(view)
            }
            .show()
    }

    private fun askOpenExternalIntent(intent: Intent, view: WebView?) {
        val isFreshPopup = view?.url.isNullOrBlank() || view?.url == "about:blank"
        val urlString = intent.data?.toString() ?: "External app link"
        UHLog.netExtDialog(urlString)
        AlertDialog.Builder(this)
            .setTitle("Open in another app?")
            .setMessage(urlString)
            .setPositiveButton("Open") { _, _ ->
                UHLog.netExtOpen(urlString)
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (_: Exception) { toast("Failed to open app") }
                if (isFreshPopup) view?.let { closeBlankPopup(it) }
            }
            .setNegativeButton("Stay") { _, _ ->
                UHLog.netExtStay(urlString)
                if (isFreshPopup) view?.let { closeBlankPopup(it) }
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
        attachCurrent()
        updateUi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val incoming = intent.data?.toString() ?: return
        val alreadyOpen = tabs.list().firstOrNull { it.url == incoming }
        if (alreadyOpen != null) {
            switchTab(alreadyOpen.id)
            return
        }
        tabs.openNew(incoming)
        attachCurrent()
        updateUi()
    }

override fun onResume() {
    super.onResume()
    UpdateChecker.resumePendingInstallIfPossible(this)
    MenuCommandStore.listener = { runOnUiThread { updateUi() } }
    if (UserscriptStore.consumeDirty(this)) {
        val v = currentView
        val u = v?.url
        if (v != null && !u.isNullOrBlank()) {
            UserscriptManager.injectOnPageStart(v, this, u)
        }
    }
    updateUi()
}

    override fun onPause() {
        super.onPause()
        tabs.saveAll()
        UHLog.onActivityPause(this)
    }

override fun onDestroy() {
    tabs.saveAll()
    DownloadStore.clearListeners()
    super.onDestroy()
}
}
