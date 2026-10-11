package app.harvester.companion

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.harvester.companion.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var webView: HarvesterWebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UHLog.init(this)
        UHLog.section("MAIN")
        UHLog.d("main", "onCreate intent=${intent?.data}")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupFab()
        installBackHandler()

        lifecycleScope.launch {
            val repo = ScriptRepository(this@MainActivity)
            if (repo.load() == null) {
                val url = AppPrefs.scriptUrl(this@MainActivity)
                if (url.isNotBlank()) {
                    repo.download(url)
                }
            }
            if (repo.load() == null) {
                openSettings()
                return@launch
            }
            attachWebView(intent?.data?.toString() ?: "about:blank")
            repo.maybeAutoUpdate()
        }
    }

    private fun attachWebView(initialUrl: String) {
        webView?.let { binding.webContainer.removeView(it); it.destroy() }
        val wv = HarvesterWebView(this)
        wv.onUrlChanged = { url -> runOnUiThread { binding.urlBar.setText(url) } }
        binding.webContainer.addView(wv)
        webView = wv
        wv.loadUrl(initialUrl)
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            webView?.let { if (it.canGoBack()) it.goBack() }
        }
        binding.btnSettings.setOnClickListener { openSettings() }
        binding.urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                val url = normalizeUrl(binding.urlBar.text.toString())
                if (url.isNotEmpty()) webView?.loadUrl(url)
                true
            } else false
        }
    }

    private fun setupFab() {
        binding.fab.setOnClickListener {
            webView?.let { FloatingMenu.show(this, it, ::attachWebView) }
        }
    }

    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(this) {
            val wv = webView
            if (wv != null && wv.canGoBack()) {
                wv.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun normalizeUrl(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return ""
        if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:")) return t
        if (t.contains(".") && !t.contains(" ")) return "https://$t"
        return "https://www.google.com/search?q=" + Uri.encode(t)
    }

    override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    intent.data?.toString()?.let { webView?.loadUrl(it) }
    }

    override fun onResume() {
        super.onResume()
        val repo = ScriptRepository(this)
        val live = repo.version()
        val seen = AppPrefs.lastVersion(this)
        if (live != null && live != seen) {
            AppPrefs.setLastVersion(this, live)
            attachWebView(webView?.url ?: "about:blank")
        }
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
