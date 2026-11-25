package app.harvester.companion.browser.tabs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class TabGroup(
    val id: String,
    val name: String,
    val colorArgb: Long,
    val isPrivate: Boolean = false,
    val isCollapsed: Boolean = false,
    val tabIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

object TabGroupStore {
    private const val PREFS_NAME = "uh_tab_groups"
    private const val KEY_GROUPS = "groups_json"
    private const val KEY_NORMAL_ORDER = "normal_tab_order_json"

    val GROUP_COLORS: List<Long> = listOf(
        0xFF457B9D, // Ocean Steel
        0xFF2A9D8F, // Teal
        0xFF8B5CF6, // Violet
        0xFFE76F51, // Coral
        0xFFE9C46A, // Amber
        0xFFEC4899, // Rose
        0xFF22C55E, // Emerald
        0xFF38BDF8  // Sky
    )

    @Volatile
    private var appContext: Context? = null
    private val lock = Any()

    // Normal groups persist to disk; Private groups live strictly in RAM
    private var normalGroups: List<TabGroup> = emptyList()
    private var privateGroups: List<TabGroup> = emptyList()

    private val _groupsFlow = MutableStateFlow<List<TabGroup>>(emptyList())
    val groupsFlow: StateFlow<List<TabGroup>> = _groupsFlow.asStateFlow()

    private val _normalOrderFlow = MutableStateFlow<List<String>>(emptyList())
    val normalOrderFlow: StateFlow<List<String>> = _normalOrderFlow.asStateFlow()

    private val _privateOrderFlow = MutableStateFlow<List<String>>(emptyList())
    val privateOrderFlow: StateFlow<List<String>> = _privateOrderFlow.asStateFlow()

    fun init(ctx: Context) {
        synchronized(lock) {
            if (appContext == null) {
                appContext = ctx.applicationContext
                normalGroups = readGroupsFromDisk(ctx.applicationContext)
                _normalOrderFlow.value = readOrderFromDisk(ctx.applicationContext)
                emitCombinedGroupsLocked()
            }
        }
    }

    private fun emitCombinedGroupsLocked() {
        _groupsFlow.value = normalGroups + privateGroups
    }

    private fun readGroupsFromDisk(ctx: Context): List<TabGroup> {
        val raw = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_GROUPS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<TabGroup>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                val name = obj.optString("name").ifBlank { "Group" }
                val color = obj.optLong("colorArgb", GROUP_COLORS[0])
                val collapsed = obj.optBoolean("isCollapsed", false)
                val createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                val idsJson = obj.optJSONArray("tabIds")
                val tabIds = mutableListOf<String>()
                if (idsJson != null) {
                    for (j in 0 until idsJson.length()) {
                        val tid = idsJson.optString(j)
                        if (tid.isNotBlank()) tabIds.add(tid)
                    }
                }
                if (id.isNotBlank() && tabIds.isNotEmpty()) {
                    list.add(
                        TabGroup(
                            id = id,
                            name = name,
                            colorArgb = color,
                            isPrivate = false,
                            isCollapsed = collapsed,
                            tabIds = tabIds,
                            createdAt = createdAt
                        )
                    )
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readOrderFromDisk(ctx: Context): List<String> {
        val raw = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_NORMAL_ORDER, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val id = arr.optString(i)
                if (id.isNotBlank()) list.add(id)
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistNormalGroupsLocked() {
        normalGroups = normalGroups.filter { it.tabIds.isNotEmpty() && !it.isPrivate }
        emitCombinedGroupsLocked()
        val ctx = appContext ?: return
        val arr = JSONArray()
        for (g in normalGroups) {
            val obj = JSONObject().apply {
                put("id", g.id)
                put("name", g.name)
                put("colorArgb", g.colorArgb)
                put("isCollapsed", g.isCollapsed)
                put("createdAt", g.createdAt)
                put("tabIds", JSONArray(g.tabIds))
            }
            arr.put(obj)
        }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GROUPS, arr.toString())
            .apply()
    }

    private fun persistNormalOrderLocked(order: List<String>) {
        _normalOrderFlow.value = order
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NORMAL_ORDER, JSONArray(order).toString())
            .apply()
    }

    private fun commitPrivateGroupsLocked() {
        privateGroups = privateGroups.filter { it.tabIds.isNotEmpty() && it.isPrivate }
        emitCombinedGroupsLocked()
    }

    fun groupForTab(tabId: String): TabGroup? {
        return _groupsFlow.value.firstOrNull { tabId in it.tabIds }
    }

    fun groupById(groupId: String): TabGroup? {
        return _groupsFlow.value.firstOrNull { it.id == groupId }
    }

    fun createGroup(
        name: String,
        colorArgb: Long,
        tabIds: List<String>,
        isPrivate: Boolean = false
    ): TabGroup {
        val uniqueIds = tabIds.distinct().filter { it.isNotBlank() }
        val group = TabGroup(
            id = (if (isPrivate) "pgrp_" else "grp_") + UUID.randomUUID().toString().take(8),
            name = name.trim().ifBlank { "Tab Group" },
            colorArgb = colorArgb,
            isPrivate = isPrivate,
            isCollapsed = false,
            tabIds = uniqueIds
        )
        synchronized(lock) {
            if (isPrivate) {
                privateGroups = privateGroups.map { existing ->
                    existing.copy(tabIds = existing.tabIds.filterNot { it in uniqueIds })
                }.filter { it.tabIds.isNotEmpty() } + group
                commitPrivateGroupsLocked()
            } else {
                normalGroups = normalGroups.map { existing ->
                    existing.copy(tabIds = existing.tabIds.filterNot { it in uniqueIds })
                }.filter { it.tabIds.isNotEmpty() } + group
                persistNormalGroupsLocked()
            }
        }
        return group
    }

    fun restoreGroup(group: TabGroup, newTabIds: List<String>) {
        val cleanIds = newTabIds.distinct().filter { it.isNotBlank() }
        if (cleanIds.isEmpty()) return
        synchronized(lock) {
            val existing = if (group.isPrivate) {
                privateGroups.firstOrNull { it.id == group.id }
            } else {
                normalGroups.firstOrNull { it.id == group.id }
            }
            if (existing != null) {
                val merged = (existing.tabIds + cleanIds).distinct()
                if (group.isPrivate) {
                    privateGroups = privateGroups.map { if (it.id == group.id) it.copy(tabIds = merged) else it }
                    commitPrivateGroupsLocked()
                } else {
                    normalGroups = normalGroups.map { if (it.id == group.id) it.copy(tabIds = merged) else it }
                    persistNormalGroupsLocked()
                }
            } else {
                val restored = group.copy(tabIds = cleanIds)
                if (group.isPrivate) {
                    privateGroups = privateGroups + restored
                    commitPrivateGroupsLocked()
                } else {
                    normalGroups = normalGroups + restored
                    persistNormalGroupsLocked()
                }
            }
        }
    }

    fun addTabToGroup(groupId: String, tabId: String) {
        if (tabId.isBlank()) return
        synchronized(lock) {
            val isPrivTarget = privateGroups.any { it.id == groupId }
            if (isPrivTarget) {
                privateGroups = privateGroups.map { g ->
                    when {
                        g.id == groupId -> g.copy(tabIds = if (tabId in g.tabIds) g.tabIds else g.tabIds + tabId)
                        tabId in g.tabIds -> g.copy(tabIds = g.tabIds.filterNot { it == tabId })
                        else -> g
                    }
                }
                commitPrivateGroupsLocked()
            } else if (normalGroups.any { it.id == groupId }) {
                normalGroups = normalGroups.map { g ->
                    when {
                        g.id == groupId -> g.copy(tabIds = if (tabId in g.tabIds) g.tabIds else g.tabIds + tabId)
                        tabId in g.tabIds -> g.copy(tabIds = g.tabIds.filterNot { it == tabId })
                        else -> g
                    }
                }
                persistNormalGroupsLocked()
            }
        }
    }

    fun addTabsToGroup(groupId: String, tabIds: List<String>) {
        tabIds.forEach { addTabToGroup(groupId, it) }
    }

    fun removeTabFromGroups(tabId: String) {
        synchronized(lock) {
            if (normalGroups.any { tabId in it.tabIds }) {
                normalGroups = normalGroups.map { g ->
                    if (tabId in g.tabIds) g.copy(tabIds = g.tabIds.filterNot { it == tabId }) else g
                }
                persistNormalGroupsLocked()
            }
            if (privateGroups.any { tabId in it.tabIds }) {
                privateGroups = privateGroups.map { g ->
                    if (tabId in g.tabIds) g.copy(tabIds = g.tabIds.filterNot { it == tabId }) else g
                }
                commitPrivateGroupsLocked()
            }
        }
    }

    fun renameAndRecolor(groupId: String, newName: String, newColorArgb: Long) {
        synchronized(lock) {
            if (privateGroups.any { it.id == groupId }) {
                privateGroups = privateGroups.map { g ->
                    if (g.id == groupId) g.copy(name = newName.trim().ifBlank { g.name }, colorArgb = newColorArgb) else g
                }
                commitPrivateGroupsLocked()
            } else {
                normalGroups = normalGroups.map { g ->
                    if (g.id == groupId) g.copy(name = newName.trim().ifBlank { g.name }, colorArgb = newColorArgb) else g
                }
                persistNormalGroupsLocked()
            }
        }
    }

    fun toggleCollapsed(groupId: String) {
        synchronized(lock) {
            if (privateGroups.any { it.id == groupId }) {
                privateGroups = privateGroups.map { g ->
                    if (g.id == groupId) g.copy(isCollapsed = !g.isCollapsed) else g
                }
                commitPrivateGroupsLocked()
            } else {
                normalGroups = normalGroups.map { g ->
                    if (g.id == groupId) g.copy(isCollapsed = !g.isCollapsed) else g
                }
                persistNormalGroupsLocked()
            }
        }
    }

    fun setCollapsed(groupId: String, collapsed: Boolean) {
        synchronized(lock) {
            if (privateGroups.any { it.id == groupId }) {
                privateGroups = privateGroups.map { g ->
                    if (g.id == groupId) g.copy(isCollapsed = collapsed) else g
                }
                commitPrivateGroupsLocked()
            } else {
                normalGroups = normalGroups.map { g ->
                    if (g.id == groupId) g.copy(isCollapsed = collapsed) else g
                }
                persistNormalGroupsLocked()
            }
        }
    }

    fun ungroup(groupId: String) {
        synchronized(lock) {
            if (privateGroups.any { it.id == groupId }) {
                privateGroups = privateGroups.filterNot { it.id == groupId }
                commitPrivateGroupsLocked()
            } else {
                normalGroups = normalGroups.filterNot { it.id == groupId }
                persistNormalGroupsLocked()
            }
        }
    }

    fun clearPrivateGroups() {
        synchronized(lock) {
            privateGroups = emptyList()
            _privateOrderFlow.value = emptyList()
            emitCombinedGroupsLocked()
        }
    }

    fun syncOrderWithTabs(tabIds: List<String>, isPrivate: Boolean) {
        synchronized(lock) {
            val validSet = tabIds.toSet()
            val current = if (isPrivate) _privateOrderFlow.value else _normalOrderFlow.value
            val next = current.filter { it in validSet }.toMutableList()
            for (id in tabIds) {
                if (id !in next) next.add(id)
            }
            if (next != current) {
                if (isPrivate) {
                    _privateOrderFlow.value = next
                } else {
                    persistNormalOrderLocked(next)
                }
            }
        }
    }

    fun moveTabOrder(fromId: String, toId: String, isPrivate: Boolean) {
        if (fromId == toId) return
        synchronized(lock) {
            val current = (if (isPrivate) _privateOrderFlow.value else _normalOrderFlow.value).toMutableList()
            val idxFrom = current.indexOf(fromId)
            val idxTo = current.indexOf(toId)
            if (idxFrom in current.indices && idxTo in current.indices && idxFrom != idxTo) {
                val moved = current.removeAt(idxFrom)
                current.add(idxTo, moved)
                if (isPrivate) {
                    _privateOrderFlow.value = current
                } else {
                    persistNormalOrderLocked(current)
                }
            }
        }
    }

    fun setExactOrder(orderedIds: List<String>, isPrivate: Boolean) {
        val clean = orderedIds.distinct().filter { it.isNotBlank() }
        synchronized(lock) {
            if (isPrivate) {
                _privateOrderFlow.value = clean
            } else {
                persistNormalOrderLocked(clean)
            }
        }
    }

    fun insertTabAfter(newTabId: String, anchorTabId: String?, isPrivate: Boolean) {
        if (newTabId.isBlank()) return
        synchronized(lock) {
            val current = (if (isPrivate) _privateOrderFlow.value else _normalOrderFlow.value)
                .filterNot { it == newTabId }
                .toMutableList()
            val anchorIdx = if (!anchorTabId.isNullOrBlank()) current.indexOf(anchorTabId) else -1
            if (anchorIdx >= 0) {
                current.add(anchorIdx + 1, newTabId)
            } else {
                current.add(newTabId)
            }
            if (isPrivate) {
                _privateOrderFlow.value = current
            } else {
                persistNormalOrderLocked(current)
            }
        }
    }

    fun cleanupOrphanedTabs(validNormalTabIds: Set<String>, validPrivateTabIds: Set<String>? = null) {
        synchronized(lock) {
            val updatedNormal = normalGroups.map { g ->
                g.copy(tabIds = g.tabIds.filter { it in validNormalTabIds })
            }.filter { it.tabIds.isNotEmpty() }
            if (updatedNormal != normalGroups) {
                normalGroups = updatedNormal
                persistNormalGroupsLocked()
            }
            if (validPrivateTabIds != null) {
                if (validPrivateTabIds.isEmpty()) {
                    if (privateGroups.isNotEmpty()) {
                        privateGroups = emptyList()
                        commitPrivateGroupsLocked()
                    }
                } else {
                    val updatedPriv = privateGroups.map { g ->
                        g.copy(tabIds = g.tabIds.filter { it in validPrivateTabIds })
                    }.filter { it.tabIds.isNotEmpty() }
                    if (updatedPriv != privateGroups) {
                        privateGroups = updatedPriv
                        commitPrivateGroupsLocked()
                    }
                }
            }
        }
    }
}
