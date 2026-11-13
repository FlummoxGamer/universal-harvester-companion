package app.harvester.companion.search

import mozilla.components.compose.browser.toolbar.store.BrowserToolbarAction
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarState
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store

class UhToolbarStore(
    initialState: BrowserToolbarState = BrowserToolbarState(),
    middleware: List<Middleware<BrowserToolbarState, BrowserToolbarAction>> = emptyList(),
) : Store<BrowserToolbarState, BrowserToolbarAction>(
    initialState = initialState,
    reducer = ::reduce,
    middleware = middleware,
)

private fun reduce(state: BrowserToolbarState, action: BrowserToolbarAction): BrowserToolbarState {
    return when (action) {
        is BrowserToolbarAction.Init -> BrowserToolbarState(mode = action.mode, displayState = action.displayState, editState = action.editState, gravity = action.gravity)
        is BrowserToolbarAction.EnterEditMode -> state.copy(mode = mozilla.components.compose.browser.toolbar.store.Mode.EDIT, editState = state.editState.copy(isQueryPrivate = action.isPrivate))
        is BrowserToolbarAction.ExitEditMode -> state.copy(mode = mozilla.components.compose.browser.toolbar.store.Mode.DISPLAY, editState = state.editState.copy(query = mozilla.components.compose.browser.toolbar.ui.BrowserToolbarQuery(""), queryWasPrefilled = false, suggestion = null))
        is BrowserToolbarAction.ToolbarGravityUpdated -> state.copy(gravity = action.gravity)
        is BrowserToolbarAction.CommitUrl -> state
        is mozilla.components.compose.browser.toolbar.store.BrowserDisplayToolbarAction.BrowserActionsStartUpdated -> state.copy(displayState = state.displayState.copy(browserActionsStart = action.actions))
        is mozilla.components.compose.browser.toolbar.store.BrowserDisplayToolbarAction.PageActionsStartUpdated -> state.copy(displayState = state.displayState.copy(pageActionsStart = action.actions))
        is mozilla.components.compose.browser.toolbar.store.BrowserDisplayToolbarAction.PageOriginUpdated -> state.copy(displayState = state.displayState.copy(pageOrigin = action.pageOrigin))
        is mozilla.components.compose.browser.toolbar.store.BrowserDisplayToolbarAction.PageActionsEndUpdated -> state.copy(displayState = state.displayState.copy(pageActionsEnd = action.actions))
        is mozilla.components.compose.browser.toolbar.store.BrowserDisplayToolbarAction.BrowserActionsEndUpdated -> state.copy(displayState = state.displayState.copy(browserActionsEnd = action.actions))
        is mozilla.components.compose.browser.toolbar.store.BrowserDisplayToolbarAction.NavigationActionsUpdated -> state.copy(displayState = state.displayState.copy(navigationActions = action.actions))
        is mozilla.components.compose.browser.toolbar.store.BrowserDisplayToolbarAction.ToolbarCFRShown -> state.copy(displayState = state.displayState.copy(cfr = action.cfr))
        is mozilla.components.compose.browser.toolbar.store.BrowserDisplayToolbarAction.ToolbarCFRDismissed -> state.copy(displayState = state.displayState.copy(cfr = null))
        is mozilla.components.compose.browser.toolbar.store.BrowserEditToolbarAction.SearchQueryUpdated -> state.copy(editState = state.editState.copy(query = action.query, isQueryPrefilled = action.isQueryPrefilled, queryWasPrefilled = state.editState.queryWasPrefilled || (action.isQueryPrefilled && action.query.current.isNotEmpty())))
        is mozilla.components.compose.browser.toolbar.store.BrowserEditToolbarAction.AutocompleteSuggestionUpdated -> state.copy(editState = state.editState.copy(suggestion = action.autocompletedSuggestion))
        is mozilla.components.compose.browser.toolbar.store.BrowserEditToolbarAction.SearchActionsStartUpdated -> state.copy(editState = state.editState.copy(editActionsStart = action.actions))
        is mozilla.components.compose.browser.toolbar.store.BrowserEditToolbarAction.SearchActionsEndUpdated -> state.copy(editState = state.editState.copy(editActionsEnd = action.actions))
        is mozilla.components.compose.browser.toolbar.store.BrowserDisplayToolbarAction.UpdateProgressBarConfig -> state.copy(displayState = state.displayState.copy(progressBarConfig = action.config))
        is mozilla.components.compose.browser.toolbar.store.BrowserToolbarInteraction.BrowserToolbarEvent -> state
        is mozilla.components.compose.browser.toolbar.store.BrowserEditToolbarAction.HintUpdated -> state.copy(editState = state.editState.copy(hint = action.hint))
    }
}
