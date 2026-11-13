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
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarStore
import mozilla.components.lib.state.ext.observeAsComposableState
import mozilla.components.lib.state.helpers.StoreProvider.Companion.fragmentStore
import mozilla.components.support.ktx.android.view.hideKeyboard
import app.harvester.companion.browser.browsingmode.BrowsingModeManager
import app.harvester.companion.components.AppStore
import app.harvester.companion.components.Components
import app.harvester.companion.components.appstate.AppAction.SearchAction.SearchEnded
import app.harvester.companion.components.appstate.MetricsUtils
import app.harvester.companion.search.BrowserStoreToFenixSearchMapperMiddleware
import app.harvester.companion.search.BrowserToolbarSearchStatusSyncMiddleware
import app.harvester.companion.search.FenixSearchMiddleware
import app.harvester.companion.search.SearchFragmentAction
import app.harvester.companion.search.SearchFragmentStore
import app.harvester.companion.search.SearchFragmentState

private const val MATERIAL_DESIGN_SCRIM = "#52000000"

class AwesomeBarComposable(
    private val fragment: Fragment,
    private val modifier: Modifier,
    private val components: Components,
    private val appStore: AppStore,
    private val browserStore: BrowserStore,
    private val toolbarStore: BrowserToolbarStore,
    private val navController: NavController,
    private val browsingModeManager: BrowsingModeManager,
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
                        onSuggestionClicked = { searchStore.dispatch(SearchFragmentAction.SuggestionClicked(it)) },
                        onAutoComplete = { searchStore.dispatch(SearchFragmentAction.SuggestionSelected(it)) },
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
            SearchFragmentState(
                query = "",
                currentTabData = null,
                searchEngineSource = app.harvester.companion.search.SearchEngineSource.None,
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
            SearchFragmentStore(
                initialState = it,
                middleware = listOf(
                    BrowserStoreToFenixSearchMapperMiddleware(browserStore, lifecycleScope, appStore),
                    BrowserToolbarSearchStatusSyncMiddleware(appStore, browsingModeManager, lifecycleScope),
                    FenixSearchMiddleware(fragment, components, appStore, toolbarStore, navController, browsingModeManager)
                ),
            )
        }
}
