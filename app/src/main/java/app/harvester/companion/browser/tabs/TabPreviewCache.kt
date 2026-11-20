package app.harvester.companion.browser.tabs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object TabPreviewCache {
    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val diskExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var previewDir: File? = null

    @Volatile
    private var lastActiveTabBitmap: Bitmap? = null

    fun init(context: Context) {
        if (previewDir == null) {
            previewDir = File(context.applicationContext.cacheDir, "tab_previews").apply { mkdirs() }
        }
    }

    fun put(tabId: String, bitmap: Bitmap) {
        val old = cache.put(tabId, bitmap)
        if (old != null && old !== bitmap && !old.isRecycled) {
            old.recycle()
        }
        setLastActiveBitmap(bitmap)

        val dir = previewDir ?: return
        val copy = try {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: Exception) {
            null
        } ?: return

        diskExecutor.execute {
            try {
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "$tabId.webp")
                val fmt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                FileOutputStream(file).use { out ->
                    copy.compress(fmt, 78, out)
                }
            } catch (_: Exception) {
            } finally {
                if (!copy.isRecycled) copy.recycle()
            }
        }
    }

    fun get(tabId: String): Bitmap? {
        val mem = cache[tabId]
        if (mem != null && !mem.isRecycled) return mem

        val dir = previewDir ?: return null
        val file = File(dir, "$tabId.webp")
        if (!file.isFile || file.length() == 0L) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)?.also { decoded ->
                cache[tabId] = decoded
            }
        } catch (_: Exception) {
            null
        }
    }

    fun setLastActiveBitmap(bitmap: Bitmap?) {
        val old = lastActiveTabBitmap
        if (old !== bitmap) {
            lastActiveTabBitmap = bitmap
            if (old != null && !cache.values.contains(old) && !old.isRecycled) {
                old.recycle()
            }
        }
    }

    fun getLastActiveBitmap(): Bitmap? = lastActiveTabBitmap

    fun remove(tabId: String) {
        val old = cache.remove(tabId)
        if (old != null && !old.isRecycled) {
            old.recycle()
        }
        val dir = previewDir ?: return
        diskExecutor.execute {
            try { File(dir, "$tabId.webp").delete() } catch (_: Exception) {}
        }
    }

    fun pruneToValidIds(validTabIds: Set<String>) {
        val dir = previewDir ?: return
        diskExecutor.execute {
            try {
                dir.listFiles()?.forEach { f ->
                    val id = f.nameWithoutExtension
                    if (id !in validTabIds) f.delete()
                }
            } catch (_: Exception) {}
        }
    }

    fun trimMemory() {
        cache.values.forEach { if (!it.isRecycled) it.recycle() }
        cache.clear()
        val old = lastActiveTabBitmap
        lastActiveTabBitmap = null
        if (old != null && !old.isRecycled) {
            old.recycle()
        }
    }

    fun clear() {
        trimMemory()
        val dir = previewDir ?: return
        diskExecutor.execute {
            try { dir.listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
        }
    }
}
