package app.harvester.companion.search.awesomebar

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import androidx.lifecycle.coroutineScope
import androidx.navigation.NavController
import mozilla.components.browser.state.action.AwesomeBarAction
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.compose.browser.awesomebar.AwesomeBar
import mozilla.components.compose.browser.awesomebar.AwesomeBarOrientation
import mozilla.components.lib.state.ext.observeAsComposableState
import mozilla.components.lib.state.helpers.StoreProvider.Companion.fragmentStore
import mozilla.components.support.ktx.android.view.hideKeyboard
import app.harvester.companion.browser.browsingmode.UhBrowsingModeManager
import app.harvester.companion.components.AppStore
import app.harvester.companion.components.Components
import app.harvester.companion.components.appstate.AppAction.SearchAction.SearchEnded
import app.harvester.companion.components.appstate.MetricsUtils
import app.harvester.companion.search.UhBrowserToSearchMiddleware
import app.harvester.companion.search.UhSearchStatusSyncMiddleware
import app.harvester.companion.search.UhSearchMiddleware
import app.harvester.companion.search.UhSearchAction
import app.harvester.companion.search.UhSearchStore
import app.harvester.companion.search.UhSearchState
import app.harvester.companion.search.UhSearchEngineSource
import app.harvester.companion.search.UhToolbarStore

private const val MATERIAL_DESIGN_SCRIM = "#52000000"

class UhSearchSuggestionsComposable(
    private val fragment: Fragment,
    private val modifier: Modifier,
    private val components: Components,
    private val appStore: AppStore,
    private val browserStore: BrowserStore,
    private val toolbarStore: UhToolbarStore,
    private val navController: NavController,
    private val browsingModeManager: UhBrowsingModeManager,
    private val searchAccessPoint: MetricsUtils.Source = MetricsUtils.Source.NONE,
) {
    private val searchStore by initializeSearchStore()

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun SearchSuggestions() {
        val isSearchActive = appStore.observeAsComposableState { it.searchState.isSearchActive }.value
        val state = searchStore.observeAsComposableState { it }.value
        val view = LocalView.current
        val focusManager = LocalFocusManager.current
        val keyboardController = LocalSoftwareKeyboardController.current

        LaunchedEffect(isSearchActive) {
            if (!isSearchActive) {
                focusManager.clearFocus()
                keyboardController?.hide()
            }
        }

        if (isSearchActive) {
            if (state.shouldShowSearchSuggestions) {
                Box(
                    modifier = modifier.background(Color(MATERIAL_DESIGN_SCRIM.toColorInt())).fillMaxSize().pointerInput(
                        WindowInsets.isImeVisible
                    ) {
                        detectTapGestures(
                            onPress = { view.hideKeyboard() },
                            onTap = {
                                focusManager.clearFocus()
                                appStore.dispatch(SearchEnded)
                            }
                        )
                    }
                ) {
                    AwesomeBar(
                        text = state.query,
                        providers = state.searchSuggestionsProviders,
                        hiddenSuggestions = state.hiddenSuggestions,
                        orientation = AwesomeBarOrientation.TOP,
                        onSuggestionClicked = { searchStore.dispatch(UhSearchAction.SuggestionClicked(it)) },
                        onAutoComplete = { searchStore.dispatch(UhSearchAction.SuggestionSelected(it)) },
                        onRemoveClicked = {},
                        onVisibilityStateUpdated = { browserStore.dispatch(AwesomeBarAction.VisibilityStateUpdated(it)) },
                        onScroll = { view.hideKeyboard() },
                        profiler = components.engine.profiler,
                    )
                }
            }
        }
    }

    private fun initializeSearchStore() =
        fragment.fragmentStore(
            UhSearchState(
                query = "",
                currentTabData = null,
                searchEngineSource = UhSearchEngineSource.None,
                defaultEngine = null,
                searchSuggestionsProviders = emptyList(),
                hiddenSuggestions = emptySet(),
                searchSuggestionsOrientedAtBottom = false,
                searchStartedForCurrentUrl = false,
                shouldShowSearchSuggestions = false,
                showSearchSuggestionsHint = false,
                showClipboardSuggestions = false,
                searchAccessPoint = searchAccessPoint,
                tabId = null
            )
        ) {
            val lifecycleScope = fragment.viewLifecycleOwner.lifecycle.coroutineScope
            UhSearchStore(
                initialState = it,
                middleware = listOf(
                    UhBrowserToSearchMiddleware(browserStore, lifecycleScope, appStore),
                    UhSearchStatusSyncMiddleware(appStore, browsingModeManager, lifecycleScope),
                    UhSearchMiddleware(fragment, components, appStore, toolbarStore, navController, browsingModeManager)
                ),
            )
        }
}
