package app.harvester.companion.components.appstate

sealed class SupportedMenuNotifications {
    object Downloads : SupportedMenuNotifications()
    object OpenInApp : SupportedMenuNotifications()
    object NotDefaultBrowser : SupportedMenuNotifications()
    data object Summarize : SupportedMenuNotifications()
    data object ExtensionUpdate : SupportedMenuNotifications()
}
