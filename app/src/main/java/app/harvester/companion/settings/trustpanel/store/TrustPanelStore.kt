package app.harvester.companion.settings.trustpanel.store

import androidx.annotation.VisibleForTesting
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.state.content.PermissionHighlightsState
import mozilla.components.concept.engine.permission.SitePermissions
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import app.harvester.companion.settings.PhoneFeature
import app.harvester.companion.utils.SitePermissionRules

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
        rules: SitePermissionRules,
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
            websitePermissionsState = createWebsitePermissionState(
                rules = rules,
                sitePermissions = sitePermissions,
                permissionHighlights = permissionHighlights,
                isPermissionBlockedByAndroid = isPermissionBlockedByAndroid,
            ),
        ),
        middleware = middleware,
    )

    companion object {
        @VisibleForTesting
        fun createWebsitePermissionState(
            rules: SitePermissionRules,
            sitePermissions: SitePermissions?,
            permissionHighlights: PermissionHighlightsState,
            isPermissionBlockedByAndroid: (PhoneFeature) -> Boolean,
        ) = PhoneFeature.entries
            .filterNot { it == PhoneFeature.AUTOPLAY_AUDIBLE || it == PhoneFeature.AUTOPLAY_INAUDIBLE }
            .associateWith { phoneFeature ->
                when (phoneFeature) {
                    PhoneFeature.AUTOPLAY -> {
                        WebsitePermission.Autoplay(
                            autoplayValue = sitePermissions.toAutoplayValue(rules),
                            isVisible = sitePermissions != null || permissionHighlights.isAutoPlayBlocking,
                            deviceFeature = phoneFeature,
                        )
                    }
                    PhoneFeature.LOCAL_NETWORK_ACCESS,
                    PhoneFeature.LOCAL_DEVICE_ACCESS -> {
                        val status = phoneFeature.getStatus(sitePermissions, rules)
                        WebsitePermission.Toggleable(
                            isEnabled = status.isAllowed(),
                            isBlockedByAndroid = false,
                            isVisible = rules.isLnaFeatureEnabled && sitePermissions != null && status.doNotAskAgain(),
                            deviceFeature = phoneFeature,
                        )
                    }
                    else -> {
                        val status = phoneFeature.getStatus(sitePermissions, rules)
                        WebsitePermission.Toggleable(
                            isEnabled = status.isAllowed(),
                            isBlockedByAndroid = isPermissionBlockedByAndroid(phoneFeature),
                            isVisible = sitePermissions != null && status.doNotAskAgain(),
                            deviceFeature = phoneFeature,
                        )
                    }
                }
            }

        private fun SitePermissions?.toAutoplayValue(rules: SitePermissionRules): AutoplayValue =
            this?.let { sitePermissions ->
                AutoplayValue.entries.find {
                    it.autoplayAudibleStatus == sitePermissions.autoplayAudible &&
                        it.autoplayInaudibleStatus == sitePermissions.autoplayInaudible
                }
            } ?: when (rules.getAutoplayUserSetting()) {
                1 -> AutoplayValue.AUTOPLAY_ALLOW_ALL
                4 -> AutoplayValue.AUTOPLAY_BLOCK_ALL
                else -> AutoplayValue.AUTOPLAY_BLOCK_AUDIBLE
            }
    }
}

private fun reducer(state: TrustPanelState, action: TrustPanelAction): TrustPanelState {
    return when (action) {
        is TrustPanelAction.Navigate, is TrustPanelAction.ClearSiteData, is TrustPanelAction.RequestClearSiteDataDialog,
        is TrustPanelAction.UpdateTrackersBlocked, is TrustPanelAction.TogglePermission, is TrustPanelAction.UpdateAutoplayValue,
        is TrustPanelAction.RequestQWAC -> state
        is TrustPanelAction.UpdateIPProtectionMenuState -> state.copy(ipProtectionMenuState = action.state)
        is TrustPanelAction.WebsitePermissionAction -> state.copy(websitePermissionsState = WebsitePermissionsStateReducer.reduce(state.websitePermissionsState, action))
        is TrustPanelAction.UpdateDetailedTrackerCategory -> state.copy(detailedTrackerCategory = action.detailedTrackerCategory)
        is TrustPanelAction.UpdateBaseDomain -> state.copy(baseDomain = action.baseDomain)
        is TrustPanelAction.ToggleTrackingProtection -> state.copy(isTrackingProtectionEnabled = !state.isTrackingProtectionEnabled)
        is TrustPanelAction.UpdateNumberOfTrackersBlocked -> state.copy(numberOfTrackersBlocked = action.newNumberOfTrackersBlocked)
        is TrustPanelAction.UpdateSitePermissions -> state.copy(sitePermissions = action.sitePermissions)
        is TrustPanelAction.UpdateQWAC -> if (action.forCertificate == state.websiteInfoState.certificate) state.copy(websiteInfoState = state.websiteInfoState.copy(qwac = action.qwac)) else state
        is TrustPanelAction.UpdateSecurityInfo -> state.copy(websiteInfoState = state.websiteInfoState.copy(isSecured = action.isSecured, certificate = action.certificate))
    }
}

private object WebsitePermissionsStateReducer {
    fun reduce(state: WebsitePermissionsState, action: TrustPanelAction.WebsitePermissionAction): WebsitePermissionsState {
        val key = action.updatedFeature
        val value = state[key]
        return when (action) {
            is TrustPanelAction.WebsitePermissionAction.GrantPermissionBlockedByAndroid -> {
                val toggleable = value as WebsitePermission.Toggleable
                state + Pair(key, toggleable.copy(isBlockedByAndroid = false))
            }
            is TrustPanelAction.WebsitePermissionAction.TogglePermission -> {
                val toggleable = value as WebsitePermission.Toggleable
                state + Pair(key, toggleable.copy(isEnabled = !value.isEnabled))
            }
            is TrustPanelAction.WebsitePermissionAction.ChangeAutoplay -> {
                val autoplay = value as WebsitePermission.Autoplay
                state + Pair(key, autoplay.copy(autoplayValue = action.autoplayValue))
            }
        }
    }
}
