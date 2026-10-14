package app.harvester.companion

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper

object DownloadStore {

    data class Item(
        val id: Long,
        val filename: String,
        val done: Long,
        val total: Long
    )

    private val items = mutableMapOf<Long, Item>()
    private val lock = Any()

    @Volatile var listener: (() -> Unit)? = null

    private var ctxRef: Context? = null
    private var receiver: BroadcastReceiver? = null
    private var observer: ContentObserver? = null

    fun ensureInstalled(ctx: Context) {
        if (ctxRef != null) return
        ctxRef = ctx.applicationContext

        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    synchronized(lock) { items.remove(id) }
                    listener?.invoke()
                }
            }
        }
        try {
            ctx.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        } catch (_: Exception) {}

        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { pollAll() }
        }
        try {
            ctx.contentResolver.registerContentObserver(
                Uri.parse("content://downloads/"), true, observer!!
            )
        } catch (_: Exception) {}
    }

    fun add(id: Long, filename: String) {
        synchronized(lock) {
            items[id] = Item(id, filename, 0L, -1L)
        }
        listener?.invoke()
    }

    fun pollAll() {
        val ctx = ctxRef ?: return
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        synchronized(lock) {
            val iter = items.entries.iterator()
            while (iter.hasNext()) {
                val (id, _) = iter.next()
                try {
                    val q = DownloadManager.Query().setFilterById(id)
                    val c = dm.query(q)
                    if (c != null) {
                        if (c.moveToFirst()) {
                            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            val cur = items[id]
                            if (cur != null) items[id] = cur.copy(done = done, total = total)
                            if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                                items[id] = items[id]!!.copy(
                                    done = total.coerceAtLeast(done),
                                    total = total.coerceAtLeast(1)
                                )
                                iter.remove()
                            }
                        } else {
                            iter.remove()
                        }
                        c.close()
                    }
                } catch (_: Exception) {}
            }
        }
        listener?.invoke()
    }

    fun active(): List<Item> = synchronized(lock) { items.values.sortedBy { it.id } }

    fun clearListeners() { listener = null }
}
