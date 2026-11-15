package app.harvester.companion.search

import android.content.ClipboardManager
import android.content.Context
import app.harvester.companion.SearchEnginePrefs
import app.harvester.companion.UHApp
import app.harvester.companion.UHLog
import app.harvester.companion.browser.search.SearchSuggestionClient
import app.harvester.companion.search.model.SuggestionType
import app.harvester.companion.search.model.UhSearchListItem
import app.harvester.companion.utils.SearchHistoryUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.selectedOrDefaultSearchEngine
import mozilla.components.feature.search.trendingsearches.TrendingSearchClient

object UhSearchDataProvider {
    private val suggestionClient = SearchSuggestionClient()

    suspend fun fetchData(context: Context, query: String, isBarBottom: Boolean): Pair<List<UhSearchListItem>, String?> = withContext(Dispatchers.IO) {
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
    val selectedTab = components.store.state.selectedTab
    if (selectedTab != null && selectedTab.content.url != "about:blank" && selectedTab.content.url.isNotBlank()) {
        val currentTabItem = UhSearchListItem.CurrentTab(
            title = selectedTab.content.title,
            url = selectedTab.content.url,
            icon = selectedTab.content.icon
        )
        if (!isBarBottom) {
            items.add(currentTabItem)
        }
    }

    items.add(UhSearchListItem.Header("Recent Searches"))
try {
    val metadata = components.placesHistoryStorage.getHistoryMetadataSince(0L)
    metadata.asSequence()
        .filter { !it.key.searchTerm.isNullOrBlank() }
        .distinctBy { it.key.searchTerm }
        .sortedByDescending { it.createdAt }
        .take(5)
        .forEach { item ->
            items.add(UhSearchListItem.Suggestion(item.key.searchTerm.orEmpty(), SuggestionType.HISTORY))
        }
} catch (e: Exception) {
    UHLog.e("search_error", "Failed to fetch recent history: ${e.message}")
}

items.add(UhSearchListItem.Header("Trending"))
try {
    val currentEngine = components.store.state.search.selectedOrDefaultSearchEngine(private = false)
    if (currentEngine != null) {
val client = TrendingSearchClient { url ->
    try {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.inputStream.bufferedReader().use { it.readText() }
    } catch (e: Exception) { null }
}
        client.setSearchEngine(currentEngine)
        val trends = client.getTrendingSearches() ?: emptyList()
        if (trends.isNotEmpty()) {
            trends.take(5).forEach { trend ->
                items.add(UhSearchListItem.Suggestion(trend, SuggestionType.TRENDING))
            }
        } else {
            items.add(UhSearchListItem.Suggestion("No trending searches available", SuggestionType.TRENDING))
        }
    }
    } catch (e: Exception) {
        UHLog.e("search_error", "Trending fetch failed: ${e.message}")
    }

    if (isBarBottom) {
        val selectedTab = components.store.state.selectedTab
        if (selectedTab != null && selectedTab.content.url != "about:blank" && selectedTab.content.url.isNotBlank()) {
            items.add(
                UhSearchListItem.CurrentTab(
                    title = selectedTab.content.title,
                    url = selectedTab.content.url,
                    icon = selectedTab.content.icon
                )
            )
        }
    }
} else {
items.add(UhSearchListItem.Header("Search"))
try {
    val engine = SearchEnginePrefs.currentEntry(context)
    val remoteSuggestions = suggestionClient.getSuggestions(engine.id, engine.suggestionsUrl, query)
    if (remoteSuggestions.isNullOrEmpty()) {
        items.add(UhSearchListItem.Suggestion(query, SuggestionType.SEARCH))
    } else {
        remoteSuggestions.take(5).forEach { sug ->
            items.add(UhSearchListItem.Suggestion(sug, SuggestionType.SEARCH))
        }
    }
} catch (e: Exception) {
    UHLog.e("search_error", "Remote suggestions failed: ${e.message}")
    items.add(UhSearchListItem.Suggestion(query, SuggestionType.SEARCH))
}

            try {
                val visits = components.placesHistoryStorage.getDetailedVisits(0L, System.currentTimeMillis())
                val historyMatches = visits.filter { 
                    !SearchHistoryUtils.isSearchUrl(context, it.url) && 
                    (it.url.contains(query, ignoreCase = true) || (it.title?.contains(query, ignoreCase = true) == true)) 
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
