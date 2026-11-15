package app.harvester.companion.search

import androidx.fragment.app.Fragment
import androidx.lifecycle.coroutineScope
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import mozilla.components.browser.state.search.SearchEngine
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import app.harvester.companion.components.AppStore
import app.harvester.companion.components.Components
import app.harvester.companion.search.awesomebar.UhSuggestionsBuilder
import app.harvester.companion.browser.browsingmode.UhBrowsingModeManager
import app.harvester.companion.search.UhToolbarStore
import app.harvester.companion.search.awesomebar.internal.CurrentTabData
import mozilla.components.compose.browser.toolbar.store.BrowserEditToolbarAction
import mozilla.components.compose.browser.toolbar.ui.BrowserToolbarQuery

class UhSearchMiddleware(
    private val fragment: Fragment,
    private val components: Components,
    private val appStore: AppStore,
    private val toolbarStore: UhToolbarStore,
    private val navController: NavController,
    private val browsingModeManager: UhBrowsingModeManager,
) : Middleware<UhSearchState, UhSearchAction> {
    private var suggestionsProvidersBuilder: UhSuggestionsBuilder? = null

    override fun invoke(
        store: Store<UhSearchState, UhSearchAction>,
        next: (UhSearchAction) -> Unit,
        action: UhSearchAction,
    ) {
        when (action) {
            is UhSearchAction.Init -> {
                next(action)
                suggestionsProvidersBuilder = UhSuggestionsBuilder(components, fragment.viewLifecycleOwner.lifecycle.coroutineScope, browsingModeManager, true, components.useCases.sessionUseCases.loadUrl, components.useCases.searchUseCases.newTabSearch, components.useCases.tabsUseCases.selectTab)
                updateSearchProviders(store)
            }
            is UhSearchAction.SearchStarted -> {
                next(action)
                val clipboard = fragment.requireContext().getSystemService(android.content.ClipboardManager::class.java)
                val hasUrl = clipboard.primaryClipDescription?.hasMimeType("text/plain") == true && clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.startsWith("http") == true
                store.dispatch(UhSearchAction.UpdateClipboardHasUrl(hasUrl))
                components.engine.speculativeCreateSession(action.inPrivateMode)
            }
            is UhSearchAction.UpdateQuery -> {
                next(action)
                val shouldShow = !isCurrentUrl(action.query, store.state.currentTabData?.url) && action.query.isNotBlank()
                store.dispatch(UhSearchAction.SearchSuggestionsVisibilityUpdated(shouldShow))
            }
            is UhSearchAction.SuggestionClicked -> {
                next(action)
                action.suggestion.onSuggestionClicked?.invoke()
                toolbarStore.dispatch(BrowserEditToolbarAction.SearchQueryUpdated(BrowserToolbarQuery("")))
            }
            else -> next(action)
        }
    }

private fun updateSearchProviders(store: Store<UhSearchState, UhSearchAction>) {
    val defaultEngine = components.store.state.search.selectedOrDefaultSearchEngine()
    var source = store.state.searchEngineSource
    if (source is UhSearchEngineSource.None && defaultEngine != null) {
        source = UhSearchEngineSource.Default(defaultEngine)
    }
    val providers = suggestionsProvidersBuilder?.getProvidersToAdd(
        UhSuggestionsBuilder.UhProviderState(
            searchEngineSource = source,
            showRecentSearches = store.state.showRecentSearches,
            showTrendingSearches = store.state.showTrendingSearches
        )
    )
    if (providers != null) {
        store.dispatch(UhSearchAction.SearchProvidersUpdated(providers.toList()))
    }
}

    private fun isCurrentUrl(query: String, url: String?): Boolean {
        return url != null && query == url
    }
}
