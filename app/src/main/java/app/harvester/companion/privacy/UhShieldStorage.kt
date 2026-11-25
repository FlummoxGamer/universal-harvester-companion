package app.harvester.companion.privacy

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.StringReader

/**
 * Disk cache and SharedPreferences persistence helpers for UH Shield filter lists and overrides.
 */
internal object UhShieldStorage {

    const val PREFS_NAME = "uh_shield_v2"
    const val KEY_PAUSED_HOSTS = "paused_hosts"
    const val KEY_JS_ALLOW_HOSTS = "js_allow_hosts"
    const val KEY_HTTP_EXCEPTION_HOSTS = "http_exception_hosts"
    const val KEY_ENABLED_LISTS = "enabled_list_ids"
    const val KEY_CUSTOM_LISTS_JSON = "custom_lists_json"
    const val KEY_CUSTOM_RULES = "custom_user_rules"
    const val KEY_STATS_ADS = "stats_ads_blocked"
    const val KEY_STATS_TRACKERS = "stats_trackers_blocked"
    const val KEY_STATS_SAVED_MS = "stats_time_saved_ms"
    const val KEY_LAST_SYNC_MS = "last_sync_ms"
    const val KEY_LIST_META_JSON = "list_meta_json"
    const val KEY_CATALOG_VERSION = "list_catalog_version"
    const val CURRENT_CATALOG_VERSION = 3

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun listCacheFile(ctx: Context, listId: String): File {
        val safe = listId.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        return File(File(ctx.filesDir, "uh_shield_lists"), "$safe.txt")
    }

    fun getEnabledListIds(ctx: Context): Set<String> {
        val p = prefs(ctx)
        val saved = p.getStringSet(KEY_ENABLED_LISTS, null)
        val savedVersion = p.getInt(KEY_CATALOG_VERSION, 1)
        if (saved != null) {
            if (savedVersion < CURRENT_CATALOG_VERSION) {
                val migrated = saved.toMutableSet()
                if (migrated.isNotEmpty()) {
                    if (savedVersion < 2) {
                        migrated.addAll(UhShieldCatalog.CATALOG_V2_NEW_DEFAULTS)
                    }
                    if (savedVersion < 3) {
                        migrated.addAll(UhShieldCatalog.CATALOG_V3_NEW_DEFAULTS)
                    }
                }
                p.edit()
                    .putStringSet(KEY_ENABLED_LISTS, migrated)
                    .putInt(KEY_CATALOG_VERSION, CURRENT_CATALOG_VERSION)
                    .apply()
                return migrated
            }
            return saved.toSet()
        }
        val defaults = UhShieldCatalog.BUILT_IN_LISTS.filter { it.defaultEnabled }.map { it.id }.toSet()
        p.edit()
            .putStringSet(KEY_ENABLED_LISTS, defaults)
            .putInt(KEY_CATALOG_VERSION, CURRENT_CATALOG_VERSION)
            .apply()
        return defaults
    }

    fun loadCustomListDefs(ctx: Context): List<FilterListDef> {
        val raw = prefs(ctx).getString(KEY_CUSTOM_LISTS_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id")
                val name = obj.optString("name")
                val url = obj.optString("url")
                if (id.isBlank() || url.isBlank()) null
                else FilterListDef(id, name.ifBlank { url }, url, url, FilterPackTier.CUSTOM, true, false)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveCustomListDefs(ctx: Context, defs: List<FilterListDef>) {
        val arr = JSONArray()
        defs.forEach { d ->
            arr.put(JSONObject().apply {
                put("id", d.id)
                put("name", d.name)
                put("url", d.url)
            })
        }
        prefs(ctx).edit().putString(KEY_CUSTOM_LISTS_JSON, arr.toString()).apply()
    }

    fun saveListMeta(ctx: Context, listId: String, ruleCount: Int, updatedMs: Long, error: String?) {
        val p = prefs(ctx)
        val root = try {
            JSONObject(p.getString(KEY_LIST_META_JSON, "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        root.put(listId, JSONObject().apply {
            put("count", ruleCount)
            put("updated", updatedMs)
            if (error != null) put("error", error)
        })
        p.edit().putString(KEY_LIST_META_JSON, root.toString()).apply()
    }

    fun loadListStatuses(ctx: Context): List<FilterListStatus> {
        val enabledIds = getEnabledListIds(ctx)
        val meta = try {
            JSONObject(prefs(ctx).getString(KEY_LIST_META_JSON, "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        val allDefs = UhShieldCatalog.BUILT_IN_LISTS + loadCustomListDefs(ctx)
        return allDefs.map { def ->
            val obj = meta.optJSONObject(def.id)
            FilterListStatus(
                def = def,
                enabled = def.id in enabledIds,
                ruleCount = obj?.optInt("count", 0) ?: 0,
                lastUpdatedMs = obj?.optLong("updated", 0L) ?: 0L,
                lastError = obj?.optString("error")?.ifBlank { null },
            )
        }
    }

    fun countEffectiveRules(text: String): Int {
        var count = 0
        BufferedReader(StringReader(text)).useLines { lines ->
            for (raw in lines) {
                val l = raw.trim()
                if (l.isNotEmpty() && !l.startsWith("!") && !l.startsWith("[") &&
                    !(l.startsWith("#") && !l.startsWith("##") && !l.startsWith("#@#") && !l.startsWith("#?#"))
                ) {
                    count++
                }
            }
        }
        return count
    }
}
