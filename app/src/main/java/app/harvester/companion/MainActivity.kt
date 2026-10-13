package app.harvester.companion

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import app.harvester.companion.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tabs: TabManager
    private lateinit var shortcutAdapter: ShortcutAdapter
    private var currentView: WebView? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { installScriptFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UHLog.init(this)
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

    private fun setupBackDispatcher() {
        onBackPressedDispatcher.addCallback(this) {
            val v = currentView
            if (v != null && v.canGoBack()) v.goBack() else finish()
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
    val url = tabs.list().firstOrNull { it.id == id }?.url
    val isBlank = url.isNullOrBlank() || url == "about:blank"
    binding.homeScreen.visibility = if (isBlank) View.VISIBLE else View.GONE
    binding.webContainer.visibility = if (isBlank) View.GONE else View.VISIBLE
}

    @SuppressLint("SetJavaScriptEnabled")
    private fun installClients(view: WebView) {
        view.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                v: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                try {
                    val url = request.url.toString()
                    if (request.method == "GET" || request.method == "POST") {
                        val hdrs = request.requestHeaders ?: emptyMap()
                        if (hdrs.isNotEmpty()) {
                            val sb = StringBuilder()
                            sb.append(request.method).append(' ').append(url).append('\n')
                            for ((k, value) in hdrs) {
                                sb.append(k).append(": ").append(value).append('\n')
                            }
                            UHLog.v("net", sb.toString().trim())
                        }
                    }
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

                askOpenExternal(v, url)
                return true
            }

            override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                UHLog.d("web", "start ${url.take(140)}")
                tabs.updateUrl(tabs.currentId(), url, null)
                if (v === currentView) {
                    binding.urlText.text = if (url == "about:blank") "" else url
                }
                UserscriptManager.injectOnPageStart(this@MainActivity, v, url)
                updateHomeVisibility()
            }

override fun onPageFinished(v: WebView, url: String) {
    UHLog.d("web", "finish ${url.take(140)}")
    if (v === currentView) {
        tabs.updateUrl(tabs.currentId(), url, null)
        updateUi()
    }
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
                val id = tabs.currentId()
                val url = tabs.list().firstOrNull { it.id == id }?.url ?: ""
                tabs.updateUrl(id, url, title)
            }

            override fun onCreateWindow(
                v: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
            ): Boolean {
                val id = tabs.openBlankTab()
                val newView = tabs.acquireView(id) ?: return false

                if (newView.getTag(R.id.webContainer) == null) {
                    installClients(newView)
                    newView.setTag(R.id.webContainer, true)
                }

                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = newView
                resultMsg.sendToTarget()

                attachCurrent()
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
        v.findViewById<View>(R.id.btnInstallScript)?.setOnClickListener {
            sheet.dismiss()
            promptInstallUserscript()
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

    private fun promptInstallUserscript() {
        val options = arrayOf("From URL", "From Clipboard", "From Local File")
        AlertDialog.Builder(this)
            .setTitle("Install userscript")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showUrlInstallDialog()
                    1 -> installFromClipboard()
                    2 -> filePickerLauncher.launch("*/*")
                }
            }
            .show()
    }

    private fun showUrlInstallDialog() {
        val input = EditText(this).apply {
            setHint("https://example.com/script.user.js")
            setTextColor(0xFFE6EDF5.toInt())
            setHintTextColor(0xFF8296AD.toInt())
            setPadding(32, 32, 32, 32)
            setSingleLine()
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("Install from URL")
            .setView(input)
            .setPositiveButton("Install") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotBlank()) downloadAndInstallScript(url)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun installFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text.toString()
            if (text.contains("@match") || text.contains("==UserScript==")) {
                val name = "script_" + System.currentTimeMillis()
                if (UserscriptManager.install(this, name, text)) {
                    Toast.makeText(this, "Installed from clipboard", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Clipboard is not a userscript", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun installScriptFromUri(uri: Uri) {
        try {
            val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (content != null) {
                val name = "script_" + System.currentTimeMillis()
                if (UserscriptManager.install(this, name, content)) {
                    Toast.makeText(this, "Script installed", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to read file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadAndInstallScript(url: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.connect()
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val name = "script_" + System.currentTimeMillis()
                    val ok = UserscriptManager.install(this@MainActivity, name, body)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            if (ok) "Script installed" else "Install failed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "HTTP ${conn.responseCode}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
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
                    currentView?.loadUrl(target)
                    return
                }
            }

            val resolved = packageManager.queryIntentActivities(intent, 0)
            if (resolved.isNotEmpty()) {
                askOpenExternalIntent(intent, currentView)
            } else {
                val fallback = intent.getStringExtra("browser_fallback_url")
                if (!fallback.isNullOrBlank()) {
                    currentView?.loadUrl(fallback)
                } else {
                    val pkg = intent.`package`
                    if (!pkg.isNullOrBlank()) {
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

        AlertDialog.Builder(this)
            .setTitle("Open in another app?")
            .setMessage(url)
            .setPositiveButton("Open") { _, _ ->
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
                if (isFreshPopup) closeBlankPopup(view)
            }
            .show()
    }

    private fun askOpenExternalIntent(intent: Intent, view: WebView?) {
        val isFreshPopup = view?.url.isNullOrBlank() || view?.url == "about:blank"
        AlertDialog.Builder(this)
            .setTitle("Open in another app?")
            .setMessage(intent.data?.toString() ?: "External app link")
            .setPositiveButton("Open") { _, _ ->
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(this, "Failed to open app", Toast.LENGTH_SHORT).show()
                }
                if (isFreshPopup) view?.let { closeBlankPopup(it) }
            }
            .setNegativeButton("Stay") { _, _ ->
                if (isFreshPopup) view?.let { closeBlankPopup(it) }
            }
            .show()
    }

    private fun closeBlankPopup(view: WebView) {
        val id = tabs.currentId()
        if (id <= 0L) return
        tabs.close(id)
        val remaining = tabs.list()
        if (remaining.isNotEmpty()) {
            tabs.acquireView(remaining.first().id)
            attachCurrent()
        }
        updateUi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val incoming = intent.data?.toString() ?: return
        tabs.openNew(incoming)
        attachCurrent()
        updateUi()
    }

override fun onResume() {
    super.onResume()
    UpdateChecker.resumePendingInstallIfPossible(this)
}

override fun onPause() {
    super.onPause()
    tabs.saveAll()
    UHLog.onActivityPause(this)
}

    override fun onDestroy() {
        tabs.saveAll()
        super.onDestroy()
    }
}
