package app.harvester.companion.components

import mozilla.components.lib.state.State

data class AppState(
    val desktopMode: DesktopModeState = DesktopModeState(),
    val tabGroups: TabGroupState = TabGroupState()
) : State

data class DesktopModeState(
    val isActive: Boolean = false
)

data class TabGroupState(
    val groups: List<String> = emptyList()
)
