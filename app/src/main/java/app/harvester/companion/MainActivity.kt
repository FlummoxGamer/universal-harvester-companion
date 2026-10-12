package app.harvester.companion

import android.annotation.SuppressLint
import android.content.ClipData
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

        binding.btnBack.setOnClickListener {
            currentView?.let { if (it.canGoBack()) it.goBack() }
        }
        binding.btnForward.setOnClickListener {
            currentView?.let { if (it.canGoForward()) it.goForward() }
        }
        binding.urlBar.setOnClickListener { openUrlDialog() }
        binding.btnMenu.setOnClickListener { showMenu() }
        binding.tabCount.setOnClickListener { openTabGrid() }

        binding.homeScreen.findViewById<View>(R.id.homeSearchIcon)?.setOnClickListener {
            loadUrlFromHome(SearchEnginePrefs.getHomeUrl(this))
        }
        binding.homeScreen.findViewWithTag<View>("btnExtensions")?.setOnClickListener {
            promptInstallUserscript()
        }
        binding.homeScreen.findViewWithTag<View>("btnX")?.setOnClickListener {
            loadUrlFromHome("https://x.com")
        }
        binding.homeScreen.findViewWithTag<View>("btnAdd")?.setOnClickListener {
            openUrlDialog()
        }

        onBackPressedDispatcher.addCallback(this) {
            val v = currentView
            if (v != null && v.canGoBack()) v.goBack() else finish()
        }

        val existing = tabs.list()
        if (existing.isEmpty()) {
            tabs.openNew("about:blank")
        } else {
            tabs.acquireView(existing.first().id)
        }
        attachCurrent()
        UHLog.d("main", "onCreate complete")
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
        val isBlank = currentView?.url == "about:blank" || currentView == null
        binding.homeScreen.visibility = if (isBlank) View.VISIBLE else View.GONE
        binding.webContainer.visibility = if (isBlank) View.GONE else View.VISIBLE
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun installClients(view: WebView) {
        view.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                v: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                try {
                    val url = request.url.toString()
                    if (request.method == "GET" || request.method == "POST") {
                        val hdrs = request.requestHeaders ?: emptyMap()
                        if (hdrs.isNotEmpty()) {
                            val sb = StringBuilder()
                            sb.append(request.method).append(' ').append(url).append('\n')
                            for ((k, value) in hdrs) sb.append(k).append(": ").append(value).append('\n')
                            UHLog.d("net", sb.toString().trim())
                        }
                    }
                } catch (_: Exception) {}
                return null
            }

            override fun shouldOverrideUrlLoading(
                v: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                if (url.startsWith("intent:")) {
                    try { startActivity(Intent.parseUri(url, Intent.URI_INTENT_SCHEME)) } catch (_: Exception) {}
                    return true
                }
                val internal = url.startsWith("http") || url.startsWith("https") ||
                        url.startsWith("about:") || url.startsWith("data:") ||
                        url.startsWith("blob:") || url.startsWith("file:")
                if (!internal) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
                    return true
                }
                return false
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
                val id = tabs.currentId()
                val url = tabs.list().firstOrNull { it.id == id }?.url ?: ""
                tabs.updateUrl(id, url, title)
            }

            override fun onCreateWindow(
                v: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
            ): Boolean {
                val id = tabs.openNew("about:blank")
                switchTab(id)
                val newView = tabs.currentView() ?: return false
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = newView
                resultMsg.sendToTarget()
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
        val entity = tabs.list().firstOrNull { it.id == id }
        val url = entity?.url ?: ""
        binding.urlText.text = if (url == "about:blank") "" else url
        binding.urlIcon.visibility = if (url.startsWith("https")) View.VISIBLE else View.INVISIBLE
        binding.btnBack.isEnabled = currentView?.canGoBack() ?: false
        binding.btnForward.isEnabled = currentView?.canGoForward() ?: false
        binding.tabCount.text = tabs.list().size.toString()
        binding.homeSearchIcon.setImageResource(SearchEnginePrefs.getIconRes(this))
        binding.homeSearchLabel.text = SearchEnginePrefs.getLabel(this)
        updateHomeVisibility()
    }

    private fun openTabGrid() {
        TabGridDialog(
            this,
            tabs,
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
        val bottomSheetDialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.dialog_main_menu, null)
        bottomSheetDialog.setContentView(sheetView)

        sheetView.findViewById<View>(R.id.btnSetDefault)?.setOnClickListener {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            startActivity(intent)
            bottomSheetDialog.dismiss()
        }

        sheetView.findViewById<View>(R.id.btnSettings)?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            bottomSheetDialog.dismiss()
        }

        sheetView.findViewById<View>(R.id.btnNewTab)?.setOnClickListener {
            tabs.openNew("about:blank")
            attachCurrent()
            updateUi()
            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.show()
    }

    private fun promptInstallUserscript() {
        val options = arrayOf("From URL", "From Clipboard", "From Local File")
        AlertDialog.Builder(this)
            .setTitle("Install userscript")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showUrlInstallDialog()
                    1 -> installFromClipboard()
                    2 -> filePickerLauncher.launch("text/javascript")
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
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text.toString()
            if (text.contains("@match") || text.contains("==UserScript==")) {
                val name = "script_" + System.currentTimeMillis()
                if (UserscriptManager.install(this, name, text)) {
                    Toast.makeText(this, "Installed from clipboard", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Clipboard does not contain a userscript", Toast.LENGTH_SHORT).show()
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
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connect()
                if (connection.responseCode in 200..299) {
                    val scriptContent = connection.inputStream.bufferedReader().use { it.readText() }
                    val name = "script_" + System.currentTimeMillis()
                    val success = UserscriptManager.install(this@MainActivity, name, scriptContent)
                    withContext(Dispatchers.Main) {
                        if (success) Toast.makeText(this@MainActivity, "Script installed", Toast.LENGTH_SHORT).show()
                        else Toast.makeText(this@MainActivity, "Install failed", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Download failed: HTTP ${connection.responseCode}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val incoming = intent.data?.toString() ?: return
        tabs.openNew(incoming)
        attachCurrent()
        updateUi()
    }

    override fun onPause() {
        super.onPause()
        tabs.saveAll()
    }

    override fun onDestroy() {
        tabs.saveAll()
        super.onDestroy()
    }
}
