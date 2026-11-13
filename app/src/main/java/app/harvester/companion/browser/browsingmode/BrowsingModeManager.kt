package app.harvester.companion.browser.browsingmode

enum class BrowsingMode { Normal, Private }

class BrowsingModeManager {
    var mode: BrowsingMode = BrowsingMode.Normal
}
