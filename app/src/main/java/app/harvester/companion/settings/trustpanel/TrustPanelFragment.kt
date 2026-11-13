package app.harvester.companion.settings.trustpanel

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.selector.findTabOrCustomTab
import mozilla.components.lib.state.ext.observeAsComposableState
import mozilla.components.lib.state.helpers.StoreProvider.Companion.fragmentStore
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import mozilla.components.support.base.log.logger.Logger
import app.harvester.companion.components.menu.IPProtectionMenuBinding
import app.harvester.companion.settings.trustpanel.bindings.SecurityInfoBinding
import app.harvester.companion.settings.trustpanel.middleware.TrustPanelMiddleware
import app.harvester.companion.settings.trustpanel.middleware.TrustPanelNavigationMiddleware
import app.harvester.companion.settings.trustpanel.store.TrustPanelAction
import app.harvester.companion.settings.trustpanel.store.TrustPanelState
import app.harvester.companion.settings.trustpanel.store.TrustPanelStore
import app.harvester.companion.settings.trustpanel.store.WebsiteInfoState
import app.harvester.companion.settings.trustpanel.store.WebsitePermission
import app.harvester.companion.settings.trustpanel.ui.ClearSiteDataDialog
import app.harvester.companion.settings.trustpanel.ui.ProtectionPanel
import app.harvester.companion.settings.trustpanel.ui.TrackerCategoryDetailsPanel
import app.harvester.companion.settings.trustpanel.ui.TrackersBlockedPanel
import app.harvester.companion.theme.UHTheme
import app.harvester.companion.UHApp

class TrustPanelFragment : BottomSheetDialogFragment() {
    private val ipProtectionMenuBinding = ViewBoundFeatureWrapper<IPProtectionMenuBinding>()
    private val securityInfoBinding = ViewBoundFeatureWrapper<SecurityInfoBinding>()
    private val snackbarHostState = SnackbarHostState()

    private val store by fragmentStore(TrustPanelState()) {
        val lifecycleScope = viewLifecycleOwner.lifecycle.coroutineScope
        TrustPanelStore(
            isTrackingProtectionEnabled = true,
            websiteInfoState = WebsiteInfoState(isSecured = true, websiteUrl = "", websiteTitle = "", certificate = null),
            sessionState = null,
            settings = (requireContext().applicationContext as UHApp).components.settings,
            sitePermissions = null,
            permissionHighlights = mozilla.components.browser.state.state.content.PermissionHighlightsState(),
            isPermissionBlockedByAndroid = { false },
            middleware = emptyList()
        )
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = content {
        UHTheme {
            val components = (requireContext().applicationContext as UHApp).components
            var contentState: Route by remember { mutableStateOf(Route.ProtectionPanel) }

            BackHandler {
                when (contentState) {
                    Route.TrackersPanel -> contentState = Route.ProtectionPanel
                    Route.TrackerCategoryDetailsPanel -> contentState = Route.TrackersPanel
                    Route.ClearSiteDataDialog -> contentState = Route.ProtectionPanel
                    else -> this@TrustPanelFragment.dismissAllowingStateLoss()
                }
            }

            AnimatedContent(targetState = contentState, label = "TrustPanelAnimation") { route ->
                when (route) {
                    Route.ProtectionPanel -> {
                        val websiteInfoState by store.stateFlow.map { it.websiteInfoState }.collectAsState(initial = store.state.websiteInfoState)
                        val isTrackingProtectionEnabled by store.stateFlow.map { it.isTrackingProtectionEnabled }.collectAsState(initial = store.state.isTrackingProtectionEnabled)
                        val websitePermissions by store.stateFlow.map { it.websitePermissionsState.values }.collectAsState(initial = listOf())
                        
                        ProtectionPanel(
                            websiteInfoState = websiteInfoState,
                            icon = null,
                            isTrackingProtectionEnabled = isTrackingProtectionEnabled,
                            isGlobalTrackingProtectionEnabled = true,
                            isLocalPdf = false,
                            numberOfTrackersBlocked = 0,
                            websitePermissions = websitePermissions.filter { it.isVisible },
                            onTrackerBlockedMenuClick = { contentState = Route.TrackersPanel },
                            onTrackingProtectionToggleClick = { store.dispatch(TrustPanelAction.ToggleTrackingProtection) },
                            onClearSiteDataMenuClick = { contentState = Route.ClearSiteDataDialog },
                            onPrivacySecuritySettingsClick = { dismiss() },
                            onAutoplayValueClick = {},
                            onToggleablePermissionClick = {},
                            onViewCertificateClick = {},
                        )
                    }
                    Route.ClearSiteDataDialog -> {
                        ClearSiteDataDialog(
                            baseDomain = store.state.baseDomain ?: "",
                            onClearSiteDataClick = { store.dispatch(TrustPanelAction.ClearSiteData) },
                            onCancelClick = { dismiss() },
                        )
                    }
                    else -> {}
                }
            }
        }
    }
}

enum class Route {
    ProtectionPanel,
    TrackersPanel,
    TrackerCategoryDetailsPanel,
    ClearSiteDataDialog,
}
