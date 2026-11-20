package app.harvester.companion.utils

import android.content.Context
import android.net.Uri
import app.harvester.companion.SearchEnginePrefs

object SearchHistoryUtils {
    private val SEARCH_QUERY_PARAMS = listOf("q", "p", "text", "query", "search_query", "wd", "keyword")

    private val ENGINE_SUFFIXES = listOf(
        " - Google Search",
        " - Google",
        " at DuckDuckGo",
        " — DuckDuckGo",
        " - DuckDuckGo",
        " - Brave Search",
        " - Brave",
        " - Bing",
        " - Search",
        " - Yahoo Search Results",
        " - Yahoo Search",
        " - Yahoo",
        " — Yandex",
        " - Yandex"
    )

    fun isSearchUrl(context: Context, url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host?.removePrefix("www.") ?: return false
            val path = uri.path ?: ""
            val engines = SearchEnginePrefs.all(context)

            engines.any { engine ->
                val engineHost = engine.host.removePrefix("www.")
                host == engineHost && (uri.query != null || path.contains("search"))
            }
        } catch (_: Exception) {
            false
        }
    }

    fun extractSearchQuery(context: Context, url: String, fallbackTitle: String? = null): String? {
        try {
            val uri = Uri.parse(url)
            for (param in SEARCH_QUERY_PARAMS) {
                val value = uri.getQueryParameter(param)?.trim()
                if (!value.isNullOrBlank()) {
                    return stripEngineSuffix(value)
                }
            }
        } catch (_: Exception) {
        }
        val cleaned = fallbackTitle?.let { stripEngineSuffix(it) }?.trim()
        return if (!cleaned.isNullOrBlank() && !cleaned.startsWith("http")) cleaned else null
    }

    fun stripEngineSuffix(rawTitle: String): String {
        var result = rawTitle.trim()
        for (suffix in ENGINE_SUFFIXES) {
            if (result.endsWith(suffix, ignoreCase = true)) {
                result = result.removeRange(result.length - suffix.length, result.length).trim()
            }
        }
        return result
    }
}
