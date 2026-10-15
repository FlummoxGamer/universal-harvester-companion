package app.harvester.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object ShortcutStore {

    private const val NAME = "uh_shortcuts"
    private const val KEY = "items"

    fun list(ctx: Context): MutableList<Shortcut> {
        val raw = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString(KEY, null)
        if (raw.isNullOrBlank()) return defaultList()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<Shortcut>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Shortcut(
                        name = o.optString("name"),
                        url = o.optString("url"),
                        iconUrl = o.optString("iconUrl")
                    )
                )
            }
            out
        } catch (_: Exception) { defaultList() }
    }

    fun save(ctx: Context, list: List<Shortcut>) {
        val arr = JSONArray()
        for (s in list) {
            val o = JSONObject()
            o.put("name", s.name)
            o.put("url", s.url)
            o.put("iconUrl", s.iconUrl)
            arr.put(o)
        }
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
    fun shortNameFor(targetUrl: String): String {
        val host = try { java.net.URI(targetUrl).host } catch (_: Exception) { null }
            ?: return targetUrl
        val clean = host.removePrefix("www.")
        return clean.substringBefore('.')
    }

    fun add(ctx: Context, s: Shortcut) {
        val current = list(ctx)
        current.add(s)
        save(ctx, current)
    }

    fun remove(ctx: Context, url: String) {
        val current = list(ctx)
        current.removeAll { it.url == url }
        save(ctx, current)
    }

fun iconUrlFor(targetUrl: String): String {
    val host = try {
        java.net.URI(targetUrl).host
    } catch (_: Exception) { null }
    if (host.isNullOrBlank()) return ""
    val clean = host.removePrefix("www.")
    return "https://icons.duckduckgo.com/ip3/$clean.ico"
}

    private fun defaultList(): MutableList<Shortcut> = mutableListOf(
        Shortcut(
            name = "DuckDuckGo",
            url = "https://duckduckgo.com",
            iconUrl = "https://icons.duckduckgo.com/ip3/duckduckgo.com.ico"
        )
    )
}
