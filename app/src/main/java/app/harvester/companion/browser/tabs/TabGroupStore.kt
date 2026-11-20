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
    val isCollapsed: Boolean = false,
    val tabIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

object TabGroupStore {
    private const val PREFS_NAME = "uh_tab_groups"
    private const val KEY_GROUPS = "groups_json"

    val GROUP_COLORS: List<Long> = listOf(
        0xFF457B9D, // Ocean Steel (Brand Accent)
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
    private val _groupsFlow = MutableStateFlow<List<TabGroup>>(emptyList())
    val groupsFlow: StateFlow<List<TabGroup>> = _groupsFlow.asStateFlow()

    fun init(ctx: Context) {
        synchronized(lock) {
            if (appContext == null) {
                appContext = ctx.applicationContext
                _groupsFlow.value = readFromDisk(ctx.applicationContext)
            }
        }
    }

    private fun ctxOrNull(): Context? = appContext

    private fun readFromDisk(ctx: Context): List<TabGroup> {
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
                    list.add(TabGroup(id, name, color, collapsed, tabIds, createdAt))
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persist(groups: List<TabGroup>) {
        val clean = groups.filter { it.tabIds.isNotEmpty() }
        _groupsFlow.value = clean
        val ctx = ctxOrNull() ?: return
        val arr = JSONArray()
        for (g in clean) {
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

    fun groupForTab(tabId: String): TabGroup? {
        return _groupsFlow.value.firstOrNull { tabId in it.tabIds }
    }

    fun createGroup(name: String, colorArgb: Long, tabIds: List<String>): TabGroup {
        val uniqueIds = tabIds.distinct()
        val group = TabGroup(
            id = "grp_${UUID.randomUUID().toString().take(8)}",
            name = name.trim().ifBlank { "Tab Group" },
            colorArgb = colorArgb,
            isCollapsed = false,
            tabIds = uniqueIds
        )
        synchronized(lock) {
            val updated = _groupsFlow.value.map { existing ->
                existing.copy(tabIds = existing.tabIds.filterNot { it in uniqueIds })
            }.filter { it.tabIds.isNotEmpty() } + group
            persist(updated)
        }
        return group
    }

    fun addTabToGroup(groupId: String, tabId: String) {
        synchronized(lock) {
            val updated = _groupsFlow.value.map { g ->
                when {
                    g.id == groupId -> {
                        val list = if (tabId in g.tabIds) g.tabIds else g.tabIds + tabId
                        g.copy(tabIds = list)
                    }
                    tabId in g.tabIds -> g.copy(tabIds = g.tabIds.filterNot { it == tabId })
                    else -> g
                }
            }
            persist(updated)
        }
    }

    fun removeTabFromGroups(tabId: String) {
        synchronized(lock) {
            val current = _groupsFlow.value
            if (current.none { tabId in it.tabIds }) return
            val updated = current.map { g ->
                if (tabId in g.tabIds) g.copy(tabIds = g.tabIds.filterNot { it == tabId }) else g
            }
            persist(updated)
        }
    }

    fun renameAndRecolor(groupId: String, newName: String, newColorArgb: Long) {
        synchronized(lock) {
            val updated = _groupsFlow.value.map { g ->
                if (g.id == groupId) {
                    g.copy(
                        name = newName.trim().ifBlank { g.name },
                        colorArgb = newColorArgb
                    )
                } else g
            }
            persist(updated)
        }
    }

    fun toggleCollapsed(groupId: String) {
        synchronized(lock) {
            val updated = _groupsFlow.value.map { g ->
                if (g.id == groupId) g.copy(isCollapsed = !g.isCollapsed) else g
            }
            persist(updated)
        }
    }

    fun setCollapsed(groupId: String, collapsed: Boolean) {
        synchronized(lock) {
            val updated = _groupsFlow.value.map { g ->
                if (g.id == groupId) g.copy(isCollapsed = collapsed) else g
            }
            persist(updated)
        }
    }

    fun ungroup(groupId: String) {
        synchronized(lock) {
            persist(_groupsFlow.value.filterNot { it.id == groupId })
        }
    }

    fun cleanupOrphanedTabs(validNormalTabIds: Set<String>) {
        synchronized(lock) {
            val updated = _groupsFlow.value.map { g ->
                g.copy(tabIds = g.tabIds.filter { it in validNormalTabIds })
            }
            if (updated != _groupsFlow.value) {
                persist(updated)
            }
        }
    }
}
