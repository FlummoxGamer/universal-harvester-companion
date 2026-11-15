package app.harvester.companion.search.model

import android.graphics.Bitmap

sealed class UhSearchListItem {
    data class Header(val title: String) : UhSearchListItem()
    data class Suggestion(val title: String, val type: SuggestionType) : UhSearchListItem()
    data class History(val title: String, val url: String, val icon: Bitmap?) : UhSearchListItem()
    data class CurrentTab(val title: String, val url: String, val icon: Bitmap?) : UhSearchListItem()
}

enum class SuggestionType {
    SEARCH,
    TRENDING,
    HISTORY
}
