package app.harvester.companion

import android.content.Context
import android.os.Parcel
import org.mozilla.geckoview.GeckoSession
import java.io.File
import java.io.FileOutputStream

class TabManager(private val ctx: Context) {

    companion object {
        private const val MAX_ALIVE = 5
    }

    private val db = TabDb(ctx)
    private val alive = LinkedHashMap<Long, GeckoSession>(MAX_ALIVE, 0.75f, true)
    private val lock = Any()
    private var currentId: Long = -1L

    private fun stateDir(): File = File(ctx.filesDir, "tabs").apply { mkdirs() }
    private fun stateFile(id: Long): File = File(stateDir(), "$id.bin")

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
                    val bytes = f.readBytes()
                    val p = Parcel.obtain()
                    p.unmarshall(bytes, 0, bytes.size)
                    p.setDataPosition(0)
                    @Suppress("DEPRECATION")
                    val state = p.readParcelable<GeckoSession.SessionState>(
                        GeckoSession.SessionState::class.java.classLoader
                    )
                    p.recycle()
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
                    val state = s.saveState() ?: continue
                    val p = Parcel.obtain()
                    p.writeParcelable(state, 0)
                    val bytes = p.marshall()
                    p.recycle()
                    FileOutputStream(stateFile(id)).use { it.write(bytes) }
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
                    val state = eldest.value.saveState()
                    if (state != null) {
                        val p = Parcel.obtain()
                        p.writeParcelable(state, 0)
                        val bytes = p.marshall()
                        p.recycle()
                        FileOutputStream(stateFile(eldest.key)).use { out -> out.write(bytes) }
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
