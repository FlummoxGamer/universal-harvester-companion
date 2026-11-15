package app.harvester.companion.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
        val (items, clipboard) = UhSearchDataProvider.fetchData(getApplication(), query, isBarBottom)
        store.dispatch(UhSearchAction.UpdateResults(items, clipboard))
    }
}

fun clearQuery(isBarBottom: Boolean) {
    onQueryChanged("", isBarBottom)
}
}
