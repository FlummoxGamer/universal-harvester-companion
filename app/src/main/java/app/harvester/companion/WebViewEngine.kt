package app.harvester.companion

import android.annotation.SuppressLint
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

object WebViewEngine {

    @SuppressLint("SetJavaScriptEnabled")
    fun create(ctx: Context): WebView {
        val themed = ContextThemeWrapper(ctx, R.style.Theme_UHCompanion)
        val view = WebView(themed)
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            val ua = userAgentString
            if (ua != null) userAgentString = ua.replace("; wv", "")
        }
        try {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        } catch (_: Throwable) {}
        val bridge = UserscriptBridge(ctx)
        view.addJavascriptInterface(bridge, "UH_BRIDGE")
        bridge.attach(view)
        return view
    }
}
