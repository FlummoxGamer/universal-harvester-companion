package app.harvester.companion.privacy

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.UHLog
import app.harvester.companion.core.network.UhHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * UH Shield — Flagship 3-layer built-in ad, tracker, annoyance & malware blocker.
 *
 * Layer 1 (Network): Full ABP/uBO rule engine — O(1) domain sets (all-party, 3rd-party, popup, exceptions)
 *                    + structured path/wildcard/regex/resource-type/domain/exception/redirect rules,
 *                    synced in chunks to the bundled WebExtension with zero rule truncation.
 * Layer 2 (Cosmetic & Anti-Adblock): Global + per-domain CSS hiding rules, #@# cosmetic exceptions,
 *                    procedural :has-text() selectors, blocked-banner collapsing, and uBO MAIN-world scriptlets.
 * Layer 3 (Gecko Native ETP & Cookie Banners): Coordinated via [UhPrivacyController] with live stat sync.
 *
 * Modularized across same-package helpers: [UhShieldModels], [UhShieldCatalog],
 * [UhShieldBootstrapRules], [UhShieldUrlCleaner], [UhShieldStorage], and [UhShieldUpdateWorker].
 */
object UhShieldEngine {

    private const val WORK_NAME = "uh_shield_filter_sync"

    // Average wall-clock latency saved per blocked ad/tracker request
    private const val AD_TIME_SAVED_MS = 85L
    private const val TRACKER_TIME_SAVED_MS = 45L

    val BUILT_IN_LISTS: List<FilterListDef>
        get() = UhShieldCatalog.BUILT_IN_LISTS

    val FILTER_CATEGORIES: List<FilterCategoryDef>
        get() = UhShieldCatalog.FILTER_CATEGORIES

    data class State(
        val level: ShieldLevel = ShieldLevel.AGGRESSIVE,
        val pausedHosts: Set<String> = emptySet(),
        val lifetimeStats: ShieldStatsSnapshot = ShieldStatsSnapshot(),
        val lists: List<FilterListStatus> = emptyList(),
        val categories: List<FilterCategoryStatus> = emptyList(),
        val customUserRules: String = "",
        val totalCompiledRules: Int = 0,
        val isSyncing: Boolean = false,
        val lastSyncMs: Long = 0L,
        val lastSyncError: String? = null,
    )

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var recompileJob: Job? = null

    // Per-list compiled cache so toggling lists/categories merges in memory with zero disk reparse
    private val parsedListCache = ConcurrentHashMap<String, UhAdblockRuleCompiler.CompiledFilterSet>()

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

        val prefs = UhShieldStorage.prefs(app)
        val level = ShieldLevel.fromCode(SettingsPrefs.getInt(app, SettingsPrefs.K_PG_SHIELD_LEVEL, ShieldLevel.AGGRESSIVE.code))
        val paused = prefs.getStringSet(UhShieldStorage.KEY_PAUSED_HOSTS, emptySet())?.toSet() ?: emptySet()
        val jsAlways = prefs.getStringSet(UhShieldStorage.KEY_JS_ALLOW_HOSTS, emptySet())?.toSet() ?: emptySet()
        val httpExc = prefs.getStringSet(UhShieldStorage.KEY_HTTP_EXCEPTION_HOSTS, emptySet())?.toSet() ?: emptySet()
        val ads = prefs.getLong(UhShieldStorage.KEY_STATS_ADS, 0L)
        val trackers = prefs.getLong(UhShieldStorage.KEY_STATS_TRACKERS, 0L)
        val savedMs = prefs.getLong(UhShieldStorage.KEY_STATS_SAVED_MS, 0L)
        val lastSync = prefs.getLong(UhShieldStorage.KEY_LAST_SYNC_MS, 0L)
        val userRules = prefs.getString(UhShieldStorage.KEY_CUSTOM_RULES, "") ?: ""

        _jsAllowAlwaysHosts.value = jsAlways
        _httpExceptionHosts.value = httpExc
        lifetimeAds.set(ads)
        lifetimeTrackers.set(trackers)
        lifetimeSavedMs.set(savedMs)

        val enabledIds = UhShieldStorage.getEnabledListIds(app)
        compiledSet = if (enabledIds.isNotEmpty()) {
            UhShieldBootstrapRules.buildBootstrapFilterSet()
        } else {
            UhAdblockRuleCompiler.CompiledFilterSet.EMPTY
        }

        val statuses = UhShieldStorage.loadListStatuses(app)
        val cats = buildCategoryStatuses(statuses)
        _state.value = State(
            level = level,
            pausedHosts = paused,
            lifetimeStats = ShieldStatsSnapshot(ads, trackers, savedMs),
            lists = statuses,
            categories = cats,
            customUserRules = userRules,
            totalCompiledRules = estimateActiveRules(statuses, userRules, compiledSet.totalRules),
            lastSyncMs = lastSync,
        )

        scope.launch {
            recompileFromDisk(app)
            val stale = (System.currentTimeMillis() - lastSync) > TimeUnit.HOURS.toMillis(24)
            if (stale && UhShieldStorage.getEnabledListIds(app).isNotEmpty()) {
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
        UhShieldStorage.prefs(ctx).edit().putStringSet(UhShieldStorage.KEY_PAUSED_HOSTS, next).apply()
        _state.update { it.copy(pausedHosts = next) }
        onCompiledRulesUpdated?.invoke()
        return paused
    }

    fun removePausedHost(host: String) {
        val clean = normalizeHost(host)
        val ctx = appContext ?: return
        val next = _state.value.pausedHosts - clean
        UhShieldStorage.prefs(ctx).edit().putStringSet(UhShieldStorage.KEY_PAUSED_HOSTS, next).apply()
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
        UhShieldStorage.prefs(ctx).edit().putStringSet(UhShieldStorage.KEY_JS_ALLOW_HOSTS, next).apply()
        _jsAllowAlwaysHosts.value = next
        jsBlockedByHost.remove(host)
        _jsBlockedTick.value = System.nanoTime()
        onCompiledRulesUpdated?.invoke()
    }

    fun revokeJsAlways(host: String) {
        val clean = normalizeHost(host)
        val ctx = appContext ?: return
        val next = _jsAllowAlwaysHosts.value - clean
        UhShieldStorage.prefs(ctx).edit().putStringSet(UhShieldStorage.KEY_JS_ALLOW_HOSTS, next).apply()
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
        UhShieldStorage.prefs(ctx).edit().putStringSet(UhShieldStorage.KEY_HTTP_EXCEPTION_HOSTS, next).apply()
        _httpExceptionHosts.value = next
    }

    fun removeHttpException(host: String) {
        val clean = normalizeHost(host)
        val ctx = appContext ?: return
        val next = _httpExceptionHosts.value - clean
        UhShieldStorage.prefs(ctx).edit().putStringSet(UhShieldStorage.KEY_HTTP_EXCEPTION_HOSTS, next).apply()
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
            appContext?.let { ctx ->
                UhShieldStorage.prefs(ctx).edit()
                    .putLong(UhShieldStorage.KEY_STATS_ADS, newAds)
                    .putLong(UhShieldStorage.KEY_STATS_TRACKERS, newTrackers)
                    .putLong(UhShieldStorage.KEY_STATS_SAVED_MS, newSaved)
                    .apply()
            }
        }
    }

    fun resetLifetimeStats() {
        val ctx = appContext ?: return
        lifetimeAds.set(0L)
        lifetimeTrackers.set(0L)
        lifetimeSavedMs.set(0L)
        hostStatsMap.clear()
        lastGeckoEtpByTab.clear()
        UhShieldStorage.prefs(ctx).edit()
            .putLong(UhShieldStorage.KEY_STATS_ADS, 0L)
            .putLong(UhShieldStorage.KEY_STATS_TRACKERS, 0L)
            .putLong(UhShieldStorage.KEY_STATS_SAVED_MS, 0L)
            .apply()
        _state.update { it.copy(lifetimeStats = ShieldStatsSnapshot()) }
        _hostStatsTick.value = System.nanoTime()
    }

    // ─── Filter List Management ─────────────────────────────────────────────

    fun setCategoryEnabled(categoryId: String, enabled: Boolean) {
        val ctx = appContext ?: return
        val cat = FILTER_CATEGORIES.firstOrNull { it.id == categoryId } ?: return
        val currentEnabled = UhShieldStorage.getEnabledListIds(ctx).toMutableSet()
        if (enabled) currentEnabled.addAll(cat.listIds) else currentEnabled.removeAll(cat.listIds.toSet())
        UhShieldStorage.prefs(ctx).edit().putStringSet(UhShieldStorage.KEY_ENABLED_LISTS, currentEnabled).apply()

        _state.update { st ->
            val updatedLists = st.lists.map {
                if (it.def.id in cat.listIds) it.copy(enabled = enabled) else it
            }
            val instantTotal = estimateActiveRules(updatedLists, st.customUserRules)
            st.copy(
                lists = updatedLists,
                categories = buildCategoryStatuses(updatedLists),
                totalCompiledRules = instantTotal,
            )
        }

        scheduleDebouncedRecompile(ctx, listsToEnsureDownloaded = if (enabled) cat.listIds else emptyList())
    }

    fun setListEnabled(listId: String, enabled: Boolean) {
        val ctx = appContext ?: return
        val currentEnabled = UhShieldStorage.getEnabledListIds(ctx).toMutableSet()
        if (enabled) currentEnabled.add(listId) else currentEnabled.remove(listId)
        UhShieldStorage.prefs(ctx).edit().putStringSet(UhShieldStorage.KEY_ENABLED_LISTS, currentEnabled).apply()

        _state.update { st ->
            val updatedLists = st.lists.map {
                if (it.def.id == listId) it.copy(enabled = enabled) else it
            }
            val instantTotal = estimateActiveRules(updatedLists, st.customUserRules)
            st.copy(
                lists = updatedLists,
                categories = buildCategoryStatuses(updatedLists),
                totalCompiledRules = instantTotal,
            )
        }

        scheduleDebouncedRecompile(ctx, listsToEnsureDownloaded = if (enabled) listOf(listId) else emptyList())
    }

    private fun scheduleDebouncedRecompile(ctx: Context, listsToEnsureDownloaded: List<String> = emptyList()) {
        recompileJob?.cancel()
        recompileJob = scope.launch {
            delay(160L)
            if (listsToEnsureDownloaded.isNotEmpty()) {
                val missing = listsToEnsureDownloaded.filter { id ->
                    val file = UhShieldStorage.listCacheFile(ctx, id)
                    !file.exists() || file.length() < 64L
                }
                if (missing.isNotEmpty()) {
                    _state.update { it.copy(isSyncing = true) }
                    try {
                        for (id in missing) {
                            syncSingleList(ctx, id)
                        }
                    } finally {
                        _state.update { it.copy(isSyncing = false) }
                    }
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
        val customs = UhShieldStorage.loadCustomListDefs(ctx).toMutableList()
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
        UhShieldStorage.saveCustomListDefs(ctx, customs)

        val enabledSet = UhShieldStorage.getEnabledListIds(ctx).toMutableSet()
        enabledSet.add(id)
        UhShieldStorage.prefs(ctx).edit().putStringSet(UhShieldStorage.KEY_ENABLED_LISTS, enabledSet).apply()

        val statuses = UhShieldStorage.loadListStatuses(ctx)
        _state.update {
            it.copy(
                lists = statuses,
                categories = buildCategoryStatuses(statuses),
                totalCompiledRules = estimateActiveRules(statuses, it.customUserRules),
            )
        }
        scheduleDebouncedRecompile(ctx, listsToEnsureDownloaded = listOf(id))
        return true
    }

    fun removeCustomFilterList(listId: String) {
        val ctx = appContext ?: return
        val customs = UhShieldStorage.loadCustomListDefs(ctx).filterNot { it.id == listId }
        UhShieldStorage.saveCustomListDefs(ctx, customs)
        parsedListCache.remove(listId)
        UhShieldStorage.listCacheFile(ctx, listId).delete()
        val enabledSet = UhShieldStorage.getEnabledListIds(ctx) - listId
        UhShieldStorage.prefs(ctx).edit().putStringSet(UhShieldStorage.KEY_ENABLED_LISTS, enabledSet).apply()
        val statuses = UhShieldStorage.loadListStatuses(ctx)
        _state.update {
            it.copy(
                lists = statuses,
                categories = buildCategoryStatuses(statuses),
                totalCompiledRules = estimateActiveRules(statuses, it.customUserRules),
            )
        }
        scheduleDebouncedRecompile(ctx)
    }

    fun saveCustomUserRules(rulesText: String) {
        val ctx = appContext ?: return
        UhShieldStorage.prefs(ctx).edit().putString(UhShieldStorage.KEY_CUSTOM_RULES, rulesText).apply()
        _state.update {
            it.copy(
                customUserRules = rulesText,
                totalCompiledRules = estimateActiveRules(it.lists, rulesText),
            )
        }
        scheduleDebouncedRecompile(ctx)
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
            val enabledIds = UhShieldStorage.getEnabledListIds(ctx)
            val allDefs = BUILT_IN_LISTS + UhShieldStorage.loadCustomListDefs(ctx)
            val targets = allDefs.filter { it.id in enabledIds }
            for (def in targets) {
                val err = syncSingleList(ctx, def.id)
                if (err != null && anyError == null) anyError = err
            }
            val now = System.currentTimeMillis()
            UhShieldStorage.prefs(ctx).edit().putLong(UhShieldStorage.KEY_LAST_SYNC_MS, now).apply()
            recompileFromDisk(ctx)
            val statuses = UhShieldStorage.loadListStatuses(ctx)
            _state.update {
                it.copy(
                    isSyncing = false,
                    lastSyncMs = now,
                    lastSyncError = anyError,
                    lists = statuses,
                    categories = buildCategoryStatuses(statuses),
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
        val allDefs = BUILT_IN_LISTS + UhShieldStorage.loadCustomListDefs(ctx)
        val def = allDefs.firstOrNull { it.id == listId } ?: return null
        return try {
            val req = Request.Builder()
                .url(def.url)
                .header("User-Agent", UhHttpClient.DEFAULT_USER_AGENT)
                .build()
            UhHttpClient.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val msg = "HTTP ${resp.code}"
                    UhShieldStorage.saveListMeta(ctx, listId, 0, System.currentTimeMillis(), msg)
                    return msg
                }
                val body = resp.body?.string().orEmpty()
                if (body.length < 32) {
                    UhShieldStorage.saveListMeta(ctx, listId, 0, System.currentTimeMillis(), "Empty response")
                    return "Empty response"
                }
                val out = UhShieldStorage.listCacheFile(ctx, listId)
                out.parentFile?.mkdirs()
                out.writeText(body)
                parsedListCache.remove(listId)
                val count = UhShieldStorage.countEffectiveRules(body)
                UhShieldStorage.saveListMeta(ctx, listId, count, System.currentTimeMillis(), null)
                null
            }
        } catch (e: Exception) {
            val msg = e.message?.take(60) ?: "Network error"
            UhShieldStorage.saveListMeta(ctx, listId, 0, System.currentTimeMillis(), msg)
            msg
        }
    }

    private fun buildCategoryStatuses(statuses: List<FilterListStatus>): List<FilterCategoryStatus> {
        val byId = statuses.associateBy { it.def.id }
        return FILTER_CATEGORIES.map { cat ->
            val members = cat.listIds.mapNotNull { byId[it] }
            val enabled = members.any { it.enabled }
            val rules = members.sumOf { st ->
                val cached = parsedListCache[st.def.id]?.totalRules ?: 0
                if (cached > 0) cached else st.ruleCount
            }
            FilterCategoryStatus(def = cat, enabled = enabled, ruleCount = rules)
        }
    }

    private fun estimateActiveRules(
        statuses: List<FilterListStatus>,
        customRules: String,
        fallbackBootstrapCount: Int = 0,
    ): Int {
        val enabled = statuses.filter { it.enabled }
        val customCount = if (customRules.isBlank()) 0 else customRules.lineSequence().count {
            val t = it.trim()
            t.length >= 3 && !t.startsWith("!") && !(t.startsWith("#") && !t.startsWith("##") && !t.startsWith("#@#") && !t.startsWith("#?#"))
        }
        if (enabled.isEmpty() && customCount == 0) return 0
        var sum = 0
        for (st in enabled) {
            val cached = parsedListCache[st.def.id]?.totalRules ?: 0
            sum += if (cached > 0) cached else st.ruleCount
        }
        if (sum == 0 && enabled.isNotEmpty()) {
            sum = if (fallbackBootstrapCount > 0) fallbackBootstrapCount else UhShieldBootstrapRules.fallbackRuleCount
        }
        return sum + customCount
    }

    private fun recompileFromDisk(ctx: Context) {
        val enabledIds = UhShieldStorage.getEnabledListIds(ctx)
        val customRules = UhShieldStorage.prefs(ctx).getString(UhShieldStorage.KEY_CUSTOM_RULES, "") ?: ""

        // If user explicitly disabled EVERY filter list and has no custom rules -> 0 active rules!
        if (enabledIds.isEmpty() && customRules.isBlank()) {
            compiledSet = UhAdblockRuleCompiler.CompiledFilterSet.EMPTY
            val statuses = UhShieldStorage.loadListStatuses(ctx)
            _state.update {
                it.copy(
                    totalCompiledRules = 0,
                    lists = statuses,
                    categories = buildCategoryStatuses(statuses),
                )
            }
            onCompiledRulesUpdated?.invoke()
            return
        }

        val allDefs = BUILT_IN_LISTS + UhShieldStorage.loadCustomListDefs(ctx)
        val setsToMerge = ArrayList<UhAdblockRuleCompiler.CompiledFilterSet>(enabledIds.size + 1)
        var parsedAnyDiskList = false

        for (def in allDefs) {
            if (def.id !in enabledIds) continue
            val cached = parsedListCache[def.id]
            if (cached != null) {
                setsToMerge.add(cached)
                parsedAnyDiskList = true
                continue
            }
            val file = UhShieldStorage.listCacheFile(ctx, def.id)
            if (!file.exists() || file.length() < 32L) continue
            try {
                val singleBuilder = UhAdblockRuleCompiler.Builder()
                file.bufferedReader().useLines { lines ->
                    UhAdblockRuleCompiler.parseLines(
                        lines = lines,
                        isTrackerList = def.isTrackerList,
                        builder = singleBuilder,
                    )
                }
                val singleCompiled = singleBuilder.build()
                parsedListCache[def.id] = singleCompiled
                setsToMerge.add(singleCompiled)
                parsedAnyDiskList = true
            } catch (e: Exception) {
                UHLog.w("shield", "Failed parsing cached list ${def.id}: ${e.message}")
            }
        }

        // Only apply offline Day-0 bootstrap when lists ARE enabled but none have downloaded to disk yet
        if (enabledIds.isNotEmpty() && !parsedAnyDiskList) {
            setsToMerge.add(UhShieldBootstrapRules.buildBootstrapFilterSet())
        }

        if (customRules.isNotBlank()) {
            val customBuilder = UhAdblockRuleCompiler.Builder()
            UhAdblockRuleCompiler.parseLines(
                lines = customRules.lineSequence(),
                isTrackerList = false,
                builder = customBuilder,
            )
            setsToMerge.add(customBuilder.build())
        }

        val compiled = UhAdblockRuleCompiler.mergeSets(setsToMerge)
        compiledSet = compiled

        val statuses = UhShieldStorage.loadListStatuses(ctx)
        _state.update {
            it.copy(
                totalCompiledRules = compiled.totalRules,
                lists = statuses,
                categories = buildCategoryStatuses(statuses),
            )
        }
        onCompiledRulesUpdated?.invoke()
    }

    // ─── Request Classification & WebExtension Payload Builders ─────────────

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
        if (UhShieldUrlCleaner.matchesDomainSet(reqHost, set.exceptionDomains)) return 0
        if (isThirdParty && UhShieldUrlCleaner.matchesDomainSet(reqHost, set.thirdPartyExceptionDomains)) return 0

        if (st.level == ShieldLevel.STANDARD && !isThirdParty) return 0

        if (UhShieldUrlCleaner.matchesDomainSet(reqHost, set.trackerDomains)) return 2
        if (UhShieldUrlCleaner.matchesDomainSet(reqHost, set.adDomains)) return 1
        if (isThirdParty) {
            if (UhShieldUrlCleaner.matchesDomainSet(reqHost, set.thirdPartyTrackerDomains)) return 2
            if (UhShieldUrlCleaner.matchesDomainSet(reqHost, set.thirdPartyAdDomains)) return 1
        }
        return 0
    }

    fun buildExtensionRuntimePayload(ctx: Context): JSONObject =
        UhShieldPayloadBuilder.buildExtensionRuntimePayload(
            ctx = ctx,
            state = _state.value,
            compiledSet = compiledSet,
            jsAllowOnceHosts = jsAllowOnceHosts,
            jsAllowAlwaysHosts = _jsAllowAlwaysHosts.value,
        )

    fun buildChunkedRuntimeMessages(ctx: Context): List<JSONObject> =
        UhShieldPayloadBuilder.buildChunkedRuntimeMessages(
            ctx = ctx,
            state = _state.value,
            compiledSet = compiledSet,
            jsAllowOnceHosts = jsAllowOnceHosts,
            jsAllowAlwaysHosts = _jsAllowAlwaysHosts.value,
        )

    fun getCosmeticSelectorsForHost(pageUrl: String): List<String> =
        UhShieldPayloadBuilder.getCosmeticSelectorsForHost(
            pageUrl = pageUrl,
            state = _state.value,
            compiledSet = compiledSet,
        )

    fun buildPageProtectPayload(ctx: Context, pageUrl: String): JSONObject =
        UhShieldPayloadBuilder.buildPageProtectPayload(
            ctx = ctx,
            pageUrl = pageUrl,
            state = _state.value,
            compiledSet = compiledSet,
        )

    // ─── URL Cleaning & Host Utilities (delegated to UhShieldUrlCleaner) ───

    fun cleanTrackingUrl(rawUrl: String, stripTrackers: Boolean = true, deAmp: Boolean = true): String =
        UhShieldUrlCleaner.cleanTrackingUrl(rawUrl, stripTrackers, deAmp)

    fun normalizeHost(urlOrHost: String): String =
        UhShieldUrlCleaner.normalizeHost(urlOrHost)
}
