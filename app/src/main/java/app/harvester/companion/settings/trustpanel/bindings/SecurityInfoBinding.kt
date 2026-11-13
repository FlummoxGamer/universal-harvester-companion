package app.harvester.companion.settings.trustpanel.bindings

import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import mozilla.components.browser.state.selector.findTabOrCustomTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.lib.state.helpers.AbstractBinding
import mozilla.components.support.ktx.kotlinx.coroutines.flow.ifAnyChanged
import app.harvester.companion.settings.trustpanel.store.TrustPanelAction

class SecurityInfoBinding(
    browserStore: BrowserStore,
    private val sessionId: String,
    private val urlHost: String?,
    private val onSecurityInfoUpdate: (TrustPanelAction.UpdateSecurityInfo) -> Unit,
    private val onHostChange: () -> Unit,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : AbstractBinding<BrowserState>(browserStore, mainDispatcher) {

    override suspend fun onState(flow: Flow<BrowserState>) {
        flow
            .mapNotNull { state -> state.findTabOrCustomTab(sessionId)?.content }
            .ifAnyChanged { content -> arrayOf(content.url, content.securityInfo) }
            .collect { content ->
                if (content.url.toUri().host != urlHost) {
                    onHostChange()
                    return@collect
                }
                onSecurityInfoUpdate(
                    TrustPanelAction.UpdateSecurityInfo(
                        isSecured = content.securityInfo.isSecure,
                        certificate = content.securityInfo.certificate,
                    )
                )
            }
    }
}
