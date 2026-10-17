package app.harvester.companion

import android.annotation.SuppressLint
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

object WebViewEngine {

    @SuppressLint("SetJavaScriptEnabled")
    fun create(ctx: Context, incognito: Boolean = false): WebView {
        val themed = ContextThemeWrapper(ctx, R.style.Theme_UHCompanion)
        val view = WebView(themed)
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = !incognito
            databaseEnabled = !incognito
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = if (incognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
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
            if (!incognito) {
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
            }
        } catch (_: Throwable) {}
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(view.settings, false)
            }
        } catch (_: Throwable) {}
        val bridge = UserscriptBridge(ctx)
        view.addJavascriptInterface(bridge, "UH_BRIDGE")
        bridge.attach(view)
        view.tag = bridge
        return view
    }
    private fun isSystemDark(ctx: Context): Boolean {
    return try {
        (ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    } catch (_: Exception) { false }
    }
    fun applyDarkMode(view: WebView, dark: Boolean) {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(view.settings, dark)
            }
        } catch (_: Throwable) {}
    }
}
