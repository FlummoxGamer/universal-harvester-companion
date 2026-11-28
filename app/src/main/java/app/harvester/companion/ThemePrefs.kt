package app.harvester.companion

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import app.harvester.companion.privacy.UhPrivacyController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mozilla.components.concept.engine.mediaquery.PreferredColorScheme

object ThemePrefs {

    private const val NAME = "uh_theme"
    private const val KEY_MODE = "mode"

    const val MODE_SYSTEM = 0
    const val MODE_LIGHT = 1
    const val MODE_DARK = 2

    private val _modeFlow = MutableStateFlow<Int?>(null)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getMode(ctx: Context): Int = prefs(ctx).getInt(KEY_MODE, MODE_SYSTEM)

    fun modeFlow(ctx: Context): StateFlow<Int> {
        if (_modeFlow.value == null) {
            _modeFlow.value = getMode(ctx)
        }
        @Suppress("UNCHECKED_CAST")
        return _modeFlow.asStateFlow() as StateFlow<Int>
    }

    fun isDark(ctx: Context, mode: Int = getMode(ctx)): Boolean = when (mode) {
        MODE_DARK -> true
        MODE_LIGHT -> false
        else -> {
            val sysUiMode = android.content.res.Resources.getSystem().configuration.uiMode
            (sysUiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun isAmoled(ctx: Context, mode: Int = getMode(ctx)): Boolean =
        isDark(ctx, mode) && SettingsPrefs.getBool(ctx, SettingsPrefs.K_ENHANCE_DARK, false)

    fun resolveBackgroundColorInt(ctx: Context): Int = when {
        isAmoled(ctx) -> 0xFF000000.toInt()
        isDark(ctx) -> 0xFF0A0F1A.toInt()
        else -> 0xFFFFFFFF.toInt()
    }

    fun setMode(ctx: Context, mode: Int) {
        prefs(ctx).edit().putInt(KEY_MODE, mode).apply()
        _modeFlow.value = mode
        syncAppCompatNightMode(mode)
        syncEngineColorScheme(ctx, mode)
        try {
            UhPrivacyController.applyAllToEngine(ctx)
        } catch (_: Exception) {}
    }

    fun applyCurrent(ctx: Context) {
        val mode = getMode(ctx)
        _modeFlow.value = mode
        syncAppCompatNightMode(mode)
        syncEngineColorScheme(ctx, mode)
    }

    private fun syncAppCompatNightMode(mode: Int) {
        val target = when (mode) {
            MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != target) {
            AppCompatDelegate.setDefaultNightMode(target)
        }
    }

    private fun syncEngineColorScheme(ctx: Context, mode: Int) {
        try {
            val app = ctx.applicationContext as? UHApp ?: return
            app.components.engine.settings.preferredColorScheme = if (isDark(ctx, mode)) {
                PreferredColorScheme.Dark
            } else {
                PreferredColorScheme.Light
            }
        } catch (e: Exception) {
            UHLog.w("theme_warn", "Failed updating engine preferredColorScheme: ${e.message}")
        }
    }
}
