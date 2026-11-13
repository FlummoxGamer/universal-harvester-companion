package app.harvester.companion.search

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.browser.state.selector.findTab
import mozilla.components.compose.browser.awesomebar.internal.CurrentTabData
import mozilla.components.compose.browser.awesomebar.internal.CurrentTabData
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import mozilla.components.lib.state.ext.flow

class UhBrowserToSearchMiddleware(
    private val browserStore: BrowserStore,
    private val scope: CoroutineScope,
    private val appStore: app.harvester.companion.components.AppStore? = null,
) : Middleware<UhSearchState, UhSearchAction> {
    override fun invoke(
        store: Store<UhSearchState, UhSearchAction>,
        next: (UhSearchAction) -> Unit,
        action: UhSearchAction,
    ) {
        next(action)
        if (action is UhSearchAction.Init) {
            scope.launch {
                appStore?.flow()?.map { state ->
                    state.searchState.sourceTabId.takeIf { state.searchState.isSearchActive }
                }?.distinctUntilChanged()?.collect { tabId ->
                    val tab = if (tabId != null) browserStore.state.findTab(tabId) else null
                    store.dispatch(UhSearchAction.SearchSourceTabUpdated(tabId, tab?.let { CurrentTabData(it.content.title, it.content.url, it.content.icon) }))
                }
            }
            scope.launch {
                browserStore.flow().map { it.search }.distinctUntilChanged().collect { searchState ->
                    store.dispatch(UhSearchAction.UpdateSearchState(searchState, appStore?.state?.mode?.isPrivate ?: false))
                }
            }
        }
    }
}
