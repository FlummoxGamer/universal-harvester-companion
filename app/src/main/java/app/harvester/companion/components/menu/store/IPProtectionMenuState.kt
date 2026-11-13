package app.harvester.companion.components.menu.store

import mozilla.components.feature.ipprotection.store.state.DataVolume
import mozilla.components.feature.ipprotection.store.state.IPProtectionStatus

data class IPProtectionMenuState(
    val status: IPProtectionStatus = IPProtectionStatus.Uninitialized,
    val dataLimit: DataVolume? = null,
)
