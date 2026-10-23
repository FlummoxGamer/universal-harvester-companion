package app.harvester.companion

import android.content.Context

object UserscriptStore {

    private const val NAME = "uh_scripts"
    private const val KEY_ENABLED_PREFIX = "enabled_"
    private const val KEY_DIRTY = "dirty"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context, name: String): Boolean =
        prefs(ctx).getBoolean(KEY_ENABLED_PREFIX + name, true)

    fun setEnabled(ctx: Context, name: String, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED_PREFIX + name, on).apply()
        markDirty(ctx)
    }

    fun forget(ctx: Context, name: String) {
        prefs(ctx).edit().remove(KEY_ENABLED_PREFIX + name).apply()
        markDirty(ctx)
    }

    fun markDirty(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_DIRTY, true).apply()
    }

    fun consumeDirty(ctx: Context): Boolean {
        val p = prefs(ctx)
        val was = p.getBoolean(KEY_DIRTY, false)
        if (was) p.edit().putBoolean(KEY_DIRTY, false).apply()
        return was
    }
}
