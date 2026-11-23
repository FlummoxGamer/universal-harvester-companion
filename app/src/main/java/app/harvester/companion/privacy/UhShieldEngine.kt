package app.harvester.companion.privacy

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.UHLog
import app.harvester.companion.core.network.UhHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class ShieldLevel(val code: Int, val label: String, val summary: String) {
    OFF(0, "Off", "Ad and tracker blocking disabled"),
    STANDARD(1, "Standard", "Blocks third-party ads, trackers, and malware with minimal site impact"),
    AGGRESSIVE(2, "Aggressive", "Blocks 1st & 3rd-party ads, CNAME trackers, cosmetics, and anti-adblock scripts");

    companion object {
        fun fromCode(code: Int): ShieldLevel = entries.firstOrNull { it.code == code } ?: AGGRESSIVE
    }
}

enum class FilterPackTier {
    CORE,
    NUCLEAR,
    SOCIAL,
    REGIONAL,
    CUSTOM,
}

data class FilterListDef(
    val id: String,
    val name: String,
    val description: String,
    val url: String,
    val tier: FilterPackTier,
    val defaultEnabled: Boolean,
    val isTrackerList: Boolean = false,
)

data class FilterListStatus(
    val def: FilterListDef,
    val enabled: Boolean,
    val ruleCount: Int = 0,
    val lastUpdatedMs: Long = 0L,
    val lastError: String? = null,
)

data class ShieldStatsSnapshot(
    val adsBlocked: Long = 0L,
    val trackersBlocked: Long = 0L,
    val estimatedTimeSavedMs: Long = 0L,
) {
    val totalBlocked: Long get() = adsBlocked + trackersBlocked

    fun formattedTimeSaved(): String {
        val totalSec = estimatedTimeSavedMs / 1000L
        return when {
            totalSec < 60L -> "${totalSec}s"
            totalSec < 3600L -> {
                val mins = totalSec / 60L
                val secs = totalSec % 60L
                if (mins < 10L && secs >= 10L) "${mins}m ${secs}s" else "${mins}m"
            }
            else -> {
                val hrs = totalSec / 3600.0
                String.format(Locale.US, "%.1fh", hrs)
            }
        }
    }
}

data class JsBlockedPageState(
    val host: String = "",
    val totalBlocked: Int = 0,
    val blockedDomains: Map<String, Int> = emptyMap(),
    val updatedAtMs: Long = 0L,
)

/**
 * UH Shield — Flagship 3-layer built-in ad, tracker, annoyance & malware blocker.
 *
 * Layer 1 (Network): Full ABP/uBO rule engine — O(1) domain sets (all-party, 3rd-party, popup, exceptions)
 *                    + structured path/wildcard/regex/resource-type/domain/exception/redirect rules,
 *                    synced in chunks to the bundled WebExtension with zero rule truncation.
 * Layer 2 (Cosmetic & Anti-Adblock): Global + per-domain CSS hiding rules, #@# cosmetic exceptions,
 *                    procedural :has-text() selectors, blocked-banner collapsing, and uBO MAIN-world scriptlets.
 * Layer 3 (Gecko Native ETP & Cookie Banners): Coordinated via [UhPrivacyController] with live stat sync.
 */
object UhShieldEngine {

    private const val PREFS_NAME = "uh_shield_v2"
    private const val KEY_PAUSED_HOSTS = "paused_hosts"
    private const val KEY_JS_ALLOW_HOSTS = "js_allow_hosts"
    private const val KEY_HTTP_EXCEPTION_HOSTS = "http_exception_hosts"
    private const val KEY_ENABLED_LISTS = "enabled_list_ids"
    private const val KEY_CUSTOM_LISTS_JSON = "custom_lists_json"
    private const val KEY_CUSTOM_RULES = "custom_user_rules"
    private const val KEY_STATS_ADS = "stats_ads_blocked"
    private const val KEY_STATS_TRACKERS = "stats_trackers_blocked"
    private const val KEY_STATS_SAVED_MS = "stats_time_saved_ms"
    private const val KEY_LAST_SYNC_MS = "last_sync_ms"
    private const val KEY_LIST_META_JSON = "list_meta_json"
    private const val WORK_NAME = "uh_shield_filter_sync"

    // Average wall-clock latency saved per blocked ad/tracker request
    private const val AD_TIME_SAVED_MS = 85L
    private const val TRACKER_TIME_SAVED_MS = 45L

    // Chunk sizes for Binder-safe Native Messaging transfers to uh_bridge
    private const val DOMAIN_CHUNK_SIZE = 8_000
    private const val STRUCTURED_RULE_CHUNK_SIZE = 1_200

    val BUILT_IN_LISTS: List<FilterListDef> = listOf(
        // === Full Power Default Pack (enabled out of the box) ===
        FilterListDef(
            id = "easylist",
            name = "EasyList",
            description = "Primary web advertisement filter list",
            url = "https://easylist.to/easylist/easylist.txt",
            tier = FilterPackTier.CORE,
            defaultEnabled = true,
            isTrackerList = false,
        ),
        FilterListDef(
            id = "easyprivacy",
            name = "EasyPrivacy",
            description = "Comprehensive analytics, beacons & cross-site trackers",
            url = "https://easylist.to/easylist/easyprivacy.txt",
            tier = FilterPackTier.CORE,
            defaultEnabled = true,
            isTrackerList = true,
        ),
        FilterListDef(
            id = "ubo_filters",
            name = "uBlock Core Filters",
            description = "High-precision ads, popunders & anti-adblock fixes",
            url = "https://ublockorigin.github.io/uAssets/filters/filters.txt",
            tier = FilterPackTier.CORE,
            defaultEnabled = true,
            isTrackerList = false,
        ),
        FilterListDef(
            id = "ubo_privacy",
            name = "uBlock Privacy",
            description = "Advanced tracker & telemetry network filters",
            url = "https://ublockorigin.github.io/uAssets/filters/privacy.txt",
            tier = FilterPackTier.CORE,
            defaultEnabled = true,
            isTrackerList = true,
        ),
        FilterListDef(
            id = "ubo_badware",
            name = "uBlock Badware & Quick Fixes",
            description = "Malvertising, scam redirects & fast site break fixes",
            url = "https://ublockorigin.github.io/uAssets/filters/quick-fixes.txt",
            tier = FilterPackTier.CORE,
            defaultEnabled = true,
            isTrackerList = false,
        ),
        FilterListDef(
            id = "peter_lowe",
            name = "Peter Lowe's Ad & Tracking Server List",
            description = "Ultra-clean curated ad & tracker domain blocklist",
            url = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext",
            tier = FilterPackTier.CORE,
            defaultEnabled = true,
            isTrackerList = true,
        ),
        FilterListDef(
            id = "hagezi_pro",
            name = "HaGeZi Multi PRO",
            description = "Industry-leading ads, trackers, telemetry & CNAME cloaking",
            url = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/pro.txt",
            tier = FilterPackTier.CORE,
            defaultEnabled = true,
            isTrackerList = true,
        ),
        FilterListDef(
            id = "fanboy_cookies",
            name = "Fanboy Cookie Notices & Annoyances",
            description = "Removes cookie popups, newsletter modals & floating nags",
            url = "https://secure.fanboy.co.nz/fanboy-cookiemonster.txt",
            tier = FilterPackTier.CORE,
            defaultEnabled = true,
            isTrackerList = false,
        ),

        // === Optional Extra & Nuclear Customization Packs ===
        FilterListDef(
            id = "hagezi_ultimate",
            name = "HaGeZi Multi ULTIMATE (Nuclear)",
            description = "Maximum-strength blocking for aggressive trackers & ad networks",
            url = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/ultimate.txt",
            tier = FilterPackTier.NUCLEAR,
            defaultEnabled = false,
            isTrackerList = true,
        ),
        FilterListDef(
            id = "oisd_big",
            name = "OISD Big (Nuclear)",
            description = "Massive curated blocklist targeting ads, phishing & telemetry",
            url = "https://big.oisd.nl/",
            tier = FilterPackTier.NUCLEAR,
            defaultEnabled = false,
            isTrackerList = true,
        ),
        FilterListDef(
            id = "urlhaus_malware",
            name = "URLHaus Malware & Phishing",
            description = "Real-time malicious payload & phishing domain defense",
            url = "https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-online.txt",
            tier = FilterPackTier.NUCLEAR,
            defaultEnabled = false,
            isTrackerList = true,
        ),
        FilterListDef(
            id = "ubo_annoyances",
            name = "uBlock Annoyances & Overlays",
            description = "Strips app-install banners, paywall nags & chat popups",
            url = "https://ublockorigin.github.io/uAssets/filters/annoyances-others.txt",
            tier = FilterPackTier.NUCLEAR,
            defaultEnabled = false,
            isTrackerList = false,
        ),
        FilterListDef(
            id = "fanboy_social",
            name = "Fanboy Social Media Blocking",
            description = "Blocks embedded share buttons, like widgets & social scripts",
            url = "https://easylist.to/easylist/fanboy-social.txt",
            tier = FilterPackTier.SOCIAL,
            defaultEnabled = false,
            isTrackerList = true,
        ),
        FilterListDef(
            id = "adguard_mobile",
            name = "AdGuard Mobile Ads",
            description = "Specialized mobile web banners, sticky ads & app prompts",
            url = "https://filters.adtidy.org/extension/ublock/filters/11.txt",
            tier = FilterPackTier.REGIONAL,
            defaultEnabled = false,
            isTrackerList = false,
        ),
        FilterListDef(
            id = "indian_list",
            name = "IndianList (Regional)",
            description = "Ad & popup filters for Indian news, media & regional sites",
            url = "https://easylist-downloads.adblockplus.org/indianlist.txt",
            tier = FilterPackTier.REGIONAL,
            defaultEnabled = false,
            isTrackerList = false,
        ),
        FilterListDef(
            id = "japanese_list",
            name = "AdGuard Japanese (Regional)",
            description = "Ad filters for Japanese web portals, manga & media sites",
            url = "https://filters.adtidy.org/extension/ublock/filters/7.txt",
            tier = FilterPackTier.REGIONAL,
            defaultEnabled = false,
            isTrackerList = false,
        ),
    )

    data class State(
        val level: ShieldLevel = ShieldLevel.AGGRESSIVE,
        val pausedHosts: Set<String> = emptySet(),
        val lifetimeStats: ShieldStatsSnapshot = ShieldStatsSnapshot(),
        val lists: List<FilterListStatus> = emptyList(),
        val customUserRules: String = "",
        val totalCompiledRules: Int = 0,
        val isSyncing: Boolean = false,
        val lastSyncMs: Long = 0L,
        val lastSyncError: String? = null,
    )

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    var onCompiledRulesUpdated: (() -> Unit)? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // Per-host stats for current session (used by Trust Panel "This Site")
    private val hostStatsMap = ConcurrentHashMap<String, ShieldStatsSnapshot>()
    private val _hostStatsTick = MutableStateFlow(0L)
    val hostStatsTick: StateFlow<Long> = _hostStatsTick.asStateFlow()

    // Gecko native ETP delta tracking per tabId -> (normalizedHost, lastReportedCount)
    private val lastGeckoEtpByTab = ConcurrentHashMap<String, Pair<String, Int>>()

    // Smart JS blocking live state per page host (shown in slim in-page banner ONLY when > 0)
    private val jsBlockedByHost = ConcurrentHashMap<String, JsBlockedPageState>()
    private val _jsBlockedTick = MutableStateFlow(0L)
    val jsBlockedTick: StateFlow<Long> = _jsBlockedTick.asStateFlow()

    // Per-session "Allow Once" & persistent "Always Allow" JS hosts
    private val jsAllowOnceHosts: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private val _jsAllowAlwaysHosts = MutableStateFlow<Set<String>>(emptySet())
    val jsAllowAlwaysHosts: StateFlow<Set<String>> = _jsAllowAlwaysHosts.asStateFlow()

    // Persistent HTTPS-Only per-site HTTP exceptions (1-tap fallback)
    private val _httpExceptionHosts = MutableStateFlow<Set<String>>(emptySet())
    val httpExceptionHosts: StateFlow<Set<String>> = _httpExceptionHosts.asStateFlow()

    private val lifetimeAds = AtomicLong(0L)
    private val lifetimeTrackers = AtomicLong(0L)
    private val lifetimeSavedMs = AtomicLong(0L)
    private val lastStatsFlushMs = AtomicLong(0L)

    @Volatile
    private var compiledSet: UhAdblockRuleCompiler.CompiledFilterSet = UhAdblockRuleCompiler.CompiledFilterSet.EMPTY

    fun init(ctx: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        appContext = app

        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val level = ShieldLevel.fromCode(SettingsPrefs.getInt(app, SettingsPrefs.K_PG_SHIELD_LEVEL, ShieldLevel.AGGRESSIVE.code))
        val paused = prefs.getStringSet(KEY_PAUSED_HOSTS, emptySet())?.toSet() ?: emptySet()
        val jsAlways = prefs.getStringSet(KEY_JS_ALLOW_HOSTS, emptySet())?.toSet() ?: emptySet()
        val httpExc = prefs.getStringSet(KEY_HTTP_EXCEPTION_HOSTS, emptySet())?.toSet() ?: emptySet()
        val ads = prefs.getLong(KEY_STATS_ADS, 0L)
        val trackers = prefs.getLong(KEY_STATS_TRACKERS, 0L)
        val savedMs = prefs.getLong(KEY_STATS_SAVED_MS, 0L)
        val lastSync = prefs.getLong(KEY_LAST_SYNC_MS, 0L)
        val userRules = prefs.getString(KEY_CUSTOM_RULES, "") ?: ""

        _jsAllowAlwaysHosts.value = jsAlways
        _httpExceptionHosts.value = httpExc
        lifetimeAds.set(ads)
        lifetimeTrackers.set(trackers)
        lifetimeSavedMs.set(savedMs)

        val enabledIds = getEnabledListIds(app)
        // Only seed offline Day-0 bootstrap if the user actually has lists enabled
        if (enabledIds.isNotEmpty()) {
            compiledSet = buildBootstrapFilterSet()
        } else {
            compiledSet = UhAdblockRuleCompiler.CompiledFilterSet.EMPTY
        }

        val statuses = loadListStatuses(app)
        _state.value = State(
            level = level,
            pausedHosts = paused,
            lifetimeStats = ShieldStatsSnapshot(ads, trackers, savedMs),
            lists = statuses,
            customUserRules = userRules,
            totalCompiledRules = compiledSet.totalRules,
            lastSyncMs = lastSync,
        )

        scope.launch {
            recompileFromDisk(app)
            val stale = (System.currentTimeMillis() - lastSync) > TimeUnit.HOURS.toMillis(24)
            if (stale && getEnabledListIds(app).isNotEmpty()) {
                syncEnabledLists(force = false)
            }
        }

        schedulePeriodicSync(app)
    }

    private fun schedulePeriodicSync(ctx: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = PeriodicWorkRequestBuilder<UhShieldUpdateWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        } catch (e: Exception) {
            UHLog.w("shield", "Failed scheduling periodic filter sync: ${e.message}")
        }
    }

    fun setLevel(level: ShieldLevel) {
        val ctx = appContext ?: return
        SettingsPrefs.setInt(ctx, SettingsPrefs.K_PG_SHIELD_LEVEL, level.code)
        SettingsPrefs.setBool(ctx, SettingsPrefs.K_PG_ADS, level != ShieldLevel.OFF)
        _state.update { it.copy(level = level) }
        onCompiledRulesUpdated?.invoke()
    }

    fun isSitePaused(urlOrHost: String): Boolean {
        val host = normalizeHost(urlOrHost)
        if (host.isBlank()) return false
        return host in _state.value.pausedHosts
    }

    fun toggleSitePaused(urlOrHost: String): Boolean {
        val host = normalizeHost(urlOrHost)
        val ctx = appContext ?: return false
        if (host.isBlank()) return false
        val next = _state.value.pausedHosts.toMutableSet()
        val paused = if (host in next) {
            next.remove(host)
            false
        } else {
            next.add(host)
            true
        }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_PAUSED_HOSTS, next).apply()
        _state.update { it.copy(pausedHosts = next) }
        onCompiledRulesUpdated?.invoke()
        return paused
    }

    fun removePausedHost(host: String) {
        val clean = normalizeHost(host)
        val ctx = appContext ?: return
        val next = _state.value.pausedHosts - clean
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_PAUSED_HOSTS, next).apply()
        _state.update { it.copy(pausedHosts = next) }
        onCompiledRulesUpdated?.invoke()
    }

    // ─── Smart JavaScript Blocking State & Controls ─────────────────────────

    fun reportJsBlocked(pageHost: String, totalBlocked: Int, blockedDomains: Map<String, Int>) {
        val host = normalizeHost(pageHost)
        if (host.isBlank()) return
        if (totalBlocked <= 0) {
            jsBlockedByHost.remove(host)
        } else {
            jsBlockedByHost[host] = JsBlockedPageState(
                host = host,
                totalBlocked = totalBlocked,
                blockedDomains = blockedDomains,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
        _jsBlockedTick.value = System.nanoTime()
    }

    fun clearJsBlockedForHost(urlOrHost: String) {
        val host = normalizeHost(urlOrHost)
        if (host.isNotBlank() && jsBlockedByHost.remove(host) != null) {
            _jsBlockedTick.value = System.nanoTime()
        }
    }

    fun getJsBlockedState(urlOrHost: String): JsBlockedPageState? {
        val host = normalizeHost(urlOrHost)
        if (host.isBlank()) return null
        val st = jsBlockedByHost[host] ?: return null
        return if (st.totalBlocked > 0) st else null
    }

    fun allowJsOnce(urlOrHost: String) {
        val host = normalizeHost(urlOrHost)
        if (host.isBlank()) return
        jsAllowOnceHosts.add(host)
        jsBlockedByHost.remove(host)
        _jsBlockedTick.value = System.nanoTime()
        onCompiledRulesUpdated?.invoke()
    }

    fun allowJsAlways(urlOrHost: String) {
        val host = normalizeHost(urlOrHost)
        val ctx = appContext ?: return
        if (host.isBlank()) return
        val next = _jsAllowAlwaysHosts.value + host
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_JS_ALLOW_HOSTS, next).apply()
        _jsAllowAlwaysHosts.value = next
        jsBlockedByHost.remove(host)
        _jsBlockedTick.value = System.nanoTime()
        onCompiledRulesUpdated?.invoke()
    }

    fun revokeJsAlways(host: String) {
        val clean = normalizeHost(host)
        val ctx = appContext ?: return
        val next = _jsAllowAlwaysHosts.value - clean
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_JS_ALLOW_HOSTS, next).apply()
        _jsAllowAlwaysHosts.value = next
        onCompiledRulesUpdated?.invoke()
    }

    fun isJsAllowedForHost(urlOrHost: String): Boolean {
        val host = normalizeHost(urlOrHost)
        if (host.isBlank()) return false
        return host in jsAllowOnceHosts || host in _jsAllowAlwaysHosts.value
    }

    // ─── HTTPS-Only Per-Site HTTP Exception Store ───────────────────────────

    fun isHttpException(urlOrHost: String): Boolean {
        val host = normalizeHost(urlOrHost)
        if (host.isBlank()) return false
        return host in _httpExceptionHosts.value
    }

    fun addHttpException(urlOrHost: String) {
        val host = normalizeHost(urlOrHost)
        val ctx = appContext ?: return
        if (host.isBlank()) return
        val next = _httpExceptionHosts.value + host
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_HTTP_EXCEPTION_HOSTS, next).apply()
        _httpExceptionHosts.value = next
    }

    fun removeHttpException(host: String) {
        val clean = normalizeHost(host)
        val ctx = appContext ?: return
        val next = _httpExceptionHosts.value - clean
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_HTTP_EXCEPTION_HOSTS, next).apply()
        _httpExceptionHosts.value = next
    }

    // ─── Per-Host & Lifetime Stats + Gecko Native ETP Sync ──────────────────

    fun getHostStats(urlOrHost: String): ShieldStatsSnapshot {
        val host = normalizeHost(urlOrHost)
        if (host.isBlank()) return ShieldStatsSnapshot()
        return hostStatsMap[host] ?: ShieldStatsSnapshot()
    }

    fun resetHostStats(urlOrHost: String) {
        val host = normalizeHost(urlOrHost)
        if (host.isNotBlank()) {
            hostStatsMap.remove(host)
            _hostStatsTick.value = System.nanoTime()
        }
    }

    fun onTabNavigated(tabId: String, newUrl: String) {
        val newHost = normalizeHost(newUrl)
        val prev = lastGeckoEtpByTab[tabId]
        if (prev == null || prev.first != newHost) {
            if (newHost.isNotBlank()) {
                lastGeckoEtpByTab[tabId] = newHost to 0
                hostStatsMap.remove(newHost)
                jsBlockedByHost.remove(newHost)
                _hostStatsTick.value = System.nanoTime()
                _jsBlockedTick.value = System.nanoTime()
            } else {
                lastGeckoEtpByTab.remove(tabId)
            }
        }
    }

    /**
     * Syncs GeckoView native Enhanced Tracking Protection blocked tracker count
     * into UH Shield lifetime & per-site stats using per-tab deltas (no double-counting).
     */
    fun recordGeckoEtpTrackers(tabId: String, pageUrl: String, currentGeckoCount: Int) {
        val host = normalizeHost(pageUrl)
        if (host.isBlank() || currentGeckoCount < 0) return
        val prev = lastGeckoEtpByTab[tabId]
        val prevCount = if (prev != null && prev.first == host) prev.second else 0
        lastGeckoEtpByTab[tabId] = host to currentGeckoCount
        val delta = currentGeckoCount - prevCount
        if (delta > 0) {
            recordBlockedEvent(host, isTracker = true, count = delta)
        }
    }

    fun recordBlockedEvent(pageHost: String, isTracker: Boolean, count: Int = 1) {
        if (count <= 0) return
        val adDelta = if (!isTracker) count.toLong() else 0L
        val trackerDelta = if (isTracker) count.toLong() else 0L
        val timeDelta = adDelta * AD_TIME_SAVED_MS + trackerDelta * TRACKER_TIME_SAVED_MS

        val newAds = lifetimeAds.addAndGet(adDelta)
        val newTrackers = lifetimeTrackers.addAndGet(trackerDelta)
        val newSaved = lifetimeSavedMs.addAndGet(timeDelta)

        val cleanHost = normalizeHost(pageHost)
        if (cleanHost.isNotBlank()) {
            hostStatsMap.compute(cleanHost) { _, existing ->
                val cur = existing ?: ShieldStatsSnapshot()
                cur.copy(
                    adsBlocked = cur.adsBlocked + adDelta,
                    trackersBlocked = cur.trackersBlocked + trackerDelta,
                    estimatedTimeSavedMs = cur.estimatedTimeSavedMs + timeDelta,
                )
            }
            _hostStatsTick.value = System.nanoTime()
        }

        _state.update {
            it.copy(lifetimeStats = ShieldStatsSnapshot(newAds, newTrackers, newSaved))
        }

        val now = System.currentTimeMillis()
        val prev = lastStatsFlushMs.get()
        if (now - prev > 2000L && lastStatsFlushMs.compareAndSet(prev, now)) {
            appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
                ?.putLong(KEY_STATS_ADS, newAds)
                ?.putLong(KEY_STATS_TRACKERS, newTrackers)
                ?.putLong(KEY_STATS_SAVED_MS, newSaved)
                ?.apply()
        }
    }

    fun resetLifetimeStats() {
        val ctx = appContext ?: return
        lifetimeAds.set(0L)
        lifetimeTrackers.set(0L)
        lifetimeSavedMs.set(0L)
        hostStatsMap.clear()
        lastGeckoEtpByTab.clear()
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_STATS_ADS, 0L)
            .putLong(KEY_STATS_TRACKERS, 0L)
            .putLong(KEY_STATS_SAVED_MS, 0L)
            .apply()
        _state.update { it.copy(lifetimeStats = ShieldStatsSnapshot()) }
        _hostStatsTick.value = System.nanoTime()
    }

    // ─── Filter List Management ─────────────────────────────────────────────

    fun setListEnabled(listId: String, enabled: Boolean) {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentEnabled = getEnabledListIds(ctx).toMutableSet()
        if (enabled) currentEnabled.add(listId) else currentEnabled.remove(listId)
        prefs.edit().putStringSet(KEY_ENABLED_LISTS, currentEnabled).apply()

        _state.update { st ->
            st.copy(lists = st.lists.map { if (it.def.id == listId) it.copy(enabled = enabled) else it })
        }

        scope.launch {
            val file = listCacheFile(ctx, listId)
            if (enabled && (!file.exists() || file.length() < 64L)) {
                _state.update { it.copy(isSyncing = true) }
                try {
                    syncSingleList(ctx, listId)
                } finally {
                    _state.update { it.copy(isSyncing = false) }
                }
            }
            recompileFromDisk(ctx)
        }
    }

    fun addCustomFilterList(name: String, url: String): Boolean {
        val ctx = appContext ?: return false
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("https://") && !cleanUrl.startsWith("http://")) return false
        val cleanName = name.trim().ifBlank {
            try { URI(cleanUrl).host?.removePrefix("www.") ?: "Custom List" } catch (_: Exception) { "Custom List" }
        }
        val id = "custom_${System.currentTimeMillis()}"
        val customs = loadCustomListDefs(ctx).toMutableList()
        if (customs.any { it.url.equals(cleanUrl, ignoreCase = true) }) return false
        val def = FilterListDef(
            id = id,
            name = cleanName,
            description = cleanUrl,
            url = cleanUrl,
            tier = FilterPackTier.CUSTOM,
            defaultEnabled = true,
            isTrackerList = false,
        )
        customs.add(def)
        saveCustomListDefs(ctx, customs)

        val enabledSet = getEnabledListIds(ctx).toMutableSet()
        enabledSet.add(id)
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_ENABLED_LISTS, enabledSet).apply()

        _state.update { it.copy(lists = loadListStatuses(ctx)) }
        scope.launch {
            syncSingleList(ctx, id)
            recompileFromDisk(ctx)
        }
        return true
    }

    fun removeCustomFilterList(listId: String) {
        val ctx = appContext ?: return
        val customs = loadCustomListDefs(ctx).filterNot { it.id == listId }
        saveCustomListDefs(ctx, customs)
        listCacheFile(ctx, listId).delete()
        val enabledSet = getEnabledListIds(ctx) - listId
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_ENABLED_LISTS, enabledSet).apply()
        _state.update { it.copy(lists = loadListStatuses(ctx)) }
        scope.launch { recompileFromDisk(ctx) }
    }

    fun saveCustomUserRules(rulesText: String) {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CUSTOM_RULES, rulesText).apply()
        _state.update { it.copy(customUserRules = rulesText) }
        scope.launch { recompileFromDisk(ctx) }
    }

    fun syncEnabledLists(force: Boolean = true) {
        val ctx = appContext ?: return
        if (_state.value.isSyncing && !force) return
        scope.launch {
            syncEnabledListsSuspend(ctx)
        }
    }

    internal suspend fun syncEnabledListsSuspend(ctx: Context): Boolean {
        if (_state.value.isSyncing) return true
        _state.update { it.copy(isSyncing = true, lastSyncError = null) }
        var anyError: String? = null
        try {
            val enabledIds = getEnabledListIds(ctx)
            val allDefs = BUILT_IN_LISTS + loadCustomListDefs(ctx)
            val targets = allDefs.filter { it.id in enabledIds }
            for (def in targets) {
                val err = syncSingleList(ctx, def.id)
                if (err != null && anyError == null) anyError = err
            }
            val now = System.currentTimeMillis()
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_SYNC_MS, now).apply()
            recompileFromDisk(ctx)
            _state.update {
                it.copy(
                    isSyncing = false,
                    lastSyncMs = now,
                    lastSyncError = anyError,
                    lists = loadListStatuses(ctx),
                )
            }
            return anyError == null
        } catch (e: Exception) {
            UHLog.w("shield", "Filter list sync error: ${e.message}")
            _state.update { it.copy(isSyncing = false, lastSyncError = e.message ?: "Sync failed") }
            return false
        }
    }

    private fun syncSingleList(ctx: Context, listId: String): String? {
        val allDefs = BUILT_IN_LISTS + loadCustomListDefs(ctx)
        val def = allDefs.firstOrNull { it.id == listId } ?: return null
        return try {
            val req = Request.Builder()
                .url(def.url)
                .header("User-Agent", UhHttpClient.DEFAULT_USER_AGENT)
                .build()
            UhHttpClient.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val msg = "HTTP ${resp.code}"
                    saveListMeta(ctx, listId, 0, System.currentTimeMillis(), msg)
                    return msg
                }
                val body = resp.body?.string().orEmpty()
                if (body.length < 32) {
                    saveListMeta(ctx, listId, 0, System.currentTimeMillis(), "Empty response")
                    return "Empty response"
                }
                val out = listCacheFile(ctx, listId)
                out.parentFile?.mkdirs()
                out.writeText(body)
                val count = countEffectiveRules(body)
                saveListMeta(ctx, listId, count, System.currentTimeMillis(), null)
                null
            }
        } catch (e: Exception) {
            val msg = e.message?.take(60) ?: "Network error"
            saveListMeta(ctx, listId, 0, System.currentTimeMillis(), msg)
            msg
        }
    }

    private fun countEffectiveRules(text: String): Int {
        var count = 0
        BufferedReader(StringReader(text)).useLines { lines ->
            for (raw in lines) {
                val l = raw.trim()
                if (l.isNotEmpty() && !l.startsWith("!") && !l.startsWith("[") &&
                    !(l.startsWith("#") && !l.startsWith("##") && !l.startsWith("#@#") && !l.startsWith("#?#"))
                ) {
                    count++
                }
            }
        }
        return count
    }

    private fun buildBootstrapFilterSet(): UhAdblockRuleCompiler.CompiledFilterSet {
        val builder = UhAdblockRuleCompiler.Builder()
        builder.adDomains.addAll(BOOTSTRAP_AD_DOMAINS)
        builder.trackerDomains.addAll(BOOTSTRAP_TRACKER_DOMAINS)
        BOOTSTRAP_URL_PATTERNS.forEach { pat ->
            builder.addNetworkRule(
                UhAdblockRuleCompiler.CompiledNetworkRule(
                    pattern = pat,
                    isTracker = false,
                )
            )
        }
        builder.globalCosmetics.addAll(BOOTSTRAP_GLOBAL_COSMETICS)
        BOOTSTRAP_DOMAIN_COSMETICS.forEach { (dom, sels) ->
            builder.domainCosmetics[dom] = sels.toMutableList()
        }
        return builder.build()
    }

    private fun recompileFromDisk(ctx: Context) {
        val enabledIds = getEnabledListIds(ctx)
        val customRules = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_RULES, "") ?: ""

        // If user explicitly disabled EVERY filter list and has no custom rules -> 0 active rules!
        if (enabledIds.isEmpty() && customRules.isBlank()) {
            compiledSet = UhAdblockRuleCompiler.CompiledFilterSet.EMPTY
            _state.update { it.copy(totalCompiledRules = 0, lists = loadListStatuses(ctx)) }
            onCompiledRulesUpdated?.invoke()
            return
        }

        val builder = UhAdblockRuleCompiler.Builder()
        val allDefs = BUILT_IN_LISTS + loadCustomListDefs(ctx)
        var parsedAnyDiskList = false

        for (def in allDefs) {
            if (def.id !in enabledIds) continue
            val file = listCacheFile(ctx, def.id)
            if (!file.exists() || file.length() < 32L) continue
            try {
                file.bufferedReader().useLines { lines ->
                    UhAdblockRuleCompiler.parseLines(
                        lines = lines,
                        isTrackerList = def.isTrackerList,
                        builder = builder,
                    )
                }
                parsedAnyDiskList = true
            } catch (e: Exception) {
                UHLog.w("shield", "Failed parsing cached list ${def.id}: ${e.message}")
            }
        }

        // Only apply offline Day-0 bootstrap when lists ARE enabled but none have downloaded to disk yet
        if (enabledIds.isNotEmpty() && !parsedAnyDiskList) {
            builder.adDomains.addAll(BOOTSTRAP_AD_DOMAINS)
            builder.trackerDomains.addAll(BOOTSTRAP_TRACKER_DOMAINS)
            BOOTSTRAP_URL_PATTERNS.forEach { pat ->
                builder.addNetworkRule(UhAdblockRuleCompiler.CompiledNetworkRule(pattern = pat))
            }
            builder.globalCosmetics.addAll(BOOTSTRAP_GLOBAL_COSMETICS)
            BOOTSTRAP_DOMAIN_COSMETICS.forEach { (dom, sels) ->
                builder.domainCosmetics.getOrPut(dom) { ArrayList() }.addAll(sels)
            }
        }

        if (customRules.isNotBlank()) {
            UhAdblockRuleCompiler.parseLines(
                lines = customRules.lineSequence(),
                isTrackerList = false,
                builder = builder,
            )
        }

        val compiled = builder.build()
        compiledSet = compiled

        _state.update { it.copy(totalCompiledRules = compiled.totalRules, lists = loadListStatuses(ctx)) }
        onCompiledRulesUpdated?.invoke()
    }

    /**
     * Ultra-fast O(1) domain-suffix check invoked from native queries.
     * Returns: 0 = allow, 1 = block (ad), 2 = block (tracker).
     */
    fun classifyRequest(requestUrl: String, pageUrl: String, isThirdParty: Boolean): Int {
        val st = _state.value
        if (st.level == ShieldLevel.OFF) return 0
        val pageHost = normalizeHost(pageUrl)
        if (pageHost.isNotBlank() && pageHost in st.pausedHosts) return 0

        val reqHost = normalizeHost(requestUrl)
        if (reqHost.isBlank()) return 0

        val set = compiledSet
        if (matchesDomainSet(reqHost, set.exceptionDomains)) return 0
        if (isThirdParty && matchesDomainSet(reqHost, set.thirdPartyExceptionDomains)) return 0

        if (st.level == ShieldLevel.STANDARD && !isThirdParty) return 0

        if (matchesDomainSet(reqHost, set.trackerDomains)) return 2
        if (matchesDomainSet(reqHost, set.adDomains)) return 1
        if (isThirdParty) {
            if (matchesDomainSet(reqHost, set.thirdPartyTrackerDomains)) return 2
            if (matchesDomainSet(reqHost, set.thirdPartyAdDomains)) return 1
        }
        return 0
    }

    private fun matchesDomainSet(host: String, set: Set<String>): Boolean {
        if (set.isEmpty()) return false
        var candidate = host
        while (true) {
            if (candidate in set) return true
            val dot = candidate.indexOf('.')
            if (dot < 0 || dot >= candidate.length - 1) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    /**
     * Lightweight runtime settings payload (used for quick synchronous handshake).
     * Full filter lists are pushed asynchronously via [buildChunkedRuntimeMessages] without truncation.
     */
    fun buildExtensionRuntimePayload(ctx: Context): JSONObject {
        val st = _state.value
        val pausedArr = JSONArray().apply { st.pausedHosts.forEach { put(it) } }
        val jsOnceArr = JSONArray().apply { jsAllowOnceHosts.forEach { put(it) } }
        val jsAlwaysArr = JSONArray().apply { _jsAllowAlwaysHosts.value.forEach { put(it) } }
        val darkBypassArr = JSONArray().apply {
            SettingsPrefs.getStringSet(ctx, SettingsPrefs.K_DARK_BYPASS_HOSTS).forEach { put(it) }
        }

        return JSONObject().apply {
            put("shieldLevel", st.level.code)
            put("totalRules", compiledSet.totalRules)
            put("pausedHosts", pausedArr)
            put("smartBlockJs", SettingsPrefs.getBool(ctx, SettingsPrefs.K_PG_SCRIPT, false))
            put("jsAllowOnceHosts", jsOnceArr)
            put("jsAllowAlwaysHosts", jsAlwaysArr)
            put("cookieBanners", SettingsPrefs.getBool(ctx, SettingsPrefs.K_PG_COOKIE_BANNERS, true))
            put("clearUrls", SettingsPrefs.getBool(ctx, SettingsPrefs.K_PG_CLEAR_URLS, true))
            put("deAmp", SettingsPrefs.getBool(ctx, SettingsPrefs.K_ADV_DEAMP, true))
            put("blockPopups", SettingsPrefs.getBool(ctx, SettingsPrefs.K_PG_POPUP_BLOCK, true))
            put("antiFingerprint", SettingsPrefs.getBool(ctx, SettingsPrefs.K_PG_FINGERPRINT, true))
            put("dnt", SettingsPrefs.getBool(ctx, SettingsPrefs.K_ADV_DNT, false))
            put("gpc", SettingsPrefs.getBool(ctx, SettingsPrefs.K_ADV_GPC, true))
            put("webrtcPolicy", SettingsPrefs.getInt(ctx, SettingsPrefs.K_ADV_WEBRTC_POLICY, 1))
            put("backgroundPlay", SettingsPrefs.getBool(ctx, SettingsPrefs.K_BACKGROUND_PLAY, true))
            put("forceDarkWeb", UhPrivacyController.isForceDarkWebEffectivelyEnabled(ctx))
            put("enhanceDark", SettingsPrefs.getBool(ctx, SettingsPrefs.K_ENHANCE_DARK, false))
            put("darkBypassHosts", darkBypassArr)
        }
    }

    /**
     * Builds a Binder-safe chunked Native Messaging stream (BEGIN -> CHUNK* -> COMMIT)
     * so 300k-500k+ compiled rules sync completely to uh_bridge with zero truncation.
     */
    fun buildChunkedRuntimeMessages(ctx: Context): List<JSONObject> {
        val syncId = System.nanoTime()
        val set = compiledSet
        val messages = ArrayList<JSONObject>(32)

        messages.add(
            JSONObject().apply {
                put("action", "SHIELD_CONFIG_BEGIN")
                put("syncId", syncId)
                put("settings", buildExtensionRuntimePayload(ctx))
            }
        )

        fun appendDomainChunks(kind: String, domains: Set<String>) {
            if (domains.isEmpty()) return
            var arr = JSONArray()
            var count = 0
            for (d in domains) {
                arr.put(d)
                count++
                if (count >= DOMAIN_CHUNK_SIZE) {
                    messages.add(
                        JSONObject().apply {
                            put("action", "SHIELD_CONFIG_CHUNK")
                            put("syncId", syncId)
                            put("kind", kind)
                            put("items", arr)
                        }
                    )
                    arr = JSONArray()
                    count = 0
                }
            }
            if (count > 0) {
                messages.add(
                    JSONObject().apply {
                        put("action", "SHIELD_CONFIG_CHUNK")
                        put("syncId", syncId)
                        put("kind", kind)
                        put("items", arr)
                    }
                )
            }
        }

        appendDomainChunks("adDomains", set.adDomains)
        appendDomainChunks("trackerDomains", set.trackerDomains)
        appendDomainChunks("thirdPartyAdDomains", set.thirdPartyAdDomains)
        appendDomainChunks("thirdPartyTrackerDomains", set.thirdPartyTrackerDomains)
        appendDomainChunks("popupDomains", set.popupDomains)
        appendDomainChunks("exceptionDomains", set.exceptionDomains)
        appendDomainChunks("thirdPartyExceptionDomains", set.thirdPartyExceptionDomains)

        if (set.networkRules.isNotEmpty()) {
            var arr = JSONArray()
            var count = 0
            for (rule in set.networkRules) {
                arr.put(rule.toWireJson())
                count++
                if (count >= STRUCTURED_RULE_CHUNK_SIZE) {
                    messages.add(
                        JSONObject().apply {
                            put("action", "SHIELD_CONFIG_CHUNK")
                            put("syncId", syncId)
                            put("kind", "networkRules")
                            put("items", arr)
                        }
                    )
                    arr = JSONArray()
                    count = 0
                }
            }
            if (count > 0) {
                messages.add(
                    JSONObject().apply {
                        put("action", "SHIELD_CONFIG_CHUNK")
                        put("syncId", syncId)
                        put("kind", "networkRules")
                        put("items", arr)
                    }
                )
            }
        }

        messages.add(
            JSONObject().apply {
                put("action", "SHIELD_CONFIG_COMMIT")
                put("syncId", syncId)
                put("totalRules", set.totalRules)
            }
        )

        return messages
    }

    /**
     * Returns combined global + domain-specific cosmetic CSS selectors for a given page host,
     * honoring #@# cosmetic exceptions and ~domain exclusions.
     */
    fun getCosmeticSelectorsForHost(pageUrl: String): List<String> {
        val st = _state.value
        if (st.level == ShieldLevel.OFF) return emptyList()
        val host = normalizeHost(pageUrl)
        if (host.isNotBlank() && host in st.pausedHosts) return emptyList()

        val set = compiledSet
        if (set.totalRules == 0) return emptyList()

        val hostExceptions = HashSet<String>()
        val hostExcludedGlobals = HashSet<String>()
        val domainMatches = ArrayList<String>(24)

        if (host.isNotBlank()) {
            var candidate = host
            while (true) {
                set.domainCosmeticExceptions[candidate]?.let { hostExceptions.addAll(it) }
                set.excludedDomainCosmetics[candidate]?.let { hostExcludedGlobals.addAll(it) }
                set.domainCosmetics[candidate]?.let { domainMatches.addAll(it) }
                val dot = candidate.indexOf('.')
                if (dot < 0 || dot >= candidate.length - 1) break
                candidate = candidate.substring(dot + 1)
            }
        }

        val result = ArrayList<String>(set.globalCosmetics.size + domainMatches.size)
        for (sel in set.globalCosmetics) {
            if (sel !in hostExceptions && sel !in hostExcludedGlobals) {
                result.add(sel)
            }
        }
        for (sel in domainMatches) {
            if (sel !in hostExceptions) {
                result.add(sel)
            }
        }
        return result
    }

    /**
     * Builds the full page-level protection payload returned for GET_PAGE_PROTECT in content.js.
     * Automatically relaxes fingerprinting protection when UH Shield is paused on this host.
     */
    fun buildPageProtectPayload(ctx: Context, pageUrl: String): JSONObject {
        val st = _state.value
        val host = normalizeHost(pageUrl)
        val paused = host.isNotBlank() && host in st.pausedHosts
        val shieldActive = st.level != ShieldLevel.OFF && !paused
        val set = compiledSet

        val selArr = JSONArray()
        val procArr = JSONArray()
        val scriptletsArr = JSONArray()

        if (shieldActive && set.totalRules > 0) {
            getCosmeticSelectorsForHost(pageUrl).forEach { selArr.put(it) }

            set.globalProcedural.forEach { procArr.put(it.toWireJson()) }
            if (host.isNotBlank()) {
                var candidate = host
                while (true) {
                    set.domainProcedural[candidate]?.forEach { procArr.put(it.toWireJson()) }
                    set.domainScriptlets[candidate]?.forEach { scriptletsArr.put(it.toWireJson()) }
                    val dot = candidate.indexOf('.')
                    if (dot < 0 || dot >= candidate.length - 1) break
                    candidate = candidate.substring(dot + 1)
                }
            }
        }

        val antiFingerprintPref = SettingsPrefs.getBool(ctx, SettingsPrefs.K_PG_FINGERPRINT, true)

        return JSONObject().apply {
            put("shieldLevel", st.level.code)
            put("paused", paused)
            put("cosmetics", selArr)
            put("procedural", procArr)
            put("scriptlets", scriptletsArr)
            put("cookieBanners", SettingsPrefs.getBool(ctx, SettingsPrefs.K_PG_COOKIE_BANNERS, true) && !paused)
            put("blockPopups", SettingsPrefs.getBool(ctx, SettingsPrefs.K_PG_POPUP_BLOCK, true) && !paused)
            put("antiFingerprint", antiFingerprintPref && !paused)
            put("backgroundPlay", SettingsPrefs.getBool(ctx, SettingsPrefs.K_BACKGROUND_PLAY, true))
            put("webrtcPolicy", SettingsPrefs.getInt(ctx, SettingsPrefs.K_ADV_WEBRTC_POLICY, 1))
            put("forceDarkWeb", UhPrivacyController.isForceDarkWebEffectivelyEnabled(ctx))
            put("enhanceDark", SettingsPrefs.getBool(ctx, SettingsPrefs.K_ENHANCE_DARK, false))
            put("darkBypassed", UhPrivacyController.isSiteDarkBypassed(ctx, pageUrl))
        }
    }

    /**
     * Expanded ClearURLs + Redirect Link Unwrapper + Auto De-AMP URL cleaner.
     */
    fun cleanTrackingUrl(rawUrl: String, stripTrackers: Boolean = true, deAmp: Boolean = true): String {
        if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) return rawUrl
        var current = rawUrl.trim()

        if (stripTrackers) {
            current = unwrapRedirectUrl(current)
        }
        if (deAmp) {
            current = unwrapAmpUrl(current)
        }
        if (!stripTrackers) return current

        return try {
            val qIdx = current.indexOf('?')
            if (qIdx < 0) return current
            val hashIdx = current.indexOf('#', qIdx)
            val base = current.substring(0, qIdx)
            val query = if (hashIdx >= 0) current.substring(qIdx + 1, hashIdx) else current.substring(qIdx + 1)
            val frag = if (hashIdx >= 0) current.substring(hashIdx) else ""
            if (query.isBlank()) return current

            val kept = query.split('&').filter { pair ->
                if (pair.isBlank()) return@filter false
                val key = pair.substringBefore('=').lowercase(Locale.ROOT)
                !isTrackingQueryParam(key)
            }
            if (kept.isEmpty()) base + frag else "$base?${kept.joinToString("&")}$frag"
        } catch (_: Exception) {
            current
        }
    }

    private fun unwrapRedirectUrl(url: String): String {
        return try {
            val uri = URI(url)
            val host = (uri.host ?: return url).lowercase(Locale.ROOT)
            val path = (uri.path ?: "").lowercase(Locale.ROOT)
            val rawQuery = uri.rawQuery ?: return url

            val targetParam = when {
                (host.contains("google.") && path == "/url") -> extractQueryParam(rawQuery, "q")
                    ?: extractQueryParam(rawQuery, "url")
                (host == "l.facebook.com" || host == "lm.facebook.com" || host == "l.messenger.com") && path.startsWith("/l.php") ->
                    extractQueryParam(rawQuery, "u")
                host == "l.instagram.com" -> extractQueryParam(rawQuery, "u")
                host == "out.reddit.com" -> extractQueryParam(rawQuery, "url")
                host == "away.vk.com" && path.startsWith("/away.php") -> extractQueryParam(rawQuery, "to")
                host == "steamcommunity.com" && path.startsWith("/linkfilter") ->
                    extractQueryParam(rawQuery, "u") ?: extractQueryParam(rawQuery, "url")
                host == "exit.sc" -> extractQueryParam(rawQuery, "url")
                host == "href.li" && rawQuery.startsWith("http") -> rawQuery
                else -> null
            } ?: return url

            val decoded = URLDecoder.decode(targetParam, "UTF-8").trim()
            if (decoded.startsWith("https://") || decoded.startsWith("http://")) decoded else url
        } catch (_: Exception) {
            url
        }
    }

    private fun extractQueryParam(rawQuery: String, key: String): String? {
        for (part in rawQuery.split('&')) {
            val eq = part.indexOf('=')
            if (eq > 0 && part.substring(0, eq).equals(key, ignoreCase = true)) {
                return part.substring(eq + 1)
            }
        }
        return null
    }

    private fun unwrapAmpUrl(url: String): String {
        return try {
            val lower = url.lowercase(Locale.ROOT)
            val ampSIdx = lower.indexOf("/amp/s/")
            if (ampSIdx > 0 && (lower.contains(".google.") || lower.contains("ampproject.org"))) {
                val target = url.substring(ampSIdx + 7)
                if (target.contains('.')) {
                    return "https://${URLDecoder.decode(target, "UTF-8").removePrefix("https://").removePrefix("http://")}"
                }
            }
            val ampIdx = lower.indexOf("/amp/")
            if (ampIdx > 0 && lower.contains(".google.")) {
                val target = url.substring(ampIdx + 5)
                if (target.contains('.') && !target.startsWith("s/")) {
                    return "https://${URLDecoder.decode(target, "UTF-8").removePrefix("https://").removePrefix("http://")}"
                }
            }
            url
        } catch (_: Exception) {
            url
        }
    }

    private fun isTrackingQueryParam(key: String): Boolean {
        if (key.startsWith("utm_") ||
            key.startsWith("hsa_") ||
            key.startsWith("mtm_") ||
            key.startsWith("pk_") ||
            key.startsWith("pd_rd_") ||
            key.startsWith("pf_rd_") ||
            key.startsWith("ga_") ||
            key.startsWith("mc_")
        ) {
            return true
        }
        return key in TRACKING_PARAMS
    }

    fun normalizeHost(urlOrHost: String): String {
        val raw = urlOrHost.trim()
        if (raw.isBlank()) return ""
        return try {
            val host = if (raw.contains("://")) URI(raw).host else raw.substringBefore('/').substringBefore(':')
            host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
        } catch (_: Exception) {
            raw.lowercase(Locale.ROOT).removePrefix("www.").substringBefore('/')
        }
    }

    private fun listCacheFile(ctx: Context, listId: String): File {
        val safe = listId.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        return File(File(ctx.filesDir, "uh_shield_lists"), "$safe.txt")
    }

    private fun getEnabledListIds(ctx: Context): Set<String> {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(KEY_ENABLED_LISTS, null)
        if (saved != null) return saved.toSet()
        val defaults = BUILT_IN_LISTS.filter { it.defaultEnabled }.map { it.id }.toSet()
        prefs.edit().putStringSet(KEY_ENABLED_LISTS, defaults).apply()
        return defaults
    }

    private fun loadCustomListDefs(ctx: Context): List<FilterListDef> {
        val raw = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_LISTS_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id")
                val name = obj.optString("name")
                val url = obj.optString("url")
                if (id.isBlank() || url.isBlank()) null
                else FilterListDef(id, name.ifBlank { url }, url, url, FilterPackTier.CUSTOM, true, false)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveCustomListDefs(ctx: Context, defs: List<FilterListDef>) {
        val arr = JSONArray()
        defs.forEach { d ->
            arr.put(JSONObject().apply {
                put("id", d.id)
                put("name", d.name)
                put("url", d.url)
            })
        }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CUSTOM_LISTS_JSON, arr.toString()).apply()
    }

    private fun saveListMeta(ctx: Context, listId: String, ruleCount: Int, updatedMs: Long, error: String?) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val root = try {
            JSONObject(prefs.getString(KEY_LIST_META_JSON, "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        root.put(listId, JSONObject().apply {
            put("count", ruleCount)
            put("updated", updatedMs)
            if (error != null) put("error", error)
        })
        prefs.edit().putString(KEY_LIST_META_JSON, root.toString()).apply()
    }

    private fun loadListStatuses(ctx: Context): List<FilterListStatus> {
        val enabledIds = getEnabledListIds(ctx)
        val meta = try {
            JSONObject(ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LIST_META_JSON, "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        val allDefs = BUILT_IN_LISTS + loadCustomListDefs(ctx)
        return allDefs.map { def ->
            val obj = meta.optJSONObject(def.id)
            FilterListStatus(
                def = def,
                enabled = def.id in enabledIds,
                ruleCount = obj?.optInt("count", 0) ?: 0,
                lastUpdatedMs = obj?.optLong("updated", 0L) ?: 0L,
                lastError = obj?.optString("error")?.ifBlank { null },
            )
        }
    }

    private val TRACKING_PARAMS: Set<String> = setOf(
        "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "twclid",
        "yclid", "ymclid", "igshid", "igsh", "si", "feature", "pp", "ref_src", "ref_url",
        "oly_anon_id", "oly_enc_id", "vero_id", "wickedid",
        "_hsenc", "_hsmi", "hsctatracking", "mkt_tok", "trk", "trkid",
        "spm", "scm", "aff_id", "aff_sub", "click_id", "clickid", "irclickid",
        "s_cid", "s_kwcid", "ef_id", "icid", "ncid", "sr_share", "share_id",
        "__cf_chl_tk", "guccounter", "guce_referrer", "guce_referrer_sig",
        "nb", "ei", "ved", "uact", "oq", "gs_lcp", "sclient",
        "is_from_webapp", "sender_device", "sender_web_id", "_t", "_r",
        "rb_clickid", "s_cid", "scid", "sscid", "ttclid", "li_fat_id",
        "epik", "ndclid", "oborigurl", "dicbo", "ad_id", "adset_id", "campaign_id",
        "ref_", "qid", "sr", "dib", "dib_tag", "ascsubtag", "linkcode", "linkid",
        "cxt", "cn", "ref_campaign", "ref_source", "share_source",
    )

    // Bundled high-impact Day-0 bootstrap lists — ONLY used when lists are enabled and not yet downloaded
    private val BOOTSTRAP_AD_DOMAINS: Set<String> = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "pagead2.googlesyndication.com", "tpc.googlesyndication.com", "ade.googlesyndication.com",
        "adnxs.com", "adsrvr.org", "advertising.com", "adform.net", "adcolony.com", "admob.com",
        "amazon-adsystem.com", "aax.amazon-adsystem.com", "criteo.com", "criteo.net",
        "taboola.com", "outbrain.com", "mgid.com", "revcontent.com", "zergnet.com",
        "pubmatic.com", "rubiconproject.com", "openx.net", "casalemedia.com", "indexww.com",
        "smartadserver.com", "bidswitch.net", "triplelift.com", "sharethrough.com", "33across.com",
        "media.net", "yieldmo.com", "teads.tv", "spotxchange.com", "springserve.com",
        "popads.net", "popcash.net", "propellerads.com", "exoclick.com", "exosrv.com",
        "juicyads.com", "trafficjunky.net", "trafficstars.com", "adsterra.com", "a-ads.com",
        "adcash.com", "hilltopads.net", "clickadu.com", "monetag.com", "pushame.com",
        "zedo.com", "undertone.com", "gumgum.com", "sovrn.com", "lijit.com", "kargo.com",
        "moatads.com", "adsafeprotected.com", "doubleverify.com", "serving-sys.com",
        "flashtalking.com", "innovid.com", "tremorhub.com", "vungle.com", "unityads.unity3d.com",
        "applovin.com", "chartboost.com", "inmobi.com", "ironsrc.com", "startappservice.com",
        "ads.yahoo.com", "ads.linkedin.com", "ads.twitter.com", "ads-api.twitter.com",
        "ads.tiktok.com", "ads.reddit.com", "ads.pinterest.com", "an.facebook.com",
        "static.ads-twitter.com", "pixel.advertising.com", "banners.ebay.com",
    )

    private val BOOTSTRAP_TRACKER_DOMAINS: Set<String> = setOf(
        "google-analytics.com", "googletagmanager.com", "googletagservices.com",
        "analytics.google.com", "stats.g.doubleclick.net", "app-measurement.com",
        "connect.facebook.net", "pixel.facebook.com", "tr.snapchat.com", "sc-static.net",
        "analytics.tiktok.com", "analytics.twitter.com", "t.co", "bat.bing.com",
        "clarity.ms", "hotjar.com", "static.hotjar.com", "script.hotjar.com",
        "fullstory.com", "logrocket.com", "mouseflow.com", "crazyegg.com", "luckyorange.com",
        "mixpanel.com", "api.mixpanel.com", "segment.io", "api.segment.io", "cdn.segment.com",
        "amplitude.com", "api.amplitude.com", "heapanalytics.com", "kissmetrics.com",
        "scorecardresearch.com", "quantserve.com", "quantcount.com", "chartbeat.com", "chartbeat.net",
        "omtrdc.net", "2o7.net", "demdex.net", "everesttech.net", "bluekai.com", "krxd.net",
        "exelator.com", "rlcdn.com", "tapad.com", "liveramp.com", "pippio.com", "id5-sync.com",
        "crwdcntrl.net", "agkn.com", "narrative.io", "lotame.com", "eyeota.net",
        "newrelic.com", "bam.nr-data.net", "js-agent.newrelic.com", "bugsnag.com",
        "sentry.io", "browser-intake-datadoghq.com", "branch.io", "app.link", "adjust.com",
        "appsflyer.com", "kochava.com", "singular.net", "braze.com", "onesignal.com",
        "clevertap.com", "webengage.com", "mc.yandex.ru", "metrika.yandex.ru", "hm.baidu.com",
        "coinhive.com", "coin-hive.com", "authedmine.com", "cryptoloot.pro",
    )

    private val BOOTSTRAP_URL_PATTERNS: List<String> = listOf(
        "/pagead/js/", "/pagead/ads", "/adserver/", "/adservice/", "/ads/banner",
        "/advert/", "/ad_banner", "/sponsored_ads", "/prebid.js", "/gpt.js",
        "/pubads_impl", "/popunder", "/track/pixel", "/beacon.js", "/telemetry/v1",
        "/ad_300x250", "/ad_728x90", "/ads/static/", "/banner_ad",
    )

    private val BOOTSTRAP_GLOBAL_COSMETICS: List<String> = listOf(
        "ins.adsbygoogle",
        "[id^='google_ads_iframe']",
        "[id^='div-gpt-ad']",
        ".adsbygoogle",
        ".ad-container",
        ".ad-wrapper",
        ".ad-banner",
        ".ad-slot",
        ".sponsored-content",
        ".taboola-container",
        "#taboola-below-article-thumbnails",
        ".OUTBRAIN",
        "[data-ad-slot]",
        "[data-ad-unit]",
        "[data-google-query-id]",
        "iframe[src*='doubleclick.net']",
        "iframe[src*='googlesyndication.com']",
    )

    private val BOOTSTRAP_DOMAIN_COSMETICS: Map<String, List<String>> = mapOf(
        "youtube.com" to listOf(
            "ytd-ad-slot-renderer",
            "ytd-banner-promo-renderer",
            "ytd-promoted-sparkles-web-renderer",
            "ytm-promoted-sparkles-web-renderer",
            "ytm-companion-ad-renderer",
            "#player-ads",
            ".ytp-ad-overlay-container",
            ".ytp-ad-module",
            "ad-slot-renderer",
        ),
        "m.youtube.com" to listOf(
            "ytm-promoted-sparkles-web-renderer",
            "ytm-companion-ad-renderer",
            "ad-slot-renderer",
            ".ad-showing .video-ads",
        ),
        "reddit.com" to listOf(
            "shreddit-ad-post",
            "[data-testid='promoted-post']",
            ".promotedlink",
        ),
    )
}

class UhShieldUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return try {
            UhShieldEngine.init(applicationContext)
            val ok = UhShieldEngine.syncEnabledListsSuspend(applicationContext)
            if (ok) Result.success() else Result.retry()
        } catch (e: Exception) {
            UHLog.w("shield", "UhShieldUpdateWorker failed: ${e.message}")
            Result.retry()
        }
    }
}
