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


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UHLog.init(this)
        SessionRecorder.init(this)
        UHLog.section("MAIN")

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        tabs = TabManager.get(this)

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
        binding.btnBack.setOnClickListener {
            currentView?.let { if (it.canGoBack()) it.goBack() }
        }
        binding.btnForward.setOnClickListener {
            currentView?.let { if (it.canGoForward()) it.goForward() }
        }
        binding.urlBar.setOnClickListener { openUrlDialog() }
        binding.btnMenu.setOnClickListener { showMenu() }
        binding.tabCount.setOnClickListener { openTabGrid() }
    }

private var lastBackPress = 0L

private fun setupBackDispatcher() {
    onBackPressedDispatcher.addCallback(this) {
        val v = currentView
        val id = if (v != null) tabs.idForView(v) else -1L

        // 1. Popup tab? Close it.
        if (v != null && id in popupTabs) {
            closeBlankPopup(v)
            return@addCallback
        }

        // 2. WebView history?
        if (v != null && v.canGoBack()) {
            v.goBack()
            return@addCallback
        }

        // 3. Current page is a real site — return to home instead of exiting.
        val url = v?.url
        val isOnSite = !url.isNullOrBlank() && url != "about:blank" &&
            !url.startsWith("chrome-error://")
        if (isOnSite) {
            tabs.openNew("about:blank")
            attachCurrent()
            updateUi()
            return@addCallback
        }

        // 4. More than one tab?
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

        // 5. Already on home, last tab. Double-press to exit.
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
                val s = Shortcut(
                    name = name,
                    url = fixedUrl,
                    iconUrl = ShortcutStore.iconUrlFor(fixedUrl)
                )
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
        synchronized(page3p) {
            page3p.getOrPut(id) { mutableSetOf() }.add(host)
        }
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
        v.postDelayed({ refreshUserMenuFab() }, 500)
    }
    if (v === currentView) updateUi()
}

            override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
                tabs.setNav(v, v.canGoBack(), v.canGoForward())
                if (v === currentView) {
                    binding.btnBack.isEnabled = v.canGoBack()
                    binding.btnForward.isEnabled = v.canGoForward()
                }
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

    private fun switchTab(id: Long) {
        tabs.saveCurrentState()
        tabs.acquireView(id)
        attachCurrent()
        val url = tabs.list().firstOrNull { it.id == id }?.url ?: ""
        binding.urlText.text = if (url == "about:blank") "" else url
        updateUi()
    }

    private fun updateUi() {
        prunePopupTabs()
        val id = tabs.currentId()
        val url = tabs.list().firstOrNull { it.id == id }?.url ?: ""
        binding.urlText.text = if (url == "about:blank") "" else url
        binding.urlIcon.visibility = if (url.startsWith("https")) View.VISIBLE else View.INVISIBLE
        binding.btnBack.isEnabled = currentView?.canGoBack() ?: false
        binding.btnForward.isEnabled = currentView?.canGoForward() ?: false
        binding.tabCount.text = tabs.list().size.toString()
        updateHomeVisibility()
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

        v.findViewById<View>(R.id.btnNewTab)?.setOnClickListener {
            tabs.openNew("about:blank")
            attachCurrent()
            updateUi()
            sheet.dismiss()
        }
        v.findViewById<View>(R.id.btnReload)?.setOnClickListener {
            currentView?.reload()
            sheet.dismiss()
        }

        v.findViewById<View>(R.id.btnTools)?.setOnClickListener {
            sheet.dismiss()
            startActivity(Intent(this, ToolsActivity::class.java))
        }
        v.findViewById<View>(R.id.btnScripts)?.setOnClickListener {
            sheet.dismiss()
            startActivity(Intent(this, ScriptsActivity::class.java))
        }
        v.findViewById<View>(R.id.btnSettings)?.setOnClickListener {
            sheet.dismiss()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        v.findViewById<View>(R.id.btnLogs)?.setOnClickListener {
            sheet.dismiss()
            startActivity(Intent(this, LogsActivity::class.java))
        }
        sheet.show()
    }


    private fun handleIntentUrl(url: String) {
        try {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)

            val dataScheme = intent.data?.scheme ?: ""
            val schemeExtra = intent.scheme ?: ""
            val isWeb =
                dataScheme == "http" || dataScheme == "https" ||
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
                            startActivity(
                                Intent(Intent.ACTION_VIEW).apply {
                                    data = Uri.parse("market://details?id=$pkg")
                                }
                            )
                        } catch (_: Exception) {
                            Toast.makeText(this, "App not installed", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        UHLog.netScheme(url, "unresolved")
                        Toast.makeText(this, "App not installed", Toast.LENGTH_SHORT).show()
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
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) {
                    Toast.makeText(this, "No app can handle this link", Toast.LENGTH_SHORT).show()
                }
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
                } catch (_: Exception) {
                    Toast.makeText(this, "Failed to open app", Toast.LENGTH_SHORT).show()
                }
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
        if (remaining.isEmpty()) {
            tabs.openNew("about:blank")
        } else {
            tabs.acquireView(remaining.first().id)
        }
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

    private var userMenuFab: android.widget.ImageButton? = null

private fun refreshUserMenuFab() {
    val commands = MenuCommandStore.all()
    if (commands.isEmpty()) {
        userMenuFab?.visibility = View.GONE
        return
    }
    if (userMenuFab == null) {
        val size = (44 * resources.displayMetrics.density).toInt()
        val margin = (16 * resources.displayMetrics.density).toInt()
        val lp = android.widget.FrameLayout.LayoutParams(size, size).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            bottomMargin = margin
            rightMargin = margin
        }
        val fab = android.widget.ImageButton(this).apply {
            layoutParams = lp
            setImageResource(R.drawable.ic_menu)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFF22D3EE.toInt())
            }
            setColorFilter(0xFF0B0F14.toInt())
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(24, 24, 24, 24)
            setOnClickListener { showUserMenuCommands() }
        }
        (binding.root as? android.view.ViewGroup)?.addView(fab)
        userMenuFab = fab
    }
    userMenuFab?.visibility = View.VISIBLE
}

private fun showUserMenuCommands() {
    val commands = MenuCommandStore.all()
    if (commands.isEmpty()) return
    val names = commands.map { it.name }.toTypedArray()
    AlertDialog.Builder(this)
        .setTitle("Userscript commands")
        .setItems(names) { _, which ->
            val cmd = commands[which]
            currentView?.evaluateJavascript(
                "try{window.__uh_runMenuCommand('${cmd.id}')}catch(e){}",
                null
            )
        }
        .show()
}

override fun onResume() {
    super.onResume()
    UpdateChecker.resumePendingInstallIfPossible(this)
    MenuCommandStore.listener = { runOnUiThread { refreshUserMenuFab() } }
    if (UserscriptStore.consumeDirty(this)) {
        tabs.list().forEach { _ ->
            // re-register on every alive view
        }
        val v = currentView
        if (v != null) UserscriptManager.registerDocumentStart(v, this)
    }
    }
override fun onPause() {
    super.onPause()
    MenuCommandStore.listener = null
    tabs.saveAll()
    UHLog.onActivityPause(this)
}

    override fun onDestroy() {
        tabs.saveAll()
        super.onDestroy()
    }
}
