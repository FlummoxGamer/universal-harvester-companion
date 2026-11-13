package app.harvester.companion.search.awesomebar

import kotlinx.coroutines.CoroutineScope
import mozilla.components.browser.state.search.SearchEngine
import mozilla.components.concept.awesomebar.AwesomeBar
import mozilla.components.feature.awesomebar.provider.SearchSuggestionProvider
import mozilla.components.feature.awesomebar.provider.SearchActionProvider
import mozilla.components.feature.awesomebar.provider.HistoryStorageSuggestionProvider
import mozilla.components.feature.awesomebar.provider.BookmarksStorageSuggestionProvider
import mozilla.components.feature.awesomebar.provider.SearchEngineSuggestionProvider
import mozilla.components.feature.search.SearchUseCases
import mozilla.components.feature.session.SessionUseCases.LoadUrlUseCase
import mozilla.components.feature.tabs.TabsUseCases
import app.harvester.companion.components.Components
import app.harvester.companion.browser.browsingmode.BrowsingModeManager

class SearchSuggestionsProvidersBuilder(
    private val components: Components,
    private val scope: CoroutineScope,
    private val browsingModeManager: BrowsingModeManager,
    private val includeSelectedTab: Boolean,
    private val loadUrlUseCase: LoadUrlUseCase,
    private val searchUseCase: SearchUseCases.SearchUseCase,
    private val selectTabUseCase: TabsUseCases.SelectTabUseCase,
) {
    private val engineForSpeculativeConnects = if (browsingModeManager.mode == app.harvester.companion.browser.browsingmode.BrowsingMode.Normal) components.engine else null

    private val defaultHistoryStorageProvider = HistoryStorageSuggestionProvider(components.placesHistoryStorage, loadUrlUseCase, components.engine, engineForSpeculativeConnects, false)
    private val defaultSearchSuggestionProvider = SearchSuggestionProvider(store = components.store, searchUseCase = searchUseCase, fetchClient = components.client, mode = SearchSuggestionProvider.Mode.MULTIPLE_SUGGESTIONS, limit = 3, engine = engineForSpeculativeConnects, filterExactMatch = true, private = browsingModeManager.mode.isPrivate)
    private val defaultSearchActionProvider = SearchActionProvider(store = components.store, searchUseCase = searchUseCase)
    private var searchEngineSuggestionProvider: SearchEngineSuggestionProvider = SearchEngineSuggestionProvider(components.store.state.search.searchEngines, {}, titleFormatter = { "Search with $it" })
    
    internal fun getProvidersToAdd(state: SearchProviderState): Set<AwesomeBar.SuggestionProvider> {
        val providers = mutableSetOf<AwesomeBar.SuggestionProvider>()
        providers.add(defaultSearchActionProvider)
        providers.add(defaultSearchSuggestionProvider)
        providers.add(defaultHistoryStorageProvider)
        providers.add(BookmarksStorageSuggestionProvider(components.placesBookmarksStorage, loadUrlUseCase, components.engine, engineForSpeculativeConnects, false))
        providers.add(searchEngineSuggestionProvider)
        return providers
    }

    internal fun getFilterForCurrentEngineResults(state: SearchProviderState): app.harvester.companion.search.awesomebar.SearchSuggestionsProvidersBuilder.SearchResultFilter? {
        return state.searchEngineSource.searchEngine?.resultsUrl?.let { SearchResultFilter.CurrentEngine(it) }
    }

    data class SearchProviderState(
        val searchEngineSource: SearchEngineSource,
        val showSearchSuggestions: Boolean = true,
        val showAllHistorySuggestions: Boolean = true,
        val showAllBookmarkSuggestions: Boolean = true,
    )

    sealed interface SearchResultFilter {
        data class CurrentEngine(val resultsUri: android.net.Uri) : SearchResultFilter
    }
}
