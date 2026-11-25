package app.harvester.companion.browser.tabs

import android.content.Context
import app.harvester.companion.SettingsPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.concept.engine.mediasession.MediaSession
import org.json.JSONArray
import java.net.URI

enum class TabSortMode(val code: Int, val label: String) {
    MANUAL(0, "Manual order"),
    RECENT(1, "Most recently used"),
    OLDEST(2, "Oldest first"),
    TITLE_AZ(3, "Title (A–Z)"),
    DOMAIN(4, "Website (A–Z)");

    companion object {
        fun fromCode(code: Int): TabSortMode = entries.firstOrNull { it.code == code } ?: MANUAL
    }
}

/**
 * Shared tab metadata store:
 * 1) Persisted Pinned Tabs (Normal tabs only, shielded from Close All, Inactive, & Auto-Close).
 * 2) Persisted Tab Sort Mode.
 * 3) RAM-only Per-Tab Audio Mute state synchronized with Gecko MediaSession.Controller.muteAudio.
 */
object TabMetaStore {
    private const val PREFS_NAME = "uh_tab_meta"
    private const val KEY_PINNED_IDS = "pinned_tab_ids_json"

    @Volatile
    private var appContext: Context? = null
    private val lock = Any()

    private val _pinnedIdsFlow = MutableStateFlow<List<String>>(emptyList())
    val pinnedIdsFlow: StateFlow<List<String>> = _pinnedIdsFlow.asStateFlow()

    private val _sortModeFlow = MutableStateFlow(TabSortMode.MANUAL)
    val sortModeFlow: StateFlow<TabSortMode> = _sortModeFlow.asStateFlow()

    private val _mutedTabIdsFlow = MutableStateFlow<Set<String>>(emptySet())
    val mutedTabIdsFlow: StateFlow<Set<String>> = _mutedTabIdsFlow.asStateFlow()

    fun init(ctx: Context) {
        synchronized(lock) {
            if (appContext == null) {
                appContext = ctx.applicationContext
                _pinnedIdsFlow.value = readPinnedFromDisk(ctx.applicationContext)
                val savedSort = SettingsPrefs.getInt(ctx.applicationContext, SettingsPrefs.K_TAB_SORT_MODE, 0)
                _sortModeFlow.value = TabSortMode.fromCode(savedSort)
            }
        }
    }

    private fun readPinnedFromDisk(ctx: Context): List<String> {
        val raw = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PINNED_IDS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val id = arr.optString(i)
                if (id.isNotBlank()) list.add(id)
            }
            list.distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistPinnedLocked(ids: List<String>) {
        val clean = ids.distinct().filter { it.isNotBlank() }
        _pinnedIdsFlow.value = clean
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PINNED_IDS, JSONArray(clean).toString())
            .apply()
    }

    fun isPinned(tabId: String): Boolean = tabId in _pinnedIdsFlow.value

    fun pinTab(tabId: String) {
        if (tabId.isBlank()) return
        // Pinned tabs live in the dedicated top strip rather than inside a group
        TabGroupStore.removeTabFromGroups(tabId)
        synchronized(lock) {
            if (tabId !in _pinnedIdsFlow.value) {
                persistPinnedLocked(_pinnedIdsFlow.value + tabId)
            }
        }
    }

    fun unpinTab(tabId: String) {
        synchronized(lock) {
            if (tabId in _pinnedIdsFlow.value) {
                persistPinnedLocked(_pinnedIdsFlow.value.filterNot { it == tabId })
            }
        }
    }

    fun togglePin(tabId: String): Boolean {
        return if (isPinned(tabId)) {
            unpinTab(tabId)
            false
        } else {
            pinTab(tabId)
            true
        }
    }

    fun prunePinned(validNormalTabIds: Set<String>) {
        synchronized(lock) {
            val filtered = _pinnedIdsFlow.value.filter { it in validNormalTabIds }
            if (filtered.size != _pinnedIdsFlow.value.size) {
                persistPinnedLocked(filtered)
            }
        }
    }

    fun setSortMode(ctx: Context, mode: TabSortMode) {
        _sortModeFlow.value = mode
        SettingsPrefs.setInt(ctx.applicationContext, SettingsPrefs.K_TAB_SORT_MODE, mode.code)
    }

    fun isTabPlayingAudio(tab: TabSessionState): Boolean {
        return tab.mediaSessionState?.playbackState == MediaSession.PlaybackState.PLAYING
    }

    fun hasMediaSession(tab: TabSessionState): Boolean {
        val st = tab.mediaSessionState?.playbackState
        return st == MediaSession.PlaybackState.PLAYING || st == MediaSession.PlaybackState.PAUSED
    }

    fun isTabMuted(tab: TabSessionState): Boolean {
        return (tab.mediaSessionState?.muted == true) || (tab.id in _mutedTabIdsFlow.value)
    }

    fun isTabSleeping(tab: TabSessionState, selectedTabId: String?): Boolean {
        val url = tab.content.url
        return tab.id != selectedTabId &&
            tab.engineState.engineSession == null &&
            url.isNotBlank() &&
            url != "about:blank"
    }

    fun toggleMute(tab: TabSessionState): Boolean {
        val targetMute = !isTabMuted(tab)
        setTabMuted(tab, targetMute)
        return targetMute
    }

    fun setTabMuted(tab: TabSessionState, muted: Boolean) {
        try {
            tab.mediaSessionState?.controller?.muteAudio(muted)
        } catch (_: Exception) {}
        synchronized(lock) {
            _mutedTabIdsFlow.value = if (muted) {
                _mutedTabIdsFlow.value + tab.id
            } else {
                _mutedTabIdsFlow.value - tab.id
            }
        }
    }

    fun pruneMuted(validTabIds: Set<String>) {
        synchronized(lock) {
            val filtered = _mutedTabIdsFlow.value.filterTo(mutableSetOf()) { it in validTabIds }
            if (filtered.size != _mutedTabIdsFlow.value.size) {
                _mutedTabIdsFlow.value = filtered
            }
        }
    }
}

internal fun extractHostLabel(rawUrl: String): String {
    if (rawUrl.isBlank() || rawUrl == "about:blank") return ""
    return try {
        URI(rawUrl).host?.removePrefix("www.") ?: rawUrl
    } catch (_: Exception) {
        rawUrl
    }
}

internal fun normalizeUrlForDedup(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank() || trimmed == "about:blank") return ""
    return try {
        val u = URI(trimmed)
        val scheme = (u.scheme ?: "https").lowercase()
        val host = (u.host ?: "").lowercase().removePrefix("www.")
        val path = (u.path ?: "").trimEnd('/')
        val query = if (u.query.isNullOrBlank()) "" else "?${u.query}"
        "$scheme://$host$path$query"
    } catch (_: Exception) {
        trimmed.substringBefore("#").trimEnd('/').lowercase()
    }
}
