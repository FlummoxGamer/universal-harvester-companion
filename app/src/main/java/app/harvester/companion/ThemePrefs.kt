package app.harvester.companion

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object ThemePrefs {

    private const val NAME = "uh_theme"
    private const val KEY_MODE = "mode"

    const val MODE_SYSTEM = 0
    const val MODE_LIGHT = 1
    const val MODE_DARK = 2

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getMode(ctx: Context): Int = prefs(ctx).getInt(KEY_MODE, MODE_SYSTEM)

    fun setMode(ctx: Context, mode: Int) {
        prefs(ctx).edit().putInt(KEY_MODE, mode).apply()
        apply(mode)
    }

    fun apply(mode: Int) {
        AppCompatDelegate.setDefaultNightMode(when (mode) {
            MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        })
    }

    fun applyCurrent(ctx: Context) = apply(getMode(ctx))
}
