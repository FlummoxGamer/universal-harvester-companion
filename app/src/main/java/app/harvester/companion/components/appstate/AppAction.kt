package app.harvester.companion.components.appstate

import com.google.android.material.snackbar.BaseTransientBottomBar.LENGTH_SHORT
import mozilla.components.browser.state.search.SearchEngine
import mozilla.components.lib.state.Action

sealed class AppAction : Action {
    data class UpdateFirstFrameDrawn(val drawn: Boolean) : AppAction()
    data class BrowsingModeManagerModeChanged(val mode: BrowsingMode) : AppAction()
    data class OrientationChange(val orientation: OrientationMode) : AppAction()
    data class UpdateTabsTrayVisibility(val visible: Boolean) : AppAction()
    data class UpdateStandardSnackbarErrorAction(val standardSnackbarError: StandardSnackbarError?) : AppAction()

    sealed class AppLifecycleAction : AppAction() {
        object StartAction : AppLifecycleAction()
        object ResumeAction : AppLifecycleAction()
        object PauseAction : AppLifecycleAction()
    }

    sealed class SnackbarAction : AppAction() {
        data object SnackbarDismissed : SnackbarAction()
        data object SnackbarShown : SnackbarAction()
        data object Reset : SnackbarAction()
        data class ShowSnackbar(val title: String, val duration: Int = LENGTH_SHORT) : SnackbarAction()
    }

    sealed class SearchAction : AppAction() {
        data class SearchStarted(
            val tabId: String? = null,
            val source: MetricsUtils.Source = MetricsUtils.Source.NONE,
        ) : SearchAction()
        data object SearchEnded : SearchAction()
        data class SearchEngineSelected(
            val searchEngine: SearchEngine,
            val isUserSelected: Boolean,
        ) : SearchAction()
    }

    sealed class MenuNotification : AppAction() {
        data class AddMenuNotification(val notification: SupportedMenuNotifications) : MenuNotification()
        data class RemoveMenuNotification(val notification: SupportedMenuNotifications) : MenuNotification()
    }
}
