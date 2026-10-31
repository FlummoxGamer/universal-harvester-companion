package app.harvester.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.security.MessageDigest

object WallpaperStore {
    private const val PREFS_NAME = "uh_wallpapers"
    private const val KEY_CURRENT = "current_wallpaper_path"
    private const val KEY_TEXT_COLOR = "wallpaper_text_color"

    private fun getPrefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getWallpaperDir(ctx: Context): File {
        val dir = File(ctx.getExternalFilesDir(null)?.parentFile, "wallpapers")
        if (!dir.exists()) dir.mkdirs()
        val nomedia = File(dir, ".nomedia")
        if (!nomedia.exists()) {
            try { nomedia.createNewFile() } catch (_: Exception) {}
        }
        return dir
    }

    fun saveWallpaper(ctx: Context, uri: Uri): String? {
        return try {
            val mime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
            val ext = when {
                mime.contains("png") -> "png"
                mime.contains("webp") -> "webp"
                else -> "jpg"
            }
            val file = File(getWallpaperDir(ctx), "wall_${System.currentTimeMillis()}.$ext")
            
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val hash = getFastHash(file)
            if (hash.isNotEmpty()) {
                val existing = getWallpaperDir(ctx).listFiles { f -> f.name.startsWith("wall_") }?.find {
                    it.absolutePath != file.absolutePath && getFastHash(it) == hash
                }
                if (existing != null) {
                    existing.delete()
                }
            }
            cleanOldWallpapers(ctx)
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    fun downloadRemoteWallpaper(ctx: Context, url: String): String? {
        return try {
            val file = File(getWallpaperDir(ctx), "wall_${System.currentTimeMillis()}.jpg")
            val request = okhttp3.Request.Builder().url(url).build()
            val response = okhttp3.OkHttpClient().newCall(request).execute()
            if (response.isSuccessful && response.body != null) {
                response.body!!.byteStream().use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                
                val hash = getFastHash(file)
                if (hash.isNotEmpty()) {
                    val existing = getWallpaperDir(ctx).listFiles { f -> f.name.startsWith("wall_") }?.find {
                        it.absolutePath != file.absolutePath && getFastHash(it) == hash
                    }
                    if (existing != null) {
                        existing.delete()
                    }
                }
                cleanOldWallpapers(ctx)
                file.absolutePath
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun getFastHash(file: File): String {
        return try {
            if (!file.exists() || file.length() == 0L) return ""
            val md = MessageDigest.getInstance("MD5")
            md.update(file.length().toString().toByteArray())
            val buffer = ByteArray(1024)
            file.inputStream().use { it.read(buffer) }
            md.update(buffer)
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { "" }
    }

    private fun cleanOldWallpapers(ctx: Context) {
        val dir = getWallpaperDir(ctx)
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("wall_") } ?: return
        if (files.size > 20) {
            files.sortedBy { it.lastModified() }.take(files.size - 20).forEach { it.delete() }
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
        return dir.listFiles { file -> file.isFile && file.name.startsWith("wall_") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.absolutePath }
            ?.take(20) ?: emptyList()
    }

    fun deleteWallpaper(path: String) {
        try { File(path).delete() } catch (_: Exception) {}
    }

    fun clearAll(ctx: Context) {
        try {
            getWallpaperDir(ctx).listFiles()?.forEach {
                if (it.name != ".nomedia") it.delete()
            }
            getPrefs(ctx).edit().clear().apply()
        } catch (_: Exception) {}
    }

    fun getSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(path, options)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
