package app.harvester.companion.settings.trustpanel.store

import java.security.cert.X509Certificate
import mozilla.components.lib.state.Action

sealed class TrustPanelAction : Action {
    data object ClearSiteData : TrustPanelAction()
    data object ToggleTrackingProtection : TrustPanelAction()
    data object RequestClearSiteDataDialog : TrustPanelAction()
    data class UpdateBaseDomain(val baseDomain: String) : TrustPanelAction()
    data object RequestQWAC : TrustPanelAction()
    data class UpdateQWAC(val qwac: X509Certificate?, val forCertificate: X509Certificate?) : TrustPanelAction()
    data class UpdateSecurityInfo(val isSecured: Boolean, val certificate: X509Certificate?) : TrustPanelAction()
    data class UpdateIPProtectionMenuState(val state: app.harvester.companion.components.menu.store.IPProtectionMenuState) : TrustPanelAction()
}
