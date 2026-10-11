package app.harvester.companion

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError
import java.util.WeakHashMap

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var view: GeckoView
    private lateinit var tabs: TabManager
    private var session: GeckoSession? = null
    private var stripAdapter: StripAdapter? = null

    // B5: install delegates at most once per (Activity, session) pair.
    // Weak keys so evicted sessions don't leak.
    private val instrumented = WeakHashMap<GeckoSession, Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UHLog.init(this)
        UHLog.section("MAIN")

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        view = binding.geckoView
        tabs = TabManager.get(this)

        binding.btnBack.isEnabled = false
        binding.btnBack.setOnClickListener {
            val s = session
            if (s != null && tabs.canGoBack(s)) s.goBack()
        }
        binding.urlBar.setOnClickListener { openUrlDialog() }
        binding.btnMenu.setOnClickListener { showMenu() }
        binding.tabCount.setOnClickListener { openTabGrid() }

        binding.tabStrip.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        stripAdapter = StripAdapter { id -> switchTab(id) }
        binding.tabStrip.adapter = stripAdapter

        // B4: system back → page history first, exit only at root.
        onBackPressedDispatcher.addCallback(this) {
            val s = session
            if (s != null && tabs.canGoBack(s)) s.goBack() else finish()
        }

        val existing = tabs.list()
        val startId = if (existing.isEmpty()) {
            tabs.openNew(intent?.data?.toString() ?: "about:blank")
        } else {
            existing.first().id
        }
        val s = tabs.switchTo(startId)
        attachSession(s)
        val url = tabs.list().firstOrNull { it.id == startId }?.url ?: ""
        updateUrlText(url)
        refreshStrip()
    }

    private fun attachSession(s: GeckoSession?) {
        if (s == null) return
        if (!instrumented.containsKey(s)) {
            installDelegates(s)
            instrumented[s] = true
        }
        view.setSession(s)
        session = s
        binding.btnBack.isEnabled = tabs.canGoBack(s)
    }

    private fun installDelegates(s: GeckoSession) {
        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, value: Boolean) {
                tabs.setCanGoBack(session, value)
                if (session === this@MainActivity.session) {
                    runOnUiThread { binding.btnBack.isEnabled = value }
                }
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                val url = request.uri ?: return GeckoResult.fromValue(AllowOrDeny.ALLOW)

                if (request.target === GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                    val id = tabs.openNew(url)
                    runOnUiThread { switchTab(id) }
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }

                if (url.startsWith("intent:")) {
                    try {
                        startActivity(Intent.parseUri(url, Intent.URI_INTENT_SCHEME))
                    } catch (_: Exception) {
                        val fallback = try {
                            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                                .getStringExtra("browser_fallback_url")
                        } catch (_: Exception) { null }
                        if (!fallback.isNullOrBlank()) session.loadUri(fallback)
                    }
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }

                val http = url.startsWith("http://") || url.startsWith("https://")
                val internal = url.startsWith("about:") || url.startsWith("data:") ||
                    url.startsWith("moz-extension:") || url.startsWith("blob:") ||
                    url.startsWith("file:")
                if (!http && !internal) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {}
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                UHLog.w("nav", "load error code=${error.code} uri=$uri")
                return null
            }
        }

        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            // B1: key callbacks on the firing session, never on currentId().
            override fun onPageStart(session: GeckoSession, url: String) {
                UHLog.v("web", "start ${url.take(140)}")
                val id = tabs.idForSession(session)
                if (id > 0) {
                    tabs.updateUrl(id, url, null)
                    runOnUiThread {
                        if (session === this@MainActivity.session) updateUrlText(url)
                        refreshStrip()
                    }
                }
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                UHLog.d("web", "stop success=$success")
            }

            override fun onSessionStateChange(
                session: GeckoSession,
                sessionState: GeckoSession.SessionState
            ) {
                val id = tabs.idForSession(session)
                if (id > 0) tabs.recordState(id, sessionState)
            }
        }

        s.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                val id = tabs.idForSession(session)
                if (id > 0 && !title.isNullOrBlank()) {
                    val storedUrl = tabs.list().firstOrNull { it.id == id }?.url ?: ""
                    tabs.updateUrl(id, storedUrl, title)
                    runOnUiThread { refreshStrip() }
                }
            }
        }
    }

    private fun switchTab(id: Long) {
        if (id == tabs.currentId()) {
            refreshStrip()
            return
        }
        val s = tabs.switchTo(id) ?: return
        attachSession(s)
        val url = tabs.list().firstOrNull { it.id == id }?.url ?: ""
        updateUrlText(url)
        refreshStrip()
    }

    private fun updateUrlText(url: String) {
        binding.urlText.text = url.take(80)
        binding.urlIcon.visibility =
            if (url.startsWith("https")) View.VISIBLE else View.INVISIBLE
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
                val id = tabs.openNew("about:blank")
                switchTab(id)
            }
        ).apply {
            // B3: after per-tab close or close-all, current session may differ.
            setOnDismissListener {
                val live = tabs.currentSession()
                if (live != null && live !== session) {
                    attachSession(live)
                    val url = tabs.list().firstOrNull { it.id == tabs.currentId() }?.url ?: ""
                    updateUrlText(url)
                }
                refreshStrip()
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
                if (target.isNotBlank()) {
                    session?.loadUri(target)
                    binding.urlText.text = target.take(80)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun normalize(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return ""
        if (t.startsWith("http://") || t.startsWith("https://") ||
            t.startsWith("about:") || t.startsWith("moz-extension:")) return t
        if (t.contains(".") && !t.contains(" ")) return "https://$t"
        return "https://html.duckduckgo.com/html/?q=" + Uri.encode(t)
    }

    private fun showMenu() {
        val popup = PopupMenu(this, binding.btnMenu)
        popup.menu.add(0, 1, 0, "New Tab")
        popup.menu.add(0, 2, 1, "Reload")
        popup.menu.add(0, 3, 2, "Extensions")
        popup.menu.add(0, 4, 3, "Settings")
        popup.menu.add(0, 5, 4, "Logs")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    val id = tabs.openNew("about:blank")
                    switchTab(id)
                    true
                }
                2 -> { session?.reload(); true }
                3 -> { startActivity(Intent(this, ExtensionsActivity::class.java)); true }
                4 -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
                5 -> { startActivity(Intent(this, LogsActivity::class.java)); true }
                else -> false
            }
        }
        popup.show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val incoming = intent.data?.toString()
        if (incoming.isNullOrBlank()) return
        val id = tabs.openNew(incoming)
        switchTab(id)
    }

    override fun onPause() {
        super.onPause()
        tabs.saveAll()
    }

    override fun onDestroy() {
        tabs.saveAll()
        super.onDestroy()
    }

    private class StripAdapter(
        private val onTap: (Long) -> Unit
    ) : RecyclerView.Adapter<StripAdapter.VH>() {

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
            // NOTE: strip close button still has no handler — flagged separately.
        }

        override fun getItemCount(): Int = items.size

        fun update(list: List<TabEntity>, currentId: Long) {
            items = list
            current = currentId
            notifyDataSetChanged()
        }
    }
}
