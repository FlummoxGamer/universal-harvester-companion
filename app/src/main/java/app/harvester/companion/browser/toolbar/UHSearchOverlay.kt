package app.harvester.companion.browser.toolbar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.Fragment
import androidx.lifecycle.viewmodel.compose.viewModel
import app.harvester.companion.UHApp
import app.harvester.companion.browser.BrowserViewModel
import app.harvester.companion.search.UhSearchViewModel
import app.harvester.companion.search.ui.UhSearchScreen
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
    
    val searchViewModel: UhSearchViewModel = viewModel(fragment)

    UhSearchScreen(
        viewModel = searchViewModel,
        isBarBottom = isBarBottom,
        addressBarContent = {
            UHAddressBar(
                url = selectedTab?.content?.url ?: "",
                isEditing = true,
                tabCount = browserState.tabs.size,
                iconState = if (selectedTab?.content?.url?.startsWith("https") == true) 1 else 0,
                isWallpaperActive = false,
                editText = editText,
                onEditTextChanged = { 
                    viewModel.onTextChanged(it)
                    searchViewModel.onQueryChanged(it, isBarBottom)
                },
                onSubmit = { 
                    val target = viewModel.normalize(it)
                    if (target.isNotBlank()) {
                        components.useCases.sessionUseCases.loadUrl(target)
                        viewModel.exitEditMode()
                    }
                },
                onEditStart = { },
                onClear = { 
                    viewModel.onTextChanged("")
                    searchViewModel.clearQuery(isBarBottom)
                },
                onSearchEngineClick = onSearchEngineClick,
                onLockClick = onLockClick,
                onScriptMenuClick = {},
                onMenuClick = onMenuClick,
                onTabCountClick = onTabCountClick,
                searchEngine = searchEngine
            )
        },
        onDismiss = { onDismissRequest() },
        onSubmit = { query ->
            val target = viewModel.normalize(query)
            if (target.isNotBlank()) {
                components.useCases.sessionUseCases.loadUrl(target)
                viewModel.exitEditMode()
            }
        }
    )
}
