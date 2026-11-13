package app.harvester.companion.search

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarAction
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarState
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import mozilla.components.lib.state.ext.flow
import app.harvester.companion.components.AppStore
import app.harvester.companion.components.appstate.AppAction.SearchAction.SearchEnded
import app.harvester.companion.browser.browsingmode.UhBrowsingModeManager

class UhSearchStatusSyncMiddleware(
    private val appStore: AppStore,
    private val browsingModeManager: UhBrowsingModeManager,
    private val scope: CoroutineScope,
) : Middleware<BrowserToolbarState, BrowserToolbarAction> {
    override fun invoke(
        store: Store<BrowserToolbarState, BrowserToolbarAction>,
        next: (BrowserToolbarAction) -> Unit,
        action: BrowserToolbarAction,
    ) {
        next(action)
        if (action is BrowserToolbarAction.Init) {
            scope.launch {
                appStore.flow().distinctUntilChangedBy { it.searchState.isSearchActive }.collect {
                    if (it.searchState.isSearchActive) {
                        store.dispatch(BrowserToolbarAction.EnterEditMode(browsingModeManager.mode.isPrivate))
                    } else {
                        store.dispatch(BrowserToolbarAction.ExitEditMode)
                    }
                }
            }
        }
        if (action is BrowserToolbarAction.ExitEditMode) {
            appStore.dispatch(SearchEnded)
        }
    }
}
