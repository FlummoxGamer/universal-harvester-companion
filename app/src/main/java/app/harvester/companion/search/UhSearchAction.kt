package app.harvester.companion.search

import app.harvester.companion.search.model.UhSearchListItem
import mozilla.components.lib.state.Action

sealed class UhSearchAction : Action {
    data class UpdateQuery(val query: String) : UhSearchAction()
    data class UpdateResults(
        val items: List<UhSearchListItem>,
        val clipboardUrl: String?,
        val currentTab: UhSearchListItem.CurrentTab?
    ) : UhSearchAction()
    data object ClearQuery : UhSearchAction()
}
