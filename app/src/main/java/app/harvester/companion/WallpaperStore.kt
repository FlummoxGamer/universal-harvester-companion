package app.harvester.companion

import android.content.Context
import android.net.Uri

object WallpaperStore {
    private const val PREFS_NAME = "uh_wallpapers"
    private const val KEY_CURRENT = "current_wallpaper"
    private const val KEY_RECENT = "recent_wallpapers"
    private const val KEY_TEXT_COLOR = "wallpaper_text_color"
    private const val MAX_RECENT = 28

    private fun getPrefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getCurrentWallpaper(ctx: Context): String? {
        return getPrefs(ctx).getString(KEY_CURRENT, null)
    }

    fun setCurrentWallpaper(ctx: Context, uri: String?, textColor: Int) {
        getPrefs(ctx).edit()
            .putString(KEY_CURRENT, uri)
            .putInt(KEY_TEXT_COLOR, textColor)
            .apply()
        if (uri != null && uri != "default") {
            addRecent(ctx, uri)
        }
    }

    fun getTextColor(ctx: Context): Int {
        return getPrefs(ctx).getInt(KEY_TEXT_COLOR, 0)
    }

    fun getRecent(ctx: Context): List<String> {
        val raw = getPrefs(ctx).getString(KEY_RECENT, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("|").filter { it.isNotBlank() }
    }

    private fun addRecent(ctx: Context, uri: String) {
        val current = getRecent(ctx).toMutableList()
        current.remove(uri)
        current.add(0, uri)
        if (current.size > MAX_RECENT) {
            current.subList(MAX_RECENT, current.size).clear()
        }
        getPrefs(ctx).edit().putString(KEY_RECENT, current.joinToString("|")).apply()
    }

    fun removeRecent(ctx: Context, uri: String) {
        val current = getRecent(ctx).toMutableList()
        current.remove(uri)
        getPrefs(ctx).edit().putString(KEY_RECENT, current.joinToString("|")).apply()
    }
}
