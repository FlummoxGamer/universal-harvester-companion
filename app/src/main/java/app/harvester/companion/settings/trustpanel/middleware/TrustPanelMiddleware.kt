package app.harvester.companion.settings.trustpanel.middleware

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.concept.engine.Engine
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.session.TrackingProtectionUseCases
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import app.harvester.companion.settings.trustpanel.store.TrustPanelAction
import app.harvester.companion.settings.trustpanel.store.TrustPanelState

class TrustPanelMiddleware(
    private val engine: Engine,
    private val sessionUseCases: SessionUseCases,
    private val trackingProtectionUseCases: TrackingProtectionUseCases,
    private val onDismiss: suspend () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
) : Middleware<TrustPanelState, TrustPanelAction> {

    override fun invoke(
        store: Store<TrustPanelState, TrustPanelAction>,
        next: (TrustPanelAction) -> Unit,
        action: TrustPanelAction,
    ) {
        val currentState = store.state
        when (action) {
            is TrustPanelAction.ClearSiteData -> scope.launch {
                currentState.baseDomain?.let {
                    engine.clearData(host = it, data = Engine.BrowsingData.select(Engine.BrowsingData.AUTH_SESSIONS, Engine.BrowsingData.ALL_SITE_DATA))
                }
                onDismiss()
            }
            TrustPanelAction.ToggleTrackingProtection -> scope.launch {
                currentState.sessionState?.let { session ->
                    if (currentState.isTrackingProtectionEnabled) {
                        trackingProtectionUseCases.addException(session.id)
                    } else {
                        trackingProtectionUseCases.removeException(session.id)
                    }
                    sessionUseCases.reload.invoke(session.id)
                }
            }
            is TrustPanelAction.RequestClearSiteDataDialog -> scope.launch {
                store.dispatch(TrustPanelAction.UpdateBaseDomain(currentState.sessionState?.content?.url ?: ""))
            }
            else -> Unit
        }
        next(action)
    }
}
