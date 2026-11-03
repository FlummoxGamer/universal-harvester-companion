package app.harvester.companion.components

import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store

class AppStore(
    initialState: AppState = AppState(),
    middlewares: List<Middleware<AppState, AppAction>> = emptyList()
) : Store<AppState, AppAction>(
    initialState,
    ::reduce,
    middlewares
)

private fun reduce(state: AppState, action: AppAction): AppState {
    return when (action) {
        is AppAction.DesktopModeUpdated -> state.copy(
            desktopMode = state.desktopMode.copy(isActive = action.isActive)
        )
        is AppAction.TabGroupsUpdated -> state.copy(
            tabGroups = state.tabGroups.copy(groups = action.groups)
        )
    }
}
