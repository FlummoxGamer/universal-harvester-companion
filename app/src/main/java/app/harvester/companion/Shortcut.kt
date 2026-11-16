package app.harvester.companion

import java.util.UUID

data class Shortcut(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val iconUrl: String
)
