package app.harvester.companion.settings.trustpanel.middleware

import androidx.activity.result.ActivityResultLauncher
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.content.blocking.TrackerLog
import mozilla.components.concept.engine.permission.SitePermissions
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.session.TrackingProtectionUseCases
import mozilla.components.lib.publicsuffixlist.PublicSuffixList
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import mozilla.components.support.ktx.kotlin.getOrigin
import app.harvester.companion.components.PermissionStorage
import app.harvester.companion.settings.trustpanel.store.AutoplayValue
import app.harvester.companion.settings.trustpanel.store.TrustPanelAction
import app.harvester.companion.settings.trustpanel.store.TrustPanelState
import app.harvester.companion.settings.trustpanel.store.WebsitePermission
import app.harvester.companion.settings.trustpanel.store.WebsitePermissionsState
import app.harvester.companion.trackingprotection.TrackerBuckets
import app.harvester.companion.trackingprotection.TrackingProtectionCategory
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
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : Middleware<TrustPanelState, TrustPanelAction> {

    override fun invoke(
        store: Store<TrustPanelState, TrustPanelAction>,
        next: (TrustPanelAction) -> Unit,
        action: TrustPanelAction,
    ) {
        val currentState = store.state
        when (action) {
            is TrustPanelAction.ClearSiteData -> clearSiteData(currentState)
            is TrustPanelAction.RequestClearSiteDataDialog -> requestClearSiteDataDialog(currentState, store)
            TrustPanelAction.ToggleTrackingProtection -> toggleTrackingProtection(currentState)
            is TrustPanelAction.UpdateTrackersBlocked -> updateTrackersBlocked(currentState, action.trackerLogs, store)
            is TrustPanelAction.TogglePermission -> togglePermission(currentState, action.permission, store)
            is TrustPanelAction.UpdateAutoplayValue -> updateAutoplayValue(currentState, action.autoplayValue, store)
            is TrustPanelAction.RequestQWAC -> requestQwac(currentState, store)
            else -> Unit
        }
        next(action)
        if (action is TrustPanelAction.UpdateSecurityInfo && action.certificate != null && action.certificate != currentState.websiteInfoState.certificate) {
            store.dispatch(TrustPanelAction.RequestQWAC)
        }
    }

    private fun requestQwac(currentState: TrustPanelState, store: Store<TrustPanelState, TrustPanelAction>) {
        val certificate = currentState.websiteInfoState.certificate
        scope.launch(mainDispatcher) {
            currentState.sessionState?.engineState?.engineSession?.qwacStatus { qwac ->
                store.dispatch(TrustPanelAction.UpdateQWAC(qwac = qwac, forCertificate = certificate))
            }
        }
    }

    private fun toggleTrackingProtection(currentState: TrustPanelState) = scope.launch {
        currentState.sessionState?.let { session ->
            if (currentState.isTrackingProtectionEnabled) {
                trackingProtectionUseCases.addException(session.id)
            } else {
                trackingProtectionUseCases.removeException(session.id)
            }
            sessionUseCases.reload.invoke(session.id)
        }
    }

    private fun updateTrackersBlocked(currentState: TrustPanelState, newTrackersBlocked: List<TrackerLog>, store: Store<TrustPanelState, TrustPanelAction>) = scope.launch {
        currentState.bucketedTrackers.updateIfNeeded(newTrackersBlocked)
        store.dispatch(TrustPanelAction.UpdateNumberOfTrackersBlocked(currentState.bucketedTrackers.numberOfTrackersBlocked()))
    }

    private fun requestClearSiteDataDialog(currentState: TrustPanelState, store: Store<TrustPanelState, TrustPanelAction>) = scope.launch {
        val host = currentState.sessionState?.content?.url?.toUri()?.host.orEmpty()
        val domain = publicSuffixList.getPublicSuffixPlusOne(host).await()
        domain?.let { store.dispatch(TrustPanelAction.UpdateBaseDomain(it)) }
    }

    private fun clearSiteData(currentState: TrustPanelState) = scope.launch {
        currentState.baseDomain?.let {
            engine.clearData(host = it, data = Engine.BrowsingData.select(Engine.BrowsingData.AUTH_SESSIONS, Engine.BrowsingData.ALL_SITE_DATA))
        }
        onDismiss()
    }

    private fun togglePermission(currentState: TrustPanelState, permission: WebsitePermission.Toggleable, store: Store<TrustPanelState, TrustPanelAction>) = scope.launch {
        if (permission.isBlockedByAndroid) {
            requestPermissionsLauncher.launch(permission.deviceFeature.androidPermissionsList)
            return@launch
        }
        if (currentState.sitePermissions == null) {
            store.dispatch(TrustPanelAction.Navigate.ManagePhoneFeature(permission.deviceFeature))
            return@launch
        }
        currentState.sessionState?.let { session ->
            val updatedSitePermissions = currentState.sitePermissions.toggle(permission.deviceFeature)
            permissionStorage.updateSitePermissions(sitePermissions = updatedSitePermissions, private = session.content.private)
            store.dispatch(TrustPanelAction.UpdateSitePermissions(updatedSitePermissions))
            store.dispatch(TrustPanelAction.WebsitePermissionAction.TogglePermission(permission.deviceFeature))
            sessionUseCases.reload.invoke(session.id)
        }
    }

    private fun updateAutoplayValue(currentState: TrustPanelState, autoplayValue: AutoplayValue, store: Store<TrustPanelState, TrustPanelAction>) = scope.launch {
        if (currentState.websitePermissionsState.getAutoplayValue() == autoplayValue) return@launch
        currentState.sessionState?.let { session ->
            val updatedSitePermissions: SitePermissions
            if (currentState.sitePermissions == null) {
                val origin = requireNotNull(session.content.url.getOrigin())
                updatedSitePermissions = settings.getSitePermissionsCustomSettingsRules().toSitePermissions(origin).updateAutoplayPermissions(autoplayValue)
                permissionStorage.add(sitePermissions = updatedSitePermissions, private = session.content.private)
            } else {
                updatedSitePermissions = currentState.sitePermissions.updateAutoplayPermissions(autoplayValue)
                permissionStorage.updateSitePermissions(sitePermissions = updatedSitePermissions, private = session.content.private)
            }
            store.dispatch(TrustPanelAction.UpdateSitePermissions(updatedSitePermissions))
            store.dispatch(TrustPanelAction.WebsitePermissionAction.ChangeAutoplay(autoplayValue))
            sessionUseCases.reload.invoke(session.id)
        }
    }

    private fun SitePermissions.updateAutoplayPermissions(autoplayValue: AutoplayValue) = this.copy(
        autoplayAudible = autoplayValue.autoplayAudibleStatus,
        autoplayInaudible = autoplayValue.autoplayInaudibleStatus,
    )

    private fun WebsitePermissionsState.getAutoplayValue() = (this[app.harvester.companion.settings.PhoneFeature.AUTOPLAY] as? WebsitePermission.Autoplay)?.autoplayValue
}

private fun TrackerBuckets.numberOfTrackersBlocked() = TrackingProtectionCategory.entries.sumOf { this.get(it, true).size }
