package app.harvester.companion.components

import mozilla.components.lib.state.State

data class AppState(
    val isForeground: Boolean = false,
    val browsingMode: BrowsingMode = BrowsingMode.Normal,
) : State

enum class BrowsingMode { Normal, Private }
