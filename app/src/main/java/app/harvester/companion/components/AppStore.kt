package app.harvester.companion.components

import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import app.harvester.companion.components.appstate.AppAction
import app.harvester.companion.components.appstate.AppState
import app.harvester.companion.components.appstate.AppStoreReducer

class AppStore(
    initialState: AppState = AppState(),
    middlewares: List<Middleware<AppState, AppAction>> = emptyList(),
) : Store<AppState, AppAction>(initialState, AppStoreReducer::reduce, middlewares)
