package app.harvester.companion

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

@SuppressLint("SetJavaScriptEnabled")
class HarvesterWebView(context: Context) : WebView(context) {

    var onUrlChanged: ((String) -> Unit)? = null

    private val gmBridge = GmApiBridge(context)

    init {
        configure()
        addJavascriptInterface(gmBridge, "UH_GMBridge")
        gmBridge.attachWebView(this)
        installDocumentStartScript()
        installDownloads()
    }

    private fun configure() {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = true
        settings.allowContentAccess = true

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        webChromeClient = WebChromeClient()

        webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            UHLog.v("web", "page start ${url?.take(140)}")
            url?.let { onUrlChanged?.invoke(it) }
        }
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            UHLog.d("web", "page done ${url?.take(140)}")
            url?.let { onUrlChanged?.invoke(it) }
        }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }
        }
    }

    private fun installDocumentStartScript() {
        val userScript = ScriptRepository(context).load() ?: return
        val combined = GmPolyfill.build() + "\n;\n" + userScript
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(this, combined, setOf("*"))
            } catch (_: Exception) {}
        } else {
            // Fallback for WebView < 83: inject on page finished
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    url?.let { onUrlChanged?.invoke(it) }
                    view?.evaluateJavascript(combined, null)
                }
            }
        }
    }

    private fun installDownloads() {
        setDownloadListener(DownloadListener { url, _, _, mime, _ ->
            DownloadHelper.saveFromUrl(context, url, mime)
        })
    }
}
