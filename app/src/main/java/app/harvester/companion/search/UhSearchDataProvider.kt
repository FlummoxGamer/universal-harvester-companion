package app.harvester.companion.search

import android.content.ClipboardManager
import android.content.Context
import app.harvester.companion.SearchEnginePrefs
import app.harvester.companion.UHApp
import app.harvester.companion.UHLog
import app.harvester.companion.browser.search.SearchSuggestionClient
import app.harvester.companion.search.model.SuggestionType
import app.harvester.companion.search.model.UhSearchListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UhSearchDataProvider {
    private val suggestionClient = SearchSuggestionClient()

    suspend fun fetchData(context: Context, query: String): Pair<List<UhSearchListItem>, String?> = withContext(Dispatchers.IO) {
        val items = mutableListOf<UhSearchListItem>()
        var clipboardUrl: String? = null

        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
            if (clipText?.startsWith("http://") == true || clipText?.startsWith("https://") == true) {
                clipboardUrl = clipText
            }
        } catch (e: Exception) {
            UHLog.e("search_error", "Clipboard error: ${e.message}")
        }

        val components = (context.applicationContext as UHApp).components

        if (query.isBlank()) {
            items.add(UhSearchListItem.Header("Recent Searches"))
            try {
                val visits = components.placesHistoryStorage.getDetailedVisits(0L, System.currentTimeMillis())
                visits.filter { it.title != null && it.title.isNotBlank() }
                    .distinctBy { it.title }
                    .take(5)
                    .forEach { visit ->
                        items.add(UhSearchListItem.Suggestion(visit.title ?: visit.url, SuggestionType.HISTORY))
                    }
            } catch (e: Exception) {
                UHLog.e("search_error", "Failed to fetch recent history: ${e.message}")
            }

            items.add(UhSearchListItem.Header("Trending"))
            try {
                val engine = SearchEnginePrefs.currentEntry(context)
                // Mocking trending since we removed Mozilla's provider
                val trends = listOf("weather", "news", "sports", "stocks", "crypto")
                trends.forEach { trend ->
                    items.add(UhSearchListItem.Suggestion(trend, SuggestionType.TRENDING))
                }
            } catch (e: Exception) {
                UHLog.e("search_error", "Trending fetch failed: ${e.message}")
            }

        } else {
            items.add(UhSearchListItem.Header("Search"))
            try {
                val engine = SearchEnginePrefs.currentEntry(context)
                val remoteSuggestions = suggestionClient.getSuggestions(engine.id, engine.suggestionsUrl, query) ?: emptyList()
                remoteSuggestions.take(5).forEach { sug ->
                    items.add(UhSearchListItem.Suggestion(sug, SuggestionType.SEARCH))
                }
            } catch (e: Exception) {
                UHLog.e("search_error", "Remote suggestions failed: ${e.message}")
            }

            try {
                val visits = components.placesHistoryStorage.getDetailedVisits(0L, System.currentTimeMillis())
                val historyMatches = visits.filter { 
                    it.url.contains(query, ignoreCase = true) || (it.title?.contains(query, ignoreCase = true) == true) 
                }.take(5)
                
                if (historyMatches.isNotEmpty()) {
                    items.add(UhSearchListItem.Header("History"))
                    historyMatches.forEach { visit ->
                        items.add(UhSearchListItem.History(visit.title ?: visit.url, visit.url, null))
                    }
                }
            } catch (e: Exception) {
                UHLog.e("search_error", "Local history search failed: ${e.message}")
            }
        }

        Pair(items, clipboardUrl)
    }
}
