package app.harvester.companion.browser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.viewModels
import okhttp3.RequestBody.Companion.toRequestBody
import mozilla.components.browser.engine.gecko.GeckoEngine
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import app.harvester.companion.UHApp
import app.harvester.companion.browser.toolbar.HarvesterAddressBar
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.feature.session.SessionFeature
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension

class BrowserFragment : Fragment() {

    private val sessionFeature = ViewBoundFeatureWrapper<SessionFeature>()
    private val viewModel: BrowserViewModel by viewModels()
    private var initialUrl: String? = null

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    initialUrl = arguments?.getString("url")
    setupWebExtensionBridge()
}

private fun setupWebExtensionBridge() {
    val context = context ?: return
    val components = (context.applicationContext as UHApp).components
    val runtime = (components.engine as? mozilla.components.browser.engine.gecko.GeckoEngine)?.runtime ?: return
    runtime.webExtensionController.list().accept({ extList ->
        val ext = extList?.find { it.id == "uh-bridge@harvester.app" }
        if (ext != null) {
            ext.setMessageDelegate(object : WebExtension.MessageDelegate {
                override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                    val json = message as? JSONObject ?: return null
                    val type = json.optString("type")
                    when (type) {
                        "GET_SCRIPTS" -> {
                            val url = json.optString("url")
                            val code = app.harvester.companion.UserscriptManager.buildInjectionScript(context, url)
                            val res = JSONObject()
                            res.put("scripts", code)
                            return GeckoResult.fromValue(res)
                        }
                        "SAVE_BLOB" -> {
                            val filename = json.optString("filename")
                            val mime = json.optString("mime")
                            val base64 = json.optString("base64")
                            app.harvester.companion.DownloadEngine.startDownload(context, "", filename, mime, base64, null, "")
                            return GeckoResult.fromValue("ok")
                        }
                        "GM_setValue" -> {
                            context.getSharedPreferences("uh_gm", android.content.Context.MODE_PRIVATE).edit().putString("${json.optString("script")}::${json.optString("key")}", json.optString("value")).apply()
                            return GeckoResult.fromValue("ok")
                        }
                        "GM_deleteValue" -> {
                            context.getSharedPreferences("uh_gm", android.content.Context.MODE_PRIVATE).edit().remove("${json.optString("script")}::${json.optString("key")}").apply()
                            return GeckoResult.fromValue("ok")
                        }
                        "GM_xmlhttpRequest" -> {
                            val geckoResult = GeckoResult<Any>()
                            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                try {
                                    val method = json.optString("method")
                                    val url = json.optString("url")
                                    val body = if (method in listOf("POST", "PUT", "PATCH")) json.optString("data").toRequestBody(null) else null
                                    val builder = okhttp3.Request.Builder().url(url).method(method, body)
                                    val resp = okhttp3.OkHttpClient().newCall(builder.build()).execute()
                                    val resObj = JSONObject()
                                    resObj.put("status", resp.code)
                                    resObj.put("responseText", resp.body?.string() ?: "")
                                    geckoResult.complete(resObj.toString())
                                } catch (e: Exception) {
                                    geckoResult.complete(JSONObject().put("status", 0).toString())
                                }
                            }
                            return geckoResult
                        }
                        "GM_download" -> {
                            app.harvester.companion.DownloadEngine.startDownload(context, json.optString("url"), json.optString("name"), "application/octet-stream", null, null, "")
                            return GeckoResult.fromValue("ok")
                        }
                        "GM_openInTab" -> {
                            components.useCases.tabsUseCases.addTab(json.optString("url"))
                            return GeckoResult.fromValue("ok")
                        }
                    }
                    return GeckoResult.fromValue("ok")
                }
            }, "uhApp")
        }
    }, { })
}

    fun loadUrl(url: String) {
        val context = context ?: return
        val components = (context.applicationContext as UHApp).components
        val target = if (url.startsWith("http")) url else app.harvester.companion.SearchEnginePrefs.getSearchUrl(context, url)
        components.useCases.sessionUseCases.loadUrl(target)
    }

    fun onBackPressed(): Boolean {
        val context = context ?: return false
        val components = (context.applicationContext as UHApp).components
        val state = components.store.state.selectedTab
        if (state != null) {
            if (state.content.canGoBack) {
                components.useCases.sessionUseCases.goBack.invoke()
                return true
            }
        }
        return false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                val context = LocalContext.current
                val components = (context.applicationContext as UHApp).components
                val browserState by components.store.stateFlow.collectAsState()
                val selectedTab = browserState.selectedTab

                LaunchedEffect(Unit) {
                    if (browserState.tabs.isEmpty() && initialUrl != null) {
                        components.useCases.tabsUseCases.addTab(initialUrl!!)
                    }
                }

                val isEditing by viewModel.isEditing.collectAsState()
                val editText by viewModel.editText.collectAsState()
                val suggestions by viewModel.suggestions.collectAsState()

                app.harvester.companion.browser.BrowserScreen(
                    browserState = browserState,
                    sessionFeature = sessionFeature,
                    fragment = this@BrowserFragment,
                    viewModel = viewModel,
                    isEditing = isEditing,
                    editText = editText,
                    suggestions = suggestions,
                    onNavigate = { url -> loadUrl(url) }
                )
            }
        }
    }

    companion object {
        fun newInstance(url: String) = BrowserFragment().apply {
            arguments = Bundle().apply {
                putString("url", url)
            }
        }
    }
}
