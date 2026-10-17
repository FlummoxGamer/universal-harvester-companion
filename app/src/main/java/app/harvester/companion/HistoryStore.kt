package app.harvester.companion

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class HistoryStore private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "uh_history.db", null, 2) {

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
        db.execSQL(
            "CREATE TABLE search_queries (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "query TEXT NOT NULL," +
                "searched_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_search_at ON search_queries(searched_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        if (oldV < 2) {
            try {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS search_queries (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "query TEXT NOT NULL," +
                        "searched_at INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_search_at ON search_queries(searched_at DESC)")
            } catch (_: Exception) {}
        }
    }

    data class Entry(val id: Long, val url: String, val title: String, val visitedAt: Long)
    data class SearchQuery(val id: Long, val query: String, val searchedAt: Long)

@Synchronized
fun record(url: String, title: String?) {
    if (url.isBlank()) return
    if (url == "about:blank" || url.startsWith("chrome-error://")) return
    val db = writableDatabase
    val now = System.currentTimeMillis()
    // Dedup: same URL anywhere in history — update instead of insert
    val existing = db.rawQuery(
        "SELECT id FROM history WHERE url=? LIMIT 1", arrayOf(url)
    ).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
    if (existing > 0L) {
        val cv = ContentValues().apply { put("title", title ?: ""); put("visited_at", now) }
        db.update("history", cv, "id=?", arrayOf(existing.toString()))
    } else {
        val cv = ContentValues().apply {
            put("url", url); put("title", title ?: ""); put("visited_at", now)
        }
        db.insert("history", null, cv)
    }
    db.execSQL(
        "DELETE FROM history WHERE id NOT IN " +
            "(SELECT id FROM history ORDER BY visited_at DESC LIMIT 5000)"
    )
}

@Synchronized
fun recordUrlOnly(url: String, title: String?) {
    // Only record if it's a real navigation, not a sub-resource call
    if (!url.startsWith("http")) return
    record(url, title)
}

    @Synchronized
    fun recordSearch(query: String) {
        val q = query.trim()
        if (q.isBlank() || q.length > 200) return
        val db = writableDatabase
        val existing = db.rawQuery(
            "SELECT id FROM search_queries WHERE query=? LIMIT 1", arrayOf(q)
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
        if (existing > 0L) {
            val cv = ContentValues().apply { put("searched_at", System.currentTimeMillis()) }
            db.update("search_queries", cv, "id=?", arrayOf(existing.toString()))
        } else {
            val cv = ContentValues().apply {
                put("query", q); put("searched_at", System.currentTimeMillis())
            }
            db.insert("search_queries", null, cv)
        }
        db.execSQL(
            "DELETE FROM search_queries WHERE id NOT IN " +
                "(SELECT id FROM search_queries ORDER BY searched_at DESC LIMIT 500)"
        )
    }

    @Synchronized
    fun searchSearchQueries(query: String, limit: Int = 8): List<SearchQuery> {
        val out = mutableListOf<SearchQuery>()
        val q = query.trim()
        val cursor = if (q.isBlank()) {
            readableDatabase.rawQuery(
                "SELECT id, query, searched_at FROM search_queries ORDER BY searched_at DESC LIMIT ?",
                arrayOf(limit.toString())
            )
        } else {
            readableDatabase.rawQuery(
                "SELECT id, query, searched_at FROM search_queries " +
                    "WHERE query LIKE ? ORDER BY searched_at DESC LIMIT ?",
                arrayOf("%$q%", limit.toString())
            )
        }
        cursor.use { c ->
            while (c.moveToNext()) {
                out.add(SearchQuery(c.getLong(0), c.getString(1) ?: "", c.getLong(2)))
            }
        }
        return out
    }

    @Synchronized
    fun searchHistory(query: String, limit: Int = 30): List<Entry> {
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
    fun allHistory(limit: Int = 500): List<Entry> {
        val out = mutableListOf<Entry>()
        readableDatabase.rawQuery(
            "SELECT id, url, title, visited_at FROM history ORDER BY visited_at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
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
    fun deleteSearchQuery(id: Long) {
        writableDatabase.delete("search_queries", "id=?", arrayOf(id.toString()))
    }

    @Synchronized
    fun clearHistory() {
        writableDatabase.delete("history", null, null)
    }

    @Synchronized
    fun clearSearchHistory() {
        writableDatabase.delete("search_queries", null, null)
    }
}
