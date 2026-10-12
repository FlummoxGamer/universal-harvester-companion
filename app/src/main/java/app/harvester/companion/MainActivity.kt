package app.harvester.companion

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.LayoutInflater
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
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.harvester.companion.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tabs: TabManager
    private var currentView: WebView? = null
    private var stripAdapter: StripAdapter? = null

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

        binding.tabStrip.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        stripAdapter = StripAdapter { id -> switchTab(id) }
        binding.tabStrip.adapter = stripAdapter

        onBackPressedDispatcher.addCallback(this) {
            val v = currentView
            if (v != null && v.canGoBack()) v.goBack() else finish()
        }

        val existing = tabs.list()
        if (existing.isEmpty()) {
            tabs.openNew(intent?.data?.toString() ?: "about:blank")
        } else {
            tabs.currentId().let { }
            tabs.acquireView(existing.first().id)
        }
        attachCurrent()
        UHLog.d("main", "onCreate complete")
    }

    private fun attachCurrent() {
        val view = tabs.currentView() ?: return
        if (view === currentView) return

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
                if (v === currentView) binding.urlText.text = url.take(80)
                UserscriptManager.injectOnPageStart(this@MainActivity, v, url)
                refreshStrip()
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
                refreshStrip()
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
        binding.urlText.text = url.take(80)
        refreshStrip()
    }

    private fun updateUi() {
        val id = tabs.currentId()
        val entity = tabs.list().firstOrNull { it.id == id }
        val url = entity?.url ?: ""
        binding.urlText.text = url.take(80)
        binding.urlIcon.visibility = if (url.startsWith("https")) View.VISIBLE else View.INVISIBLE
        binding.btnBack.isEnabled = currentView?.canGoBack() ?: false
        binding.btnForward.isEnabled = currentView?.canGoForward() ?: false
        refreshStrip()
    }

    private fun refreshStrip() {
        val list = tabs.list()
        binding.tabCount.text = list.size.toString()
        binding.tabStrip.visibility = if (list.size > 1) View.VISIBLE else View.GONE
        stripAdapter?.update(list, tabs.currentId())
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
            setText(binding.urlText.text)
            setTextColor(0xFFE6EDF5.toInt())
            setHint("Search or URL")
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
                if (target.isNotBlank()) currentView?.loadUrl(target)
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
        return "https://html.duckduckgo.com/html/?q=" + Uri.encode(t)
    }

    private fun showMenu() {
        val popup = PopupMenu(this, binding.btnMenu)
        popup.menu.add(0, 1, 0, "New Tab")
        popup.menu.add(0, 2, 1, "Reload")
        popup.menu.add(0, 3, 2, "Install userscript")
        popup.menu.add(0, 4, 3, "Settings")
        popup.menu.add(0, 5, 4, "Logs")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { tabs.openNew("about:blank"); attachCurrent(); updateUi(); true }
                2 -> { currentView?.reload(); true }
                3 -> { promptInstallUserscript(); true }
                4 -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
                5 -> { startActivity(Intent(this, LogsActivity::class.java)); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun promptInstallUserscript() {
        val input = EditText(this).apply {
            setHint("// @match *://example.com/*\n// code...")
            setTextColor(0xFFE6EDF5.toInt())
            setHintTextColor(0xFF8296AD.toInt())
            setPadding(32, 32, 32, 32)
            minLines = 6
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(this)
            .setTitle("Install userscript")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val body = input.text.toString()
                if (body.isBlank()) return@setPositiveButton
                val name = "script_" + System.currentTimeMillis()
                if (UserscriptManager.install(this, name, body)) {
                    UHLog.d("userscript", "installed $name")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    private class StripAdapter(private val onTap: (Long) -> Unit) :
        RecyclerView.Adapter<StripAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.tabTitle)
            val close: ImageView = v.findViewById(R.id.tabClose)
        }

        private var items: List<TabEntity> = emptyList()
        private var current: Long = -1L

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_tab_strip, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val t = items[position]
            h.title.text = t.title.ifBlank {
                t.url.substringAfter("//").take(30).ifBlank { "New Tab" }
            }
            h.itemView.alpha = if (t.id == current) 1f else 0.65f
            h.itemView.setOnClickListener { onTap(t.id) }
        }

        override fun getItemCount(): Int = items.size

        fun update(list: List<TabEntity>, currentId: Long) {
            items = list
            current = currentId
            notifyDataSetChanged()
        }
    }
}
