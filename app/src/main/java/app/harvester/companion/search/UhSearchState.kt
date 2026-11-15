package app.harvester.companion.search

import app.harvester.companion.search.model.UhSearchListItem
import mozilla.components.lib.state.State

data class UhSearchState(
    val query: String = "",
    val items: List<UhSearchListItem> = emptyList(),
    val clipboardUrl: String? = null,
    val isLoading: Boolean = false
) : State
