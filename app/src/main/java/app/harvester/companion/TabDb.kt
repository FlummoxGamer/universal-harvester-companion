package app.harvester.companion

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class TabDb(ctx: Context) : SQLiteOpenHelper(ctx, "uh_tabs.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE tabs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "url TEXT NOT NULL," +
                "title TEXT," +
                "position INTEGER NOT NULL," +
                "created_at INTEGER NOT NULL," +
                "state BLOB," +
                "incognito INTEGER NOT NULL DEFAULT 0," +
                "group_id INTEGER NOT NULL DEFAULT 0)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try { db.execSQL("ALTER TABLE tabs ADD COLUMN state BLOB") } catch (_: Exception) {}
        }
        if (oldVersion < 3) {
            try { db.execSQL("ALTER TABLE tabs ADD COLUMN incognito INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE tabs ADD COLUMN group_id INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun insert(url: String, incognito: Boolean = false, groupId: Long = 0L): Long {
        val db = writableDatabase
        val maxPos = db.rawQuery("SELECT COALESCE(MAX(position), -1) FROM tabs", null).use {
            if (it.moveToFirst()) it.getInt(0) else -1
        }
        val cv = ContentValues().apply {
            put("url", url)
            put("title", "")
            put("position", maxPos + 1)
            put("created_at", System.currentTimeMillis())
            put("incognito", if (incognito) 1 else 0)
            put("group_id", groupId)
        }
        return db.insert("tabs", null, cv)
    }

    @Synchronized
    fun list(): List<TabEntity> {
        val out = mutableListOf<TabEntity>()
        readableDatabase.rawQuery(
            "SELECT id, url, title, position, incognito, group_id FROM tabs ORDER BY position ASC", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    TabEntity(
                        id = c.getLong(0),
                        url = c.getString(1) ?: "",
                        title = c.getString(2) ?: "",
                        position = c.getInt(3),
                        incognito = c.getInt(4) == 1,
                        groupId = c.getLong(5)
                    )
                )
            }
        }
        return out
    }

    @Synchronized
    fun update(id: Long, url: String, title: String?) {
        val cv = ContentValues().apply {
            put("url", url)
            if (!title.isNullOrBlank()) put("title", title)
        }
        writableDatabase.update("tabs", cv, "id=?", arrayOf(id.toString()))
    }

    @Synchronized
    fun saveState(id: Long, bytes: ByteArray) {
        val cv = ContentValues().apply { put("state", bytes) }
        writableDatabase.update("tabs", cv, "id=?", arrayOf(id.toString()))
    }

    @Synchronized
    fun loadState(id: Long): ByteArray? {
        readableDatabase.rawQuery(
            "SELECT state FROM tabs WHERE id=?", arrayOf(id.toString())
        ).use {
            if (it.moveToFirst() && !it.isNull(0)) return it.getBlob(0)
        }
        return null
    }

    @Synchronized
    fun delete(id: Long) {
        writableDatabase.delete("tabs", "id=?", arrayOf(id.toString()))
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete("tabs", null, null)
    }
}
