package app.harvester.companion.components.appstate.search

import app.harvester.companion.components.appstate.AppAction
import app.harvester.companion.components.appstate.AppState

object SearchStateReducer {
    fun reduce(state: AppState, action: AppAction.SearchAction): AppState =
        when (action) {
            is AppAction.SearchAction.SearchStarted ->
                state.copy(
                    searchState = state.searchState.copy(
                        isSearchActive = true,
                        sourceTabId = action.tabId,
                        searchAccessPoint = action.source,
                    )
                )
            is AppAction.SearchAction.SearchEnded ->
                state.copy(searchState = SearchState.EMPTY)
            is AppAction.SearchAction.SearchEngineSelected ->
                state.copy(
                    searchState = state.searchState.copy(
                        selectedSearchEngine = SelectedSearchEngine(
                            searchEngine = action.searchEngine,
                            isUserSelected = action.isUserSelected,
                        )
                    )
                )
        }
}
