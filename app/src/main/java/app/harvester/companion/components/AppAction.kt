package app.harvester.companion.components

import mozilla.components.lib.state.Action

sealed class AppAction : Action {
    data class ForegroundChanged(val isForeground: Boolean) : AppAction()
    data class BrowsingModeChanged(val mode: BrowsingMode) : AppAction()
}
