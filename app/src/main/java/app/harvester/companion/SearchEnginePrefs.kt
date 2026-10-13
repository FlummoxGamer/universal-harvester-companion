package app.harvester.companion

import android.content.Context
import java.io.File

object SearchEnginePrefs {

    private const val NAME = "uh_search"
    private const val KEY_ENGINE = "engine"

    const val ENGINE_GOOGLE = "google"
    const val ENGINE_DUCKDUCKGO = "duckduckgo"
    const val ENGINE_BING = "bing"
    const val ENGINE_BRAVE = "brave"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getEngine(ctx: Context): String =
        prefs(ctx).getString(KEY_ENGINE, ENGINE_BRAVE) ?: ENGINE_BRAVE

    fun setEngine(ctx: Context, engine: String) {
        prefs(ctx).edit().putString(KEY_ENGINE, engine).apply()
    }

    fun getSearchUrl(ctx: Context, query: String): String {
        val encoded = android.net.Uri.encode(query)
        return when (getEngine(ctx)) {
            ENGINE_GOOGLE -> "https://www.google.com/search?q=$encoded"
            ENGINE_BING -> "https://www.bing.com/search?q=$encoded"
            ENGINE_DUCKDUCKGO -> "https://duckduckgo.com/?q=$encoded"
            else -> "https://search.brave.com/search?q=$encoded"
        }
    }

    fun getLabel(ctx: Context): String = when (getEngine(ctx)) {
        ENGINE_GOOGLE -> "Google"
        ENGINE_BING -> "Bing"
        ENGINE_DUCKDUCKGO -> "DuckDuckGo"
        else -> "Brave"
    }

    fun getHomeUrl(ctx: Context): String = when (getEngine(ctx)) {
        ENGINE_GOOGLE -> "https://www.google.com"
        ENGINE_BING -> "https://www.bing.com"
        ENGINE_DUCKDUCKGO -> "https://duckduckgo.com"
        else -> "https://search.brave.com"
    }

    fun getIconUrl(ctx: Context): String = when (getEngine(ctx)) {
        ENGINE_GOOGLE -> "https://www.google.com/favicon.ico"
        ENGINE_BING -> "https://www.bing.com/favicon.ico"
        ENGINE_DUCKDUCKGO -> "https://duckduckgo.com/favicon.ico"
        else -> "https://search.brave.com/favicon.ico"
    }

    fun getIconCacheFile(ctx: Context): File {
        val dir = File(ctx.filesDir, "search_icons").apply { mkdirs() }
        return File(dir, "${getEngine(ctx)}.ico")
    }
}
