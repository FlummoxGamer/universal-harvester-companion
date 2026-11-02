package app.harvester.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.security.MessageDigest

object WallpaperStore {
    private const val PREFS_NAME = "uh_wallpapers"
    private const val KEY_CURRENT = "current_wallpaper_path"
    private const val KEY_TEXT_COLOR = "wallpaper_text_color"
    private const val KEY_IS_VIDEO = "wallpaper_is_video"
    private const val KEY_ZOOM = "wallpaper_zoom"
    private const val KEY_PAN_X = "wallpaper_pan_x"
    private const val KEY_PAN_Y = "wallpaper_pan_y"

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

fun saveMotionWallpaper(ctx: Context, uri: Uri): String? {
    return try {
        val file = File(getWallpaperDir(ctx), "motion_${System.currentTimeMillis()}.mp4")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        cleanOldWallpapers(ctx)
        file.absolutePath
    } catch (_: Exception) {
        null
    }
}

fun saveMotionWallpaperFile(ctx: Context, fileToCopy: File): String? {
    return try {
        val file = File(getWallpaperDir(ctx), "motion_${System.currentTimeMillis()}.mp4")
        fileToCopy.copyTo(file, overwrite = true)
        cleanOldWallpapers(ctx)
        file.absolutePath
    } catch (_: Exception) {
        null
    }
}

fun downloadRemoteWallpaper(ctx: Context, url: String): String? {
    return try {
        val isVideo = url.endsWith(".mp4") || url.contains("sample")
        val ext = if (isVideo) "mp4" else "jpg"
        val prefix = if (isVideo) "motion_" else "wall_"
        
        val md = MessageDigest.getInstance("MD5")
        val hash = md.digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(getWallpaperDir(ctx), "$prefix$hash.$ext")
        
        if (file.exists()) {
            UHLog.d("dedupe", "Remote wallpaper already exists. Returning cached path.")
            return file.absolutePath
        }

        val request = okhttp3.Request.Builder().url(url).build()
        val response = okhttp3.OkHttpClient().newCall(request).execute()
        if (response.isSuccessful && response.body != null) {
            response.body!!.byteStream().use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            cleanOldWallpapers(ctx)
            file.absolutePath
        } else null
    } catch (_: Exception) {
        null
    }
}

    fun generateVideoThumbnail(videoPath: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoPath)
            retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

private fun getFastHash(file: File): String {
    return try {
        if (!file.exists() || file.length() == 0L) return ""
        val md = MessageDigest.getInstance("MD5")
        md.update(file.length().toString().toByteArray())
        val buffer = ByteArray(1024)
        val bytesRead = file.inputStream().use { it.read(buffer) }
        if (bytesRead > 0) {
            md.update(buffer, 0, bytesRead)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { "" }
}

    private fun cleanOldWallpapers(ctx: Context) {
        val dir = getWallpaperDir(ctx)
        val files = dir.listFiles { f -> f.isFile && (f.name.startsWith("wall_") || f.name.startsWith("motion_")) } ?: return
        if (files.size > 20) {
            files.sortedBy { it.lastModified() }.take(files.size - 20).forEach { it.delete() }
        }
    }

    fun setCurrentWallpaper(ctx: Context, path: String?, textColor: Int, isVideo: Boolean = false) {
        getPrefs(ctx).edit()
            .putString(KEY_CURRENT, path)
            .putInt(KEY_TEXT_COLOR, textColor)
            .putBoolean(KEY_IS_VIDEO, isVideo)
            .apply()
    }

    fun getCurrentWallpaper(ctx: Context): String? {
        return getPrefs(ctx).getString(KEY_CURRENT, null)
    }

    fun isCurrentVideo(ctx: Context): Boolean {
        return getPrefs(ctx).getBoolean(KEY_IS_VIDEO, false)
    }

fun getTextColor(ctx: Context): Int {
    return getPrefs(ctx).getInt(KEY_TEXT_COLOR, 0)
}

fun setWallpaperTransform(ctx: Context, zoom: Float, panX: Float, panY: Float) {
    getPrefs(ctx).edit()
        .putFloat(KEY_ZOOM, zoom)
        .putFloat(KEY_PAN_X, panX)
        .putFloat(KEY_PAN_Y, panY)
        .apply()
}

fun getWallpaperTransform(ctx: Context): Triple<Float, Float, Float> {
    val zoom = getPrefs(ctx).getFloat(KEY_ZOOM, 1f)
    val panX = getPrefs(ctx).getFloat(KEY_PAN_X, 0.5f)
    val panY = getPrefs(ctx).getFloat(KEY_PAN_Y, 0.5f)
    return Triple(zoom, panX, panY)
}

    fun getRecent(ctx: Context): List<String> {
        val dir = getWallpaperDir(ctx)
        return dir.listFiles { file -> file.isFile && (file.name.startsWith("wall_") || file.name.startsWith("motion_")) }
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
