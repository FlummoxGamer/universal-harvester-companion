package app.harvester.companion.components.appstate

enum class BrowsingMode {
    Normal,
    Private;

    val isPrivate get() = this == Private

    companion object {
        fun fromBoolean(isPrivate: Boolean) = if (isPrivate) Private else Normal
    }
}
