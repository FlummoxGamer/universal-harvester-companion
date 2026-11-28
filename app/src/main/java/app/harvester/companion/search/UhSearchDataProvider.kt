package app.harvester.companion.search

import android.content.ClipboardManager
import android.content.Context
import android.text.Html
import app.harvester.companion.SearchEnginePrefs
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.UHApp
import app.harvester.companion.UHLog
import app.harvester.companion.browser.search.SearchSuggestionClient
import app.harvester.companion.core.network.UhHttpClient
import app.harvester.companion.search.model.SuggestionType
import app.harvester.companion.search.model.UhSearchListItem
import app.harvester.companion.utils.SearchHistoryUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.selector.selectedTab
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object UhSearchDataProvider {
    private val suggestionClient = SearchSuggestionClient()

    private val fastSearchClient by lazy {
        UhHttpClient.client.newBuilder()
            .connectTimeout(2000, TimeUnit.MILLISECONDS)
            .readTimeout(2500, TimeUnit.MILLISECONDS)
            .callTimeout(3000, TimeUnit.MILLISECONDS)
            .build()
    }

    private data class CachedTrends(val timestampMs: Long, val items: List<String>)
    private val trendsCache = ConcurrentHashMap<String, CachedTrends>()
    private const val TRENDS_TTL_MS = 30 * 60 * 1000L

    data class SearchFetchResult(
        val items: List<UhSearchListItem>,
        val clipboardUrl: String?,
        val currentTab: UhSearchListItem.CurrentTab?
    )

    suspend fun fetchData(
        context: Context,
        query: String,
        includeCurrentTab: Boolean = false,
    ): SearchFetchResult = withContext(Dispatchers.IO) {
        coroutineScope {
            val appCtx = context.applicationContext
            val components = (appCtx as UHApp).components
            val activeEngine = SearchEnginePrefs.currentEntry(appCtx)

            val clipboardDeferred = async {
                if (!SettingsPrefs.getBool(appCtx, SettingsPrefs.K_SEARCH_CLIPBOARD, true)) return@async null
                try {
                    val clipboard = appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                    if (clipText?.startsWith("http://") == true || clipText?.startsWith("https://") == true) {
                        clipText
                    } else null
                } catch (_: Exception) {
                    null
                }
            }

            val selectedTab = components.store.state.selectedTab
            val isPrivate = selectedTab?.content?.private == true
            val currentTabItem = if (
                includeCurrentTab &&
                selectedTab != null &&
                selectedTab.content.url != "about:blank" &&
                selectedTab.content.url.isNotBlank()
            ) {
                UhSearchListItem.CurrentTab(
                    title = selectedTab.content.title.ifBlank { selectedTab.content.url },
                    url = selectedTab.content.url,
                    icon = selectedTab.content.icon
                )
            } else null

            val items = mutableListOf<UhSearchListItem>()

            if (isPrivate) {
                if (query.isBlank()) {
                    val tempPrivate = PrivateSearchHistoryStore.recent(6)
                    if (tempPrivate.isNotEmpty()) {
                        items.add(UhSearchListItem.Header("Private Session"))
                        tempPrivate.forEach { term ->
                            items.add(UhSearchListItem.Suggestion(term, SuggestionType.HISTORY))
                        }
                    }
                    val normalRecent = fetchRecentSearchTerms(appCtx, components)
                        .filter { norm -> tempPrivate.none { it.equals(norm, ignoreCase = true) } }
                        .take(5)
                    if (normalRecent.isNotEmpty()) {
                        items.add(UhSearchListItem.Header("Recent Searches"))
                        normalRecent.forEach { term ->
                            items.add(UhSearchListItem.Suggestion(term, SuggestionType.HISTORY))
                        }
                    }
                } else {
                    val remoteDeferred = async {
                        val showRemote = SettingsPrefs.getBool(appCtx, SettingsPrefs.K_SEARCH_REMOTE, true)
                        if (!showRemote || !SettingsPrefs.getBool(appCtx, SettingsPrefs.K_SEARCH_SUGGESTIONS, true)) {
                            return@async listOf(query)
                        }
                        try {
                            val remote = suggestionClient.getSuggestions(activeEngine.id, activeEngine.suggestionsUrl, query)
                            if (remote.isNullOrEmpty()) listOf(query) else remote.take(5)
                        } catch (e: Exception) {
                            UHLog.w("search_warn", "Remote suggestions failed: ${e.message}")
                            listOf(query)
                        }
                    }
                    val historyDeferred = async {
                        if (!SettingsPrefs.getBool(appCtx, SettingsPrefs.K_SEARCH_HISTORY, true)) {
                            return@async emptyList<UhSearchListItem.History>()
                        }
                        try {
                            val since = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
                            val visits = components.placesHistoryStorage.getDetailedVisits(since, System.currentTimeMillis())
                            visits.asSequence()
                                .filter {
                                    !SearchHistoryUtils.isSearchUrl(appCtx, it.url) &&
                                        (it.url.contains(query, ignoreCase = true) ||
                                            (it.title?.contains(query, ignoreCase = true) == true))
                                }
                                .sortedByDescending { it.visitTime }
                                .distinctBy { it.url }
                                .take(5)
                                .map { visit ->
                                    val cleanTitle = visit.title?.let { SearchHistoryUtils.stripEngineSuffix(it) }
                                        ?.takeIf { it.isNotBlank() } ?: visit.url
                                    UhSearchListItem.History(cleanTitle, visit.url, null)
                                }
                                .toList()
                        } catch (e: Exception) {
                            UHLog.e("search_error", "Local history search failed: ${e.message}")
                            emptyList()
                        }
                    }

                    val privateMatches = PrivateSearchHistoryStore.matching(query, limit = 3)
                    if (privateMatches.isNotEmpty()) {
                        items.add(UhSearchListItem.Header("Private Session"))
                        privateMatches.forEach { term ->
                            items.add(UhSearchListItem.Suggestion(term, SuggestionType.HISTORY))
                        }
                    }

                    val suggestions = remoteDeferred.await()
                        .filter { sug -> privateMatches.none { it.equals(sug, ignoreCase = true) } }
                    if (suggestions.isNotEmpty()) {
                        items.add(UhSearchListItem.Header("Search"))
                        suggestions.forEach { sug ->
                            items.add(UhSearchListItem.Suggestion(sug, SuggestionType.SEARCH))
                        }
                    }

                    val historyMatches = historyDeferred.await()
                    if (historyMatches.isNotEmpty()) {
                        items.add(UhSearchListItem.Header("History"))
                        items.addAll(historyMatches)
                    }
                }
            } else if (query.isBlank()) {
                val showTrending = SettingsPrefs.getBool(appCtx, SettingsPrefs.K_SEARCH_TRENDING, true)
                val recentDeferred = async { fetchRecentSearchTerms(appCtx, components) }
                val trendsDeferred = async {
                    if (showTrending) fetchRegionalGoogleTrends() else emptyList()
                }

                val topRecent = recentDeferred.await()
                if (topRecent.isNotEmpty()) {
                    items.add(UhSearchListItem.Header("Recent Searches"))
                    topRecent.forEach { term ->
                        items.add(UhSearchListItem.Suggestion(term, SuggestionType.HISTORY))
                    }
                }

                val trends = trendsDeferred.await()
                if (trends.isNotEmpty()) {
                    items.add(UhSearchListItem.Header("Trending"))
                    trends.take(8).forEach { trend ->
                        items.add(UhSearchListItem.Suggestion(trend, SuggestionType.TRENDING))
                    }
                }
            } else {
                val remoteDeferred = async {
                    val showRemote = SettingsPrefs.getBool(appCtx, SettingsPrefs.K_SEARCH_REMOTE, true)
                    if (!showRemote || !SettingsPrefs.getBool(appCtx, SettingsPrefs.K_SEARCH_SUGGESTIONS, true)) {
                        return@async listOf(query)
                    }
                    try {
                        val remote = suggestionClient.getSuggestions(activeEngine.id, activeEngine.suggestionsUrl, query)
                        if (remote.isNullOrEmpty()) listOf(query) else remote.take(5)
                    } catch (e: Exception) {
                        UHLog.w("search_warn", "Remote suggestions failed: ${e.message}")
                        listOf(query)
                    }
                }

                val historyDeferred = async {
                    if (!SettingsPrefs.getBool(appCtx, SettingsPrefs.K_SEARCH_HISTORY, true)) {
                        return@async emptyList<UhSearchListItem.History>()
                    }
                    try {
                        val since = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
                        val visits = components.placesHistoryStorage.getDetailedVisits(since, System.currentTimeMillis())
                        visits.asSequence()
                            .filter {
                                !SearchHistoryUtils.isSearchUrl(appCtx, it.url) &&
                                    (it.url.contains(query, ignoreCase = true) ||
                                        (it.title?.contains(query, ignoreCase = true) == true))
                            }
                            .sortedByDescending { it.visitTime }
                            .distinctBy { it.url }
                            .take(5)
                            .map { visit ->
                                val cleanTitle = visit.title?.let { SearchHistoryUtils.stripEngineSuffix(it) }
                                    ?.takeIf { it.isNotBlank() } ?: visit.url
                                UhSearchListItem.History(cleanTitle, visit.url, null)
                            }
                            .toList()
                    } catch (e: Exception) {
                        UHLog.e("search_error", "Local history search failed: ${e.message}")
                        emptyList()
                    }
                }

                val suggestions = remoteDeferred.await()
                if (suggestions.isNotEmpty()) {
                    items.add(UhSearchListItem.Header("Search"))
                    suggestions.forEach { sug ->
                        items.add(UhSearchListItem.Suggestion(sug, SuggestionType.SEARCH))
                    }
                }

                val historyMatches = historyDeferred.await()
                if (historyMatches.isNotEmpty()) {
                    items.add(UhSearchListItem.Header("History"))
                    items.addAll(historyMatches)
                }
            }

            SearchFetchResult(
                items = items,
                clipboardUrl = clipboardDeferred.await(),
                currentTab = currentTabItem
            )
        }
    }

    private suspend fun fetchRecentSearchTerms(
        context: Context,
        components: app.harvester.companion.components.Components
    ): List<String> {
        val recentTerms = linkedMapOf<String, Pair<Long, String>>()
        try {
            val since = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
            val metadata = components.placesHistoryStorage.getHistoryMetadataSince(since)
            metadata.asSequence()
                .filter { !it.key.searchTerm.isNullOrBlank() }
                .forEach { item ->
                    val cleaned = SearchHistoryUtils.stripEngineSuffix(item.key.searchTerm.orEmpty())
                    if (cleaned.isNotBlank()) {
                        val key = cleaned.lowercase()
                        val existing = recentTerms[key]
                        if (existing == null || item.createdAt > existing.first) {
                            recentTerms[key] = item.createdAt to cleaned
                        }
                    }
                }

            val visits = components.placesHistoryStorage.getDetailedVisits(since, System.currentTimeMillis())
            visits.asSequence()
                .filter { SearchHistoryUtils.isSearchUrl(context, it.url) }
                .forEach { visit ->
                    val parsed = SearchHistoryUtils.extractSearchQuery(context, visit.url, visit.title)
                    if (!parsed.isNullOrBlank()) {
                        val key = parsed.lowercase()
                        val existing = recentTerms[key]
                        if (existing == null || visit.visitTime > existing.first) {
                            recentTerms[key] = visit.visitTime to parsed
                        }
                    }
                }
        } catch (e: Exception) {
            UHLog.e("search_error", "Failed to fetch recent searches: ${e.message}")
        }
        return recentTerms.values
            .sortedByDescending { it.first }
            .map { it.second }
            .take(5)
    }

    private fun fetchRegionalGoogleTrends(): List<String> {
        val rawCountry = Locale.getDefault().country.trim().uppercase(Locale.US)
        val geo = if (rawCountry.length == 2 && rawCountry.all { it in 'A'..'Z' }) rawCountry else "US"
        val candidates = linkedSetOf(
            "https://trends.google.com/trending/rss?geo=$geo",
            "https://trends.google.com/trends/trendingsearches/daily/rss?geo=$geo",
        )
        if (geo != "US") {
            candidates.add("https://trends.google.com/trending/rss?geo=US")
        }

        val now = System.currentTimeMillis()
        for (endpoint in candidates) {
            val cached = trendsCache[endpoint]
            if (cached != null && (now - cached.timestampMs) < TRENDS_TTL_MS && cached.items.isNotEmpty()) {
                return cached.items
            }
            val parsed = fetchTrendingRss(endpoint)
            if (parsed.isNotEmpty()) {
                trendsCache[endpoint] = CachedTrends(now, parsed)
                return parsed
            }
        }
        return emptyList()
    }

    private fun fetchTrendingRss(endpoint: String): List<String> {
        return try {
            val req = Request.Builder()
                .url(endpoint)
                .header("User-Agent", UhHttpClient.DEFAULT_USER_AGENT)
                .header("Accept", "application/rss+xml, application/xml, text/xml;q=0.9, */*;q=0.8")
                .header("Accept-Language", Locale.getDefault().toLanguageTag().ifBlank { "en-US" })
                .build()
            fastSearchClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    UHLog.w("search_warn", "Google Trends RSS ($endpoint) returned HTTP ${resp.code}")
                    return emptyList()
                }
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return emptyList()
                parseGoogleTrendsRss(body)
            }
        } catch (e: Exception) {
            UHLog.w("search_warn", "Google Trends fetch failed ($endpoint): ${e.message}")
            emptyList()
        }
    }

    private fun parseGoogleTrendsRss(xml: String): List<String> {
        val trimmed = xml.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (!trimmed.startsWith("<")) return emptyList()
        val out = mutableListOf<String>()
        val itemRegex = Regex("<item\\b[^>]*>([\\s\\S]*?)</item>", RegexOption.IGNORE_CASE)
        val newsItemRegex = Regex("<ht:news_item\\b[^>]*>[\\s\\S]*?</ht:news_item>", RegexOption.IGNORE_CASE)
        val titleRegex = Regex("<title\\b[^>]*>(?:<!\\[CDATA\\[)?([\\s\\S]*?)(?:\\]\\]>)?</title>", RegexOption.IGNORE_CASE)

        for (match in itemRegex.findAll(trimmed)) {
            // Strip nested <ht:news_item> blocks so we ONLY extract the real top-level trending search term
            val cleanItem = newsItemRegex.replace(match.groupValues[1], "")
            val titleMatch = titleRegex.find(cleanItem) ?: continue
            val decoded = Html.fromHtml(titleMatch.groupValues[1], Html.FROM_HTML_MODE_LEGACY)
                .toString()
                .replace(Regex("\\s+"), " ")
                .trim()
            if (decoded.length in 2..48 &&
                !decoded.equals("Daily Search Trends", ignoreCase = true) &&
                !decoded.equals("Trending Searches", ignoreCase = true) &&
                !decoded.startsWith("http", ignoreCase = true)
            ) {
                out.add(decoded)
            }
        }
        return out.distinctBy { it.lowercase(Locale.ROOT) }.take(8)
    }
}
