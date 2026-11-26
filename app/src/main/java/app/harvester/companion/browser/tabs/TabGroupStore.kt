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
    private const val KEY_NORMAL_TRAY_KEYS = "normal_tray_keys_json"

    val GROUP_COLORS: List<Long> = listOf(
        0xFF3B82F6, // Cobalt
        0xFF14B8A6, // Teal
        0xFF8B5CF6, // Violet
        0xFFF97316, // Tangerine
        0xFFEAB308, // Amber
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

    // Unified natural tray order storing mixed "tab_<id>" and "grp_<id>" keys so groups stay in-place
    private val _normalTrayOrderFlow = MutableStateFlow<List<String>>(emptyList())
    val normalTrayOrderFlow: StateFlow<List<String>> = _normalTrayOrderFlow.asStateFlow()

    private val _privateTrayOrderFlow = MutableStateFlow<List<String>>(emptyList())
    val privateTrayOrderFlow: StateFlow<List<String>> = _privateTrayOrderFlow.asStateFlow()

    fun tabKey(tabId: String): String = "tab_$tabId"
    fun groupKey(groupId: String): String = "grp_$groupId"

    fun init(ctx: Context) {
        synchronized(lock) {
            if (appContext == null) {
                appContext = ctx.applicationContext
                normalGroups = readGroupsFromDisk(ctx.applicationContext)
                _normalOrderFlow.value = readStringListFromDisk(ctx.applicationContext, KEY_NORMAL_ORDER)
                val savedTray = readStringListFromDisk(ctx.applicationContext, KEY_NORMAL_TRAY_KEYS)
                _normalTrayOrderFlow.value = if (savedTray.isNotEmpty()) {
                    savedTray
                } else {
                    _normalOrderFlow.value.map { tabKey(it) } + normalGroups.map { groupKey(it.id) }
                }
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

    private fun readStringListFromDisk(ctx: Context, key: String): List<String> {
        val raw = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key, null) ?: return emptyList()
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

    private fun setTrayOrderLocked(keys: List<String>, isPrivate: Boolean) {
        val clean = keys.distinct().filter { it.isNotBlank() }
        if (isPrivate) {
            _privateTrayOrderFlow.value = clean
        } else {
            _normalTrayOrderFlow.value = clean
            val ctx = appContext ?: return
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_NORMAL_TRAY_KEYS, JSONArray(clean).toString())
                .apply()
        }
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
            // Place the new group right at the natural position of its anchor tab
            val trayFlow = if (isPrivate) _privateTrayOrderFlow else _normalTrayOrderFlow
            val currentKeys = trayFlow.value.toMutableList()
            val memberKeys = uniqueIds.map { tabKey(it) }.toSet()
            val anchorIdx = currentKeys.indexOfFirst { it in memberKeys }
            currentKeys.removeAll(memberKeys)
            val newGrpKey = groupKey(group.id)
            if (anchorIdx >= 0 && anchorIdx <= currentKeys.size) {
                currentKeys.add(anchorIdx, newGrpKey)
            } else {
                currentKeys.add(newGrpKey)
            }
            setTrayOrderLocked(currentKeys, isPrivate)

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
                val trayFlow = if (group.isPrivate) _privateTrayOrderFlow else _normalTrayOrderFlow
                val keys = trayFlow.value.filterNot { it in cleanIds.map(::tabKey) }.toMutableList()
                val gk = groupKey(restored.id)
                if (gk !in keys) keys.add(gk)
                setTrayOrderLocked(keys, group.isPrivate)
            }
        }
    }

    fun addTabToGroup(groupId: String, tabId: String) {
        if (tabId.isBlank()) return
        synchronized(lock) {
            val isPrivTarget = privateGroups.any { it.id == groupId }
            val isNormTarget = normalGroups.any { it.id == groupId }
            if (!isPrivTarget && !isNormTarget) return

            val trayFlow = if (isPrivTarget) _privateTrayOrderFlow else _normalTrayOrderFlow
            val updatedKeys = trayFlow.value.filterNot { it == tabKey(tabId) }
            setTrayOrderLocked(updatedKeys, isPrivTarget)

            if (isPrivTarget) {
                privateGroups = privateGroups.map { g ->
                    when {
                        g.id == groupId -> g.copy(tabIds = if (tabId in g.tabIds) g.tabIds else g.tabIds + tabId)
                        tabId in g.tabIds -> g.copy(tabIds = g.tabIds.filterNot { it == tabId })
                        else -> g
                    }
                }
                commitPrivateGroupsLocked()
            } else {
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
            val normGrp = normalGroups.firstOrNull { tabId in it.tabIds }
            if (normGrp != null) {
                val keys = _normalTrayOrderFlow.value.toMutableList()
                val gIdx = keys.indexOf(groupKey(normGrp.id))
                val tk = tabKey(tabId)
                if (tk !in keys) {
                    if (gIdx >= 0) keys.add((gIdx + 1).coerceAtMost(keys.size), tk) else keys.add(tk)
                    setTrayOrderLocked(keys, isPrivate = false)
                }
                normalGroups = normalGroups.map { g ->
                    if (tabId in g.tabIds) g.copy(tabIds = g.tabIds.filterNot { it == tabId }) else g
                }
                persistNormalGroupsLocked()
            }
            val privGrp = privateGroups.firstOrNull { tabId in it.tabIds }
            if (privGrp != null) {
                val keys = _privateTrayOrderFlow.value.toMutableList()
                val gIdx = keys.indexOf(groupKey(privGrp.id))
                val tk = tabKey(tabId)
                if (tk !in keys) {
                    if (gIdx >= 0) keys.add((gIdx + 1).coerceAtMost(keys.size), tk) else keys.add(tk)
                    setTrayOrderLocked(keys, isPrivate = true)
                }
                privateGroups = privateGroups.map { g ->
                    if (tabId in g.tabIds) g.copy(tabIds = g.tabIds.filterNot { it == tabId }) else g
                }
                commitPrivateGroupsLocked()
            }
        }
    }

    fun reorderGroupMembers(groupId: String, orderedMemberIds: List<String>) {
        synchronized(lock) {
            val clean = orderedMemberIds.distinct().filter { it.isNotBlank() }
            if (clean.isEmpty()) return
            if (privateGroups.any { it.id == groupId }) {
                privateGroups = privateGroups.map { g ->
                    if (g.id == groupId) g.copy(tabIds = clean) else g
                }
                commitPrivateGroupsLocked()
            } else if (normalGroups.any { it.id == groupId }) {
                normalGroups = normalGroups.map { g ->
                    if (g.id == groupId) g.copy(tabIds = clean) else g
                }
                persistNormalGroupsLocked()
            }
        }
    }

    fun reorderGroupsDirectory(orderedGroupIds: List<String>, isPrivate: Boolean) {
        synchronized(lock) {
            val idxMap = orderedGroupIds.withIndex().associate { (i, id) -> id to i }
            if (isPrivate) {
                privateGroups = privateGroups.sortedBy { idxMap[it.id] ?: Int.MAX_VALUE }
                commitPrivateGroupsLocked()
            } else {
                normalGroups = normalGroups.sortedBy { idxMap[it.id] ?: Int.MAX_VALUE }
                persistNormalGroupsLocked()
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
            val privTarget = privateGroups.firstOrNull { it.id == groupId }
            if (privTarget != null) {
                val keys = _privateTrayOrderFlow.value.toMutableList()
                val idx = keys.indexOf(groupKey(groupId))
                val expanded = privTarget.tabIds.map { tabKey(it) }
                if (idx >= 0) {
                    keys.removeAt(idx)
                    keys.addAll(idx, expanded)
                } else {
                    keys.addAll(expanded)
                }
                setTrayOrderLocked(keys, isPrivate = true)
                privateGroups = privateGroups.filterNot { it.id == groupId }
                commitPrivateGroupsLocked()
                return
            }

            val normTarget = normalGroups.firstOrNull { it.id == groupId }
            if (normTarget != null) {
                val keys = _normalTrayOrderFlow.value.toMutableList()
                val idx = keys.indexOf(groupKey(groupId))
                val expanded = normTarget.tabIds.map { tabKey(it) }
                if (idx >= 0) {
                    keys.removeAt(idx)
                    keys.addAll(idx, expanded)
                } else {
                    keys.addAll(expanded)
                }
                setTrayOrderLocked(keys, isPrivate = false)
                normalGroups = normalGroups.filterNot { it.id == groupId }
                persistNormalGroupsLocked()
            }
        }
    }

    fun clearPrivateGroups() {
        synchronized(lock) {
            privateGroups = emptyList()
            _privateOrderFlow.value = emptyList()
            _privateTrayOrderFlow.value = emptyList()
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

            // Also keep unified tray key order in sync with active tabs & groups
            val modeGroups = if (isPrivate) privateGroups else normalGroups
            val groupedTabIds = modeGroups.flatMap { it.tabIds }.toSet()
            val validGroupKeys = modeGroups.filter { g -> g.tabIds.any { it in validSet } }.map { groupKey(it.id) }.toSet()
            val validTabKeys = tabIds.filterNot { it in groupedTabIds }.map { tabKey(it) }.toSet()
            val allValidKeys = validGroupKeys + validTabKeys

            val trayFlow = if (isPrivate) _privateTrayOrderFlow else _normalTrayOrderFlow
            val currentTray = trayFlow.value
            val nextTray = currentTray.filter { it in allValidKeys }.toMutableList()
            for (gk in validGroupKeys) {
                if (gk !in nextTray) nextTray.add(gk)
            }
            for (id in next) {
                val tk = tabKey(id)
                if (tk in validTabKeys && tk !in nextTray) {
                    nextTray.add(tk)
                }
            }
            if (nextTray != currentTray) {
                setTrayOrderLocked(nextTray, isPrivate)
            }
        }
    }

    fun moveTrayKey(fromKey: String, toKey: String, isPrivate: Boolean): Boolean {
        if (fromKey == toKey) return false
        synchronized(lock) {
            val trayFlow = if (isPrivate) _privateTrayOrderFlow else _normalTrayOrderFlow
            val list = trayFlow.value.toMutableList()
            val fromIdx = list.indexOf(fromKey)
            val toIdx = list.indexOf(toKey)
            if (fromIdx in list.indices && toIdx in list.indices && fromIdx != toIdx) {
                val moved = list.removeAt(fromIdx)
                list.add(toIdx, moved)
                setTrayOrderLocked(list, isPrivate)

                // Mirror single-tab order in legacy order flow
                val singleIds = list.filter { it.startsWith("tab_") }.map { it.removePrefix("tab_") }
                val existing = if (isPrivate) _privateOrderFlow.value else _normalOrderFlow.value
                val merged = existing.filterNot { it in singleIds } + singleIds
                if (isPrivate) {
                    _privateOrderFlow.value = merged
                } else {
                    persistNormalOrderLocked(merged)
                }
                return true
            }
            return false
        }
    }

    fun setExactTrayOrder(orderedKeys: List<String>, isPrivate: Boolean) {
        synchronized(lock) {
            val clean = orderedKeys.distinct().filter { it.isNotBlank() }
            setTrayOrderLocked(clean, isPrivate)
        }
    }

    fun moveTabOrder(fromId: String, toId: String, isPrivate: Boolean) {
        moveTrayKey(tabKey(fromId), tabKey(toId), isPrivate)
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

            // Insert into unified tray order right next to anchor tab (or its group if grouped)
            val trayFlow = if (isPrivate) _privateTrayOrderFlow else _normalTrayOrderFlow
            val newKey = tabKey(newTabId)
            val trayList = trayFlow.value.filterNot { it == newKey }.toMutableList()
            val anchorKey = if (!anchorTabId.isNullOrBlank()) {
                val anchorGroup = groupForTab(anchorTabId)
                if (anchorGroup != null) groupKey(anchorGroup.id) else tabKey(anchorTabId)
            } else null
            val trayAnchorIdx = if (anchorKey != null) trayList.indexOf(anchorKey) else -1
            if (trayAnchorIdx >= 0) {
                trayList.add(trayAnchorIdx + 1, newKey)
            } else {
                trayList.add(newKey)
            }
            setTrayOrderLocked(trayList, isPrivate)
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
                    if (_privateTrayOrderFlow.value.isNotEmpty()) {
                        _privateTrayOrderFlow.value = emptyList()
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
