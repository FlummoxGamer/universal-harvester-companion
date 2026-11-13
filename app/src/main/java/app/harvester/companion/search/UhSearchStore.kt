package app.harvester.companion.search

import mozilla.components.browser.state.search.SearchEngine
import mozilla.components.lib.state.Action
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.State
import mozilla.components.lib.state.Store

class UhSearchStore(
    initialState: UhSearchState,
    middleware: List<Middleware<UhSearchState, UhSearchAction>> = emptyList(),
) : Store<UhSearchState, UhSearchAction>(
    initialState = initialState,
    reducer = ::searchStateReducer,
    middleware = middleware,
) {
    init {
        dispatch(UhSearchAction.Init)
    }
}

sealed class UhSearchAction : Action {
    data object Init : UhSearchAction()
    data class SearchStarted(val selectedSearchEngine: SearchEngine?, val isUserSelected: Boolean, val inPrivateMode: Boolean, val searchStartedForCurrentUrl: Boolean) : UhSearchAction()
    data class SearchSuggestionsVisibilityUpdated(val visible: Boolean) : UhSearchAction()
    data class SearchProvidersUpdated(val providers: List<mozilla.components.concept.awesomebar.AwesomeBar.SuggestionProvider>) : UhSearchAction()
    data class UpdateQuery(val query: String) : UhSearchAction()
    data class UpdateSearchState(val search: mozilla.components.browser.state.state.SearchState, val isPrivate: Boolean = false) : UhSearchAction()
    data class SuggestionClicked(val suggestion: mozilla.components.concept.awesomebar.AwesomeBar.SuggestionItem) : UhSearchAction()
    data class SuggestionSelected(val suggestion: mozilla.components.concept.awesomebar.AwesomeBar.Suggestion) : UhSearchAction()
    data class SuggestionHidden(val suggestion: mozilla.components.concept.awesomebar.AwesomeBar.GroupedSuggestion) : UhSearchAction()
    data class SuggestionRestored(val suggestion: mozilla.components.concept.awesomebar.AwesomeBar.GroupedSuggestion) : UhSearchAction()
    data object PrivateSuggestionsCardAccepted : UhSearchAction()
    data object ReloadCurrentWebsiteClicked : UhSearchAction()
    data object ShareCurrentWebsiteDetailsClicked : UhSearchAction()
    data object CopyCurrentWebsiteDetailsClicked : UhSearchAction()
    data object EditCurrentWebsiteDetailsClicked : UhSearchAction()
    data class UpdateClipboardHasUrl(val hasUrl: Boolean) : UhSearchAction()
    data class SearchSourceTabUpdated(val tabId: String?, val currentTabData: mozilla.components.compose.browser.awesomebar.internal.CurrentTabData?) : UhSearchAction()
    data class AllowSearchSuggestionsInPrivateModePrompt(val show: Boolean) : UhSearchAction()
}

data class UhSearchState(
    val query: String,
    val currentTabData: mozilla.components.compose.browser.awesomebar.internal.CurrentTabData?,
    val searchEngineSource: UhSearchEngineSource,
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

sealed class UhSearchEngineSource {
    abstract val searchEngine: SearchEngine?
    object None : UhSearchEngineSource() { override val searchEngine: SearchEngine? = null }
    data class Default(override val searchEngine: SearchEngine) : UhSearchEngineSource()
    data class Shortcut(override val searchEngine: SearchEngine) : UhSearchEngineSource()
}

private fun searchStateReducer(state: UhSearchState, action: UhSearchAction): UhSearchState {
    return when (action) {
        is UhSearchAction.Init -> state
        is UhSearchAction.SearchSourceTabUpdated -> state.copy(tabId = action.tabId, currentTabData = action.currentTabData)
        is UhSearchAction.UpdateQuery -> state.copy(query = action.query)
        is UhSearchAction.UpdateSearchState -> state.copy(defaultEngine = action.search.selectedOrDefaultSearchEngine(private = action.isPrivate))
        is UhSearchAction.SearchSuggestionsVisibilityUpdated -> state.copy(shouldShowSearchSuggestions = action.visible)
        is UhSearchAction.SearchProvidersUpdated -> state.copy(searchSuggestionsProviders = action.providers)
        is UhSearchAction.SearchStarted -> state.copy(searchStartedForCurrentUrl = action.searchStartedForCurrentUrl)
        is UhSearchAction.UpdateClipboardHasUrl -> state.copy(clipboardHasUrl = action.hasUrl)
        is UhSearchAction.SuggestionHidden -> state.copy(hiddenSuggestions = state.hiddenSuggestions + action.suggestion)
        is UhSearchAction.SuggestionRestored -> state.copy(hiddenSuggestions = state.hiddenSuggestions - action.suggestion)
        else -> state
    }
}
