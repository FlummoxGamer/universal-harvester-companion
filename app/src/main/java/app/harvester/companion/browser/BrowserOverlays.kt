package app.harvester.companion.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.harvester.companion.SearchEnginePrefs
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.browser.menu.ContextMenuDialog
import app.harvester.companion.browser.menu.OcrResultDialog
import app.harvester.companion.browser.privacy.BlockedRedirectToast
import app.harvester.companion.browser.privacy.HomographWarningBanner
import app.harvester.companion.browser.privacy.HttpsFallbackBanner
import app.harvester.companion.browser.privacy.JsBlockedBanner
import app.harvester.companion.components.Components
import app.harvester.companion.privacy.UhHomographDetector
import app.harvester.companion.privacy.UhPrivacyController
import app.harvester.companion.privacy.UhShieldEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.HitResult

private data class ActiveHitMenu(
    val tabId: String,
    val pageUrl: String,
    val isPrivate: Boolean,
    val hitResult: HitResult,
)

/**
 * Modular Compose overlay host mounted over the BrowserFragment viewport:
 * 1) Redesigned Long-Press Context Menu dialog + OCR Result dialog
 * 2) Slim Smart JavaScript Blocking banner (ONLY shown when scripts are blocked on current page)
 * 3) 1-Tap HTTPS-Only HTTP fallback banner (shown only when HTTPS fails on an HTTP-only/broken-TLS site)
 */
@Composable
fun BrowserOverlays(
    components: Components,
    isBarBottomFlow: StateFlow<Boolean>,
) {
    val context = LocalContext.current
    val browserState by components.store.stateFlow.collectAsState()
    val isBarBottom by isBarBottomFlow.collectAsState()
    val jsTick by UhShieldEngine.jsBlockedTick.collectAsState()
    val httpExceptions by UhShieldEngine.httpExceptionHosts.collectAsState()
    val blockedRedirect by UhShieldEngine.blockedRedirectEvent.collectAsState()

    val selectedTab = browserState.selectedTab
    val tabId = selectedTab?.id.orEmpty()
    val currentUrl = selectedTab?.content?.url.orEmpty()
    val currentHost = remember(currentUrl) { UhShieldEngine.normalizeHost(currentUrl) }
    val isPrivate = selectedTab?.content?.private == true
    val isLoading = selectedTab?.content?.loading == true
    val isSecure = selectedTab?.content?.securityInfo?.isSecure == true
    val canGoBack = selectedTab?.content?.canGoBack == true

    // 1. Capture and immediately consume HitResult so rotation/resume never re-opens the menu
    var activeHitMenu by remember { mutableStateOf<ActiveHitMenu?>(null) }
    var ocrResultText by remember { mutableStateOf<String?>(null) }

    val pendingHit = selectedTab?.content?.hitResult
    LaunchedEffect(tabId, pendingHit) {
        if (pendingHit != null && tabId.isNotBlank()) {
            activeHitMenu = ActiveHitMenu(
                tabId = tabId,
                pageUrl = currentUrl,
                isPrivate = isPrivate,
                hitResult = pendingHit,
            )
            components.useCases.contextMenuUseCases.consumeHitResult(tabId)
        }
    }

    // 2. Smart JavaScript Blocked banner state (strictly hidden unless scripts were blocked)
    var dismissedJsHost by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentHost) {
        if (dismissedJsHost != null && dismissedJsHost != currentHost) {
            dismissedJsHost = null
        }
    }
    val smartJsEnabled = remember(jsTick, currentHost) {
        SettingsPrefs.getBool(context, SettingsPrefs.K_PG_SCRIPT, false)
    }
    val jsBlockedState = remember(jsTick, currentHost, smartJsEnabled) {
        if (!smartJsEnabled || currentHost.isBlank()) null
        else UhShieldEngine.getJsBlockedState(currentHost)
    }
    val showJsBanner = jsBlockedState != null &&
        jsBlockedState.totalBlocked > 0 &&
        dismissedJsHost != currentHost

    // 3. HTTPS-Only 1-tap HTTP fallback banner (debounced so normal redirects never flicker)
    var dismissedHttpsHost by remember { mutableStateOf<String?>(null) }
    var showHttpsFallback by remember { mutableStateOf(false) }

    LaunchedEffect(currentUrl, isLoading, isSecure, httpExceptions, isPrivate) {
        showHttpsFallback = false
        if (dismissedHttpsHost != null && dismissedHttpsHost != currentHost) {
            dismissedHttpsHost = null
        }
        val httpsMode = UhPrivacyController.buildHttpsOnlyMode(context)
        val httpsModeActive = httpsMode == Engine.HttpsOnlyMode.ENABLED ||
            (httpsMode == Engine.HttpsOnlyMode.ENABLED_PRIVATE_ONLY && isPrivate)

        val candidate = httpsModeActive &&
            !isLoading &&
            currentUrl.startsWith("https://", ignoreCase = true) &&
            !isSecure &&
            currentHost.isNotBlank() &&
            currentHost !in httpExceptions &&
            dismissedHttpsHost != currentHost

        if (candidate) {
            delay(450L)
            showHttpsFallback = true
        }
    }

    // 4. Auto-dismiss blocked ad-redirect / popunder toast after 4.5s
    LaunchedEffect(blockedRedirect?.id) {
        val currentEvent = blockedRedirect ?: return@LaunchedEffect
        delay(4500L)
        UhShieldEngine.dismissBlockedRedirect(currentEvent.id)
    }

    // 5. Punycode / IDN Homograph spoofing detection
    var dismissedHomographHosts by remember { mutableStateOf<Set<String>>(emptySet()) }
    val homographWarning = remember(currentUrl) {
        UhHomographDetector.inspectUrl(currentUrl)
    }
    val showHomographBanner = homographWarning != null &&
        homographWarning.asciiHost !in dismissedHomographHosts

    Box(modifier = Modifier.fillMaxSize()) {
        // Slim in-page banners dock cleanly near the toolbar edge
        val bannerAlignment = if (isBarBottom) Alignment.BottomCenter else Alignment.TopCenter
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(bannerAlignment),
        ) {
            AnimatedVisibility(
                visible = showHomographBanner && homographWarning != null,
                enter = fadeIn() + slideInVertically { if (isBarBottom) it / 2 else -it / 2 },
                exit = fadeOut() + slideOutVertically { if (isBarBottom) it / 2 else -it / 2 },
            ) {
                homographWarning?.let { warning ->
                    HomographWarningBanner(
                        warning = warning,
                        onGoBack = {
                            if (tabId.isNotBlank() && canGoBack) {
                                components.useCases.sessionUseCases.goBack(tabId)
                            } else {
                                components.useCases.sessionUseCases.loadUrl("about:blank")
                            }
                        },
                        onIgnore = {
                            dismissedHomographHosts = dismissedHomographHosts + warning.asciiHost
                        },
                    )
                }
            }

            AnimatedVisibility(
                visible = showHttpsFallback,
                enter = fadeIn() + slideInVertically { if (isBarBottom) it / 2 else -it / 2 },
                exit = fadeOut() + slideOutVertically { if (isBarBottom) it / 2 else -it / 2 },
            ) {
                HttpsFallbackBanner(
                    host = currentHost,
                    onContinueHttp = {
                        showHttpsFallback = false
                        if (tabId.isNotBlank() && currentUrl.isNotBlank()) {
                            UhPrivacyController.allowHttpFallbackForSite(context, currentUrl)
                            val httpUrl = currentUrl.replaceFirst("https://", "http://", ignoreCase = true)
                            components.useCases.sessionUseCases.loadUrl(httpUrl)
                        }
                    },
                    onDismiss = {
                        dismissedHttpsHost = currentHost
                        showHttpsFallback = false
                    },
                )
            }

            AnimatedVisibility(
                visible = showJsBanner && jsBlockedState != null,
                enter = fadeIn() + slideInVertically { if (isBarBottom) it / 2 else -it / 2 },
                exit = fadeOut() + slideOutVertically { if (isBarBottom) it / 2 else -it / 2 },
            ) {
                jsBlockedState?.let { jsState ->
                    JsBlockedBanner(
                        state = jsState,
                        onAllowOnce = {
                            UhShieldEngine.allowJsOnce(currentUrl)
                            if (tabId.isNotBlank()) {
                                components.useCases.sessionUseCases.reload(tabId)
                            }
                        },
                        onAllowAlways = {
                            UhShieldEngine.allowJsAlways(currentUrl)
                            if (tabId.isNotBlank()) {
                                components.useCases.sessionUseCases.reload(tabId)
                            }
                        },
                        onDisableGlobally = {
                            SettingsPrefs.setBool(context, SettingsPrefs.K_PG_SCRIPT, false)
                            UhShieldEngine.clearJsBlockedForHost(currentUrl)
                            UhPrivacyController.applyAllToEngine(context)
                            if (tabId.isNotBlank()) {
                                components.useCases.sessionUseCases.reload(tabId)
                            }
                        },
                        onDismiss = {
                            dismissedJsHost = currentHost
                        },
                    )
                }
            }

            AnimatedVisibility(
                visible = blockedRedirect != null,
                enter = fadeIn() + slideInVertically { if (isBarBottom) it / 2 else -it / 2 },
                exit = fadeOut() + slideOutVertically { if (isBarBottom) it / 2 else -it / 2 },
            ) {
                blockedRedirect?.let { redirectEvent ->
                    BlockedRedirectToast(
                        event = redirectEvent,
                        onAllowOnce = {
                            UhShieldEngine.allowRedirectOnce(redirectEvent.targetUrl)
                            UhShieldEngine.dismissBlockedRedirect(redirectEvent.id)
                            if (redirectEvent.targetUrl.isNotBlank()) {
                                components.useCases.sessionUseCases.loadUrl(redirectEvent.targetUrl)
                            }
                        },
                        onDismiss = {
                            UhShieldEngine.dismissBlockedRedirect(redirectEvent.id)
                        },
                    )
                }
            }
        }

        // Centered floating Material 3 Long-Press Context Menu
        activeHitMenu?.let { menu ->
            ContextMenuDialog(
                hitResult = menu.hitResult,
                pageUrl = menu.pageUrl,
                isPrivate = menu.isPrivate,
                tabsUseCases = components.useCases.tabsUseCases,
                onDismiss = { activeHitMenu = null },
                onOcrResult = { extracted -> ocrResultText = extracted },
            )
        }

        // Extracted Text OCR Result Dialog
        ocrResultText?.let { text ->
            OcrResultDialog(
                extractedText = text,
                onSearchWeb = { query ->
                    val searchUrl = SearchEnginePrefs.getSearchUrl(context, query)
                    components.useCases.tabsUseCases.addTab(
                        url = searchUrl,
                        selectTab = true,
                        private = isPrivate,
                    )
                },
                onDismiss = { ocrResultText = null },
            )
        }
    }
}
