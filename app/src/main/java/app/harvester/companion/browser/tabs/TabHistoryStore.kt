package app.harvester.companion.browser.tabs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ClosedTabRecord(
    val id: String = UUID.randomUUID().toString().take(10),
    val url: String,
    val title: String,
    val isPrivate: Boolean = false,
    val closedAt: Long = System.currentTimeMillis(),
    val groupSnapshot: TabGroup? = null
)

data class UndoTabBatch(
    val batchId: String = UUID.randomUUID().toString().take(10),
    val label: String,
    val tabs: List<ClosedTabRecord>,
    val wasPrivate: Boolean
)

/**
 * Manages:
 * 1) Group-aware Undo stack (single tab, multi-select, duplicates, inactive, or full group closures).
 * 2) Persistent Recently Closed Tabs list (normal tabs ONLY; private tabs are never written to disk).
 * 3) Last-accessed timestamp tracking per normal tab to power the Inactive Tabs section.
 */
object TabHistoryStore {
    private const val PREFS_NAME = "uh_tab_history"
    private const val KEY_RECENTLY_CLOSED = "recently_closed_json"
    private const val KEY_LAST_ACCESSED = "last_accessed_json"
    private const val MAX_RECENTLY_CLOSED = 35

    @Volatile
    private var appContext: Context? = null
    private val lock = Any()

    private val _recentlyClosedFlow = MutableStateFlow<List<ClosedTabRecord>>(emptyList())
    val recentlyClosedFlow: StateFlow<List<ClosedTabRecord>> = _recentlyClosedFlow.asStateFlow()

    private val _activeUndoFlow = MutableStateFlow<UndoTabBatch?>(null)
    val activeUndoFlow: StateFlow<UndoTabBatch?> = _activeUndoFlow.asStateFlow()

    private val _lastAccessedFlow = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastAccessedFlow: StateFlow<Map<String, Long>> = _lastAccessedFlow.asStateFlow()

    fun init(ctx: Context) {
        synchronized(lock) {
            if (appContext == null) {
                appContext = ctx.applicationContext
                _recentlyClosedFlow.value = readRecentlyClosedFromDisk(ctx.applicationContext)
                _lastAccessedFlow.value = readLastAccessedFromDisk(ctx.applicationContext)
            }
        }
    }

    private fun readRecentlyClosedFromDisk(ctx: Context): List<ClosedTabRecord> {
        val raw = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RECENTLY_CLOSED, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<ClosedTabRecord>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("url")
                if (url.isBlank() || url == "about:blank") continue
                list.add(
                    ClosedTabRecord(
                        id = obj.optString("id").ifBlank { UUID.randomUUID().toString().take(10) },
                        url = url,
                        title = obj.optString("title"),
                        isPrivate = false,
                        closedAt = obj.optLong("closedAt", System.currentTimeMillis())
                    )
                )
            }
            list.take(MAX_RECENTLY_CLOSED)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistRecentlyClosedLocked(list: List<ClosedTabRecord>) {
        val trimmed = list.filter { !it.isPrivate && it.url.isNotBlank() && it.url != "about:blank" }
            .take(MAX_RECENTLY_CLOSED)
        _recentlyClosedFlow.value = trimmed
        val ctx = appContext ?: return
        val arr = JSONArray()
        for (item in trimmed) {
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("url", item.url)
                    put("title", item.title)
                    put("closedAt", item.closedAt)
                }
            )
        }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECENTLY_CLOSED, arr.toString())
            .apply()
    }

    private fun readLastAccessedFromDisk(ctx: Context): Map<String, Long> {
        val raw = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_ACCESSED, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val map = mutableMapOf<String, Long>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val ts = obj.optLong(k, 0L)
                if (k.isNotBlank() && ts > 0L) map[k] = ts
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun persistLastAccessedLocked(map: Map<String, Long>) {
        _lastAccessedFlow.value = map
        val ctx = appContext ?: return
        val obj = JSONObject()
        for ((k, v) in map) obj.put(k, v)
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_ACCESSED, obj.toString())
            .apply()
    }

    fun recordClosedBatch(
        label: String,
        records: List<ClosedTabRecord>,
        wasPrivate: Boolean,
        allowUndo: Boolean = true
    ) {
        if (records.isEmpty()) return
        synchronized(lock) {
            val saveRecentlyClosed = appContext?.let { ctx ->
                app.harvester.companion.SettingsPrefs.getBool(
                    ctx,
                    app.harvester.companion.SettingsPrefs.K_TAB_RECENTLY_CLOSED_ENABLED,
                    true
                )
            } ?: true
            if (!wasPrivate && saveRecentlyClosed) {
                val restorable = records.filter { it.url.isNotBlank() && it.url != "about:blank" }
                if (restorable.isNotEmpty()) {
                    val existing = _recentlyClosedFlow.value.filterNot { prev ->
                        restorable.any { it.url == prev.url }
                    }
                    persistRecentlyClosedLocked(restorable + existing)
                }
            }
            if (allowUndo) {
                _activeUndoFlow.value = UndoTabBatch(
                    label = label,
                    tabs = records,
                    wasPrivate = wasPrivate
                )
            }
        }
    }

    fun consumeUndo(): UndoTabBatch? {
        synchronized(lock) {
            val current = _activeUndoFlow.value ?: return null
            _activeUndoFlow.value = null
            if (!current.wasPrivate) {
                val urlsToRemove = current.tabs.map { it.url }.toSet()
                val remaining = _recentlyClosedFlow.value.filterNot { it.url in urlsToRemove }
                if (remaining.size != _recentlyClosedFlow.value.size) {
                    persistRecentlyClosedLocked(remaining)
                }
            }
            return current
        }
    }

    fun dismissUndo(batchId: String? = null) {
        synchronized(lock) {
            if (batchId == null || _activeUndoFlow.value?.batchId == batchId) {
                _activeUndoFlow.value = null
            }
        }
    }

    fun clearPrivateUndo() {
        synchronized(lock) {
            if (_activeUndoFlow.value?.wasPrivate == true) {
                _activeUndoFlow.value = null
            }
        }
    }

    fun removeRecentlyClosed(recordId: String) {
        synchronized(lock) {
            val next = _recentlyClosedFlow.value.filterNot { it.id == recordId }
            persistRecentlyClosedLocked(next)
        }
    }

    fun clearRecentlyClosed() {
        synchronized(lock) {
            persistRecentlyClosedLocked(emptyList())
        }
    }

    fun recordTabAccess(tabId: String, nowMs: Long = System.currentTimeMillis()) {
        if (tabId.isBlank()) return
        synchronized(lock) {
            val current = _lastAccessedFlow.value
            val prev = current[tabId] ?: 0L
            // Avoid disk write spam if accessed within last 60 seconds
            if (nowMs - prev < 60_000L && prev > 0L) return
            val next = current.toMutableMap()
            next[tabId] = nowMs
            persistLastAccessedLocked(next)
        }
    }

    fun ensureTracked(tabIds: Collection<String>, nowMs: Long = System.currentTimeMillis()) {
        if (tabIds.isEmpty()) return
        synchronized(lock) {
            val current = _lastAccessedFlow.value
            var changed = false
            val next = current.toMutableMap()
            for (id in tabIds) {
                if (id.isNotBlank() && !next.containsKey(id)) {
                    next[id] = nowMs
                    changed = true
                }
            }
            if (changed) {
                persistLastAccessedLocked(next)
            }
        }
    }

    fun pruneAccessToValid(validNormalTabIds: Set<String>) {
        synchronized(lock) {
            val current = _lastAccessedFlow.value
            val filtered = current.filterKeys { it in validNormalTabIds }
            if (filtered.size != current.size) {
                persistLastAccessedLocked(filtered)
            }
        }
    }
}
