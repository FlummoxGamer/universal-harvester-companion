package app.harvester.companion.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.TextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import app.harvester.companion.UHApp
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
                val selectedTab = components.store.state.selectedTab

                LaunchedEffect(Unit) {
                    if (components.store.state.tabs.isEmpty()) {
                        components.useCases.tabsUseCases.addTab("https://mozilla.org")
                    }
                }

                var urlText by remember { mutableStateOf("") }

                LaunchedEffect(selectedTab?.content?.url) {
                    if (selectedTab?.content?.url != null) {
                        urlText = selectedTab.content.url
                    }
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    TextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        singleLine = true
                    )

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
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
