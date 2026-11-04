package app.harvester.companion.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import app.harvester.companion.UHApp
import app.harvester.companion.browser.toolbar.HarvesterAddressBar
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.feature.session.SessionFeature
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    browserState: BrowserState,
    sessionFeature: ViewBoundFeatureWrapper<SessionFeature>,
    fragment: Fragment,
    viewModel: BrowserViewModel,
    isEditing: Boolean,
    editText: String,
    suggestions: List<String>,
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    val components = (context.applicationContext as UHApp).components
    val selectedTab = browserState.selectedTab

    var showMenuSheet by remember { mutableStateOf(false) }

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

                    if (isEditing && suggestions.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0A0F1A))
                        ) {
                            items(suggestions) { suggestion ->
                                Text(
                                    text = suggestion,
                                    color = Color(0xFFE6E9EE),
                                    fontSize = 16.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val target = viewModel.normalize(suggestion)
                                            viewModel.exitEditMode()
                                            onNavigate(target)
                                        }
                                        .padding(16.dp)
                                )
                            }
                        }
                    }

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
                        onMenuClick = { showMenuSheet = true },
                        onTabCountClick = {}
                    )
                }
            }

            if (showMenuSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showMenuSheet = false },
                    sheetState = rememberModalBottomSheetState(),
                    containerColor = Color(0xFF171B21)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Menu", color = Color.White, fontSize = 20.sp)
                        // Menu items will go here in the next batch
                    }
                }
            }
        }
    }
}
