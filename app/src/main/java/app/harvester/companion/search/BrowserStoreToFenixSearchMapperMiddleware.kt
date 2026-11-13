package app.harvester.companion.search

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.compose.browser.awesomebar.internal.CurrentTabData
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import mozilla.components.lib.state.ext.flow

class BrowserStoreToFenixSearchMapperMiddleware(
    private val browserStore: BrowserStore,
    private val scope: CoroutineScope,
    private val appStore: app.harvester.companion.components.AppStore? = null,
) : Middleware<SearchFragmentState, SearchFragmentAction> {
    override fun invoke(
        store: Store<SearchFragmentState, SearchFragmentAction>,
        next: (SearchFragmentAction) -> Unit,
        action: SearchFragmentAction,
    ) {
        next(action)
        if (action is SearchFragmentAction.Init) {
            scope.launch {
                appStore?.flow()?.map { state ->
                    state.searchState.sourceTabId.takeIf { state.searchState.isSearchActive }
                }?.distinctUntilChanged()?.collect { tabId ->
                    val tab = if (tabId != null) browserStore.state.findTab(tabId) else null
                    store.dispatch(SearchFragmentAction.SearchSourceTabUpdated(tabId, tab?.let { CurrentTabData(it.content.title, it.content.url, it.content.icon) }))
                }
            }
            scope.launch {
                browserStore.flow().map { it.search }.distinctUntilChanged().collect { searchState ->
                    store.dispatch(SearchFragmentAction.UpdateSearchState(searchState, appStore?.state?.mode?.isPrivate ?: false))
                }
            }
        }
    }
}
