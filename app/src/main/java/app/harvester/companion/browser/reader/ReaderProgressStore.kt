package app.harvester.companion.browser.reader

import android.content.Context
import android.content.SharedPreferences
import app.harvester.companion.privacy.UhShieldEngine
import org.json.JSONObject

/**
 * Lightweight LRU store remembering per-article Reader Mode scroll ratio and Book Page index
 * so returning to any long article resumes right where the reader left off.
 */
object ReaderProgressStore {

    private const val PREFS_NAME = "uh_reader_progress_lru"
    private const val K_ENTRIES = "entries_json"
    private const val MAX_ENTRIES = 150

    data class ArticleProgress(
        val scrollRatio: Float,
        val pageIndex: Int,
        val updatedAt: Long
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("scrollRatio", scrollRatio.toDouble())
            put("pageIndex", pageIndex)
            put("updatedAt", updatedAt)
        }
    }

    @Volatile
    private var prefs: SharedPreferences? = null

    private fun ensurePrefs(ctx: Context): SharedPreferences {
        prefs?.let { return it }
        return ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).also {
            prefs = it
        }
    }

    private fun normalizeKey(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return ""
        val cleaned = runCatching { UhShieldEngine.cleanTrackingUrl(trimmed) }.getOrDefault(trimmed)
        return cleaned.substringBefore("#").trimEnd('/')
    }

    @Synchronized
    fun save(ctx: Context, url: String, scrollRatio: Float, pageIndex: Int) {
        val key = normalizeKey(url)
        if (key.isEmpty()) return
        val clampedRatio = scrollRatio.coerceIn(0f, 1f)
        val clampedPage = pageIndex.coerceAtLeast(0)
        if (clampedRatio < 0.01f && clampedPage == 0) return

        val sp = ensurePrefs(ctx)
        val root = runCatching { JSONObject(sp.getString(K_ENTRIES, "{}") ?: "{}") }.getOrElse { JSONObject() }
        root.put(
            key,
            ArticleProgress(
                scrollRatio = clampedRatio,
                pageIndex = clampedPage,
                updatedAt = System.currentTimeMillis()
            ).toJson()
        )

        // Trim oldest entries beyond MAX_ENTRIES
        if (root.length() > MAX_ENTRIES) {
            val all = mutableListOf<Pair<String, Long>>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val ts = root.optJSONObject(k)?.optLong("updatedAt", 0L) ?: 0L
                all.add(k to ts)
            }
            all.sortBy { it.second }
            val toDrop = all.size - MAX_ENTRIES
            for (i in 0 until toDrop) {
                root.remove(all[i].first)
            }
        }

        sp.edit().putString(K_ENTRIES, root.toString()).apply()
    }

    @Synchronized
    fun get(ctx: Context, url: String): ArticleProgress? {
        val key = normalizeKey(url)
        if (key.isEmpty()) return null
        val sp = ensurePrefs(ctx)
        val root = runCatching { JSONObject(sp.getString(K_ENTRIES, "{}") ?: "{}") }.getOrNull() ?: return null
        val obj = root.optJSONObject(key) ?: return null
        return ArticleProgress(
            scrollRatio = obj.optDouble("scrollRatio", 0.0).toFloat().coerceIn(0f, 1f),
            pageIndex = obj.optInt("pageIndex", 0).coerceAtLeast(0),
            updatedAt = obj.optLong("updatedAt", 0L)
        )
    }
}
