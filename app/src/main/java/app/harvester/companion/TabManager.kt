package app.harvester.companion

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.webkit.WebView

class TabManager private constructor(private val ctx: Context) {

    companion object {
        private const val MAX_ALIVE = 1

        @Volatile private var INSTANCE: TabManager? = null

        fun get(ctx: Context): TabManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TabManager(ctx.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private class Slot(val view: WebView) {
        var canGoBack: Boolean = false
        var canGoForward: Boolean = false
    }

    private val db = TabDb(ctx)
    private val alive = LinkedHashMap<Long, Slot>(MAX_ALIVE, 0.75f, true)
    private val pendingDestroy = mutableListOf<WebView>()
    private val lock = Any()
    private var currentId: Long = -1L

    fun openNew(url: String): Long {
        synchronized(lock) {
            val id = db.insert(url)
            evictAllExcept(id)
            val view = WebViewEngine.create(ctx)
            alive[id] = Slot(view)
            currentId = id
            if (url.isNotBlank() && url != "about:blank") view.loadUrl(url)
            return id
        }
    }

    fun openBlankTab(): Long {
        synchronized(lock) {
            val id = db.insert("about:blank")
            evictAllExcept(id)
            val view = WebViewEngine.create(ctx)
            alive[id] = Slot(view)
            currentId = id
            return id
        }
    }

    fun acquireView(id: Long): WebView? {
        synchronized(lock) {
            alive[id]?.let { currentId = id; return it.view }

            evictAllExcept(id)
            val view = WebViewEngine.create(ctx)
            alive[id] = Slot(view)
            currentId = id

            val restored = restoreState(id, view)
            if (!restored) {
                val fallback = db.list().firstOrNull { it.id == id }?.url
                if (!fallback.isNullOrBlank() && fallback != "about:blank") {
                    view.loadUrl(fallback)
                }
            }
            return view
        }
    }

    fun currentId(): Long = synchronized(lock) { currentId }

    fun currentView(): WebView? = synchronized(lock) { alive[currentId]?.view }

    fun list(): List<TabEntity> = db.list()

    fun updateUrl(id: Long, url: String, title: String?) = db.update(id, url, title)

    fun setNav(view: WebView, back: Boolean, forward: Boolean) {
        synchronized(lock) {
            val slot = alive.entries.firstOrNull { it.value.view === view }?.value ?: return
            slot.canGoBack = back
            slot.canGoForward = forward
        }
    }

    fun canGoBack(view: WebView): Boolean = synchronized(lock) {
        alive.entries.firstOrNull { it.value.view === view }?.value?.canGoBack ?: false
    }

    fun canGoForward(view: WebView): Boolean = synchronized(lock) {
        alive.entries.firstOrNull { it.value.view === view }?.value?.canGoForward ?: false
    }

    fun close(id: Long) {
        synchronized(lock) {
            val slot = alive.remove(id)
            db.delete(id)
            if (slot != null) {
                if (id == currentId) {
                    pendingDestroy.add(slot.view)
                    currentId = db.list().firstOrNull()?.id ?: -1L
                } else {
                    try { slot.view.destroy() } catch (_: Exception) {}
                }
            }
        }
    }

    fun closeAll() {
        synchronized(lock) {
            for (slot in alive.values) pendingDestroy.add(slot.view)
            alive.clear()
            db.clear()
            currentId = -1L
        }
    }

    fun drainPendingDestroy() {
        synchronized(lock) {
            if (pendingDestroy.isEmpty()) return
            for (view in pendingDestroy) {
                try {
                    (view.parent as? android.view.ViewGroup)?.removeView(view)
                    view.destroy()
                } catch (_: Exception) {}
            }
            pendingDestroy.clear()
        }
    }

    fun saveCurrentState() {
        synchronized(lock) {
            val slot = alive[currentId] ?: return
            saveState(currentId, slot.view)
        }
    }

    fun saveAll() {
        synchronized(lock) {
            for ((id, slot) in alive) saveState(id, slot.view)
        }
    }

    private fun saveState(id: Long, view: WebView) {
        try {
            val bundle = Bundle()
            view.saveState(bundle)
            val parcel = Parcel.obtain()
            bundle.writeToParcel(parcel, 0)
            val bytes = parcel.marshall()
            parcel.recycle()
            db.saveState(id, bytes)
        } catch (_: Exception) {}
    }

    private fun restoreState(id: Long, view: WebView): Boolean {
        val bytes = db.loadState(id) ?: return false
        return try {
            val parcel = Parcel.obtain()
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            val bundle = Bundle.CREATOR.createFromParcel(parcel)
            parcel.recycle()
            view.restoreState(bundle) != null
        } catch (_: Exception) { false }
    }

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
            saveState(victim.key, victim.value.view)
            pendingDestroy.add(victim.value.view)
            it.remove()
        }
    }
}
