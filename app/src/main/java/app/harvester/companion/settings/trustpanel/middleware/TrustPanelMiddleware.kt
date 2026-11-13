package app.harvester.companion.settings.trustpanel.middleware

import androidx.activity.result.ActivityResultLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import mozilla.components.concept.engine.Engine
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.session.TrackingProtectionUseCases
import mozilla.components.lib.publicsuffixlist.PublicSuffixList
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import app.harvester.companion.components.PermissionStorage
import app.harvester.companion.settings.trustpanel.store.TrustPanelAction
import app.harvester.companion.settings.trustpanel.store.TrustPanelState
import app.harvester.companion.utils.Settings

class TrustPanelMiddleware(
    private val engine: Engine,
    private val publicSuffixList: PublicSuffixList,
    private val sessionUseCases: SessionUseCases,
    private val trackingProtectionUseCases: TrackingProtectionUseCases,
    private val settings: Settings,
    private val permissionStorage: PermissionStorage,
    private val requestPermissionsLauncher: ActivityResultLauncher<Array<String>>,
    private val onDismiss: suspend () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
) : Middleware<TrustPanelState, TrustPanelAction> {
    override fun invoke(store: Store<TrustPanelState, TrustPanelAction>, next: (TrustPanelAction) -> Unit, action: TrustPanelAction) {
        next(action)
    }
}
