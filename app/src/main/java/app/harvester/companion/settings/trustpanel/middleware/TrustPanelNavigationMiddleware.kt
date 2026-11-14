package app.harvester.companion.settings.trustpanel.middleware

import android.net.Uri
import android.util.Base64
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
    private val tabsUseCases: TabsUseCases,
    private val appStore: AppStore,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
) : Middleware<TrustPanelState, TrustPanelAction> {

    override fun invoke(
        store: Store<TrustPanelState, TrustPanelAction>,
        next: (TrustPanelAction) -> Unit,
        action: TrustPanelAction,
    ) {
        next(action)
        scope.launch {
            if (action is TrustPanelAction.Navigate.SecurityCertificate) {
                val cert = store.state.websiteInfoState.certificate
                if (cert != null) {
                    val base64 = Base64.encodeToString(cert.encoded, Base64.NO_WRAP or Base64.NO_PADDING)
                    tabsUseCases.addTab("about:certificate?cert=${Uri.encode(base64)}", private = appStore.state.mode.isPrivate)
                }
            }
        }
    }
}
