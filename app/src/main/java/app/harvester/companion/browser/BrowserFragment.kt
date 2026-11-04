package app.harvester.companion.browser

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import app.harvester.companion.UHApp
import app.harvester.companion.browser.toolbar.HarvesterToolbar
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.feature.session.SessionFeature
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper

class BrowserFragment : Fragment() {

    private val sessionFeature = ViewBoundFeatureWrapper<SessionFeature>()

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
                    if (browserState.tabs.isEmpty()) {
                        components.useCases.tabsUseCases.addTab("https://mozilla.org")
                    }
                }

                MaterialTheme {
                    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0F1A)) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                
                                if (selectedTab?.content?.loading == true) {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth().height(2.dp),
                                        color = Color(0xFF457B9D),
                                        trackColor = Color(0xFF171B21)
                                    )
                                } else {
                                    Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(Color(0xFF0A0F1A)))
                                }

                                AndroidView(
                                    factory = { ctx ->
                                        val engineView = components.engine.createView(ctx)
                                        val view = engineView.asView()
                                        
                                        sessionFeature.set(
                                            feature = SessionFeature(
                                                store = components.store,
                                                goBackUseCase = components.useCases.sessionUseCases.goBack,
                                                goForwardUseCase = components.useCases.sessionUseCases.goForward,
                                                engineView = engineView,
                                                tabId = null
                                            ),
                                            owner = this@BrowserFragment,
                                            view = view
                                        )
                                        
                                        view
                                    },
                                    modifier = Modifier.weight(1f)
                                )

                                HarvesterToolbar(
                                    url = selectedTab?.content?.url ?: "",
                                    onNavigate = { url ->
                                        components.useCases.sessionUseCases.loadUrl(url)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
