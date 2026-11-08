package app.harvester.companion.components.appstate.snackbar

import app.harvester.companion.components.appstate.AppAction
import app.harvester.companion.components.appstate.AppState

object SnackbarStateReducer {
    fun reduce(state: AppState, action: AppAction.SnackbarAction): AppState =
        when (action) {
            is AppAction.SnackbarAction.SnackbarDismissed ->
                state.copy(snackbarState = SnackbarState.Dismiss(state.snackbarState))
            is AppAction.SnackbarAction.ShowSnackbar ->
                state.copy(snackbarState = SnackbarState.ShowSnackbar(action.title, action.duration))
            is AppAction.SnackbarAction.SnackbarShown,
            is AppAction.SnackbarAction.Reset ->
                state.copy(snackbarState = SnackbarState.None(state.snackbarState))
        }
}
