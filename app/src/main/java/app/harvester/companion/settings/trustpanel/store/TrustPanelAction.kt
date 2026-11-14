package app.harvester.companion.settings.trustpanel.store

import java.security.cert.X509Certificate
import mozilla.components.concept.engine.content.blocking.TrackerLog
import mozilla.components.concept.engine.permission.SitePermissions
import mozilla.components.lib.state.Action
import app.harvester.companion.components.menu.store.IPProtectionMenuState
import app.harvester.companion.settings.PhoneFeature
import app.harvester.companion.trackingprotection.TrackingProtectionCategory

sealed class TrustPanelAction : Action {
    data object ClearSiteData : TrustPanelAction()
    data object ToggleTrackingProtection : TrustPanelAction()
    data object RequestClearSiteDataDialog : TrustPanelAction()
    data class UpdateBaseDomain(val baseDomain: String) : TrustPanelAction()
    data class UpdateDetailedTrackerCategory(val detailedTrackerCategory: TrackingProtectionCategory) : TrustPanelAction()
    data class UpdateNumberOfTrackersBlocked(val newNumberOfTrackersBlocked: Int) : TrustPanelAction()
    data class UpdateTrackersBlocked(val trackerLogs: List<TrackerLog>) : TrustPanelAction()
    data class UpdateSitePermissions(val sitePermissions: SitePermissions) : TrustPanelAction()
    data class UpdateIPProtectionMenuState(val state: IPProtectionMenuState) : TrustPanelAction()
    data object RequestQWAC : TrustPanelAction()
    data class UpdateQWAC(val qwac: X509Certificate?, val forCertificate: X509Certificate?) : TrustPanelAction()
    data class UpdateSecurityInfo(val isSecured: Boolean, val certificate: X509Certificate?) : TrustPanelAction()
    data class TogglePermission(val permission: WebsitePermission.Toggleable) : TrustPanelAction()
    data class UpdateAutoplayValue(val autoplayValue: AutoplayValue) : TrustPanelAction()

    sealed class WebsitePermissionAction(open val updatedFeature: PhoneFeature) : TrustPanelAction() {
        class GrantPermissionBlockedByAndroid(override val updatedFeature: PhoneFeature) : WebsitePermissionAction(updatedFeature)
        class TogglePermission(override val updatedFeature: PhoneFeature) : WebsitePermissionAction(updatedFeature)
        class ChangeAutoplay(val autoplayValue: AutoplayValue) : WebsitePermissionAction(PhoneFeature.AUTOPLAY)
    }

    sealed class Navigate : TrustPanelAction() {
        data object PrivacySecuritySettings : Navigate()
        data class ManagePhoneFeature(val phoneFeature: PhoneFeature) : Navigate()
        data object SecurityCertificate : Navigate()
        data object QWAC : Navigate()
        data object TrackersProtectionDashboard : Navigate()
        data object IPProtectionSettings : Navigate()
    }
}
