package app.harvester.companion.components

import android.content.Context
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.search.SearchUseCases
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.tabs.TabsUseCases

class UseCases(
    context: Context,
    store: BrowserStore,
    engine: GeckoEngine
) {
    val sessionUseCases by lazy { SessionUseCases(store) }
    val tabsUseCases by lazy { TabsUseCases(store) }
    val searchUseCases by lazy { SearchUseCases(context, store, tabsUseCases, sessionUseCases) }
}
