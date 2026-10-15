package app.harvester.companion

data class TabEntity(
    val id: Long,
    val url: String,
    val title: String,
    val position: Int,
    val incognito: Boolean = false,
    val groupId: Long = 0L
)
