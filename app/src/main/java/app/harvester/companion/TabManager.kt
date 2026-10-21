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

    // pushHistory
    fun pushHistory(id: Long) {
        if (tabHistory.isNotEmpty() && tabHistory.last() == id) return
        tabHistory.add(id)
        if (tabHistory.size > 20) tabHistory.removeAt(0)
    }

    // popHistory
    fun popHistory(): Long? {
        if (tabHistory.isEmpty()) return null
        tabHistory.removeAt(tabHistory.size - 1)
        return tabHistory.lastOrNull()
    }

    // setCurrentId
    fun setCurrentId(id: Long) {
        synchronized(lock) {
            currentId = id
        }
    }

    // createUnopenedSession
    fun createUnopenedSession(incognito: Boolean = false, groupId: Long = 0L, parentId: Long = 0L): Pair<Long, GeckoSession> {
        synchronized(lock) {
            val id = db.insert("about:blank", incognito, groupId, parentId)
            val settings = GeckoSessionSettings.Builder()
                .usePrivateMode(incognito)
                .build()
            val session = GeckoSession(settings)
            alive[id] = Slot(session, incognito)
            return Pair(id, session)
        }
    }

    // openNew
    fun openNew(url: String, incognito: Boolean = false, groupId: Long = 0L, parentId: Long = 0L): Long {
        synchronized(lock) {
            val id = db.insert(url, incognito, groupId, parentId)
            val runtime = GeckoEngine.getRuntime(ctx) ?: return id
            val settings = GeckoSessionSettings.Builder()
                .usePrivateMode(incognito)
                .build()
            val session = GeckoSession(settings)
            session.open(runtime)
            
            alive[id] = Slot(session, incognito)
            currentId = id
            if (url.isNotBlank() && url != "about:blank") session.loadUri(url)
            return id
        }
    }

    // openBlankTab
    fun openBlankTab(incognito: Boolean = false, groupId: Long = 0L, parentId: Long = 0L): Long =
        openNew("about:blank", incognito, groupId, parentId)

    // acquireSession
    fun acquireSession(id: Long): GeckoSession? {
        synchronized(lock) {
            alive[id]?.let { currentId = id; return it.session }
            val entity = db.list().firstOrNull { it.id == id }
            val incognito = entity?.incognito ?: false
            val runtime = GeckoEngine.getRuntime(ctx) ?: return null
            
            val settings = GeckoSessionSettings.Builder()
                .usePrivateMode(incognito)
                .build()
            val session = GeckoSession(settings)
            session.open(runtime)
            
            alive[id] = Slot(session, incognito)
            currentId = id
            val fallback = entity?.url
            if (!fallback.isNullOrBlank() && fallback != "about:blank") session.loadUri(fallback)
            return session
        }
    }

    // currentId
    fun currentId(): Long = synchronized(lock) { currentId }
    
    // currentSession
    fun currentSession(): GeckoSession? = synchronized(lock) { alive[currentId]?.session }
    
    // currentIsIncognito
    fun currentIsIncognito(): Boolean = synchronized(lock) { alive[currentId]?.incognito ?: false }

    // idForSession
    fun idForSession(session: GeckoSession): Long = synchronized(lock) {
        alive.entries.firstOrNull { it.value.session === session }?.key ?: -1L
    }
    
    // markAttached
    fun markAttached(session: GeckoSession): Boolean = synchronized(lock) {
        attachedSessions.add(session)
    }

    // canGoBack
    fun canGoBack(session: GeckoSession): Boolean = synchronized(lock) {
        alive.entries.firstOrNull { it.value.session === session }?.value?.canGoBack ?: false
    }
    
    // canGoForward
    fun canGoForward(session: GeckoSession): Boolean = synchronized(lock) {
        alive.entries.firstOrNull { it.value.session === session }?.value?.canGoForward ?: false
    }

    // list
    fun list(): List<TabEntity> = db.list()
    
    // listNormal
    fun listNormal(): List<TabEntity> = db.list().filter { !it.incognito }
    
    // listIncognito
    fun listIncognito(): List<TabEntity> = db.list().filter { it.incognito }
    
    // listByGroup
    fun listByGroup(groupId: Long): List<TabEntity> = db.list().filter { it.groupId == groupId }
    
    // listUngrouped
    fun listUngrouped(): List<TabEntity> = db.list().filter { it.groupId == 0L }

    // hasIncognito
    fun hasIncognito(): Boolean = listIncognito().isNotEmpty()

    // updateUrl
    fun updateUrl(id: Long, url: String, title: String?) = db.update(id, url, title)

    // updateGroup
    fun updateGroup(id: Long, groupId: Long) {
        try {
            val cv = android.content.ContentValues().apply { put("group_id", groupId) }
            db.writableDatabase.update("tabs", cv, "id=?", arrayOf(id.toString()))
        } catch (_: Exception) {}
    }

    // setNav
    fun setNav(session: GeckoSession, back: Boolean, forward: Boolean) {
        synchronized(lock) {
            val slot = alive.entries.firstOrNull { it.value.session === session }?.value ?: return
            slot.canGoBack = back
            slot.canGoForward = forward
        }
    }

    // close
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

    // closeAll
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

    // closeAllIncognito
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
    
    // evictForMemory
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

    // saveCurrentState
    fun saveCurrentState() {}
    
    // saveAll
    fun saveAll() {}
}
