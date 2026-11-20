package app.harvester.companion.data.downloads.middleware

import androidx.navigation.NavController
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import app.harvester.companion.R
import app.harvester.companion.UHLog
import app.harvester.companion.data.downloads.UhDownloadUIAction
import app.harvester.companion.data.downloads.UhDownloadUIState

class UhDownloadNavigationMiddleware(private val navControllerProvider: () -> NavController?) :
    Middleware<UhDownloadUIState, UhDownloadUIAction> {

    override fun invoke(
        store: Store<UhDownloadUIState, UhDownloadUIAction>,
        next: (UhDownloadUIAction) -> Unit,
        action: UhDownloadUIAction,
    ) {
        next(action)
        val navController = navControllerProvider()
        if (navController == null && (action is UhDownloadUIAction.NavigationIconClicked || action is UhDownloadUIAction.SettingsIconClicked)) {
            UHLog.w("ui_warn", "Download navigation ignored because NavController is unavailable.")
            return
        }
        when (action) {
            is UhDownloadUIAction.NavigationIconClicked -> {
                try {
                    navController?.popBackStack()
                } catch (e: Exception) {
                    UHLog.throwable("ui_error", "Failed popping downloads backstack", e)
                }
            }
            is UhDownloadUIAction.SettingsIconClicked -> {
                try {
                    navController?.navigate(R.id.action_downloadsFragment_to_settingsFragment)
                } catch (e: Exception) {
                    UHLog.throwable("ui_error", "Failed navigating to download settings", e)
                }
            }
            else -> {}
        }
    }
}
