package app.harvester.companion.settings.trustpanel.middleware

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.browser.state.state.SessionState
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import app.harvester.companion.components.AppStore
import app.harvester.companion.settings.trustpanel.store.TrustPanelAction
import app.harvester.companion.settings.trustpanel.store.TrustPanelState
import app.harvester.companion.settings.trustpanel.store.TrustPanelStore

class TrustPanelNavigationMiddleware(
    private val privacySecurityPrefKey: String,
    private val appStore: AppStore,
    private val tabsUseCases: TabsUseCases,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
) : Middleware<TrustPanelState, TrustPanelAction> {

    override fun invoke(
        store: Store<TrustPanelState, TrustPanelAction>,
        next: (TrustPanelAction) -> Unit,
        action: TrustPanelAction,
    ) {
        next(action)
        scope.launch {
            when (action) {
                is TrustPanelAction.Navigate.SecurityCertificate -> viewCertificate(store.state.websiteInfoState.certificate?.encoded, store.state.sessionState)
                is TrustPanelAction.Navigate.QWAC -> viewCertificate(store.state.websiteInfoState.qwac?.encoded, store.state.sessionState)
                else -> Unit
            }
        }
    }

    private fun viewCertificate(certificateBytes: ByteArray?, sessionState: SessionState?) {
        if (certificateBytes == null) return
        val base64 = Base64.encodeToString(certificateBytes, Base64.NO_WRAP or Base64.NO_PADDING)
        tabsUseCases.addTab(
            "about:certificate?cert=${Uri.encode(base64)}",
            parentId = sessionState?.id,
            contextId = sessionState?.contextId,
            private = appStore.state.mode.isPrivate,
        )
    }
}
