package app.harvester.companion.search

import androidx.fragment.app.Fragment
import androidx.lifecycle.coroutineScope
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import mozilla.components.browser.state.search.SearchEngine
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarStore
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import app.harvester.companion.components.AppStore
import app.harvester.companion.components.Components
import app.harvester.companion.search.awesomebar.SearchSuggestionsProvidersBuilder
import app.harvester.companion.browser.browsingmode.BrowsingModeManager

class FenixSearchMiddleware(
    private val fragment: Fragment,
    private val components: Components,
    private val appStore: AppStore,
    private val toolbarStore: BrowserToolbarStore,
    private val navController: NavController,
    private val browsingModeManager: BrowsingModeManager,
) : Middleware<SearchFragmentState, SearchFragmentAction> {
    private var suggestionsProvidersBuilder: SearchSuggestionsProvidersBuilder? = null

    override fun invoke(
        store: Store<SearchFragmentState, SearchFragmentAction>,
        next: (SearchFragmentAction) -> Unit,
        action: SearchFragmentAction,
    ) {
        when (action) {
            is SearchFragmentAction.Init -> {
                next(action)
                suggestionsProvidersBuilder = SearchSuggestionsProvidersBuilder(components, fragment.viewLifecycleOwner.lifecycle.coroutineScope, browsingModeManager, true, components.useCases.sessionUseCases.loadUrl, components.useCases.searchUseCases.newTabSearch, components.useCases.tabsUseCases.selectTab)
                updateSearchProviders(store)
            }
            is SearchFragmentAction.SearchStarted -> {
                next(action)
                components.engine.speculativeCreateSession(action.inPrivateMode)
            }
            is SearchFragmentAction.UpdateQuery -> {
                next(action)
                val shouldShow = !isCurrentUrl(action.query, store.state.currentTabData?.url) && action.query.isNotBlank()
                store.dispatch(SearchFragmentAction.SearchSuggestionsVisibilityUpdated(shouldShow))
            }
            is SearchFragmentAction.SuggestionClicked -> {
                next(action)
                action.suggestion.onSuggestionClicked?.invoke()
                toolbarStore.dispatch(mozilla.components.compose.browser.toolbar.store.BrowserEditToolbarAction.SearchQueryUpdated(mozilla.components.compose.browser.toolbar.ui.BrowserToolbarQuery("")))
            }
            else -> next(action)
        }
    }

    private fun updateSearchProviders(store: Store<SearchFragmentState, SearchFragmentAction>) {
        val providers = suggestionsProvidersBuilder?.getProvidersToAdd(SearchSuggestionsProvidersBuilder.SearchProviderState(store.state.searchEngineSource))
        if (providers != null) {
            store.dispatch(SearchFragmentAction.SearchProvidersUpdated(providers.toList()))
        }
    }

    private fun isCurrentUrl(query: String, url: String?): Boolean {
        return url != null && query == url
    }
}
