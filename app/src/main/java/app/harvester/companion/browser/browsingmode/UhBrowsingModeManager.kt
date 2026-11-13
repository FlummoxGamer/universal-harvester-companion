package app.harvester.companion.browser.browsingmode

enum class BrowsingMode {
    Normal,
    Private;

    val isPrivate get() = this == Private
}

class UhBrowsingModeManager {
    var mode: BrowsingMode = BrowsingMode.Normal
}
