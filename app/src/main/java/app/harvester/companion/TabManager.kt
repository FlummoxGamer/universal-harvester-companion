package app.harvester.companion

import android.content.Context
import org.mozilla.geckoview.GeckoSession
import java.io.File

class TabManager(private val ctx: Context) {

    companion object {
        private const val MAX_ALIVE = 5
    }

    private val db = TabDb(ctx)
    private val alive = LinkedHashMap<Long, GeckoSession>(MAX_ALIVE, 0.75f, true)
    private val lock = Any()
    private var currentId: Long = -1L

    private fun stateDir(): File = File(ctx.filesDir, "tabs").apply { mkdirs() }
    private fun stateFile(id: Long): File = File(stateDir(), "$id.state")

    fun openNew(url: String): Long {
        synchronized(lock) {
            val id = db.insert(url)
            evictIfNeeded()
            val s = GeckoEngine.newSession()
            alive[id] = s
            currentId = id
            s.loadUri(url)
            return id
        }
    }

    fun switchTo(id: Long): GeckoSession? {
        synchronized(lock) {
            val existing = alive[id]
            if (existing != null) {
                currentId = id
                return existing
            }
            evictIfNeeded()
            val s = GeckoEngine.newSession()
            val f = stateFile(id)
            if (f.exists()) {
                try {
                    val stateStr = f.readText()
                    val state = GeckoSession.SessionState.fromString(stateStr)
                    if (state != null) s.restoreState(state)
                } catch (e: Exception) {
                    UHLog.w("tabs", "restore failed id=$id ${e.message}")
                }
            }
            alive[id] = s
            currentId = id
            if (alive.size == 1) s.loadUri("about:blank")
            return s
        }
    }

    fun currentId(): Long = synchronized(lock) { currentId }

    fun currentSession(): GeckoSession? = synchronized(lock) { alive[currentId] }

    fun list(): List<TabEntity> = db.list()

    fun count(): Int = db.list().size

    fun updateUrl(id: Long, url: String, title: String?) {
        db.update(id, url, title)
    }

    fun close(id: Long) {
        synchronized(lock) {
            alive.remove(id)?.let { try { it.close() } catch (_: Exception) {} }
            db.delete(id)
            try { stateFile(id).delete() } catch (_: Exception) {}
            if (currentId == id) {
                currentId = db.list().firstOrNull()?.id ?: -1L
            }
        }
    }

    fun closeAll() {
        synchronized(lock) {
            for (s in alive.values) try { s.close() } catch (_: Exception) {}
            alive.clear()
            db.clear()
            try { stateDir().deleteRecursively() } catch (_: Exception) {}
            currentId = -1L
        }
    }

    fun saveAll() {
        synchronized(lock) {
            for ((id, s) in alive) {
                try {
                    s.saveState().accept { state ->
                        try {
                            if (state != null) {
                                stateFile(id).writeText(state.toString())
                            }
                        } catch (e: Exception) {
                            UHLog.w("tabs", "save write failed id=$id ${e.message}")
                        }
                    }
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
                val eldest = it.next()
                if (eldest.key == currentId) continue
                try {
                    eldest.value.saveState().accept { state ->
                        try {
                            if (state != null) {
                                stateFile(eldest.key).writeText(state.toString())
                            }
                        } catch (_: Exception) {}
                    }
                    eldest.value.close()
                } catch (_: Exception) {}
                it.remove()
                evicted = true
                break
            }
            if (!evicted) break
        }
    }
}
