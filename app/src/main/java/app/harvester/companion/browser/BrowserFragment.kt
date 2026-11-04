package app.harvester.companion.browser

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.Fragment
import app.harvester.companion.UHApp
import app.harvester.companion.browser.toolbar.HarvesterAddressBar
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.feature.session.SessionFeature
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper

class BrowserFragment : Fragment() {

    private val sessionFeature = ViewBoundFeatureWrapper<SessionFeature>()
    private var initialUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialUrl = arguments?.getString("url")
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

                app.harvester.companion.browser.BrowserScreen(
                    browserState = browserState,
                    sessionFeature = sessionFeature,
                    fragment = this@BrowserFragment,
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
