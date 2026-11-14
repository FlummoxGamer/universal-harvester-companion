package app.harvester.companion.settings.trustpanel

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.flow.map
import mozilla.components.browser.state.selector.findTabOrCustomTab
import mozilla.components.lib.state.helpers.StoreProvider.Companion.fragmentStore
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import app.harvester.companion.UHApp
import app.harvester.companion.settings.trustpanel.bindings.SecurityInfoBinding
import app.harvester.companion.settings.trustpanel.middleware.TrustPanelMiddleware
import app.harvester.companion.settings.trustpanel.middleware.TrustPanelNavigationMiddleware
import app.harvester.companion.settings.trustpanel.store.TrustPanelAction
import app.harvester.companion.settings.trustpanel.store.TrustPanelState
import app.harvester.companion.settings.trustpanel.store.TrustPanelStore
import app.harvester.companion.settings.trustpanel.store.WebsiteInfoState
import app.harvester.companion.settings.trustpanel.ui.ClearSiteDataDialog
import app.harvester.companion.settings.trustpanel.ui.ProtectionPanel
import app.harvester.companion.theme.UHTheme
import java.security.cert.X509Certificate

class TrustPanelFragment : BottomSheetDialogFragment() {
    private val securityInfoBinding = ViewBoundFeatureWrapper<SecurityInfoBinding>()
    private val args by lazy { requireNotNull(arguments) }

    private val store by fragmentStore(TrustPanelState()) {
        val components = (requireContext().applicationContext as UHApp).components
        val sessionId = args.getString("sessionId") ?: ""
        val session = components.store.state.findTabOrCustomTab(sessionId)

        TrustPanelStore(
            initialState = TrustPanelState(
                isTrackingProtectionEnabled = session?.trackingProtection?.enabled ?: true,
                sessionState = session,
                websiteInfoState = WebsiteInfoState(
                    isSecured = args.getBoolean("isSecured", true),
                    websiteUrl = args.getString("url") ?: "",
                    websiteTitle = args.getString("title") ?: "",
                    certificate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) args.getSerializable("certificate", X509Certificate::class.java) else @Suppress("DEPRECATION") args.getSerializable("certificate") as? X509Certificate
                )
            ),
            middleware = listOf(
                TrustPanelMiddleware(
                    engine = components.engine,
                    sessionUseCases = components.useCases.sessionUseCases,
                    trackingProtectionUseCases = components.useCases.trackingProtectionUseCases,
                    onDismiss = { dismiss() },
                    scope = viewLifecycleOwner.lifecycleScope
                ),
                TrustPanelNavigationMiddleware(
                    tabsUseCases = components.useCases.tabsUseCases,
                    appStore = components.appStore,
                    scope = viewLifecycleOwner.lifecycleScope
                )
            )
        )
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = content {
        UHTheme {
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
                        val websitePermissions by store.stateFlow.map { it.websitePermissionsState.values }.collectAsState(initial = emptyList())
                        
                        ProtectionPanel(
                            websiteInfoState = websiteInfoState,
                            icon = null,
                            isTrackingProtectionEnabled = isTrackingProtectionEnabled,
                            isGlobalTrackingProtectionEnabled = true,
                            isLocalPdf = false,
                            numberOfTrackersBlocked = store.state.numberOfTrackersBlocked,
                            websitePermissions = websitePermissions.filter { it.isVisible },
                            onTrackerBlockedMenuClick = { contentState = Route.TrackersPanel },
                            onTrackingProtectionToggleClick = { store.dispatch(TrustPanelAction.ToggleTrackingProtection) },
                            onClearSiteDataMenuClick = { contentState = Route.ClearSiteDataDialog },
                            onPrivacySecuritySettingsClick = { dismiss() },
                            onAutoplayValueClick = {},
                            onToggleablePermissionClick = {},
                            onViewCertificateClick = { store.dispatch(TrustPanelAction.Navigate.SecurityCertificate) },
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val components = (requireContext().applicationContext as UHApp).components
        securityInfoBinding.set(
            feature = SecurityInfoBinding(
                browserStore = components.store,
                sessionId = args.getString("sessionId") ?: "",
                onSecurityInfoUpdate = { store.dispatch(it) }
            ),
            owner = viewLifecycleOwner,
            view = view
        )
    }

    companion object {
        fun newInstance(sessionId: String, url: String, title: String, isSecured: Boolean, certificate: X509Certificate?): TrustPanelFragment {
            val fragment = TrustPanelFragment()
            fragment.arguments = Bundle().apply {
                putString("sessionId", sessionId)
                putString("url", url)
                putString("title", title)
                putBoolean("isSecured", isSecured)
                putSerializable("certificate", certificate)
            }
            return fragment
        }
    }
}

enum class Route {
    ProtectionPanel,
    TrackersPanel,
    TrackerCategoryDetailsPanel,
    ClearSiteDataDialog,
}
