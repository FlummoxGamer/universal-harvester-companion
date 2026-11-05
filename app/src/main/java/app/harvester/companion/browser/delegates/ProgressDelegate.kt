package app.harvester.companion.browser.delegates

import app.harvester.companion.HistoryStore
import app.harvester.companion.UHLog
import app.harvester.companion.components.Components
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.store.BrowserStore
import org.mozilla.geckoview.GeckoSession

class ProgressDelegate(
    private val components: Components,
    private val store: BrowserStore
) : GeckoSession.ProgressDelegate {

    override fun onPageStart(session: GeckoSession, url: String) {
        UHLog.d("web", "start ${url.take(140)}")
    }

    override fun onPageStop(session: GeckoSession, success: Boolean) {
        val selectedTab = store.state.selectedTab
        val url = selectedTab?.content?.url ?: ""
        UHLog.d("web", "finish ${url.take(140)}")
        
        if (url != "about:blank" && selectedTab?.content?.private != true) {
            val title = selectedTab?.content?.title ?: ""
            HistoryStore.get(components.context).record(url, title)
        }
    }

    override fun onProgressChange(session: GeckoSession, progress: Int) {}
    override fun onSecurityChange(session: GeckoSession, securityInfo: GeckoSession.ProgressDelegate.SecurityInformation) {}
    override fun onSessionStateChange(session: GeckoSession, sessionState: GeckoSession.SessionState) {}
}
