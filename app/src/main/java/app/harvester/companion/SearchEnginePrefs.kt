package app.harvester.companion

import android.content.Context

object SearchEnginePrefs {

    private const val NAME = "uh_search"
    private const val KEY_ENGINE = "engine"

    const val ENGINE_GOOGLE = "google"
    const val ENGINE_DUCKDUCKGO = "duckduckgo"
    const val ENGINE_BING = "bing"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getEngine(ctx: Context): String =
        prefs(ctx).getString(KEY_ENGINE, ENGINE_GOOGLE) ?: ENGINE_GOOGLE

    fun setEngine(ctx: Context, engine: String) {
        prefs(ctx).edit().putString(KEY_ENGINE, engine).apply()
    }

    fun getSearchUrl(ctx: Context, query: String): String {
        val encoded = android.net.Uri.encode(query)
        return when (getEngine(ctx)) {
            ENGINE_DUCKDUCKGO -> "https://duckduckgo.com/?q=$encoded"
            ENGINE_BING -> "https://www.bing.com/search?q=$encoded"
            else -> "https://www.google.com/search?q=$encoded"
        }
    }

    fun getLabel(ctx: Context): String = when (getEngine(ctx)) {
        ENGINE_DUCKDUCKGO -> "DuckDuckGo"
        ENGINE_BING -> "Bing"
        else -> "Google"
    }

    fun getIconRes(ctx: Context): Int = when (getEngine(ctx)) {
        ENGINE_DUCKDUCKGO -> R.drawable.ic_duckduckgo
        ENGINE_BING -> R.drawable.ic_bing
        else -> R.drawable.ic_google_rounded
    }
}
