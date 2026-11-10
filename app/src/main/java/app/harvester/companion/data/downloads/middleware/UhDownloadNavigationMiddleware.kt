package app.harvester.companion.data.downloads.middleware

import androidx.navigation.NavController
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import app.harvester.companion.R
import app.harvester.companion.data.downloads.UhDownloadUIAction
import app.harvester.companion.data.downloads.UhDownloadUIState

class UhDownloadNavigationMiddleware(private val navController: NavController) :
    Middleware<UhDownloadUIState, UhDownloadUIAction> {

    override fun invoke(
        store: Store<UhDownloadUIState, UhDownloadUIAction>,
        next: (UhDownloadUIAction) -> Unit,
        action: UhDownloadUIAction,
    ) {
        next(action)
        when (action) {
            is UhDownloadUIAction.NavigationIconClicked -> navController.popBackStack()
            is UhDownloadUIAction.SettingsIconClicked -> {
                navController.navigate(R.id.action_downloadsFragment_to_downloadSettingsFragment)
            }
            else -> {}
        }
    }
}
