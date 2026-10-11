package app.harvester.companion

import android.content.Context

object AppPrefs {
    private const val NAME = "uh_prefs"
    private const val KEY_SCRIPT_URL = "script_url"
    private const val KEY_AUTO_UPDATE = "auto_update"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_LAST_VERSION = "last_version"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun scriptUrl(ctx: Context): String = prefs(ctx).getString(KEY_SCRIPT_URL, "").orEmpty()
    fun setScriptUrl(ctx: Context, url: String) { prefs(ctx).edit().putString(KEY_SCRIPT_URL, url).apply() }

    fun autoUpdate(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO_UPDATE, true)
    fun setAutoUpdate(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean(KEY_AUTO_UPDATE, on).apply() }

    fun lastCheck(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_CHECK, 0L)
    fun setLastCheck(ctx: Context, t: Long) { prefs(ctx).edit().putLong(KEY_LAST_CHECK, t).apply() }

    fun lastVersion(ctx: Context): String = prefs(ctx).getString(KEY_LAST_VERSION, "").orEmpty()
    fun setLastVersion(ctx: Context, v: String) { prefs(ctx).edit().putString(KEY_LAST_VERSION, v).apply() }
}
