package app.harvester.companion

import android.content.Context

object DebugPrefs {

    private const val NAME = "uh_debug"
    private const val KEY_VERBOSE = "verbose_logging"
    private const val KEY_LOG_LEVEL = "log_level"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun verboseLogging(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_VERBOSE, false)

    fun setVerboseLogging(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_VERBOSE, on).apply()
    }

    fun logLevel(ctx: Context): LogLevel {
        val name = prefs(ctx).getString(KEY_LOG_LEVEL, LogLevel.DEBUG.name) ?: LogLevel.DEBUG.name
        return try { LogLevel.valueOf(name) } catch (_: Exception) { LogLevel.DEBUG }
    }

    fun setLogLevel(ctx: Context, level: LogLevel) {
        prefs(ctx).edit().putString(KEY_LOG_LEVEL, level.name).apply()
    }
}
