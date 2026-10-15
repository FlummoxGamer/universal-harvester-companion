package app.harvester.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object TabGroupStore {

    private const val NAME = "uh_tab_groups"
    private const val KEY = "groups"

    data class Group(
        val id: Long,
        val name: String,
        val color: Int
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun list(ctx: Context): List<Group> {
        val raw = prefs(ctx).getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<Group>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Group(o.getLong("id"), o.getString("name"), o.optInt("color", 0xFF22D3EE.toInt())))
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    fun create(ctx: Context, name: String): Group {
        val id = System.currentTimeMillis()
        val group = Group(id, name.ifBlank { "Group" }, 0xFF22D3EE.toInt())
        val all = list(ctx).toMutableList()
        all.add(group)
        save(ctx, all)
        return group
    }

    fun rename(ctx: Context, id: Long, name: String) {
        val all = list(ctx).map { if (it.id == id) it.copy(name = name) else it }
        save(ctx, all)
    }

    fun delete(ctx: Context, id: Long) {
        val all = list(ctx).filter { it.id != id }
        save(ctx, all)
    }

    fun byId(ctx: Context, id: Long): Group? = list(ctx).firstOrNull { it.id == id }

    private fun save(ctx: Context, list: List<Group>) {
        val arr = JSONArray()
        for (g in list) {
            val o = JSONObject()
            o.put("id", g.id)
            o.put("name", g.name)
            o.put("color", g.color)
            arr.put(o)
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }
}
