package app.harvester.companion

import android.content.Context
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        val suggestionsUrl: String?,
        val trendingUrl: String?,
        val iconUrl: String,
        val isCustom: Boolean
    )

    private val _activeEngineFlow = MutableStateFlow<Entry?>(null)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getEngine(ctx: Context): String =
        prefs(ctx).getString(KEY_ENGINE, ENGINE_BRAVE) ?: ENGINE_BRAVE

    fun setEngine(ctx: Context, id: String) {
        prefs(ctx).edit().putString(KEY_ENGINE, id).apply()
        _activeEngineFlow.value = currentEntry(ctx)
    }

    fun activeEngineFlow(ctx: Context): StateFlow<Entry> {
        if (_activeEngineFlow.value == null) {
            _activeEngineFlow.value = currentEntry(ctx)
        }
        @Suppress("UNCHECKED_CAST")
        return _activeEngineFlow.asStateFlow() as StateFlow<Entry>
    }

    private fun ddgIcon(host: String) = "https://icons.duckduckgo.com/ip3/$host.ico"

    fun all(ctx: Context): List<Entry> {
        val out = mutableListOf<Entry>()
        val geo = Locale.getDefault().country.ifBlank { "US" }

        out.add(Entry(ENGINE_BRAVE, "Brave", "search.brave.com",
            "https://search.brave.com/search?q=%s",
            "https://search.brave.com/api/suggest?q=%s",
            null,
            ddgIcon("search.brave.com"), false))

        out.add(Entry(ENGINE_GOOGLE, "Google", "google.com",
            "https://www.google.com/search?q=%s",
            "https://suggestqueries.google.com/complete/search?client=firefox&q=%s",
            "https://trends.google.com/trending/rss?geo=$geo",
            ddgIcon("google.com"), false))

        out.add(Entry(ENGINE_BING, "Bing", "bing.com",
            "https://www.bing.com/search?q=%s",
            "https://api.bing.com/osjson.aspx?query=%s",
            "https://www.bing.com/hp/api/v1/trendingnow?format=json",
            ddgIcon("bing.com"), false))

        out.add(Entry(ENGINE_YAHOO, "Yahoo", "yahoo.com",
            "https://search.yahoo.com/search?p=%s",
            null,
            null,
            ddgIcon("yahoo.com"), false))

        out.add(Entry(ENGINE_DUCKDUCKGO, "DuckDuckGo", "duckduckgo.com",
            "https://duckduckgo.com/?q=%s",
            "https://duckduckgo.com/ac/?type=list&q=%s",
            null,
            ddgIcon("duckduckgo.com"), false))

        out.add(Entry(ENGINE_YANDEX, "Yandex", "yandex.com",
            "https://yandex.com/search/?text=%s",
            "https://suggest.yandex.com/suggest-ff.cgi?part=%s",
            null,
            ddgIcon("yandex.com"), false))

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
                val host = o.getString("host")
                out.add(
                    Entry(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        host = host,
                        searchUrl = o.getString("searchUrl"),
                        suggestionsUrl = null,
                        trendingUrl = null,
                        iconUrl = ddgIcon(host),
                        isCustom = true
                    )
                )
            }
            out
        } catch (e: Exception) {
            UHLog.w("search_warn", "Failed parsing custom search engines: ${e.message}")
            emptyList()
        }
    }

    fun currentEntry(ctx: Context): Entry {
        val id = getEngine(ctx)
        val list = all(ctx)
        return list.firstOrNull { it.id == id } ?: list.first()
    }

    fun addCustom(ctx: Context, name: String, url: String): Boolean {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            UHLog.w("search_warn", "Rejected custom search engine without http/https scheme.")
            return false
        }

        val host = try { java.net.URI(trimmed).host ?: return false } catch (e: Exception) {
            UHLog.w("search_warn", "Invalid custom search engine URI: ${e.message}")
            return false
        }
        if (host.isBlank() || !host.contains(".")) {
            UHLog.w("search_warn", "Rejected custom search engine with invalid host: $host")
            return false
        }

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
            Entry(id, finalName, host, searchUrl, null, null, ddgIcon(host), true)
        )
        saveCustom(ctx, current)
        _activeEngineFlow.value = currentEntry(ctx)
        return true
    }

    fun updateCustom(ctx: Context, id: String, name: String, url: String): Boolean {
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
        val updated = listCustom(ctx).map { entry ->
            if (entry.id == id) {
                Entry(id, finalName, host, searchUrl, null, null, ddgIcon(host), true)
            } else entry
        }
        saveCustom(ctx, updated)
        _activeEngineFlow.value = currentEntry(ctx)
        return true
    }

    fun deleteCustom(ctx: Context, id: String) {
        val remaining = listCustom(ctx).filter { it.id != id }
        saveCustom(ctx, remaining)
        if (getEngine(ctx) == id) {
            setEngine(ctx, ENGINE_BRAVE)
        } else {
            _activeEngineFlow.value = currentEntry(ctx)
        }
    }

    private fun saveCustom(ctx: Context, list: List<Entry>) {
        val arr = JSONArray()
        for (c in list) {
            val o = JSONObject()
            o.put("id", c.id)
            o.put("name", c.name)
            o.put("host", c.host)
            o.put("searchUrl", c.searchUrl)
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
    fun getHomeUrl(ctx: Context): String = "https://${currentEntry(ctx).host}"
}
