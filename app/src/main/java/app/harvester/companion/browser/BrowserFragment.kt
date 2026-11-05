package app.harvester.companion.browser

import android.content.Context
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
import androidx.fragment.app.viewModels
import app.harvester.companion.UHApp
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.feature.downloads.DownloadsFeature
import mozilla.components.feature.session.SessionFeature
import mozilla.components.support.utils.DefaultDownloadFileUtils
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension

class BrowserFragment : Fragment() {

    private val sessionFeature = ViewBoundFeatureWrapper<SessionFeature>()
    private val downloadsFeature = ViewBoundFeatureWrapper<DownloadsFeature>()
    private val viewModel: BrowserViewModel by viewModels()
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
        if (state != null && state.content.canGoBack) {
            components.useCases.sessionUseCases.goBack.invoke()
            return true
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
                val isEditing by viewModel.isEditing.collectAsState()
                val editText by viewModel.editText.collectAsState()
                val suggestions by viewModel.suggestions.collectAsState()

                LaunchedEffect(Unit) {
                    if (browserState.tabs.isEmpty() && initialUrl != null) {
                        components.useCases.tabsUseCases.addTab(initialUrl!!)
                    }
                }

                LaunchedEffect(browserState.selectedTabId) {
                    val tab = browserState.selectedTab
                    if (tab != null) {
                        setupFeatures(tab.id)
                    }
                }

                BrowserScreen(
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

    private fun setupFeatures(tabId: String) {
        val context = context ?: return
        val components = (context.applicationContext as UHApp).components
        
if (downloadsFeature.get() == null) {
    downloadsFeature.set(
        feature = DownloadsFeature(
            applicationContext = context.applicationContext,
            store = components.store,
            useCases = mozilla.components.feature.downloads.DownloadsUseCases(components.store),
            fragmentManager = childFragmentManager,
            tabId = tabId,
            downloadFileUtils = DefaultDownloadFileUtils(context.applicationContext)
        ),
        owner = this,
        view = requireView()
    )
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
