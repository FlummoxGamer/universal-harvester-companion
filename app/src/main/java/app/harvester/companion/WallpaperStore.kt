package app.harvester.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

object WallpaperStore {
    private const val PREFS_NAME = "uh_wallpapers"
    private const val KEY_CURRENT = "current_wallpaper_path"
    private const val KEY_TEXT_COLOR = "wallpaper_text_color"

    private fun getPrefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getWallpaperDir(ctx: Context): File {
        val dir = File(ctx.getExternalFilesDir(null), "wallpapers")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun saveWallpaper(ctx: Context, uri: Uri): String? {
        return try {
            val inputStream = ctx.contentResolver.openInputStream(uri) ?: return null
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            
            if (bitmap == null) return null
            
            val file = File(getWallpaperDir(ctx), "wall_${System.currentTimeMillis()}.jpg")
            val outputStream = file.outputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            outputStream.close()
            
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    fun setCurrentWallpaper(ctx: Context, path: String?, textColor: Int) {
        getPrefs(ctx).edit()
            .putString(KEY_CURRENT, path)
            .putInt(KEY_TEXT_COLOR, textColor)
            .apply()
    }

    fun getCurrentWallpaper(ctx: Context): String? {
        return getPrefs(ctx).getString(KEY_CURRENT, null)
    }

    fun getTextColor(ctx: Context): Int {
        return getPrefs(ctx).getInt(KEY_TEXT_COLOR, 0)
    }

    fun getRecent(ctx: Context): List<String> {
        val dir = getWallpaperDir(ctx)
        return dir.listFiles { file -> file.isFile && file.extension == "jpg" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.absolutePath } ?: emptyList()
    }

    fun deleteWallpaper(path: String) {
        try {
            File(path).delete()
        } catch (_: Exception) {}
    }

    fun clearAll(ctx: Context) {
        try {
            getWallpaperDir(ctx).listFiles()?.forEach { it.delete() }
            getPrefs(ctx).edit().clear().apply()
        } catch (_: Exception) {}
    }
}
