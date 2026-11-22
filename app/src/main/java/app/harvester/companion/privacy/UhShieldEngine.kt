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

/**
 * UH Shield — Flagship 3-layer built-in ad, tracker, annoyance & malware blocker.
 *
 * Layer 1 (Network): O(1) domain-suffix hash lookup + URL substring pattern matching + exception rules,
 *                    synced to the bundled WebExtension for sub-millisecond pre-download blocking.
 * Layer 2 (Cosmetic & Anti-Adblock): Global + per-domain CSS hiding rules & lightweight scriptlets
 *                    (anti-adblock defuser, cookie wall collapse, sponsored card cleanup).
 * Layer 3 (Gecko Native ETP): Coordinated via [UhPrivacyController].
 *
 * Ships with an embedded Day-0 bootstrap rule pack so protection starts immediately offline,
 * and downloads/caches Full Power + optional Nuclear & Custom filter lists via WorkManager.
 */
object UhShieldEngine {

    private const val PREFS_NAME = "uh_shield_v2"
    private const val KEY_PAUSED_HOSTS = "paused_hosts"
    private const val KEY_ENABLED_LISTS = "enabled_list_ids"
    private const val KEY_CUSTOM_LISTS_JSON = "custom_lists_json"
    private const val KEY_CUSTOM_RULES = "custom_user_rules"
    private const val KEY_STATS_ADS = "stats_ads_blocked"
    private const val KEY_STATS_TRACKERS = "stats_trackers_blocked"
    private const val KEY_STATS_SAVED_MS = "stats_time_saved_ms"
    private const val KEY_LAST_SYNC_MS = "last_sync_ms"
    private const val KEY_LIST_META_JSON = "list_meta_json"
    private const val WORK_NAME = "uh_shield_filter_sync"

    // Average wall-clock latency saved per blocked ad/tracker request (conservative Brave-aligned model)
    private const val AD_TIME_SAVED_MS = 85L
    private const val TRACKER_TIME_SAVED_MS = 45L

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

        // === Optional Nuclear & Extra Customization Packs ===
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

    private val lifetimeAds = AtomicLong(0L)
    private val lifetimeTrackers = AtomicLong(0L)
    private val lifetimeSavedMs = AtomicLong(0L)
    private val lastStatsFlushMs = AtomicLong(0L)

    // Compiled in-memory O(1) matcher snapshot pushed to WebExtension & native queries
    @Volatile
    private var compiledAdDomains: Set<String> = emptySet()
    @Volatile
    private var compiledTrackerDomains: Set<String> = emptySet()
    @Volatile
    private var compiledExceptionDomains: Set<String> = emptySet()
    @Volatile
    private var compiledUrlPatterns: List<String> = emptyList()
    @Volatile
    private var compiledGlobalCosmetics: List<String> = emptyList()
    @Volatile
    private var compiledDomainCosmetics: Map<String, List<String>> = emptyMap()

    fun init(ctx: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        appContext = app

        compiledAdDomains = BOOTSTRAP_AD_DOMAINS
        compiledTrackerDomains = BOOTSTRAP_TRACKER_DOMAINS
        compiledUrlPatterns = BOOTSTRAP_URL_PATTERNS
        compiledGlobalCosmetics = BOOTSTRAP_GLOBAL_COSMETICS
        compiledDomainCosmetics = BOOTSTRAP_DOMAIN_COSMETICS

        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val level = ShieldLevel.fromCode(SettingsPrefs.getInt(app, SettingsPrefs.K_PG_SHIELD_LEVEL, ShieldLevel.AGGRESSIVE.code))
        val paused = prefs.getStringSet(KEY_PAUSED_HOSTS, emptySet())?.toSet() ?: emptySet()
        val ads = prefs.getLong(KEY_STATS_ADS, 0L)
        val trackers = prefs.getLong(KEY_STATS_TRACKERS, 0L)
        val savedMs = prefs.getLong(KEY_STATS_SAVED_MS, 0L)
        val lastSync = prefs.getLong(KEY_LAST_SYNC_MS, 0L)
        val userRules = prefs.getString(KEY_CUSTOM_RULES, "") ?: ""

        lifetimeAds.set(ads)
        lifetimeTrackers.set(trackers)
        lifetimeSavedMs.set(savedMs)

        val statuses = loadListStatuses(app)
        _state.value = State(
            level = level,
            pausedHosts = paused,
            lifetimeStats = ShieldStatsSnapshot(ads, trackers, savedMs),
            lists = statuses,
            customUserRules = userRules,
            totalCompiledRules = BOOTSTRAP_AD_DOMAINS.size + BOOTSTRAP_TRACKER_DOMAINS.size + BOOTSTRAP_GLOBAL_COSMETICS.size,
            lastSyncMs = lastSync,
        )

        scope.launch {
            recompileFromDisk(app)
            val stale = (System.currentTimeMillis() - lastSync) > TimeUnit.HOURS.toMillis(24)
            if (stale) {
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

        // Batch disk persistence every 2s to avoid SharedPreferences I/O thrashing
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
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_STATS_ADS, 0L)
            .putLong(KEY_STATS_TRACKERS, 0L)
            .putLong(KEY_STATS_SAVED_MS, 0L)
            .apply()
        _state.update { it.copy(lifetimeStats = ShieldStatsSnapshot()) }
        _hostStatsTick.value = System.nanoTime()
    }

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
                syncSingleList(ctx, listId)
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
                if (l.isNotEmpty() && !l.startsWith("!") && !l.startsWith("#") && !l.startsWith("[")) {
                    count++
                }
            }
        }
        return count
    }

    private fun recompileFromDisk(ctx: Context) {
        val adDomains = HashSet<String>(BOOTSTRAP_AD_DOMAINS)
        val trackerDomains = HashSet<String>(BOOTSTRAP_TRACKER_DOMAINS)
        val exceptions = HashSet<String>()
        val urlPatterns = LinkedHashSet<String>(BOOTSTRAP_URL_PATTERNS)
        val globalCosmetics = LinkedHashSet<String>(BOOTSTRAP_GLOBAL_COSMETICS)
        val domainCosmetics = HashMap<String, MutableList<String>>()
        BOOTSTRAP_DOMAIN_COSMETICS.forEach { (k, v) -> domainCosmetics[k] = v.toMutableList() }

        val enabledIds = getEnabledListIds(ctx)
        val allDefs = BUILT_IN_LISTS + loadCustomListDefs(ctx)

        for (def in allDefs) {
            if (def.id !in enabledIds) continue
            val file = listCacheFile(ctx, def.id)
            if (!file.exists()) continue
            try {
                file.bufferedReader().useLines { lines ->
                    parseFilterLines(
                        lines = lines,
                        isTrackerList = def.isTrackerList,
                        adDomains = adDomains,
                        trackerDomains = trackerDomains,
                        exceptions = exceptions,
                        urlPatterns = urlPatterns,
                        globalCosmetics = globalCosmetics,
                        domainCosmetics = domainCosmetics,
                    )
                }
            } catch (e: Exception) {
                UHLog.w("shield", "Failed parsing cached list ${def.id}: ${e.message}")
            }
        }

        val customRules = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_RULES, "") ?: ""
        if (customRules.isNotBlank()) {
            parseFilterLines(
                lines = customRules.lineSequence(),
                isTrackerList = false,
                adDomains = adDomains,
                trackerDomains = trackerDomains,
                exceptions = exceptions,
                urlPatterns = urlPatterns,
                globalCosmetics = globalCosmetics,
                domainCosmetics = domainCosmetics,
            )
        }

        // Remove explicit exceptions from domain sets
        adDomains.removeAll(exceptions)
        trackerDomains.removeAll(exceptions)

        compiledAdDomains = adDomains
        compiledTrackerDomains = trackerDomains
        compiledExceptionDomains = exceptions
        compiledUrlPatterns = urlPatterns.take(MAX_URL_PATTERNS)
        compiledGlobalCosmetics = globalCosmetics.take(MAX_GLOBAL_COSMETICS)
        compiledDomainCosmetics = domainCosmetics

        val total = adDomains.size + trackerDomains.size + urlPatterns.size +
            globalCosmetics.size + domainCosmetics.values.sumOf { it.size }
        _state.update { it.copy(totalCompiledRules = total, lists = loadListStatuses(ctx)) }
        onCompiledRulesUpdated?.invoke()
    }

    private fun parseFilterLines(
        lines: Sequence<String>,
        isTrackerList: Boolean,
        adDomains: MutableSet<String>,
        trackerDomains: MutableSet<String>,
        exceptions: MutableSet<String>,
        urlPatterns: MutableSet<String>,
        globalCosmetics: MutableSet<String>,
        domainCosmetics: MutableMap<String, MutableList<String>>,
    ) {
        val maxDomains = 140_000
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) continue
            if (line.startsWith("#") && !line.startsWith("##")) continue

            // Cosmetic hiding rules: ##selector or domain1,domain2##selector
            val cosIdx = line.indexOf("##")
            if (cosIdx >= 0 && !line.contains("#@#") && !line.contains("#?#") && !line.contains(":has(") && !line.contains("+js(")) {
                val selector = line.substring(cosIdx + 2).trim()
                if (selector.length in 3..140 && !selector.contains("{") && !selector.contains("}")) {
                    if (cosIdx == 0) {
                        if (globalCosmetics.size < MAX_GLOBAL_COSMETICS) {
                            globalCosmetics.add(selector)
                        }
                    } else {
                        val domainsPart = line.substring(0, cosIdx)
                        domainsPart.split(',').forEach { rawDom ->
                            val dom = rawDom.trim().lowercase(Locale.ROOT)
                            if (dom.isNotBlank() && !dom.startsWith("~") && dom.contains('.')) {
                                val list = domainCosmetics.getOrPut(dom) { mutableListOf() }
                                if (list.size < 40) list.add(selector)
                            }
                        }
                    }
                }
                continue
            }

            // Skip extended procedural / scriptlet / exception cosmetic syntax
            if (line.contains("#@#") || line.contains("#?#") || line.contains("+js(")) continue

            // Exception rule: @@||domain.com^
            if (line.startsWith("@@||")) {
                val dom = extractAdblockDomain(line.substring(4))
                if (dom != null) exceptions.add(dom)
                continue
            }
            if (line.startsWith("@@")) continue

            if (adDomains.size + trackerDomains.size >= maxDomains) continue

            // Standard ABP/uBO domain rule: ||example.com^ or ||example.com^$third-party
            if (line.startsWith("||")) {
                val dom = extractAdblockDomain(line.substring(2))
                if (dom != null) {
                    if (isTrackerList) trackerDomains.add(dom) else adDomains.add(dom)
                }
                continue
            }

            // Hosts-file syntax: 0.0.0.0 ad.example.com or 127.0.0.1 tracker.example.com
            if (line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ")) {
                val dom = line.substringAfter(' ').trim().substringBefore(' ').substringBefore('#').lowercase(Locale.ROOT)
                if (isValidDomain(dom)) {
                    if (isTrackerList) trackerDomains.add(dom) else adDomains.add(dom)
                }
                continue
            }

            // Pure URL path substring patterns (e.g., /pagead/js/, /adserver/, etc.)
            if (line.startsWith("/") && !line.endsWith("/") && !line.contains('*') && !line.contains('$')) {
                val clean = line.lowercase(Locale.ROOT)
                if (clean.length in 7..64 && urlPatterns.size < MAX_URL_PATTERNS) {
                    urlPatterns.add(clean)
                }
            }
        }
    }

    private fun extractAdblockDomain(afterPipes: String): String? {
        val beforeOpts = afterPipes.substringBefore('$')
        // Only treat as pure domain rule if it ends with ^ or has no path (or only ^)
        val hostPart = beforeOpts.substringBefore('^').substringBefore('/').trim().lowercase(Locale.ROOT)
        if (beforeOpts.contains('/') && !beforeOpts.endsWith("^")) return null
        if (hostPart.contains('*') || !isValidDomain(hostPart)) return null
        return hostPart
    }

    private fun isValidDomain(dom: String): Boolean {
        if (dom.length < 4 || dom.length > 253 || !dom.contains('.')) return false
        if (dom == "localhost" || dom == "local" || dom.startsWith(".") || dom.endsWith(".")) return false
        for (ch in dom) {
            if (!(ch in 'a'..'z' || ch in '0'..'9' || ch == '.' || ch == '-')) return false
        }
        return true
    }

    /**
     * Ultra-fast O(1) domain-suffix check invoked from native or WebExtension bridge.
     * Returns: 0 = allow, 1 = block (ad), 2 = block (tracker).
     */
    fun classifyRequest(requestUrl: String, pageUrl: String, isThirdParty: Boolean): Int {
        val st = _state.value
        if (st.level == ShieldLevel.OFF) return 0
        val pageHost = normalizeHost(pageUrl)
        if (pageHost.isNotBlank() && pageHost in st.pausedHosts) return 0

        val reqHost = normalizeHost(requestUrl)
        if (reqHost.isBlank()) return 0

        // Standard mode skips pure first-party host matches to avoid accidental breakage
        if (st.level == ShieldLevel.STANDARD && !isThirdParty) return 0

        if (matchesDomainSet(reqHost, compiledExceptionDomains)) return 0
        if (matchesDomainSet(reqHost, compiledTrackerDomains)) return 2
        if (matchesDomainSet(reqHost, compiledAdDomains)) return 1

        val lowerUrl = requestUrl.lowercase(Locale.ROOT)
        for (pat in compiledUrlPatterns) {
            if (lowerUrl.contains(pat)) return 1
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
     * Builds the compact live payload sent to the bundled UH Bridge WebExtension so
     * network blocking, cosmetic hiding, scriptlets, ClearURLs, De-AMP, WebRTC,
     * background media keep-alive, and Smart Web Dark Mode run locally with zero IPC latency.
     */
    fun buildExtensionRuntimePayload(ctx: Context): JSONObject {
        val st = _state.value
        val adArr = JSONArray()
        val trackerArr = JSONArray()
        val excArr = JSONArray()

        // Cap wire payload size cleanly while keeping 100% of high-priority domains
        var count = 0
        for (d in compiledAdDomains) {
            if (count++ >= 45_000) break
            adArr.put(d)
        }
        count = 0
        for (d in compiledTrackerDomains) {
            if (count++ >= 45_000) break
            trackerArr.put(d)
        }
        for (d in compiledExceptionDomains) excArr.put(d)

        val patArr = JSONArray()
        compiledUrlPatterns.forEach { patArr.put(it) }

        val pausedArr = JSONArray()
        st.pausedHosts.forEach { pausedArr.put(it) }

        val darkBypassArr = JSONArray()
        SettingsPrefs.getStringSet(ctx, SettingsPrefs.K_DARK_BYPASS_HOSTS).forEach { darkBypassArr.put(it) }

        return JSONObject().apply {
            put("shieldLevel", st.level.code)
            put("adDomains", adArr)
            put("trackerDomains", trackerArr)
            put("exceptionDomains", excArr)
            put("urlPatterns", patArr)
            put("pausedHosts", pausedArr)
            put("cookieBanners", SettingsPrefs.getBool(ctx, SettingsPrefs.K_PG_COOKIE_BANNERS, true))
            put("clearUrls", SettingsPrefs.getBool(ctx, SettingsPrefs.K_PG_CLEAR_URLS, true))
            put("deAmp", SettingsPrefs.getBool(ctx, SettingsPrefs.K_ADV_DEAMP, true))
            put("blockPopups", SettingsPrefs.getBool(ctx, SettingsPrefs.K_PG_POPUP_BLOCK, true))
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
     * Returns combined global + domain-specific cosmetic CSS selectors for a given page host.
     */
    fun getCosmeticSelectorsForHost(pageUrl: String): List<String> {
        val st = _state.value
        if (st.level == ShieldLevel.OFF) return emptyList()
        val host = normalizeHost(pageUrl)
        if (host.isNotBlank() && host in st.pausedHosts) return emptyList()

        val result = ArrayList<String>(compiledGlobalCosmetics.size + 24)
        result.addAll(compiledGlobalCosmetics)
        if (host.isNotBlank()) {
            var candidate = host
            while (true) {
                compiledDomainCosmetics[candidate]?.let { result.addAll(it) }
                val dot = candidate.indexOf('.')
                if (dot < 0 || dot >= candidate.length - 1) break
                candidate = candidate.substring(dot + 1)
            }
        }
        return result
    }

    /**
     * ClearURLs + Auto De-AMP URL cleaner used for Copy Link, Share, and navigation.
     */
    fun cleanTrackingUrl(rawUrl: String, stripTrackers: Boolean = true, deAmp: Boolean = true): String {
        if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) return rawUrl
        var current = rawUrl.trim()

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

    private fun unwrapAmpUrl(url: String): String {
        return try {
            val lower = url.lowercase(Locale.ROOT)
            // https://www.google.com/amp/s/example.com/path -> https://example.com/path
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
        if (key.startsWith("utm_") || key.startsWith("hsa_") || key.startsWith("mtm_") || key.startsWith("pk_")) {
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

    private const val MAX_URL_PATTERNS = 450
    private const val MAX_GLOBAL_COSMETICS = 280

    private val TRACKING_PARAMS: Set<String> = setOf(
        "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "twclid",
        "yclid", "igshid", "igsh", "si", "feature", "pp", "ref_src", "ref_url",
        "mc_cid", "mc_eid", "oly_anon_id", "oly_enc_id", "vero_id", "wickedid",
        "_hsenc", "_hsmi", "hsctatracking", "mkt_tok", "trk", "trkid",
        "spm", "scm", "aff_id", "aff_sub", "click_id", "clickid", "irclickid",
        "s_cid", "s_kwcid", "icid", "ncid", "sr_share", "share_id",
        "__cf_chl_tk", "guccounter", "guce_referrer", "guce_referrer_sig",
        "nb", "ei", "ved", "uact", "oq", "gs_lcp", "sclient",
    )

    // Bundled high-impact Day-0 bootstrap lists so UH Shield blocks ads & trackers immediately offline
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
