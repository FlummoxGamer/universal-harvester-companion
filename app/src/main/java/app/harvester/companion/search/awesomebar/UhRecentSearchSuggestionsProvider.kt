package app.harvester.companion.search.awesomebar

import android.graphics.Bitmap
import java.util.UUID
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.search.SearchEngine
import app.harvester.companion.browser.storage.sync.PlacesHistoryStorage
import mozilla.components.concept.awesomebar.AwesomeBar
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.storage.HistoryMetadata
import mozilla.components.feature.search.SearchUseCases.SearchUseCase
import mozilla.components.feature.search.ext.buildSearchUrl

const val DEFAULT_RECENT_SEARCH_SUGGESTION_LIMIT = 5
private const val SEARCH_TERMS_MAXIMUM_ALLOWED_SUGGESTIONS_LIMIT = 1000
private const val MAXIMUM_ALLOWED_SUGGESTIONS_LIMIT_REACHED = "Cannot show more than $SEARCH_TERMS_MAXIMUM_ALLOWED_SUGGESTIONS_LIMIT suggestions."

class UhRecentSearchSuggestionsProvider(
    private val historyStorage: PlacesHistoryStorage,
    private val searchUseCase: SearchUseCase,
    private val searchEngine: SearchEngine?,
    private val maxNumberOfSuggestions: Int = DEFAULT_RECENT_SEARCH_SUGGESTION_LIMIT,
    private val icon: Bitmap? = null,
    private val engine: Engine? = null,
    private val showEditSuggestion: Boolean = true,
    private val suggestionsHeader: String? = null,
) : AwesomeBar.SuggestionProvider {
    init {
        if (maxNumberOfSuggestions > SEARCH_TERMS_MAXIMUM_ALLOWED_SUGGESTIONS_LIMIT) {
            throw IllegalArgumentException(MAXIMUM_ALLOWED_SUGGESTIONS_LIMIT_REACHED)
        }
    }

    override val id: String = UUID.randomUUID().toString()

    override fun groupTitle(): String? {
        return suggestionsHeader
    }

    override suspend fun onInputChanged(text: String): List<AwesomeBar.Suggestion> = coroutineScope {
        if (text.isNotBlank()) {
            return@coroutineScope emptyList()
        }

        historyStorage.cancelReads(text)
        val suggestions = withContext(this.coroutineContext) {
            historyStorage
                .getHistoryMetadataSince(Long.MIN_VALUE)
                .asSequence()
                .filter { it.totalViewTime > 0 }
                .filter { it.key.searchTerm?.startsWith(text) ?: false }
                .distinctBy { it.key.searchTerm }
                .sortedByDescending { it.createdAt }
                .take(maxNumberOfSuggestions)
                .toList()
        }

        searchEngine?.let {
            suggestions.firstOrNull()?.key?.searchTerm?.let { searchTerm ->
                engine?.speculativeConnect(it.buildSearchUrl(searchTerm))
            }
        }

        return@coroutineScope suggestions.into(
            provider = this@UhRecentSearchSuggestionsProvider,
            searchEngine = searchEngine,
            icon = icon,
            searchUseCase = searchUseCase,
            showEditSuggestion = showEditSuggestion,
        )
    }
}

private fun Iterable<HistoryMetadata>.into(
    provider: AwesomeBar.SuggestionProvider,
    searchEngine: SearchEngine?,
    icon: Bitmap?,
    searchUseCase: SearchUseCase,
    showEditSuggestion: Boolean = true,
): List<AwesomeBar.Suggestion> {
    return this.mapIndexedNotNull { index, result ->
        val safeSearchTerm = result.key.searchTerm ?: return@mapIndexedNotNull null

        AwesomeBar.Suggestion(
            provider = provider,
            icon = icon ?: searchEngine?.icon,
            title = result.key.searchTerm,
            description = null,
            editSuggestion = if (showEditSuggestion) safeSearchTerm else null,
            score = Int.MAX_VALUE - (index + 2),
            onSuggestionClicked = {
                searchUseCase.invoke(safeSearchTerm)
            },
        )
    }
}
