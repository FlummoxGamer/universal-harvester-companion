package app.harvester.companion.search

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Volatile RAM-only search history for the active Private session.
 * Never written to disk and wiped automatically whenever all Private tabs close
 * or the user taps "Clear Private Session".
 */
object PrivateSearchHistoryStore {
    private const val MAX_ITEMS = 25

    private val _queries = MutableStateFlow<List<String>>(emptyList())
    val queriesFlow: StateFlow<List<String>> = _queries.asStateFlow()

    @Synchronized
    fun record(rawQuery: String) {
        val clean = rawQuery.trim()
        if (clean.isBlank()) return
        if (clean.startsWith("http://", ignoreCase = true) ||
            clean.startsWith("https://", ignoreCase = true) ||
            clean == "about:blank"
        ) return
        val updated = buildList {
            add(clean)
            _queries.value.forEach { existing ->
                if (!existing.equals(clean, ignoreCase = true) && size < MAX_ITEMS) {
                    add(existing)
                }
            }
        }
        _queries.value = updated
    }

    fun recent(limit: Int = 8): List<String> = _queries.value.take(limit)

    fun matching(query: String, limit: Int = 5): List<String> {
        val q = query.trim()
        if (q.isBlank()) return recent(limit)
        return _queries.value.filter { it.contains(q, ignoreCase = true) }.take(limit)
    }

    @Synchronized
    fun clear() {
        _queries.value = emptyList()
    }
}
