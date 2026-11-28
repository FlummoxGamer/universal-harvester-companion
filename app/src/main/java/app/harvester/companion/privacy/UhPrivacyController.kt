package app.harvester.companion.privacy

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import app.harvester.companion.GeckoWebExtensionBridge
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.ThemePrefs
import app.harvester.companion.UHLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession.SafeBrowsingPolicy
import mozilla.components.concept.engine.EngineSession.TrackingProtectionPolicy
import mozilla.components.concept.engine.EngineSession.TrackingProtectionPolicy.CookiePolicy
import mozilla.components.concept.engine.EngineSession.TrackingProtectionPolicy.TrackingCategory
import mozilla.components.concept.engine.Settings
import mozilla.components.concept.engine.history.HistoryTrackingDelegate
import mozilla.components.concept.engine.mediaquery.PreferredColorScheme
import java.util.concurrent.atomic.AtomicBoolean

enum class DohProvider(val code: Int, val label: String, val url: String) {
    OFF(0, "Off (System DNS)", ""),
    CLOUDFLARE(1, "Cloudflare (1.1.1.1)", "https://mozilla.cloudflare-dns.com/dns-query"),
    QUAD9(2, "Quad9 (Malware Blocking)", "https://dns.quad9.net/dns-query"),
    ADGUARD(3, "AdGuard DNS (Ad & Tracker)", "https://dns.adguard-dns.com/dns-query"),
    NEXTDNS(4, "NextDNS", "https://dns.nextdns.io/dns-query"),
    CUSTOM(5, "Custom DoH Server", "");

    companion object {
        fun fromCode(code: Int): DohProvider = entries.firstOrNull { it.code == code } ?: OFF
    }
}

enum class WebRtcPolicy(val code: Int, val label: String, val summary: String, val geckoPolicy: String) {
    DEFAULT(0, "Default", "Allow standard WebRTC connections", "default"),
    DISABLE_NON_PROXIED_UDP(
        1,
        "Protect Local IP (Recommended)",
        "Hide local LAN IP on public networks",
        "default_public_interface_only",
    ),
    PROXY_ONLY(
        2,
        "Proxy / Relay Only",
        "Disable non-proxied UDP (best with VPN)",
        "disable_non_proxied_udp",
    ),
    BLOCK_ALL(
        3,
        "Block WebRTC Completely",
        "Disable RTCPeerConnection on all sites",
        "disable_non_proxied_udp",
    );

    companion object {
        fun fromCode(code: Int): WebRtcPolicy = entries.firstOrNull { it.code == code } ?: DISABLE_NON_PROXIED_UDP
    }
}

/**
 * Live Global Privacy, Security, Media & Web Dark Mode Controller.
 * Single source of truth that wires every UH Privacy Guard, Advanced, Appearance,
 * and Video setting directly into GeckoEngine, GeckoRuntime, and UH Bridge WebExtension.
 */
object UhPrivacyController {

    private val initialized = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var engineRef: Engine? = null

    @Volatile
    private var activeTabUrl: String = ""

    @Volatile
    private var activeTabIsPrivate: Boolean = false

    private val _darkBypassHosts = MutableStateFlow<Set<String>>(emptySet())
    val darkBypassHosts: StateFlow<Set<String>> = _darkBypassHosts.asStateFlow()

    fun init(ctx: Context, engine: Engine) {
        appContext = ctx.applicationContext
        engineRef = engine
        _darkBypassHosts.value = SettingsPrefs.getStringSet(ctx, SettingsPrefs.K_DARK_BYPASS_HOSTS)

        if (initialized.compareAndSet(false, true)) {
            UhShieldEngine.onCompiledRulesUpdated = {
                pushRuntimeConfigToExtension()
                appContext?.let { app ->
                    if (activeTabUrl.isNotBlank()) {
                        syncPerSiteEngineProtections(app, activeTabUrl, isPrivate = activeTabIsPrivate)
                    }
                }
            }
            UhShieldEngine.onLightweightConfigUpdated = {
                pushLightweightSettingsToExtension()
            }
            UhShieldEngine.onCnameBlockedDiscovered = { subdomain, target, isTracker ->
                GeckoWebExtensionBridge.pushCnameBlockedHost(subdomain, target, isTracker)
            }
        }
        applyAllToEngine(ctx)
    }

    fun isDarkModeActive(ctx: Context): Boolean = ThemePrefs.isDark(ctx)

    /**
     * Website Dark Mode is unified directly with App Dark Mode.
     */
    fun isForceDarkWebEffectivelyEnabled(ctx: Context): Boolean = isDarkModeActive(ctx)

    fun isSiteDarkBypassed(ctx: Context, urlOrHost: String): Boolean = false

    fun toggleSiteDarkBypass(ctx: Context, urlOrHost: String): Boolean = false

    fun removeSiteDarkBypass(ctx: Context, host: String) {
        val clean = UhShieldEngine.normalizeHost(host)
        val next = SettingsPrefs.getStringSet(ctx, SettingsPrefs.K_DARK_BYPASS_HOSTS) - clean
        SettingsPrefs.setStringSet(ctx, SettingsPrefs.K_DARK_BYPASS_HOSTS, next)
        _darkBypassHosts.value = next
    }

    fun buildPreferredColorScheme(ctx: Context): PreferredColorScheme {
        return if (isDarkModeActive(ctx)) PreferredColorScheme.Dark else PreferredColorScheme.Light
    }

    fun buildHttpsOnlyMode(ctx: Context): Engine.HttpsOnlyMode {
        val rawMode = SettingsPrefs.getInt(ctx, SettingsPrefs.K_PG_HTTPS_MODE, -1)
        val effective = if (rawMode >= 0) {
            rawMode
        } else {
            if (SettingsPrefs.getBool(ctx, SettingsPrefs.K_PG_HTTPS, true)) 2 else 1
        }
        return when (effective) {
            2 -> Engine.HttpsOnlyMode.ENABLED
            // Hardened Private Mode: always enforce HTTPS-Only in private tabs by default
            else -> Engine.HttpsOnlyMode.ENABLED_PRIVATE_ONLY
        }
    }

    fun buildStrictPrivateTrackingPolicy(): TrackingProtectionPolicy {
        return TrackingProtectionPolicy.select(
            trackingCategories = arrayOf(TrackingCategory.STRICT),
            cookiePolicy = CookiePolicy.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS,
            strictSocialTrackingProtection = true,
            cookiePurging = true,
            allowListBaselineTrackingProtection = true,
            allowListConvenienceTrackingProtection = false,
        )
    }

    fun buildTrackingProtectionPolicy(ctx: Context): TrackingProtectionPolicy {
        val shieldLevel = ShieldLevel.fromCode(
            SettingsPrefs.getInt(ctx, SettingsPrefs.K_PG_SHIELD_LEVEL, ShieldLevel.AGGRESSIVE.code)
        )
        val antiFingerprint = SettingsPrefs.getBool(ctx, SettingsPrefs.K_PG_FINGERPRINT, true)
        val cookieChoice = SettingsPrefs.getInt(ctx, SettingsPrefs.K_PG_COOKIES, 0)
        val cookiePolicy = when (cookieChoice) {
            1 -> CookiePolicy.ACCEPT_ONLY_FIRST_PARTY
            2 -> CookiePolicy.ACCEPT_NONE
            3 -> CookiePolicy.ACCEPT_ALL
            else -> CookiePolicy.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS
        }

        if (shieldLevel == ShieldLevel.OFF && !antiFingerprint) {
            return TrackingProtectionPolicy.select(
                trackingCategories = arrayOf(TrackingCategory.NONE),
                cookiePolicy = cookiePolicy,
                cookiePurging = false,
            )
        }

        val categories = if (shieldLevel == ShieldLevel.AGGRESSIVE) {
            arrayOf(TrackingCategory.STRICT)
        } else {
            arrayOf(TrackingCategory.RECOMMENDED)
        }

        return TrackingProtectionPolicy.select(
            trackingCategories = categories,
            cookiePolicy = cookiePolicy,
            strictSocialTrackingProtection = shieldLevel == ShieldLevel.AGGRESSIVE,
            cookiePurging = true,
            allowListBaselineTrackingProtection = true,
            allowListConvenienceTrackingProtection = shieldLevel != ShieldLevel.AGGRESSIVE,
        )
    }

    fun buildSafeBrowsingPolicy(ctx: Context): Array<SafeBrowsingPolicy> {
        val enabled = SettingsPrefs.getBool(ctx, SettingsPrefs.K_ADV_SAFE_BROWSING, true)
        return if (enabled) arrayOf(SafeBrowsingPolicy.RECOMMENDED) else arrayOf(SafeBrowsingPolicy.NONE)
    }

    fun buildDohConfig(ctx: Context): Pair<Engine.DohSettingsMode, String> {
        val provider = DohProvider.fromCode(SettingsPrefs.getInt(ctx, SettingsPrefs.K_ADV_DOH_PROVIDER, DohProvider.OFF.code))
        if (provider == DohProvider.OFF) {
            return Engine.DohSettingsMode.DEFAULT to ""
        }
        val url = if (provider == DohProvider.CUSTOM) {
            SettingsPrefs.getString(ctx, SettingsPrefs.K_ADV_DOH_CUSTOM_URL, "").trim()
        } else {
            provider.url
        }
        if (!url.startsWith("https://")) {
            return Engine.DohSettingsMode.DEFAULT to ""
        }
        return Engine.DohSettingsMode.INCREASED to url
    }

    fun buildDefaultSettings(
        ctx: Context,
        downloadDelegate: mozilla.components.browser.engine.gecko.util.EngineDownloadDelegate,
        historyDelegate: HistoryTrackingDelegate,
    ): DefaultSettings {
        val (dohMode, dohUrl) = buildDohConfig(ctx)
        val clearUrls = SettingsPrefs.getBool(ctx, SettingsPrefs.K_PG_CLEAR_URLS, true)
        val antiFingerprint = SettingsPrefs.getBool(ctx, SettingsPrefs.K_PG_FINGERPRINT, true)
        val backgroundPlay = SettingsPrefs.getBool(ctx, SettingsPrefs.K_BACKGROUND_PLAY, true)
        val gpc = SettingsPrefs.getBool(ctx, SettingsPrefs.K_ADV_GPC, true)

        // Smart JavaScript Blocking is enforced selectively at the 3rd-party script level
        // in uh_bridge so first-party apps & video players stay functional instead of blanking out.
        return DefaultSettings(
            remoteDebuggingEnabled = false,
            testingModeEnabled = false,
            downloadDelegate = downloadDelegate,
            preferredColorScheme = buildPreferredColorScheme(ctx),
            automaticFontSizeAdjustment = false,
            fontInflationEnabled = false,
            suspendMediaWhenInactive = !backgroundPlay,
            forceUserScalableContent = true,
            loginAutofillEnabled = false,
            enterpriseRootsEnabled = false,
            javascriptEnabled = true,
            trackingProtectionPolicy = buildTrackingProtectionPolicy(ctx),
            httpsOnlyMode = buildHttpsOnlyMode(ctx),
            globalPrivacyControlEnabled = gpc,
            fingerprintingProtection = antiFingerprint,
            fingerprintingProtectionPrivateBrowsing = true,
            queryParameterStripping = clearUrls,
            queryParameterStrippingPrivateBrowsing = true,
            dohSettingsMode = dohMode,
            dohProviderUrl = dohUrl,
            emailTrackerBlockingPrivateBrowsing = true,
            historyTrackingDelegate = historyDelegate,
        )
    }

    /**
     * Synchronizes per-site engine overrides for the active tab URL:
     * 1) Relaxes GeckoView fingerprinting protection when UH Shield is paused on this host.
     * 2) Relaxes HTTPS-Only when the user granted a 1-tap HTTP exception for this host.
     */
    fun syncPerSiteEngineProtections(ctx: Context, url: String, isPrivate: Boolean = false) {
        activeTabUrl = url
        activeTabIsPrivate = isPrivate
        val app = ctx.applicationContext
        val engine = engineRef ?: return
        val paused = !isPrivate && url.isNotBlank() && UhShieldEngine.isSitePaused(url)
        val httpExc = !isPrivate && url.isNotBlank() && UhShieldEngine.isHttpException(url)
        val baseFingerprint = SettingsPrefs.getBool(app, SettingsPrefs.K_PG_FINGERPRINT, true)
        val targetFingerprint = if (isPrivate) true else (baseFingerprint && !paused)
        val targetHttpsMode = when {
            isPrivate -> Engine.HttpsOnlyMode.ENABLED
            httpExc -> Engine.HttpsOnlyMode.ENABLED_PRIVATE_ONLY
            else -> buildHttpsOnlyMode(app)
        }
        val targetTrackingPolicy = if (isPrivate) {
            buildStrictPrivateTrackingPolicy()
        } else {
            buildTrackingProtectionPolicy(app)
        }

        mainHandler.post {
            try {
                val s = engine.settings
                if (s.fingerprintingProtection != targetFingerprint) {
                    s.fingerprintingProtection = targetFingerprint
                }
                s.fingerprintingProtectionPrivateBrowsing = true
                s.queryParameterStrippingPrivateBrowsing = true
                if (s.httpsOnlyMode != targetHttpsMode) {
                    s.httpsOnlyMode = targetHttpsMode
                }
                s.trackingProtectionPolicy = targetTrackingPolicy
            } catch (e: Exception) {
                UHLog.w("privacy", "Error syncing per-site engine protections: ${e.message}")
            }
        }
    }

    /**
     * Grants a persistent per-site HTTP exception and immediately disables HTTPS-Only
     * for the active navigation so an HTTP-only or broken-TLS site can load over HTTP.
     */
    fun allowHttpFallbackForSite(ctx: Context, urlOrHost: String) {
        val app = ctx.applicationContext
        UhShieldEngine.addHttpException(urlOrHost)
        syncPerSiteEngineProtections(app, urlOrHost)
    }

    /**
     * Applies all live privacy, security, media, and appearance settings to the running
     * GeckoEngine + UH Bridge WebExtension immediately without requiring an app restart.
     */
    fun applyAllToEngine(ctx: Context) {
        val app = ctx.applicationContext
        val engine = engineRef
        if (engine != null) {
            mainHandler.post {
                try {
                    val s = engine.settings
                    val (dohMode, dohUrl) = buildDohConfig(app)
                    val clearUrls = SettingsPrefs.getBool(app, SettingsPrefs.K_PG_CLEAR_URLS, true)
                    val antiFingerprint = SettingsPrefs.getBool(app, SettingsPrefs.K_PG_FINGERPRINT, true)
                    val cookieBanners = SettingsPrefs.getBool(app, SettingsPrefs.K_PG_COOKIE_BANNERS, true)
                    val backgroundPlay = SettingsPrefs.getBool(app, SettingsPrefs.K_BACKGROUND_PLAY, true)
                    val gpc = SettingsPrefs.getBool(app, SettingsPrefs.K_ADV_GPC, true)

                    val pausedActive = !activeTabIsPrivate && activeTabUrl.isNotBlank() && UhShieldEngine.isSitePaused(activeTabUrl)
                    val httpExcActive = !activeTabIsPrivate && activeTabUrl.isNotBlank() && UhShieldEngine.isHttpException(activeTabUrl)

                    s.trackingProtectionPolicy = if (activeTabIsPrivate) {
                        buildStrictPrivateTrackingPolicy()
                    } else {
                        buildTrackingProtectionPolicy(app)
                    }
                    s.safeBrowsingPolicy = buildSafeBrowsingPolicy(app)
                    s.httpsOnlyMode = when {
                        activeTabIsPrivate -> Engine.HttpsOnlyMode.ENABLED
                        httpExcActive -> Engine.HttpsOnlyMode.ENABLED_PRIVATE_ONLY
                        else -> buildHttpsOnlyMode(app)
                    }
                    s.globalPrivacyControlEnabled = gpc
                    s.fingerprintingProtection = if (activeTabIsPrivate) true else (antiFingerprint && !pausedActive)
                    s.fingerprintingProtectionPrivateBrowsing = true
                    s.queryParameterStripping = clearUrls
                    s.queryParameterStrippingPrivateBrowsing = true
                    // Smart JS blocking runs selectively in uh_bridge (never deadlocks pages globally)
                    s.javascriptEnabled = true
                    s.suspendMediaWhenInactive = !backgroundPlay
                    s.preferredColorScheme = buildPreferredColorScheme(app)
                    s.dohSettingsMode = dohMode
                    if (dohUrl.isNotBlank()) {
                        s.dohProviderUrl = dohUrl
                    }
                    applyNativeCookieBannerModeIfSupported(s, cookieBanners)
                    applyNativeEchIfSupported(s, dohUrl.isNotBlank())
                } catch (e: Exception) {
                    UHLog.w("privacy", "Error applying live settings to GeckoEngine: ${e.message}")
                }
            }
        }
        pushLightweightSettingsToExtension()
        pushRuntimeConfigToExtension()
    }

    /**
     * Enables Gecko's native Cookie Banner Handling API on GeckoView versions where it is still
     * exposed, while staying 100% binary/compile compatible with GeckoView 154+ (where Bug 2058143
     * retired the Gecko API and UH Shield's Shadow-DOM + CMP auto-rejector handles banners instead).
     */
    private fun applyNativeCookieBannerModeIfSupported(settings: Settings, enabled: Boolean) {
        try {
            val modeClass = Class.forName("mozilla.components.concept.engine.EngineSession\$CookieBannerHandlingMode")
            val constants = modeClass.enumConstants ?: return
            val targetName = if (enabled) "REJECT_OR_ACCEPT_ALL" else "DISABLED"
            val targetValue = constants.firstOrNull { (it as? Enum<*>)?.name == targetName } ?: return

            settings.javaClass.methods
                .firstOrNull { it.name == "setCookieBannerHandlingMode" && it.parameterTypes.size == 1 }
                ?.invoke(settings, targetValue)
            settings.javaClass.methods
                .firstOrNull { it.name == "setCookieBannerHandlingModePrivateBrowsing" && it.parameterTypes.size == 1 }
                ?.invoke(settings, targetValue)
        } catch (_: Throwable) {
            // Expected on GeckoView 154+; content.js Shadow-DOM & CMP auto-rejector handles cookie banners
        }
    }

    private fun applyNativeEchIfSupported(settings: Settings, dohActive: Boolean) {
        if (!dohActive) return
        try {
            // Enable Encrypted Client Hello (ECH) when supported by the bundled GeckoView version
            settings.javaClass.methods
                .firstOrNull { it.name == "setEncryptedClientHelloEnabled" && it.parameterTypes.size == 1 }
                ?.invoke(settings, true)
        } catch (_: Throwable) {
            // Graceful no-op on GeckoView versions where ECH is managed internally with DoH
        }
    }

    /**
     * 1-tap "Forget This Site": clears cookies, localStorage, IndexedDB, and auth sessions
     * for the target host and wipes its live shield stats/logs.
     */
    fun forgetSiteData(ctx: Context, urlOrHost: String, onComplete: (() -> Unit)? = null) {
        val host = UhShieldEngine.normalizeHost(urlOrHost)
        if (host.isBlank()) {
            onComplete?.invoke()
            return
        }
        UhShieldEngine.resetHostStats(host)
        UhShieldEngine.clearRequestLogForHost(host)
        UhShieldEngine.clearJsBlockedForHost(host)
        mainHandler.post {
            try {
                engineRef?.clearData(
                    data = Engine.BrowsingData.select(
                        Engine.BrowsingData.AUTH_SESSIONS,
                        Engine.BrowsingData.ALL_SITE_DATA,
                    ),
                    host = host,
                    onSuccess = { onComplete?.invoke() },
                    onError = { onComplete?.invoke() },
                ) ?: onComplete?.invoke()
            } catch (_: Exception) {
                onComplete?.invoke()
            }
        }
    }

    /**
     * Purges ephemeral auth sessions & caches when the last Private tab closes,
     * keeping normal-mode logins intact while ensuring zero leftover private traces.
     */
    fun purgePrivateSessionData(ctx: Context) {
        activeTabIsPrivate = false
        mainHandler.post {
            try {
                engineRef?.clearData(
                    data = Engine.BrowsingData.select(
                        Engine.BrowsingData.AUTH_SESSIONS,
                        Engine.BrowsingData.ALL_CACHES,
                    )
                )
            } catch (e: Exception) {
                UHLog.w("privacy", "Failed purging private session caches: ${e.message}")
            }
        }
        applyAllToEngine(ctx)
    }

    /**
     * Silently evicts cookies and storage dropped by rapid cross-site bounce-tracker redirect hops.
     */
    fun evictBounceTrackerHost(hostOrUrl: String) {
        val host = UhShieldEngine.normalizeHost(hostOrUrl)
        if (host.isBlank()) return
        mainHandler.post {
            try {
                engineRef?.clearData(
                    data = Engine.BrowsingData.select(Engine.BrowsingData.ALL_SITE_DATA),
                    host = host,
                )
            } catch (_: Exception) {}
        }
    }

    /**
     * Opt-in Auto-Shred Closed Sites: when enabled, clears site data once the last tab
     * belonging to a non-whitelisted host is closed.
     */
    fun onTabClosedCheckAutoShred(ctx: Context, closedUrl: String, remainingTabUrls: List<String>) {
        if (!SettingsPrefs.getBool(ctx, SettingsPrefs.K_PG_AUTO_SHRED, false)) return
        val closedHost = UhShieldEngine.normalizeHost(closedUrl)
        if (closedHost.isBlank()) return
        val allowList = SettingsPrefs.getStringSet(ctx, SettingsPrefs.K_PG_AUTO_SHRED_ALLOW_HOSTS)
        if (closedHost in allowList) return
        val stillOpen = remainingTabUrls.any { UhShieldEngine.normalizeHost(it) == closedHost }
        if (!stillOpen) {
            forgetSiteData(ctx, closedHost)
        }
    }

    fun pushLightweightSettingsToExtension() {
        val ctx = appContext ?: return
        ioScope.launch {
            try {
                val cfg = UhShieldEngine.buildExtensionRuntimePayload(ctx)
                GeckoWebExtensionBridge.pushPrivacyRuntimeConfig(cfg)
            } catch (e: Exception) {
                UHLog.w("privacy", "Failed pushing lightweight privacy config: ${e.message}")
            }
        }
    }

    fun pushRuntimeConfigToExtension() {
        val ctx = appContext ?: return
        ioScope.launch {
            try {
                val chunks = UhShieldEngine.buildChunkedRuntimeMessages(ctx)
                GeckoWebExtensionBridge.pushChunkedPrivacyConfig(chunks)
            } catch (e: Exception) {
                UHLog.w("privacy", "Failed pushing chunked privacy config to bridge: ${e.message}")
            }
        }
    }
}
