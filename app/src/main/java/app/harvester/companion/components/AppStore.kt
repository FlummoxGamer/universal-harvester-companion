package app.harvester.companion.components

import mozilla.components.lib.state.Store

class AppStore(
    initialState: AppState = AppState(),
) : Store<AppState, AppAction>(initialState, ::reduce) {
    companion object {
        fun reduce(state: AppState, action: AppAction): AppState = when (action) {
            is AppAction.ForegroundChanged -> state.copy(isForeground = action.isForeground)
            is AppAction.BrowsingModeChanged -> state.copy(browsingMode = action.mode)
        }
    }
}
