package app.harvester.companion

import android.content.Context

object DebugPrefs {

    private const val NAME = "uh_debug"
    private const val KEY_VERBOSE = "verbose_logging"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun verboseLogging(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_VERBOSE, false)

    fun setVerboseLogging(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_VERBOSE, on).apply()
    }
}
