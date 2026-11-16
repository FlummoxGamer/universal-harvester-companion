package app.harvester.companion.browser.toolbar

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import app.harvester.companion.R
import app.harvester.companion.UHApp
import app.harvester.companion.browser.BrowserViewModel
import app.harvester.companion.browser.menu.MainMenuDialogFragment
import app.harvester.companion.browser.search.UHSearchEnginePicker
import app.harvester.companion.search.UhSearchViewModel
import app.harvester.companion.search.ui.UhSearchScreen
import app.harvester.companion.settings.trustpanel.TrustPanelFragment
import mozilla.components.browser.state.selector.selectedTab

@Composable
fun UhGlobalToolbar(
    viewModel: BrowserViewModel,
    navController: NavController,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val components = (context.applicationContext as UHApp).components
    val isEditing by viewModel.isEditing.collectAsState()
    val isBarBottom by viewModel.isBarBottom.collectAsState()
    val searchEngine by viewModel.searchEngine.collectAsState()
    val editText by viewModel.editText.collectAsState()
    val browserState by components.store.stateFlow.collectAsState()
    val selectedTab = browserState.selectedTab
    val searchViewModel: UhSearchViewModel = viewModel()
    var showSearchEnginePicker by remember { mutableStateOf(false) }

    val currentDestination = navController.currentBackStackEntryAsState().value?.destination
    val isBrowser = currentDestination?.id == R.id.browserFragment

    val onSearchSubmit = { query: String ->
        val target = viewModel.normalize(query)
        if (target.isNotBlank()) {
            if (isBrowser) {
                components.useCases.sessionUseCases.loadUrl(target)
            } else {
                val bundle = Bundle().apply { putString("url", target) }
                navController.navigate(R.id.action_homeFragment_to_browserFragment, bundle)
            }
            viewModel.exitEditMode()
        }
    }

    Box(modifier = modifier) {
        if (isEditing) {
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
                    onSubmit = onSearchSubmit,
                    onEditStart = { },
                    onClear = { 
                        viewModel.onTextChanged("")
                        searchViewModel.clearQuery(isBarBottom)
                    },
                    onSearchEngineClick = { showSearchEnginePicker = true },
                    onLockClick = {
                        val tab = components.store.state.selectedTab
                        if (tab != null) {
                            TrustPanelFragment.newInstance(
                                sessionId = tab.id,
                                url = tab.content.url,
                                title = tab.content.title ?: "",
                                isSecured = tab.content.securityInfo.isSecure,
                                certificate = tab.content.securityInfo.certificate
                            ).show((context as AppCompatActivity).supportFragmentManager, "TrustPanel")
                        }
                    },
                    onScriptMenuClick = {},
                    onMenuClick = { 
                        MainMenuDialogFragment.newInstance(!isBrowser).show((context as AppCompatActivity).supportFragmentManager, "MainMenu")
                    },
                    onTabCountClick = {},
                    searchEngine = searchEngine
                )
            },
            onDismiss = { viewModel.exitEditMode() },
            onSubmit = onSearchSubmit
        )
}     else {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = if (isBarBottom) Alignment.BottomCenter else Alignment.TopCenter
         ) {
            UHAddressBar(
                url = selectedTab?.content?.url ?: "",
                isEditing = isEditing,
                tabCount = browserState.tabs.size,
                iconState = if (selectedTab?.content?.url?.startsWith("https") == true) 1 else 0,
                isWallpaperActive = currentDestination?.id == R.id.homeFragment,
                editText = editText,
                onEditTextChanged = { viewModel.onTextChanged(it) },
                onSubmit = onSearchSubmit,
                onEditStart = { viewModel.enterEditMode("") },
                onClear = { },
                onSearchEngineClick = { showSearchEnginePicker = true },
                onLockClick = {
                    val tab = components.store.state.selectedTab
                    if (tab != null) {
                        TrustPanelFragment.newInstance(
                            sessionId = tab.id,
                            url = tab.content.url,
                            title = tab.content.title ?: "",
                            isSecured = tab.content.securityInfo.isSecure,
                            certificate = tab.content.securityInfo.certificate
                        ).show((context as AppCompatActivity).supportFragmentManager, "TrustPanel")
                    }
                },
                onScriptMenuClick = {},
                onMenuClick = { 
                    MainMenuDialogFragment.newInstance(!isBrowser).show((context as AppCompatActivity).supportFragmentManager, "MainMenu")
                },
                onTabCountClick = {},
                searchEngine = searchEngine
            )
        }
    }
}

if (showSearchEnginePicker) {
        UHSearchEnginePicker(
            onDismiss = { showSearchEnginePicker = false },
            onSelect = { 
                showSearchEnginePicker = false
            }
        )
    }
}
