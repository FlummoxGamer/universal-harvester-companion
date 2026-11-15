package app.harvester.companion.search

import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store

class UhSearchStore(
    initialState: UhSearchState = UhSearchState(),
    middleware: List<Middleware<UhSearchState, UhSearchAction>> = emptyList()
) : Store<UhSearchState, UhSearchAction>(
    initialState = initialState,
    reducer = ::reduce,
    middleware = middleware
)

private fun reduce(state: UhSearchState, action: UhSearchAction): UhSearchState {
    return when (action) {
        is UhSearchAction.UpdateQuery -> state.copy(query = action.query)
        is UhSearchAction.UpdateResults -> state.copy(items = action.items, clipboardUrl = action.clipboardUrl)
        is UhSearchAction.ClearQuery -> state.copy(query = "", items = emptyList(), clipboardUrl = null)
    }
}
