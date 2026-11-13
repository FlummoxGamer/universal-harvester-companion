package app.harvester.companion.settings.trustpanel.middleware

import androidx.navigation.NavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import app.harvester.companion.components.AppStore
import app.harvester.companion.settings.trustpanel.store.TrustPanelAction
import app.harvester.companion.settings.trustpanel.store.TrustPanelState

class TrustPanelNavigationMiddleware(
    private val navController: NavController,
    private val appStore: AppStore,
    private val tabsUseCases: TabsUseCases,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
) : Middleware<TrustPanelState, TrustPanelAction> {
    override fun invoke(store: Store<TrustPanelState, TrustPanelAction>, next: (TrustPanelAction) -> Unit, action: TrustPanelAction) {
        next(action)
    }
}
