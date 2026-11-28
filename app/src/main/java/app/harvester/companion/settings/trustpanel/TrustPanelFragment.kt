package app.harvester.companion.settings.trustpanel

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.compose.content
import androidx.lifecycle.lifecycleScope
import android.widget.Toast
import app.harvester.companion.GeckoWebExtensionBridge
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.UHApp
import app.harvester.companion.components.PermissionStorage
import app.harvester.companion.privacy.ShieldLevel
import app.harvester.companion.privacy.UhPrivacyController
import app.harvester.companion.privacy.UhShieldEngine
import app.harvester.companion.settings.privacy.PrivacyGuardActivity
import app.harvester.companion.settings.privacy.SiteSettingsActivity
import app.harvester.companion.settings.trustpanel.bindings.SecurityInfoBinding
import app.harvester.companion.settings.trustpanel.middleware.TrustPanelMiddleware
import app.harvester.companion.settings.trustpanel.middleware.TrustPanelNavigationMiddleware
import app.harvester.companion.settings.trustpanel.store.AutoplayValue
import app.harvester.companion.settings.trustpanel.store.TrustPanelAction
import app.harvester.companion.settings.trustpanel.store.TrustPanelState
import app.harvester.companion.settings.trustpanel.store.TrustPanelStore
import app.harvester.companion.settings.trustpanel.store.WebsiteInfoState
import app.harvester.companion.settings.trustpanel.ui.ClearSiteDataDialog
import app.harvester.companion.settings.trustpanel.ui.LiveRequestInspectorPanel
import app.harvester.companion.settings.trustpanel.ui.ProtectionPanel
import app.harvester.companion.settings.trustpanel.ui.TrackerCategoryDetailsPanel
import app.harvester.companion.settings.trustpanel.ui.TrackersBlockedPanel
import app.harvester.companion.theme.UHTheme
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.selector.findTabOrCustomTab
import mozilla.components.lib.state.helpers.StoreProvider.Companion.fragmentStore
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import mozilla.components.support.ktx.kotlin.getOrigin
import java.security.MessageDigest
import java.security.cert.X509Certificate

class TrustPanelFragment : BottomSheetDialogFragment() {
    private val args by lazy { requireNotNull(arguments) }
    private val securityInfoBinding = ViewBoundFeatureWrapper<SecurityInfoBinding>()
    private var permissionsCallback: ((Map<String, Boolean>) -> Unit) = {}

    private val requestPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { isGranted ->
        permissionsCallback.invoke(isGranted)
    }

    private val store by fragmentStore(TrustPanelState()) {
        val components = (requireContext().applicationContext as UHApp).components
        val sessionId = args.getString("sessionId") ?: ""
        val session = components.store.state.findTabOrCustomTab(sessionId)

        TrustPanelStore(
            isTrackingProtectionEnabled = session?.trackingProtection?.enabled ?: true,
            websiteInfoState = WebsiteInfoState(
                isSecured = args.getBoolean("isSecured", true),
                websiteUrl = args.getString("url") ?: "",
                websiteTitle = args.getString("title") ?: "",
                certificate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    args.getSerializable("certificate", X509Certificate::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    args.getSerializable("certificate") as? X509Certificate
                },
            ),
            sessionState = session,
            rules = components.sitePermissionRules,
            sitePermissions = null,
            permissionHighlights = session?.content?.permissionHighlights
                ?: mozilla.components.browser.state.state.content.PermissionHighlightsState(),
            isPermissionBlockedByAndroid = { false },
            middleware = listOf(
                TrustPanelMiddleware(
                    engine = components.engine,
                    publicSuffixList = components.publicSuffixList,
                    sessionUseCases = components.useCases.sessionUseCases,
                    trackingProtectionUseCases = components.useCases.trackingProtectionUseCases,
                    rules = components.sitePermissionRules,
                    permissionStorage = PermissionStorage(requireContext()),
                    requestPermissionsLauncher = requestPermissionsLauncher,
                    onDismiss = { dismissAllowingStateLoss() },
                    scope = viewLifecycleOwner.lifecycleScope,
                ),
                TrustPanelNavigationMiddleware(
                    privacySecurityPrefKey = "",
                    appStore = components.appStore,
                    tabsUseCases = components.useCases.tabsUseCases,
                    scope = viewLifecycleOwner.lifecycleScope,
                ),
            ),
        )
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = content {
        val components = (requireContext().applicationContext as UHApp).components
        val url = args.getString("url") ?: ""

        LaunchedEffect(url) {
            val origin = url.getOrigin() ?: return@LaunchedEffect
            val isPrivate = store.state.sessionState?.content?.private == true
            val found = withContext(Dispatchers.IO) {
                try {
                    PermissionStorage(requireContext()).findSitePermissionsBy(origin, private = isPrivate)
                } catch (_: Exception) {
                    null
                }
            }
            if (found != null) {
                store.dispatch(TrustPanelAction.UpdateSitePermissions(found))
            }
        }

        UHTheme {
            val ctx = LocalContext.current
            var contentState: Route by remember { mutableStateOf(Route.ProtectionPanel) }
            var showCertDialog by remember { mutableStateOf(false) }
            var showAutoplayDialog by remember { mutableStateOf(false) }

            val shieldState by UhShieldEngine.state.collectAsState()
            val hostTick by UhShieldEngine.hostStatsTick.collectAsState()
            val requestLogTick by UhShieldEngine.requestLogTick.collectAsState()
            val jsTick by UhShieldEngine.jsBlockedTick.collectAsState()
            val jsAllowAlwaysHosts by UhShieldEngine.jsAllowAlwaysHosts.collectAsState()
            val darkBypassHosts by UhPrivacyController.darkBypassHosts.collectAsState()
            val trustState by store.stateFlow.collectAsState()

            val websiteInfoState = trustState.websiteInfoState
            val websitePermissions = trustState.websitePermissionsState.values
            val trackersBlockedCount = trustState.numberOfTrackersBlocked
            val baseDomain = trustState.baseDomain

            if (showCertDialog) {
                val cert = websiteInfoState.certificate
                val sha256 = remember(cert) {
                    try {
                        cert?.encoded?.let { bytes ->
                            MessageDigest.getInstance("SHA-256").digest(bytes)
                                .joinToString(":") { "%02X".format(it) }
                        } ?: "Unavailable"
                    } catch (_: Exception) {
                        "Unavailable"
                    }
                }
                AlertDialog(
                    onDismissRequest = { showCertDialog = false },
                    title = {
                        Text(
                            text = if (websiteInfoState.isSecured) "TLS Certificate" else "Connection Info",
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    text = {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (cert != null) {
                                CertField("Issued To", cert.subjectX500Principal.name)
                                CertField("Issued By", cert.issuerX500Principal.name)
                                CertField("Valid From", cert.notBefore.toString())
                                CertField("Expires", cert.notAfter.toString())
                                CertField("SHA-256 Fingerprint", sha256, mono = true)
                            } else {
                                Text(
                                    text = if (websiteInfoState.isSecured) {
                                        "Encrypted HTTPS connection verified by GeckoView."
                                    } else {
                                        "This connection is unencrypted HTTP."
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showCertDialog = false }) {
                            Text("Close", color = MaterialTheme.colorScheme.primary)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.background,
                    shape = RoundedCornerShape(20.dp),
                )
            }

            if (showAutoplayDialog) {
                AlertDialog(
                    onDismissRequest = { showAutoplayDialog = false },
                    title = { Text("Autoplay Setting", color = MaterialTheme.colorScheme.onSurface) },
                    text = {
                        Column {
                            listOf(
                                AutoplayValue.AUTOPLAY_ALLOW_ALL to "Allow audio and video",
                                AutoplayValue.AUTOPLAY_BLOCK_AUDIBLE to "Block audio only",
                                AutoplayValue.AUTOPLAY_BLOCK_ALL to "Block audio and video",
                            ).forEach { (value, label) ->
                                TextButton(
                                    onClick = {
                                        showAutoplayDialog = false
                                        store.dispatch(TrustPanelAction.UpdateAutoplayValue(value))
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(label, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showAutoplayDialog = false }) {
                            Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.background,
                    shape = RoundedCornerShape(20.dp),
                )
            }

            BackHandler {
                when (contentState) {
                    Route.TrackersPanel -> contentState = Route.ProtectionPanel
                    Route.TrackerCategoryDetailsPanel -> contentState = Route.TrackersPanel
                    Route.ClearSiteDataDialog -> contentState = Route.ProtectionPanel
                    Route.RequestInspectorPanel -> contentState = Route.ProtectionPanel
                    else -> this@TrustPanelFragment.dismissAllowingStateLoss()
                }
            }

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Surface(
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth(),
                ) {
                    AnimatedContent(targetState = contentState, label = "TrustPanelAnimation") { route ->
                        when (route) {
                            Route.ProtectionPanel -> {
                                val siteUrl = websiteInfoState.websiteUrl.ifBlank { url }
                                val siteHost = remember(siteUrl) { UhShieldEngine.normalizeHost(siteUrl) }
                                val siteStats = remember(siteHost, hostTick, trackersBlockedCount) {
                                    val engineHostStats = UhShieldEngine.getHostStats(siteHost)
                                    val mergedTrackers = maxOf(engineHostStats.trackersBlocked, trackersBlockedCount.toLong())
                                    val mergedSaved = engineHostStats.estimatedTimeSavedMs +
                                        ((mergedTrackers - engineHostStats.trackersBlocked).coerceAtLeast(0L) * 45L)
                                    engineHostStats.copy(
                                        trackersBlocked = mergedTrackers,
                                        estimatedTimeSavedMs = mergedSaved,
                                    )
                                }
                                val isShieldPausedForSite = siteHost.isNotBlank() && siteHost in shieldState.pausedHosts
                                val showSmartJsToggle = remember {
                                    SettingsPrefs.getBool(ctx, SettingsPrefs.K_PG_SCRIPT, false)
                                }
                                val isSmartJsAllowedForSite = remember(siteHost, jsAllowAlwaysHosts, jsTick) {
                                    siteHost.isNotBlank() && UhShieldEngine.isJsAllowedForHost(siteHost)
                                }
                                val smartJsBlockedCount = remember(siteHost, jsTick) {
                                    UhShieldEngine.getJsBlockedState(siteHost)?.totalBlocked ?: 0
                                }
                                val showWebDarkToggle = false
                                val isWebDarkBypassed = false
                                val requestLogs = remember(siteHost, requestLogTick) {
                                    UhShieldEngine.getRequestLogForHost(siteHost)
                                }
                                val requestLogsBlockedCount = remember(requestLogs) {
                                    requestLogs.count { it.action.isBlocked }
                                }

                                ProtectionPanel(
                                    websiteInfoState = websiteInfoState,
                                    siteHost = siteHost,
                                    siteStats = siteStats,
                                    lifetimeStats = shieldState.lifetimeStats,
                                    shieldLevel = shieldState.level,
                                    isShieldPausedForSite = isShieldPausedForSite,
                                    showSmartJsToggle = showSmartJsToggle,
                                    isSmartJsAllowedForSite = isSmartJsAllowedForSite,
                                    smartJsBlockedCount = smartJsBlockedCount,
                                    showWebDarkToggle = showWebDarkToggle,
                                    isWebDarkEnabledForSite = !isWebDarkBypassed,
                                    numberOfGeckoTrackersBlocked = trackersBlockedCount,
                                    requestLogTotalCount = requestLogs.size,
                                    requestLogBlockedCount = requestLogsBlockedCount,
                                    websitePermissions = websitePermissions.filter { it.isVisible },
                                    onToggleShieldForSite = {
                                        if (shieldState.level == ShieldLevel.OFF) {
                                            dismissAllowingStateLoss()
                                            ctx.startActivity(Intent(ctx, PrivacyGuardActivity::class.java))
                                        } else {
                                            val paused = UhShieldEngine.toggleSitePaused(siteUrl)
                                            // Keep Gecko per-site ETP aligned with UH Shield pause state
                                            val geckoEnabled = trustState.isTrackingProtectionEnabled
                                            if ((paused && geckoEnabled) || (!paused && !geckoEnabled)) {
                                                store.dispatch(TrustPanelAction.ToggleTrackingProtection)
                                            } else {
                                                store.state.sessionState?.let {
                                                    components.useCases.sessionUseCases.reload(it.id)
                                                }
                                            }
                                            UhPrivacyController.pushRuntimeConfigToExtension()
                                        }
                                    },
                                    onToggleSmartJsForSite = {
                                        if (isSmartJsAllowedForSite) {
                                            UhShieldEngine.revokeJsAlways(siteHost)
                                        } else {
                                            UhShieldEngine.allowJsAlways(siteHost)
                                        }
                                        UhPrivacyController.pushLightweightSettingsToExtension()
                                        store.state.sessionState?.let {
                                            components.useCases.sessionUseCases.reload(it.id)
                                        }
                                    },
                                    onToggleWebDarkForSite = {
                                        UhPrivacyController.toggleSiteDarkBypass(ctx, siteUrl)
                                    },
                                    onBlockElementClick = {
                                        dismissAllowingStateLoss()
                                        val started = GeckoWebExtensionBridge.startElementPicker()
                                        if (!started) {
                                            Toast.makeText(ctx, "Element picker unavailable on this page", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onRequestInspectorClick = {
                                        contentState = Route.RequestInspectorPanel
                                    },
                                    onForgetSiteClick = {
                                        UhPrivacyController.forgetSiteData(ctx, siteUrl) {
                                            Toast.makeText(
                                                ctx.applicationContext,
                                                "Cleared cookies & storage for $siteHost",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                        store.state.sessionState?.let {
                                            components.useCases.sessionUseCases.reload(it.id)
                                        }
                                        dismissAllowingStateLoss()
                                    },
                                    onTrackerDetailsClick = {
                                        if (trackersBlockedCount > 0) {
                                            contentState = Route.TrackersPanel
                                        }
                                    },
                                    onClearSiteDataMenuClick = {
                                        store.dispatch(TrustPanelAction.RequestClearSiteDataDialog)
                                        contentState = Route.ClearSiteDataDialog
                                    },
                                    onPrivacyGuardClick = {
                                        dismissAllowingStateLoss()
                                        ctx.startActivity(Intent(ctx, PrivacyGuardActivity::class.java))
                                    },
                                    onSiteSettingsClick = {
                                        dismissAllowingStateLoss()
                                        ctx.startActivity(Intent(ctx, SiteSettingsActivity::class.java))
                                    },
                                    onAutoplayValueClick = { showAutoplayDialog = true },
                                    onToggleablePermissionClick = { store.dispatch(TrustPanelAction.TogglePermission(it)) },
                                    onViewCertificateClick = { showCertDialog = true },
                                )
                            }

                            Route.RequestInspectorPanel -> {
                                val siteUrl = websiteInfoState.websiteUrl.ifBlank { url }
                                val siteHost = remember(siteUrl) { UhShieldEngine.normalizeHost(siteUrl) }
                                val requestLogs = remember(siteHost, requestLogTick) {
                                    UhShieldEngine.getRequestLogForHost(siteHost)
                                }
                                LiveRequestInspectorPanel(
                                    siteHost = siteHost,
                                    entries = requestLogs,
                                    onAllowHost = { reqHost ->
                                        if (reqHost.isNotBlank()) {
                                            UhShieldEngine.appendCustomRule("@@||$reqHost^")
                                            Toast.makeText(ctx, "Added allow rule for $reqHost", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onBlockHost = { reqHost ->
                                        if (reqHost.isNotBlank()) {
                                            UhShieldEngine.appendCustomRule("||$reqHost^")
                                            Toast.makeText(ctx, "Added block rule for $reqHost", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onReloadClick = {
                                        UhShieldEngine.clearRequestLogForHost(siteHost)
                                        store.state.sessionState?.let {
                                            components.useCases.sessionUseCases.reload(it.id)
                                        }
                                    },
                                    onBackButtonClick = { contentState = Route.ProtectionPanel },
                                )
                            }

                            Route.TrackersPanel -> {
                                TrackersBlockedPanel(
                                    title = args.getString("title") ?: "",
                                    url = url,
                                    numberOfTrackersBlocked = trackersBlockedCount,
                                    numberOfTrackersBlockedThisWeek = 0,
                                    bucketedTrackers = store.state.bucketedTrackers,
                                    onTrackerCategoryClick = { category ->
                                        store.dispatch(TrustPanelAction.UpdateDetailedTrackerCategory(category))
                                        contentState = Route.TrackerCategoryDetailsPanel
                                    },
                                    onTrackersBlockedThisWeekClicked = {},
                                    onBackButtonClick = { contentState = Route.ProtectionPanel },
                                )
                            }

                            Route.TrackerCategoryDetailsPanel -> {
                                TrackerCategoryDetailsPanel(
                                    title = args.getString("title") ?: "",
                                    url = url,
                                    detailedTrackerCategory = store.state.detailedTrackerCategory,
                                    bucketedTrackers = store.state.bucketedTrackers,
                                    onBackButtonClick = { contentState = Route.TrackersPanel },
                                )
                            }

                            Route.ClearSiteDataDialog -> {
                                ClearSiteDataDialog(
                                    baseDomain = baseDomain ?: (android.net.Uri.parse(url).host.orEmpty()),
                                    onClearSiteDataClick = { store.dispatch(TrustPanelAction.ClearSiteData) },
                                    onCancelClick = { contentState = Route.ProtectionPanel },
                                )
                            }
                        }
                    }
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
                urlHost = args.getString("url")?.let { android.net.Uri.parse(it).host },
                onSecurityInfoUpdate = { store.dispatch(it) },
                onHostChange = { dismissAllowingStateLoss() },
            ),
            owner = viewLifecycleOwner,
            view = view,
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

@androidx.compose.runtime.Composable
private fun CertField(label: String, value: String, mono: Boolean = false) {
    Column(modifier = Modifier.padding(bottom = 4.dp)) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

enum class Route {
    ProtectionPanel,
    TrackersPanel,
    TrackerCategoryDetailsPanel,
    ClearSiteDataDialog,
    RequestInspectorPanel,
}
