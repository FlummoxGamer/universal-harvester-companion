package app.harvester.companion.utils

import android.content.Context
import android.net.Uri
import app.harvester.companion.SearchEnginePrefs

object SearchHistoryUtils {
    fun isSearchUrl(context: Context, url: String): Boolean {
        val uri = Uri.parse(url)
        val host = uri.host?.removePrefix("www.") ?: return false
        val path = uri.path ?: ""
        val engines = SearchEnginePrefs.all(context)

        return engines.any { engine ->
            val engineHost = engine.host.removePrefix("www.")
            host == engineHost && (uri.query != null || path.contains("search"))
        }
    }
}
