package app.harvester.companion.components.appstate

import app.harvester.companion.components.appstate.search.SearchStateReducer
import app.harvester.companion.components.appstate.snackbar.SnackbarStateReducer

internal object AppStoreReducer {
    fun reduce(state: AppState, action: AppAction): AppState =
        when (action) {
            is AppAction.UpdateFirstFrameDrawn -> state.copy(firstFrameDrawn = action.drawn)
            is AppAction.BrowsingModeManagerModeChanged -> state.copy(mode = action.mode)
            is AppAction.OrientationChange -> state.copy(orientation = action.orientation)
            is AppAction.UpdateTabsTrayVisibility -> state.copy(isTabsTrayVisible = action.visible)
            is AppAction.UpdateStandardSnackbarErrorAction -> state.copy(standardSnackbarError = action.standardSnackbarError)
            
            is AppAction.AppLifecycleAction.StartAction -> state
            is AppAction.AppLifecycleAction.ResumeAction -> state.copy(isForeground = true)
            is AppAction.AppLifecycleAction.PauseAction -> state.copy(isForeground = false)

            is AppAction.SnackbarAction -> SnackbarStateReducer.reduce(state, action)
            is AppAction.SearchAction -> SearchStateReducer.reduce(state, action)
            
            is AppAction.MenuNotification.AddMenuNotification -> 
                state.copy(supportedMenuNotifications = state.supportedMenuNotifications + action.notification)
            is AppAction.MenuNotification.RemoveMenuNotification -> 
                state.copy(supportedMenuNotifications = state.supportedMenuNotifications - action.notification)
        }
}
