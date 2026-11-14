package app.harvester.companion.components.menu.store

enum class IPProtectionStatus { Disabled, Enabled }

data class IPProtectionMenuState(
    val status: IPProtectionStatus = IPProtectionStatus.Disabled,
    val dataLimit: Any? = null,
)
