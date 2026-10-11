package app.harvester.companion

import android.content.Context
import org.mozilla.geckoview.GeckoSession
import java.io.File

// B1: idForSession + per-session canGoBack state.
// B2: switchTo no longer wipes restored state.
// Also made singleton so sessions survive Activity recreation.
class TabManager private constructor(private val ctx: Context) {

    companion object {
        private const val MAX_ALIVE = 5

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
            evictIfNeeded()
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

            evictIfNeeded()
            val s = GeckoEngine.newSession()

            // B2: only load a fallback URL if restore actually failed.
            var restored = false
            val f = stateFile(id)
            if (f.exists()) {
                try {
                    val st = GeckoSession.SessionState.fromString(f.readText())
                    if (st != null) {
                        s.restoreState(st)
                        restored = true
                        UHLog.v("tabs", "restored id=$id")
                    }
                } catch (e: Exception) {
                    UHLog.w("tabs", "restore failed id=$id ${e.message}")
                }
            }

            alive[id] = Slot(s)
            currentId = id

            if (!restored) {
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

    // B1: reverse lookup — every delegate callback keys on the firing session.
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
            alive.remove(id)?.session?.let { try { it.close() } catch (_: Exception) {} }
            db.delete(id)
            try { stateFile(id).delete() } catch (_: Exception) {}
            if (currentId == id) {
                currentId = db.list().firstOrNull()?.id ?: -1L
            }
        }
    }

    fun closeAll() {
        synchronized(lock) {
            for (slot in alive.values) try { slot.session.close() } catch (_: Exception) {}
            alive.clear()
            db.clear()
            try { stateDir().deleteRecursively() } catch (_: Exception) {}
            currentId = -1L
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

    private fun evictIfNeeded() {
        while (alive.size >= MAX_ALIVE) {
            val it = alive.entries.iterator()
            var evicted = false
            while (it.hasNext()) {
                val entry = it.next()
                if (entry.key == currentId) continue
                try {
                    entry.value.latestState?.let { st ->
                        stateFile(entry.key).writeText(st.toString())
                    }
                    entry.value.session.close()
                } catch (_: Exception) {}
                it.remove()
                evicted = true
                break
            }
            if (!evicted) break
        }
    }
}
