package app.harvester.companion.components.appstate.search

import mozilla.components.browser.state.search.SearchEngine
import app.harvester.companion.components.appstate.MetricsUtils

data class SearchState(
    val isSearchActive: Boolean,
    val selectedSearchEngine: SelectedSearchEngine?,
    val sourceTabId: String?,
    val searchAccessPoint: MetricsUtils.Source,
) {
    companion object {
        val EMPTY = SearchState(
            isSearchActive = false,
            selectedSearchEngine = null,
            sourceTabId = null,
            searchAccessPoint = MetricsUtils.Source.NONE,
        )
    }
}

data class SelectedSearchEngine(
    val searchEngine: SearchEngine,
    val isUserSelected: Boolean,
)
