package app.harvester.companion

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.webkit.WebView

class TabManager private constructor(private val ctx: Context) {

    companion object {
        @Volatile private var INSTANCE: TabManager? = null
        fun get(ctx: Context): TabManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TabManager(ctx.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private class Slot(val view: WebView, val incognito: Boolean) {
        var canGoBack: Boolean = false
        var canGoForward: Boolean = false
    }

    private val db = TabDb(ctx)
    private val alive = LinkedHashMap<Long, Slot>(16, 0.75f, true)
    private val pendingDestroy = mutableListOf<WebView>()
    private val lock = Any()
    private var currentId: Long = -1L

    fun openNew(url: String, incognito: Boolean = false, groupId: Long = 0L): Long {
        synchronized(lock) {
            val id = db.insert(url, incognito, groupId)
            val view = WebViewEngine.create(ctx)
            alive[id] = Slot(view, incognito)
            currentId = id
            if (url.isNotBlank() && url != "about:blank") view.loadUrl(url)
            return id
        }
    }

    fun openBlankTab(incognito: Boolean = false, groupId: Long = 0L): Long =
        openNew("about:blank", incognito, groupId)

    fun acquireView(id: Long): WebView? {
        synchronized(lock) {
            alive[id]?.let { currentId = id; return it.view }
            val entity = db.list().firstOrNull { it.id == id }
            val incognito = entity?.incognito ?: false
            val view = WebViewEngine.create(ctx)
            alive[id] = Slot(view, incognito)
            currentId = id
            val restored = restoreState(id, view)
            if (!restored) {
                val fallback = entity?.url
                if (!fallback.isNullOrBlank() && fallback != "about:blank") view.loadUrl(fallback)
            }
            return view
        }
    }

    fun currentId(): Long = synchronized(lock) { currentId }
    fun currentView(): WebView? = synchronized(lock) { alive[currentId]?.view }
    fun currentIsIncognito(): Boolean = synchronized(lock) { alive[currentId]?.incognito ?: false }

    fun idForView(view: WebView): Long = synchronized(lock) {
        alive.entries.firstOrNull { it.value.view === view }?.key ?: -1L
    }

    fun list(): List<TabEntity> = db.list()
    fun listNormal(): List<TabEntity> = db.list().filter { !it.incognito }
    fun listIncognito(): List<TabEntity> = db.list().filter { it.incognito }
    fun listByGroup(groupId: Long): List<TabEntity> = db.list().filter { it.groupId == groupId }
    fun listUngrouped(): List<TabEntity> = db.list().filter { it.groupId == 0L }

    fun hasIncognito(): Boolean = listIncognito().isNotEmpty()

    fun updateUrl(id: Long, url: String, title: String?) = db.update(id, url, title)

    fun setNav(view: WebView, back: Boolean, forward: Boolean) {
        synchronized(lock) {
            val slot = alive.entries.firstOrNull { it.value.view === view }?.value ?: return
            slot.canGoBack = back
            slot.canGoForward = forward
        }
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

    fun closeAllIncognito() {
        synchronized(lock) {
            val incognitoIds = db.list().filter { it.incognito }.map { it.id }
            for (id in incognitoIds) {
                val slot = alive.remove(id) ?: continue
                pendingDestroy.add(slot.view)
                db.delete(id)
            }
            if (currentId in incognitoIds) {
                currentId = db.list().firstOrNull()?.id ?: -1L
            }
        }
        // Clear cookie jar and storage since incognito is closed
        try {
            val cookieManager = android.webkit.CookieManager.getInstance()
            if (incognitoCleared()) {
                cookieManager.removeAllCookies(null)
                cookieManager.flush()
            }
        } catch (_: Exception) {}
    }

    private fun incognitoCleared(): Boolean = listIncognito().isEmpty()

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
                try { saveState(id, slot.view) } catch (_: Exception) {}
                pendingDestroy.add(slot.view)
            }
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
            if (slot.incognito) return  // don't persist incognito
            saveState(currentId, slot.view)
        }
    }

    fun saveAll() {
        synchronized(lock) {
            for ((id, slot) in alive) {
                if (slot.incognito) continue
                saveState(id, slot.view)
            }
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
}
