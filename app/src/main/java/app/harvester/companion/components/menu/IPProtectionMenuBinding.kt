package app.harvester.companion.components.menu

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import mozilla.components.feature.ipprotection.store.IPProtectionStore
import mozilla.components.feature.ipprotection.store.state.IPProtectionState
import mozilla.components.feature.ipprotection.store.state.UsageQuota
import mozilla.components.feature.ipprotection.store.state.ipProtectionStatus
import mozilla.components.lib.state.helpers.AbstractBinding
import app.harvester.companion.components.menu.store.IPProtectionMenuState

class IPProtectionMenuBinding(
    ipProtectionStore: IPProtectionStore,
    private val onIPProtectionStatusUpdate: (IPProtectionMenuState) -> Unit,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : AbstractBinding<IPProtectionState>(ipProtectionStore, mainDispatcher) {

    override suspend fun onState(flow: Flow<IPProtectionState>) {
        flow
            .map { it.toMenuState() }
            .distinctUntilChanged()
            .collect {
                onIPProtectionStatusUpdate(it)
            }
    }
}

internal fun IPProtectionState.toMenuState() =
    IPProtectionMenuState(
        status = ipProtectionStatus,
        dataLimit = (usageQuota as? UsageQuota.Metered)?.max,
    )
