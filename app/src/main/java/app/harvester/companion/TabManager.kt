package app.harvester.companion

import android.content.Context
import org.mozilla.geckoview.GeckoSession
import java.io.File

class TabManager private constructor(private val ctx: Context) {

    companion object {
        // FIX: 5 was too many for low-RAM devices. One live session at a time,
        // matching Firefox Focus's model. Restore from disk on switch (~300ms).
        private const val MAX_ALIVE = 1

        @Volatile private var INSTANCE: TabManager? = null

        fun get(ctx: Context): TabManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TabManager(ctx.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private class Slot(val session: GeckoSession) {
        var latestState: GeckoSession.SessionState? = null
        var canGoBack: Boolean = false
    }

    private val db = TabDb(ctx)
    private val alive = LinkedHashMap<Long, Slot>(MAX_ALIVE, 0.75f, true)
    // FIX: sessions scheduled for close but still referenced by GeckoView.
    // MainActivity calls drainPendingClose() after it reassigns the view.
    private val pendingClose = mutableListOf<GeckoSession>()
    private val lock = Any()
    private var currentId: Long = -1L

    private fun stateDir(): File = File(ctx.filesDir, "tabs").apply { mkdirs() }
    private fun stateFile(id: Long): File = File(stateDir(), "$id.state")

    private fun slotFor(session: GeckoSession): Slot? =
        alive.entries.firstOrNull { it.value.session === session }?.value

    private fun idFor(session: GeckoSession): Long =
        alive.entries.firstOrNull { it.value.session === session }?.key ?: -1L

    fun openNew(url: String): Long {
        synchronized(lock) {
            val id = db.insert(url)
            evictAllExcept(id)
            val s = GeckoEngine.newSession()
            alive[id] = Slot(s)
            currentId = id
            s.loadUri(url)
            return id
        }
    }

    fun switchTo(id: Long): GeckoSession? {
        synchronized(lock) {
            alive[id]?.let { currentId = id; return it.session }

            // FIX: restoreState before open. Read state from disk first, then
            // hand it to GeckoEngine.newSession which sequences it correctly.
            val restoredState: GeckoSession.SessionState? = try {
                val f = stateFile(id)
                if (f.exists()) GeckoSession.SessionState.fromString(f.readText()) else null
            } catch (e: Exception) {
                UHLog.w("tabs", "state parse failed id=$id ${e.message}")
                null
            }

            evictAllExcept(id)
            val s = GeckoEngine.newSession(restoreState = restoredState)
            alive[id] = Slot(s)
            currentId = id

            if (restoredState == null) {
                val fallback = db.list()
                    .firstOrNull { it.id == id }
                    ?.url
                    ?.takeIf { it.isNotBlank() }
                s.loadUri(fallback ?: "about:blank")
            }
            return s
        }
    }

    fun recordState(id: Long, state: GeckoSession.SessionState) {
        synchronized(lock) { alive[id]?.latestState = state }
    }

    fun idForSession(session: GeckoSession): Long = synchronized(lock) { idFor(session) }

    fun setCanGoBack(session: GeckoSession, value: Boolean) = synchronized(lock) {
        slotFor(session)?.canGoBack = value
    }

    fun canGoBack(session: GeckoSession): Boolean = synchronized(lock) {
        slotFor(session)?.canGoBack ?: false
    }

    fun currentId(): Long = synchronized(lock) { currentId }

    fun currentSession(): GeckoSession? = synchronized(lock) { alive[currentId]?.session }

    fun list(): List<TabEntity> = db.list()

    fun count(): Int = db.list().size

    fun updateUrl(id: Long, url: String, title: String?) {
        db.update(id, url, title)
    }

    fun close(id: Long) {
        synchronized(lock) {
            val slot = alive.remove(id)
            db.delete(id)
            try { stateFile(id).delete() } catch (_: Exception) {}
            if (slot != null) {
                if (id == currentId) {
                    // FIX: the session is probably still attached to GeckoView.
                    // Defer the native close until MainActivity reassigns the view.
                    pendingClose.add(slot.session)
                    currentId = db.list().firstOrNull()?.id ?: -1L
                } else {
                    try { slot.session.close() } catch (_: Exception) {}
                }
            }
        }
    }

    fun closeAll() {
        synchronized(lock) {
            // FIX: defer all closes, then let MainActivity drain after detaching.
            for (slot in alive.values) pendingClose.add(slot.session)
            alive.clear()
            db.clear()
            try { stateDir().deleteRecursively() } catch (_: Exception) {}
            currentId = -1L
        }
    }

    /**
     * FIX: called by MainActivity after view.setSession(newSession).
     * At this point GeckoView has detached from any previous session, so it is
     * safe to call native close() without risking a use-after-free.
     */
    fun drainPendingClose() {
        synchronized(lock) {
            if (pendingClose.isEmpty()) return
            for (s in pendingClose) {
                try { s.close() } catch (_: Exception) {}
            }
            UHLog.v("tabs", "drained ${pendingClose.size} pending closes")
            pendingClose.clear()
        }
    }

    fun saveAll() {
        synchronized(lock) {
            for ((id, slot) in alive) {
                try {
                    val s = slot.latestState ?: continue
                    stateFile(id).writeText(s.toString())
                } catch (e: Exception) {
                    UHLog.w("tabs", "save failed id=$id ${e.message}")
                }
            }
        }
    }

    // FIX: does NOT close sessions inline. Moves victims to pendingClose so
    // MainActivity can drain them safely after reassigning the GeckoView.
    private fun evictAllExcept(keepId: Long) {
        while (alive.size >= MAX_ALIVE) {
            val it = alive.entries.iterator()
            var victim: MutableMap.MutableEntry<Long, Slot>? = null
            while (it.hasNext()) {
                val entry = it.next()
                if (entry.key == keepId) continue
                victim = entry
                break
            }
            if (victim == null) break
            try {
                victim.value.latestState?.let { st ->
                    stateFile(victim.key).writeText(st.toString())
                }
            } catch (_: Exception) {}
            pendingClose.add(victim.value.session)
            it.remove()
        }
    }
}
