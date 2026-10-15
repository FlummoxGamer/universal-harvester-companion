package app.harvester.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object SearchEnginePrefs {

    private const val NAME = "uh_search"
    private const val KEY_ENGINE = "engine"
    private const val KEY_CUSTOM = "custom_list"

    const val ENGINE_GOOGLE = "google"
    const val ENGINE_DUCKDUCKGO = "duckduckgo"
    const val ENGINE_BING = "bing"
    const val ENGINE_BRAVE = "brave"
    const val ENGINE_YAHOO = "yahoo"
    const val ENGINE_YANDEX = "yandex"

    data class Entry(
        val id: String,
        val name: String,
        val host: String,
        val searchUrl: String,
        val iconUrl: String,
        val isCustom: Boolean
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getEngine(ctx: Context): String =
        prefs(ctx).getString(KEY_ENGINE, ENGINE_BRAVE) ?: ENGINE_BRAVE

    fun setEngine(ctx: Context, id: String) {
        prefs(ctx).edit().putString(KEY_ENGINE, id).apply()
    }

    fun all(ctx: Context): List<Entry> {
        val out = mutableListOf<Entry>()

        out.add(Entry(ENGINE_BRAVE, "Brave", "search.brave.com",
            "https://search.brave.com/search?q=%s",
            "https://search.brave.com/favicon.ico", false))

        out.add(Entry(ENGINE_GOOGLE, "Google", "google.com",
            "https://www.google.com/search?q=%s",
            "https://www.google.com/favicon.ico", false))

        out.add(Entry(ENGINE_BING, "Bing", "bing.com",
            "https://www.bing.com/search?q=%s",
            "https://www.bing.com/favicon.ico", false))

        out.add(Entry(ENGINE_YAHOO, "Yahoo", "yahoo.com",
            "https://search.yahoo.com/search?p=%s",
            "https://search.yahoo.com/favicon.ico", false))

        out.add(Entry(ENGINE_DUCKDUCKGO, "DuckDuckGo", "duckduckgo.com",
            "https://duckduckgo.com/?q=%s",
            "https://duckduckgo.com/favicon.ico", false))

        out.add(Entry(ENGINE_YANDEX, "Yandex", "yandex.com",
            "https://yandex.com/search/?text=%s",
            "https://yandex.com/favicon.ico", false))

        out.addAll(listCustom(ctx))
        return out
    }

    fun listCustom(ctx: Context): List<Entry> {
        val raw = prefs(ctx).getString(KEY_CUSTOM, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<Entry>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Entry(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        host = o.getString("host"),
                        searchUrl = o.getString("searchUrl"),
                        iconUrl = o.optString("iconUrl", "https://icons.duckduckgo.com/ip3/${o.getString("host")}.ico"),
                        isCustom = true
                    )
                )
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    fun currentEntry(ctx: Context): Entry {
        val id = getEngine(ctx)
        return all(ctx).firstOrNull { it.id == id }
            ?: all(ctx).first()
    }

    fun addCustom(ctx: Context, name: String, url: String): Boolean {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return false

        val host = try { java.net.URI(trimmed).host ?: return false } catch (_: Exception) { return false }
        if (host.isBlank() || !host.contains(".")) return false

        val searchUrl = if (trimmed.contains("%s")) {
            trimmed
        } else {
            val base = trimmed.trimEnd('/')
            if (base.contains("?")) "$base&q=%s" else "$base?q=%s"
        }

        val finalName = name.trim().ifBlank { shortNameFromHost(host) }
        val id = "custom_" + System.currentTimeMillis()
        val current = listCustom(ctx).toMutableList()
        current.add(
            Entry(id, finalName, host, searchUrl,
                "https://icons.duckduckgo.com/ip3/$host.ico", true)
        )
        saveCustom(ctx, current)
        return true
    }

    fun deleteCustom(ctx: Context, id: String) {
        val remaining = listCustom(ctx).filter { it.id != id }
        saveCustom(ctx, remaining)
    }

    private fun saveCustom(ctx: Context, list: List<Entry>) {
        val arr = JSONArray()
        for (c in list) {
            val o = JSONObject()
            o.put("id", c.id)
            o.put("name", c.name)
            o.put("host", c.host)
            o.put("searchUrl", c.searchUrl)
            o.put("iconUrl", c.iconUrl)
            arr.put(o)
        }
        prefs(ctx).edit().putString(KEY_CUSTOM, arr.toString()).apply()
    }

    private fun shortNameFromHost(host: String): String {
        val clean = host.removePrefix("www.")
        return clean.substringBefore('.')
    }

    fun getSearchUrl(ctx: Context, query: String): String {
        val encoded = android.net.Uri.encode(query)
        return currentEntry(ctx).searchUrl.replace("%s", encoded)
    }

    fun getLabel(ctx: Context): String = currentEntry(ctx).name
    fun getHomeUrl(ctx: Context): String {
        val entry = currentEntry(ctx)
        return "https://${entry.host}"
    }
}
