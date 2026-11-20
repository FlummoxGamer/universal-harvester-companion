package app.harvester.companion.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.harvester.companion.UHLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class UhSearchViewModel(application: Application) : AndroidViewModel(application) {
    private val store = UhSearchStore()
    val state: StateFlow<UhSearchState> = store.stateFlow
    private var fetchJob: Job? = null

    fun onQueryChanged(query: String, isBarBottom: Boolean) {
        store.dispatch(UhSearchAction.UpdateQuery(query))
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            try {
                val result = UhSearchDataProvider.fetchData(getApplication(), query)
                store.dispatch(UhSearchAction.UpdateResults(result.items, result.clipboardUrl, result.currentTab))
            } catch (e: Exception) {
                UHLog.throwable("search_error", "Failed updating search results", e)
            }
        }
    }

    fun clearQuery(isBarBottom: Boolean) {
        onQueryChanged("", isBarBottom)
    }
}
