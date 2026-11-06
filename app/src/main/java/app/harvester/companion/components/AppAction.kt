package app.harvester.companion.components

sealed class AppAction {
    data class ForegroundChanged(val isForeground: Boolean) : AppAction()
    data class BrowsingModeChanged(val mode: BrowsingMode) : AppAction()
}
