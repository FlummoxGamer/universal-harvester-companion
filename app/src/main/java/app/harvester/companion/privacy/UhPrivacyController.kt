package app.harvester.companion.privacy

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import app.harvester.companion.GeckoWebExtensionBridge
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.ThemePrefs
import app.harvester.companion.UHLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession.SafeBrowsingPolicy
import mozilla.components.concept.engine.EngineSession.TrackingProtectionPolicy
import mozilla.components.concept.engine.EngineSession.TrackingProtectionPolicy.CookiePolicy
import mozilla.components.concept.engine.EngineSession.TrackingProtectionPolicy.TrackingCategory
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
        "Hide local LAN IP and route through public interface or proxy",
        "default_public_interface_only",
    ),
    PROXY_ONLY(
        2,
        "Strict Proxy / Relay Only",
        "Disable non-proxied UDP completely (best with VPN)",
        "disable_non_proxied_udp",
    ),
    BLOCK_ALL(
        3,
        "Block WebRTC Completely",
        "Disable RTCPeerConnection to prevent all IP leaks",
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

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var engineRef: Engine? = null

    private val _darkBypassHosts = MutableStateFlow<Set<String>>(emptySet())
    val darkBypassHosts: StateFlow<Set<String>> = _darkBypassHosts.asStateFlow()

    fun init(ctx: Context, engine: Engine) {
        appContext = ctx.applicationContext
        engineRef = engine
        _darkBypassHosts.value = SettingsPrefs.getStringSet(ctx, SettingsPrefs.K_DARK_BYPASS_HOSTS)

        if (initialized.compareAndSet(false, true)) {
            UhShieldEngine.onCompiledRulesUpdated = {
                pushRuntimeConfigToExtension()
            }
        }
        applyAllToEngine(ctx)
    }

    fun isDarkModeActive(ctx: Context): Boolean {
        return when (ThemePrefs.getMode(ctx)) {
            ThemePrefs.MODE_DARK -> true
            ThemePrefs.MODE_LIGHT -> false
            else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
    }

    /**
     * Force Web Dark Mode is ONLY active when the browser is in Dark Mode AND enabled.
     */
    fun isForceDarkWebEffectivelyEnabled(ctx: Context): Boolean {
        return isDarkModeActive(ctx) && SettingsPrefs.getBool(ctx, SettingsPrefs.K_FORCE_DARK_SITES, false)
    }

    fun isSiteDarkBypassed(ctx: Context, urlOrHost: String): Boolean {
        val host = UhShieldEngine.normalizeHost(urlOrHost)
        if (host.isBlank()) return false
        return host in SettingsPrefs.getStringSet(ctx, SettingsPrefs.K_DARK_BYPASS_HOSTS)
    }

    fun toggleSiteDarkBypass(ctx: Context, urlOrHost: String): Boolean {
        val host = UhShieldEngine.normalizeHost(urlOrHost)
        if (host.isBlank()) return false
        val current = SettingsPrefs.getStringSet(ctx, SettingsPrefs.K_DARK_BYPASS_HOSTS).toMutableSet()
        val bypassed = if (host in current) {
            current.remove(host)
            false
        } else {
            current.add(host)
            true
        }
        SettingsPrefs.setStringSet(ctx, SettingsPrefs.K_DARK_BYPASS_HOSTS, current)
        _darkBypassHosts.value = current
        pushRuntimeConfigToExtension()
        return bypassed
    }

    fun removeSiteDarkBypass(ctx: Context, host: String) {
        val clean = UhShieldEngine.normalizeHost(host)
        val next = SettingsPrefs.getStringSet(ctx, SettingsPrefs.K_DARK_BYPASS_HOSTS) - clean
        SettingsPrefs.setStringSet(ctx, SettingsPrefs.K_DARK_BYPASS_HOSTS, next)
        _darkBypassHosts.value = next
        pushRuntimeConfigToExtension()
    }

    fun buildPreferredColorScheme(ctx: Context): PreferredColorScheme {
        return when (ThemePrefs.getMode(ctx)) {
            ThemePrefs.MODE_LIGHT -> PreferredColorScheme.Light
            ThemePrefs.MODE_DARK -> PreferredColorScheme.Dark
            else -> if (isDarkModeActive(ctx)) PreferredColorScheme.Dark else PreferredColorScheme.Light
        }
    }

    fun buildHttpsOnlyMode(ctx: Context): Engine.HttpsOnlyMode {
        // Migrate legacy boolean K_PG_HTTPS cleanly if mode hasn't been written yet
        val rawMode = SettingsPrefs.getInt(ctx, SettingsPrefs.K_PG_HTTPS_MODE, -1)
        val effective = if (rawMode >= 0) {
            rawMode
        } else {
            if (SettingsPrefs.getBool(ctx, SettingsPrefs.K_PG_HTTPS, true)) 2 else 0
        }
        return when (effective) {
            2 -> Engine.HttpsOnlyMode.ENABLED
            1 -> Engine.HttpsOnlyMode.ENABLED_PRIVATE_ONLY
            else -> Engine.HttpsOnlyMode.DISABLED
        }
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
        val blockJs = SettingsPrefs.getBool(ctx, SettingsPrefs.K_PG_SCRIPT, false)
        val backgroundPlay = SettingsPrefs.getBool(ctx, SettingsPrefs.K_BACKGROUND_PLAY, true)
        val gpc = SettingsPrefs.getBool(ctx, SettingsPrefs.K_ADV_GPC, true)

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
            javascriptEnabled = !blockJs,
            trackingProtectionPolicy = buildTrackingProtectionPolicy(ctx),
            httpsOnlyMode = buildHttpsOnlyMode(ctx),
            globalPrivacyControlEnabled = gpc,
            fingerprintingProtection = antiFingerprint,
            fingerprintingProtectionPrivateBrowsing = true,
            queryParameterStripping = clearUrls,
            queryParameterStrippingPrivateBrowsing = clearUrls,
            dohSettingsMode = dohMode,
            dohProviderUrl = dohUrl,
            emailTrackerBlockingPrivateBrowsing = true,
            historyTrackingDelegate = historyDelegate,
        )
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
                    val blockJs = SettingsPrefs.getBool(app, SettingsPrefs.K_PG_SCRIPT, false)
                    val backgroundPlay = SettingsPrefs.getBool(app, SettingsPrefs.K_BACKGROUND_PLAY, true)
                    val gpc = SettingsPrefs.getBool(app, SettingsPrefs.K_ADV_GPC, true)

                    s.trackingProtectionPolicy = buildTrackingProtectionPolicy(app)
                    s.safeBrowsingPolicy = buildSafeBrowsingPolicy(app)
                    s.httpsOnlyMode = buildHttpsOnlyMode(app)
                    s.globalPrivacyControlEnabled = gpc
                    s.fingerprintingProtection = antiFingerprint
                    s.fingerprintingProtectionPrivateBrowsing = true
                    s.queryParameterStripping = clearUrls
                    s.queryParameterStrippingPrivateBrowsing = clearUrls
                    s.javascriptEnabled = !blockJs
                    s.suspendMediaWhenInactive = !backgroundPlay
                    s.preferredColorScheme = buildPreferredColorScheme(app)
                    s.dohSettingsMode = dohMode
                    if (dohUrl.isNotBlank()) {
                        s.dohProviderUrl = dohUrl
                    }
                } catch (e: Exception) {
                    UHLog.w("privacy", "Error applying live settings to GeckoEngine: ${e.message}")
                }
            }
        }
        pushRuntimeConfigToExtension()
    }

    fun pushRuntimeConfigToExtension() {
        val ctx = appContext ?: return
        try {
            val payload = UhShieldEngine.buildExtensionRuntimePayload(ctx)
            GeckoWebExtensionBridge.pushPrivacyRuntimeConfig(payload)
        } catch (e: Exception) {
            UHLog.w("privacy", "Failed pushing privacy config to bridge: ${e.message}")
        }
    }
}
