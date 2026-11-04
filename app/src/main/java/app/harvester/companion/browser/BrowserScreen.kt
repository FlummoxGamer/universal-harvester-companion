package app.harvester.companion.browser

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import app.harvester.companion.UHApp
import app.harvester.companion.browser.toolbar.HarvesterAddressBar
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.feature.session.SessionFeature
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper

@Composable
fun BrowserScreen(
    browserState: BrowserState,
    sessionFeature: ViewBoundFeatureWrapper<SessionFeature>,
    fragment: Fragment,
    viewModel: BrowserViewModel,
    isEditing: Boolean,
    editText: String,
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    val components = (context.applicationContext as UHApp).components
    val selectedTab = browserState.selectedTab

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
                                owner = fragment,
                                view = view
                            )
                            
                            view
                        },
                        modifier = Modifier.weight(1f)
                    )

                    HarvesterAddressBar(
                        url = selectedTab?.content?.url ?: "",
                        isEditing = isEditing,
                        tabCount = browserState.tabs.size,
                        iconState = if (selectedTab?.content?.url?.startsWith("https") == true) 1 else 0,
                        isWallpaperActive = false,
                        editText = editText,
                        onEditTextChanged = { viewModel.onTextChanged(it) },
                        onSubmit = { 
                            val target = viewModel.normalize(it)
                            if (target.isNotBlank()) {
                                viewModel.exitEditMode()
                                onNavigate(target)
                            }
                        },
                        onEditStart = { viewModel.enterEditMode(selectedTab?.content?.url ?: "") },
                        onClear = { viewModel.onTextChanged("") },
                        onSearchEngineClick = {},
                        onScriptMenuClick = {},
                        onMenuClick = {},
                        onTabCountClick = {}
                    )
                }
            }
        }
    }
}
