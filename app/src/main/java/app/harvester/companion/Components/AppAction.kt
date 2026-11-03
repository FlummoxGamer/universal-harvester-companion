package app.harvester.companion.components

import mozilla.components.lib.state.Action

sealed class AppAction : Action {
    data class DesktopModeUpdated(val isActive: Boolean) : AppAction()
    data class TabGroupsUpdated(val groups: List<String>) : AppAction()
}
