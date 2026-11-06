package app.harvester.companion.components

data class AppState(
    val isForeground: Boolean = false,
    val browsingMode: BrowsingMode = BrowsingMode.Normal,
)

enum class BrowsingMode { Normal, Private }
