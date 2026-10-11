package app.harvester.companion

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import app.harvester.companion.databinding.ActivityMainBinding
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var view: GeckoView
    private var session: GeckoSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UHLog.init(this)
        UHLog.section("MAIN")
        UHLog.d("main", "onCreate")

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        view = binding.geckoView

        binding.btnBack.setOnClickListener { session?.goBack() }
        binding.urlBar.setOnClickListener { openUrlDialog() }
        binding.btnMenu.setOnClickListener { showMenu() }
        binding.tabCount.setOnClickListener { /* batch 2 */ }

        openSession(intent?.data?.toString())
    }

    private fun openSession(initialUrl: String?) {
        session?.close()
        val s = GeckoEngine.newSession()
        installDelegates(s)
        view.setSession(s)
        session = s
        s.loadUri(initialUrl ?: "about:blank")
    }

    private fun installDelegates(s: GeckoSession) {
        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession, url: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>?, hasUserGesture: Boolean
            ) {
                runOnUiThread {
                    binding.urlText.text = url?.take(80) ?: ""
                    binding.urlIcon.visibility = if (url?.startsWith("https") == true) View.VISIBLE else View.GONE
                }
                UHLog.v("nav", "location ${url?.take(140)}")
            }

            override fun onLoadRequest(
                session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<GeckoSession.NavigationDelegate.LoadRequest>? {
                val url = request.uri
                if (url.startsWith("intent:")) {
                    try {
                        val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        startActivity(intent)
                        return GeckoResult.fromValue(null)
                    } catch (_: Exception) {
                        val fallback = try {
                            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                                .getStringExtra("browser_fallback_url")
                        } catch (_: Exception) { null }
                        if (!fallback.isNullOrBlank()) {
                            session.loadUri(fallback)
                            return GeckoResult.fromValue(null)
                        }
                    }
                }
                if (!url.startsWith("http") && !url.startsWith("about:") && !url.startsWith("data:") && !url.startsWith("moz-extension:")) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
                    return GeckoResult.fromValue(null)
                }
                return GeckoResult.fromValue(request)
            }

            override fun onLoadError(
                session: GeckoSession, uri: String?, error: WebRequestError
            ): GeckoResult<String>? {
                UHLog.w("nav", "load error code=${error.code} uri=$uri")
                return null
            }
        }

        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                UHLog.v("web", "start ${url.take(140)}")
            }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                UHLog.d("web", "stop success=$success")
            }
        }

        s.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                UHLog.v("web", "title $title")
            }
        }
    }

    private fun openUrlDialog() {
        val input = android.widget.EditText(this).apply {
            setText(binding.urlText.text)
            setTextColor(0xFFE6EDF5.toInt())
            setHint("Search or URL")
            setHintTextColor(0xFF8296AD.toInt())
            setPadding(32, 32, 32, 32)
            setSingleLine()
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Go to")
            .setView(input)
            .setPositiveButton("Go") { _, _ ->
                val raw = input.text.toString().trim()
                val target = normalize(raw)
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
        if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:") || t.startsWith("moz-extension:")) return t
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
                1 -> { openSession(null); true }
                2 -> { session?.reload(); true }
                3 -> {
                    startActivity(Intent(this, ExtensionsActivity::class.java))
                    true
                }
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
        intent.data?.toString()?.let { session?.loadUri(it) }
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }
}
