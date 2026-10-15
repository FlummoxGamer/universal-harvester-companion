package app.harvester.companion

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class HistoryStore private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "uh_history.db", null, 1) {

    companion object {
        @Volatile private var INSTANCE: HistoryStore? = null
        fun get(ctx: Context): HistoryStore = INSTANCE ?: synchronized(this) {
            INSTANCE ?: HistoryStore(ctx.applicationContext).also { INSTANCE = it }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE history (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "url TEXT NOT NULL," +
                "title TEXT," +
                "visited_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_visited ON history(visited_at DESC)")
        db.execSQL("CREATE INDEX idx_url ON history(url)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        db.execSQL("DROP TABLE IF EXISTS history")
        onCreate(db)
    }

    data class Entry(val id: Long, val url: String, val title: String, val visitedAt: Long)

    @Synchronized
    fun record(url: String, title: String?) {
        if (url.isBlank()) return
        if (url == "about:blank" || url.startsWith("chrome-error://")) return
        val db = writableDatabase
        // if same URL visited within last 3s, update instead of duplicate
        val now = System.currentTimeMillis()
        val existing = db.rawQuery(
            "SELECT id FROM history WHERE url=? AND visited_at > ? LIMIT 1",
            arrayOf(url, (now - 3000).toString())
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
        if (existing > 0L) {
            val cv = ContentValues().apply {
                put("title", title ?: "")
                put("visited_at", now)
            }
            db.update("history", cv, "id=?", arrayOf(existing.toString()))
        } else {
            val cv = ContentValues().apply {
                put("url", url)
                put("title", title ?: "")
                put("visited_at", now)
            }
            db.insert("history", null, cv)
        }
        // prune: keep max 5000
        db.execSQL(
            "DELETE FROM history WHERE id NOT IN " +
                "(SELECT id FROM history ORDER BY visited_at DESC LIMIT 5000)"
        )
    }

    @Synchronized
    fun search(query: String, limit: Int = 30): List<Entry> {
        val out = mutableListOf<Entry>()
        val q = query.trim()
        val cursor = if (q.isBlank()) {
            readableDatabase.rawQuery(
                "SELECT id, url, title, visited_at FROM history ORDER BY visited_at DESC LIMIT ?",
                arrayOf(limit.toString())
            )
        } else {
            val like = "%$q%"
            readableDatabase.rawQuery(
                "SELECT id, url, title, visited_at FROM history " +
                    "WHERE url LIKE ? OR title LIKE ? ORDER BY visited_at DESC LIMIT ?",
                arrayOf(like, like, limit.toString())
            )
        }
        cursor.use { c ->
            while (c.moveToNext()) {
                out.add(Entry(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3)))
            }
        }
        return out
    }

    @Synchronized
    fun delete(id: Long) {
        writableDatabase.delete("history", "id=?", arrayOf(id.toString()))
    }

    @Synchronized
    fun deleteByUrl(url: String) {
        writableDatabase.delete("history", "url=?", arrayOf(url))
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete("history", null, null)
    }
}
