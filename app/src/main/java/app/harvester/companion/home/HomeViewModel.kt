package app.harvester.companion.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.harvester.companion.Shortcut
import app.harvester.companion.ShortcutStore
import app.harvester.companion.WallpaperStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    private val _shortcuts = MutableStateFlow(ShortcutStore.list(context))
    val shortcuts: StateFlow<List<Shortcut>> = _shortcuts

    private val _wallpaperState = MutableStateFlow(WallpaperStore.wallpaperFlow(context).value)
    val wallpaperState: StateFlow<WallpaperStore.WallpaperState> = _wallpaperState

    init {
        viewModelScope.launch {
            WallpaperStore.wallpaperFlow(context).collect { wp ->
                _wallpaperState.value = wp
            }
        }
    }

    fun refresh() {
        _shortcuts.value = ShortcutStore.list(context)
        _wallpaperState.value = WallpaperStore.wallpaperFlow(context).value
    }

    fun moveShortcut(from: Int, to: Int) {
        val list = _shortcuts.value.toMutableList()
        if (from in list.indices && to in list.indices) {
            if (from < to) {
                for (i in from until to) {
                    java.util.Collections.swap(list, i, i + 1)
                }
            } else {
                for (i in from downTo to + 1) {
                    java.util.Collections.swap(list, i, i - 1)
                }
            }
            _shortcuts.value = list
            ShortcutStore.save(context, list)
        }
    }

    fun addShortcut(shortcut: Shortcut) {
        ShortcutStore.add(context, shortcut)
        refresh()
    }

    fun removeShortcut(shortcut: Shortcut) {
        ShortcutStore.remove(context, shortcut.url)
        refresh()
    }

    fun updateShortcut(old: Shortcut, new: Shortcut) {
        ShortcutStore.remove(context, old.url)
        ShortcutStore.add(context, new)
        refresh()
    }
}
