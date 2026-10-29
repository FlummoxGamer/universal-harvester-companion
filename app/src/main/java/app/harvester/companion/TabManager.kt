package app.harvester.companion

import android.content.Context
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings

class TabManager private constructor(private val ctx: Context) {

    companion object {
        @Volatile private var INSTANCE: TabManager? = null
        fun get(ctx: Context): TabManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TabManager(ctx.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private class Slot(val session: GeckoSession, val incognito: Boolean) {
        var canGoBack: Boolean = false
        var canGoForward: Boolean = false
    }

    private val db = TabDb(ctx)
    private val alive = LinkedHashMap<Long, Slot>(16, 0.75f, true)
    private val tabHistory = mutableListOf<Long>()
    private val lock = Any()
    private var currentId: Long = -1L
    private val attachedSessions = mutableSetOf<GeckoSession>()

    init {
        currentId = ctx.getSharedPreferences("uh_tabs", Context.MODE_PRIVATE).getLong("last_tab_id", -1L)
    }

    fun pushHistory(id: Long) {
        if (tabHistory.isNotEmpty() && tabHistory.last() == id) return
        tabHistory.add(id)
        if (tabHistory.size > 20) tabHistory.removeAt(0)
    }

    fun popHistory(): Long? {
        if (tabHistory.isEmpty()) return null
        tabHistory.removeAt(tabHistory.size - 1)
        return tabHistory.lastOrNull()
    }

    fun setCurrentId(id: Long) {
        synchronized(lock) {
            currentId = id
            ctx.getSharedPreferences("uh_tabs", Context.MODE_PRIVATE).edit().putLong("last_tab_id", id).apply()
        }
    }

    fun createUnopenedSession(incognito: Boolean = false, groupId: Long = 0L, parentId: Long = 0L): Pair<Long, GeckoSession> {
        synchronized(lock) {
            val id = db.insert("about:blank", incognito, groupId, parentId)
            val settings = GeckoSessionSettings.Builder()
                .usePrivateMode(incognito)
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .build()
            val session = GeckoSession(settings)
            alive[id] = Slot(session, incognito)
            return Pair(id, session)
        }
    }

    fun openNew(url: String, incognito: Boolean = false, groupId: Long = 0L, parentId: Long = 0L): Long {
        synchronized(lock) {
            val id = db.insert(url, incognito, groupId, parentId)
            val runtime = GeckoEngine.getRuntime(ctx) ?: return id
            val settings = GeckoSessionSettings.Builder()
                .usePrivateMode(incognito)
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .build()
            val session = GeckoSession(settings)
            session.open(runtime)
            
            alive[id] = Slot(session, incognito)
            currentId = id
            if (url.isNotBlank() && url != "about:blank") session.loadUri(url)
            return id
        }
    }

    fun openBlankTab(incognito: Boolean = false, groupId: Long = 0L, parentId: Long = 0L): Long =
        openNew("about:blank", incognito, groupId, parentId)

    fun acquireSession(id: Long): GeckoSession? {
        synchronized(lock) {
            alive[id]?.let { currentId = id; return it.session }
            val entity = db.list().firstOrNull { it.id == id }
            val incognito = entity?.incognito ?: false
            val runtime = GeckoEngine.getRuntime(ctx) ?: return null
            
            val settings = GeckoSessionSettings.Builder()
                .usePrivateMode(incognito)
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .build()
            val session = GeckoSession(settings)
            session.open(runtime)
            
            alive[id] = Slot(session, incognito)
            currentId = id
            
            val savedState = db.loadState(id)
            val fallback = entity?.url
            if (savedState != null) {
                try {
                    val stateObj = org.mozilla.geckoview.GeckoSession.SessionState.fromString(savedState)
                    if (stateObj != null) {
                        session.restoreState(stateObj)
                    } else if (!fallback.isNullOrBlank() && fallback != "about:blank") {
                        session.loadUri(fallback)
                    }
                } catch (_: Exception) {
                    if (!fallback.isNullOrBlank() && fallback != "about:blank") session.loadUri(fallback)
                }
            } else if (!fallback.isNullOrBlank() && fallback != "about:blank") {
                session.loadUri(fallback)
            }
            return session
        }
    }

    fun currentId(): Long = synchronized(lock) { currentId }
    
    fun currentSession(): GeckoSession? = synchronized(lock) { alive[currentId]?.session }
    
    fun currentIsIncognito(): Boolean = synchronized(lock) { alive[currentId]?.incognito ?: false }

    fun idForSession(session: GeckoSession): Long = synchronized(lock) {
        alive.entries.firstOrNull { it.value.session === session }?.key ?: -1L
    }
    
    fun markAttached(session: GeckoSession): Boolean = synchronized(lock) {
        attachedSessions.add(session)
    }

    fun canGoBack(session: GeckoSession): Boolean = synchronized(lock) {
        alive.entries.firstOrNull { it.value.session === session }?.value?.canGoBack ?: false
    }
    
    fun canGoForward(session: GeckoSession): Boolean = synchronized(lock) {
        alive.entries.firstOrNull { it.value.session === session }?.value?.canGoForward ?: false
    }

    fun list(): List<TabEntity> = db.list()
    
    fun listNormal(): List<TabEntity> = db.list().filter { !it.incognito }
    
    fun listIncognito(): List<TabEntity> = db.list().filter { it.incognito }
    
    fun listByGroup(groupId: Long): List<TabEntity> = db.list().filter { it.groupId == groupId }
    
    fun listUngrouped(): List<TabEntity> = db.list().filter { it.groupId == 0L }

    fun hasIncognito(): Boolean = listIncognito().isNotEmpty()

    fun updateUrl(id: Long, url: String, title: String?) = db.update(id, url, title)

    fun saveState(id: Long, stateStr: String) = db.saveState(id, stateStr)
    fun loadState(id: Long): String? = db.loadState(id)

    fun updateGroup(id: Long, groupId: Long) {
        try {
            val cv = android.content.ContentValues().apply { put("group_id", groupId) }
            db.writableDatabase.update("tabs", cv, "id=?", arrayOf(id.toString()))
        } catch (_: Exception) {}
    }

    fun setNav(session: GeckoSession, back: Boolean, forward: Boolean) {
        synchronized(lock) {
            val slot = alive.entries.firstOrNull { it.value.session === session }?.value ?: return
            slot.canGoBack = back
            slot.canGoForward = forward
        }
    }

    fun close(id: Long) {
        synchronized(lock) {
            val slot = alive.remove(id)
            db.delete(id)
            if (slot != null) {
                attachedSessions.remove(slot.session)
                try { slot.session.close() } catch (_: Exception) {}
                if (id == currentId) {
                    currentId = db.list().firstOrNull()?.id ?: -1L
                }
            }
        }
    }

    fun closeAll() {
        synchronized(lock) {
            for (slot in alive.values) {
                try { slot.session.close() } catch (_: Exception) {}
            }
            alive.clear()
            attachedSessions.clear()
            db.clear()
            currentId = -1L
        }
    }

    fun closeAllIncognito() {
        synchronized(lock) {
            val incognitoIds = db.list().filter { it.incognito }.map { it.id }
            for (id in incognitoIds) {
                val slot = alive.remove(id) ?: continue
                attachedSessions.remove(slot.session)
                try { slot.session.close() } catch (_: Exception) {}
                db.delete(id)
            }
            if (currentId in incognitoIds) {
                currentId = db.list().firstOrNull()?.id ?: -1L
            }
        }
    }
    
    fun evictForMemory(keepCount: Int) {
        val victims: List<Long>
        synchronized(lock) {
            if (alive.size <= keepCount) return
            val keepId = currentId
            val out = mutableListOf<Long>()
            for (id in alive.keys) {
                if (id == keepId) continue
                if (alive.size - out.size <= keepCount) break
                out.add(id)
            }
            victims = out
        }
        if (victims.isEmpty()) return
        synchronized(lock) {
            for (id in victims) {
                val slot = alive.remove(id) ?: continue
                attachedSessions.remove(slot.session)
                try { slot.session.close() } catch (_: Exception) {}
            }
        }
    }

    fun saveCurrentState() {}
    fun saveAll() {}

    fun detachDelegates() {
        synchronized(lock) {
            for (slot in alive.values) {
                try {
                    slot.session.promptDelegate = null
                    slot.session.contentDelegate = null
                    slot.session.navigationDelegate = null
                    slot.session.progressDelegate = null
                    slot.session.historyDelegate = null
                    slot.session.contentBlockingDelegate = null
                    slot.session.permissionDelegate = null
                } catch (_: Exception) {}
            }
        }
    }

    fun destroyAllSessions() {
        synchronized(lock) {
            for (slot in alive.values) {
                try {
                    slot.session.promptDelegate = null
                    slot.session.contentDelegate = null
                    slot.session.navigationDelegate = null
                    slot.session.progressDelegate = null
                    slot.session.historyDelegate = null
                    slot.session.contentBlockingDelegate = null
                    slot.session.permissionDelegate = null
                    slot.session.close()
                } catch (_: Exception) {}
            }
            alive.clear()
            attachedSessions.clear()
        }
    }

    fun updateTheme(isDark: Boolean) {
        synchronized(lock) {
            try {
                val runtimeSettings = GeckoEngine.getRuntime(ctx)?.settings
                if (runtimeSettings != null) {
                    runtimeSettings.preferredColorScheme = if (isDark) {
                        org.mozilla.geckoview.GeckoRuntimeSettings.COLOR_SCHEME_DARK
                    } else {
                        org.mozilla.geckoview.GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
                    }
                }
            } catch (_: Exception) {}
        }
    }
}
}
