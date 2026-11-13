package app.harvester.companion.search

import mozilla.components.browser.state.search.SearchEngine
import mozilla.components.lib.state.Action
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.State
import mozilla.components.lib.state.Store

class SearchFragmentStore(
    initialState: SearchFragmentState,
    middleware: List<Middleware<SearchFragmentState, SearchFragmentAction>> = emptyList(),
) : Store<SearchFragmentState, SearchFragmentAction>(
    initialState = initialState,
    reducer = ::searchStateReducer,
    middleware = middleware,
) {
    init {
        dispatch(SearchFragmentAction.Init)
    }
}

sealed class SearchFragmentAction : Action {
    data object Init : SearchFragmentAction()
    data class SearchStarted(val selectedSearchEngine: SearchEngine?, val isUserSelected: Boolean, val inPrivateMode: Boolean, val searchStartedForCurrentUrl: Boolean) : SearchFragmentAction()
    data class SearchSuggestionsVisibilityUpdated(val visible: Boolean) : SearchFragmentAction()
    data class SearchProvidersUpdated(val providers: List<mozilla.components.concept.awesomebar.AwesomeBar.SuggestionProvider>) : SearchFragmentAction()
    data class UpdateQuery(val query: String) : SearchFragmentAction()
    data class UpdateSearchState(val search: mozilla.components.browser.state.state.SearchState, val isPrivate: Boolean = false) : SearchFragmentAction()
    data class SuggestionClicked(val suggestion: mozilla.components.concept.awesomebar.AwesomeBar.SuggestionItem) : SearchFragmentAction()
    data class SuggestionSelected(val suggestion: mozilla.components.concept.awesomebar.AwesomeBar.Suggestion) : SearchFragmentAction()
    data class SuggestionHidden(val suggestion: mozilla.components.concept.awesomebar.AwesomeBar.GroupedSuggestion) : SearchFragmentAction()
    data class SuggestionRestored(val suggestion: mozilla.components.concept.awesomebar.AwesomeBar.GroupedSuggestion) : SearchFragmentAction()
    data object PrivateSuggestionsCardAccepted : SearchFragmentAction()
    data object ReloadCurrentWebsiteClicked : SearchFragmentAction()
    data object ShareCurrentWebsiteDetailsClicked : SearchFragmentAction()
    data object CopyCurrentWebsiteDetailsClicked : SearchFragmentAction()
    data object EditCurrentWebsiteDetailsClicked : SearchFragmentAction()
    data class UpdateClipboardHasUrl(val hasUrl: Boolean) : SearchFragmentAction()
    data class SearchSourceTabUpdated(val tabId: String?, val currentTabData: mozilla.components.compose.browser.awesomebar.internal.CurrentTabData?) : SearchFragmentAction()
    data class AllowSearchSuggestionsInPrivateModePrompt(val show: Boolean) : SearchFragmentAction()
}

data class SearchFragmentState(
    val query: String,
    val currentTabData: mozilla.components.compose.browser.awesomebar.internal.CurrentTabData?,
    val searchEngineSource: SearchEngineSource,
    val defaultEngine: SearchEngine?,
    val searchSuggestionsProviders: List<mozilla.components.concept.awesomebar.AwesomeBar.SuggestionProvider>,
    val hiddenSuggestions: Set<mozilla.components.concept.awesomebar.AwesomeBar.GroupedSuggestion>,
    val searchSuggestionsOrientedAtBottom: Boolean,
    val searchStartedForCurrentUrl: Boolean,
    val shouldShowSearchSuggestions: Boolean,
    val showSearchSuggestionsHint: Boolean,
    val showClipboardSuggestions: Boolean,
    val clipboardHasUrl: Boolean = false,
    val searchAccessPoint: app.harvester.companion.components.appstate.MetricsUtils.Source,
    val tabId: String?
) : State

sealed class SearchEngineSource {
    abstract val searchEngine: SearchEngine?
    object None : SearchEngineSource() { override val searchEngine: SearchEngine? = null }
    data class Default(override val searchEngine: SearchEngine) : SearchEngineSource()
    data class Shortcut(override val searchEngine: SearchEngine) : SearchEngineSource()
}

private fun searchStateReducer(state: SearchFragmentState, action: SearchFragmentAction): SearchFragmentState {
    return when (action) {
        is SearchFragmentAction.Init -> state
        is SearchFragmentAction.SearchSourceTabUpdated -> state.copy(tabId = action.tabId, currentTabData = action.currentTabData)
        is SearchFragmentAction.UpdateQuery -> state.copy(query = action.query)
        is SearchFragmentAction.UpdateSearchState -> state.copy(defaultEngine = action.search.selectedOrDefaultSearchEngine(private = action.isPrivate))
        is SearchFragmentAction.SearchSuggestionsVisibilityUpdated -> state.copy(shouldShowSearchSuggestions = action.visible)
        is SearchFragmentAction.SearchProvidersUpdated -> state.copy(searchSuggestionsProviders = action.providers)
        is SearchFragmentAction.SearchStarted -> state.copy(searchStartedForCurrentUrl = action.searchStartedForCurrentUrl)
        is SearchFragmentAction.UpdateClipboardHasUrl -> state.copy(clipboardHasUrl = action.hasUrl)
        is SearchFragmentAction.SuggestionHidden -> state.copy(hiddenSuggestions = state.hiddenSuggestions + action.suggestion)
        is SearchFragmentAction.SuggestionRestored -> state.copy(hiddenSuggestions = state.hiddenSuggestions - action.suggestion)
        else -> state
    }
}
