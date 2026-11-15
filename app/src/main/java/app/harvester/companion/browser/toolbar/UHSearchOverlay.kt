package app.harvester.companion.browser.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import app.harvester.companion.R
import app.harvester.companion.UHApp
import app.harvester.companion.browser.BrowserViewModel
import app.harvester.companion.search.UhToolbarStore
import app.harvester.companion.search.awesomebar.UhSearchSuggestionsComposable
import mozilla.components.browser.state.selector.selectedTab

@Composable
fun UHSearchOverlay(
    viewModel: BrowserViewModel,
    fragment: Fragment,
    onDismissRequest: () -> Unit,
    onSearchEngineClick: () -> Unit,
    onLockClick: () -> Unit,
    onMenuClick: () -> Unit,
    onTabCountClick: () -> Unit
) {
    val components = (fragment.requireContext().applicationContext as UHApp).components
    val editText by viewModel.editText.collectAsState()
    val isBarBottom by viewModel.isBarBottom.collectAsState()
    val searchEngine by viewModel.searchEngine.collectAsState()
    val browserState by components.store.stateFlow.collectAsState()
    val selectedTab = browserState.selectedTab

    Popup(
        alignment = androidx.compose.ui.Alignment.TopCenter,
        properties = PopupProperties(focusable = true)
    ) {
        Column(modifier = Modifier.fillMaxSize().background(colorResource(id = R.color.bg))) {
            if (!isBarBottom) {
                UHAddressBar(
                    url = selectedTab?.content?.url ?: "",
                    isEditing = true,
                    tabCount = browserState.tabs.size,
                    iconState = if (selectedTab?.content?.url?.startsWith("https") == true) 1 else 0,
                    isWallpaperActive = false,
                    editText = editText,
                    onEditTextChanged = { viewModel.onTextChanged(it) },
                    onSubmit = { 
                        val target = viewModel.normalize(it)
                        if (target.isNotBlank()) {
                            viewModel.exitEditMode()
                            components.useCases.sessionUseCases.loadUrl(target)
                        }
                    },
                    onEditStart = { },
                    onClear = { viewModel.onTextChanged("") },
                    onSearchEngineClick = onSearchEngineClick,
                    onLockClick = onLockClick,
                    onScriptMenuClick = {},
                    onMenuClick = onMenuClick,
                    onTabCountClick = onTabCountClick,
                    searchEngine = searchEngine
                )
                androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                    val toolbarStore = remember { UhToolbarStore() }
                    UhSearchSuggestionsComposable(
                        fragment = fragment,
                        modifier = Modifier.fillMaxSize(),
                        components = components,
                        appStore = components.appStore,
                        browserStore = components.store,
                        toolbarStore = toolbarStore,
                        navController = fragment.findNavController(),
                        browsingModeManager = viewModel.browsingModeManager,
                        query = editText
                    ).SearchSuggestions()
                }
            } else {
                androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                    val toolbarStore = remember { UhToolbarStore() }
                    UhSearchSuggestionsComposable(
                        fragment = fragment,
                        modifier = Modifier.fillMaxSize(),
                        components = components,
                        appStore = components.appStore,
                        browserStore = components.store,
                        toolbarStore = toolbarStore,
                        navController = fragment.findNavController(),
                        browsingModeManager = viewModel.browsingModeManager,
                        query = editText
                    ).SearchSuggestions()
                }
                UHAddressBar(
                    url = selectedTab?.content?.url ?: "",
                    isEditing = true,
                    tabCount = browserState.tabs.size,
                    iconState = if (selectedTab?.content?.url?.startsWith("https") == true) 1 else 0,
                    isWallpaperActive = false,
                    editText = editText,
                    onEditTextChanged = { viewModel.onTextChanged(it) },
                    onSubmit = { 
                        val target = viewModel.normalize(it)
                        if (target.isNotBlank()) {
                            viewModel.exitEditMode()
                            components.useCases.sessionUseCases.loadUrl(target)
                        }
                    },
                    onEditStart = { },
                    onClear = { viewModel.onTextChanged("") },
                    onSearchEngineClick = onSearchEngineClick,
                    onLockClick = onLockClick,
                    onScriptMenuClick = {},
                    onMenuClick = onMenuClick,
                    onTabCountClick = onTabCountClick,
                    searchEngine = searchEngine
                )
            }
        }
    }
}
