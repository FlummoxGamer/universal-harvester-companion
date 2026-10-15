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

    data class CustomEngine(
        val id: String,
        val name: String,
        val host: String,
        val url: String
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getEngine(ctx: Context): String =
        prefs(ctx).getString(KEY_ENGINE, ENGINE_BRAVE) ?: ENGINE_BRAVE

    fun setEngine(ctx: Context, engine: String) {
        prefs(ctx).edit().putString(KEY_ENGINE, engine).apply()
    }

    fun getSearchUrl(ctx: Context, query: String): String {
        val encoded = android.net.Uri.encode(query)
        val eng = getEngine(ctx)

        listCustom(ctx).firstOrNull { it.id == eng }?.let { c ->
            return c.url.replace("%s", encoded)
        }

        return when (eng) {
            ENGINE_GOOGLE -> "https://www.google.com/search?q=$encoded"
            ENGINE_BING -> "https://www.bing.com/search?q=$encoded"
            ENGINE_DUCKDUCKGO -> "https://duckduckgo.com/?q=$encoded"
            ENGINE_YAHOO -> "https://search.yahoo.com/search?p=$encoded"
            ENGINE_YANDEX -> "https://yandex.com/search/?text=$encoded"
            else -> "https://search.brave.com/search?q=$encoded"
        }
    }

    fun getLabel(ctx: Context): String {
        val eng = getEngine(ctx)
        listCustom(ctx).firstOrNull { it.id == eng }?.let { return it.name }
        return when (eng) {
            ENGINE_GOOGLE -> "Google"
            ENGINE_BING -> "Bing"
            ENGINE_DUCKDUCKGO -> "DuckDuckGo"
            ENGINE_YAHOO -> "Yahoo"
            ENGINE_YANDEX -> "Yandex"
            else -> "Brave"
        }
    }

    fun getHomeUrl(ctx: Context): String {
        val eng = getEngine(ctx)
        listCustom(ctx).firstOrNull { it.id == eng }?.let { c ->
            // strip the query template to get the base URL
            return c.url.substringBefore("?").substringBefore("/search")
        }
        return when (eng) {
            ENGINE_GOOGLE -> "https://www.google.com"
            ENGINE_BING -> "https://www.bing.com"
            ENGINE_DUCKDUCKGO -> "https://duckduckgo.com"
            ENGINE_YAHOO -> "https://search.yahoo.com"
            ENGINE_YANDEX -> "https://yandex.com"
            else -> "https://search.brave.com"
        }
    }

    fun getIconUrl(ctx: Context): String {
        val eng = getEngine(ctx)
        listCustom(ctx).firstOrNull { it.id == eng }?.let { c ->
            return "https://icons.duckduckgo.com/ip3/${c.host}.ico"
        }
        return when (eng) {
            ENGINE_GOOGLE -> "https://www.google.com/favicon.ico"
            ENGINE_BING -> "https://www.bing.com/favicon.ico"
            ENGINE_DUCKDUCKGO -> "https://duckduckgo.com/favicon.ico"
            ENGINE_YAHOO -> "https://search.yahoo.com/favicon.ico"
            ENGINE_YANDEX -> "https://yandex.com/favicon.ico"
            else -> "https://search.brave.com/favicon.ico"
        }
    }

    fun listCustom(ctx: Context): List<CustomEngine> {
        val raw = prefs(ctx).getString(KEY_CUSTOM, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<CustomEngine>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    CustomEngine(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        host = o.getString("host"),
                        url = o.getString("url")
                    )
                )
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    fun addCustom(ctx: Context, name: String, url: String) {
        val host = try { java.net.URI(url).host ?: url } catch (_: Exception) { url }
        val id = "custom_" + System.currentTimeMillis()
        val current = listCustom(ctx).toMutableList()
        current.add(CustomEngine(id, name, host, url))
        saveCustom(ctx, current)
    }

    fun deleteCustom(ctx: Context, id: String) {
        val remaining = listCustom(ctx).filter { it.id != id }
        saveCustom(ctx, remaining)
    }

    private fun saveCustom(ctx: Context, list: List<CustomEngine>) {
        val arr = JSONArray()
        for (c in list) {
            val o = JSONObject()
            o.put("id", c.id)
            o.put("name", c.name)
            o.put("host", c.host)
            o.put("url", c.url)
            arr.put(o)
        }
        prefs(ctx).edit().putString(KEY_CUSTOM, arr.toString()).apply()
    }
}
