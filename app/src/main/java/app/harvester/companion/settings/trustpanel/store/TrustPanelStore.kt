package app.harvester.companion.settings.trustpanel.store

import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.state.content.PermissionHighlightsState
import mozilla.components.concept.engine.permission.SitePermissions
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import app.harvester.companion.settings.PhoneFeature

class TrustPanelStore(
    initialState: TrustPanelState = TrustPanelState(),
    middleware: List<Middleware<TrustPanelState, TrustPanelAction>> = emptyList(),
) : Store<TrustPanelState, TrustPanelAction>(
    initialState = initialState,
    reducer = ::reducer,
    middleware = middleware,
) {
    constructor(
        isTrackingProtectionEnabled: Boolean,
        websiteInfoState: WebsiteInfoState,
        sessionState: SessionState?,
        settings: app.harvester.companion.utils.Settings,
        sitePermissions: SitePermissions?,
        permissionHighlights: PermissionHighlightsState,
        isPermissionBlockedByAndroid: (PhoneFeature) -> Boolean,
        middleware: List<Middleware<TrustPanelState, TrustPanelAction>> = emptyList(),
    ) : this(
        initialState = TrustPanelState(
            isTrackingProtectionEnabled = isTrackingProtectionEnabled,
            numberOfTrackersBlocked = sessionState?.trackingProtection?.blockedTrackers?.size ?: 0,
            sessionState = sessionState,
            sitePermissions = sitePermissions,
            websiteInfoState = websiteInfoState,
        ),
        middleware = middleware,
    )
}

private fun reducer(state: TrustPanelState, action: TrustPanelAction): TrustPanelState {
    return when (action) {
        is TrustPanelAction.UpdateIPProtectionMenuState -> state.copy(ipProtectionMenuState = action.state)
        is TrustPanelAction.UpdateBaseDomain -> state.copy(baseDomain = action.baseDomain)
        is TrustPanelAction.ToggleTrackingProtection -> state.copy(isTrackingProtectionEnabled = !state.isTrackingProtectionEnabled)
        is TrustPanelAction.UpdateQWAC -> if (action.forCertificate == state.websiteInfoState.certificate) state.copy(websiteInfoState = state.websiteInfoState.copy(qwac = action.qwac)) else state
        is TrustPanelAction.UpdateSecurityInfo -> state.copy(websiteInfoState = state.websiteInfoState.copy(isSecured = action.isSecured, certificate = action.certificate))
        else -> state
    }
}
