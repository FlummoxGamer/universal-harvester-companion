package app.harvester.companion.browser.tabs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Persistent reactive Tab & Home preview store:
 * - Normal tab previews and the real Home screen snapshot live in permanent internal filesDir
 *   so Android OS cache sweeps and app restarts NEVER wipe open tab previews.
 * - Private tab previews live strictly in volatile RAM (zero disk writes) and are wiped on close.
 */
object TabPreviewCache {
    private const val HOME_SNAPSHOT_KEY = "_uh_home_snapshot"
    private const val MAX_PREVIEW_WIDTH = 540

    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val loadingFromDisk = ConcurrentHashMap.newKeySet<String>()
    private val missingOnDisk = ConcurrentHashMap.newKeySet<String>()
    private val privateTabIds = ConcurrentHashMap.newKeySet<String>()
    private val diskExecutor = Executors.newSingleThreadExecutor()

    private val _versionFlow = MutableStateFlow(0L)
    val versionFlow: StateFlow<Long> = _versionFlow.asStateFlow()

    @Volatile
    private var previewDir: File? = null

    @Volatile
    private var legacyCacheDir: File? = null

    @Volatile
    private var lastActiveTabBitmap: Bitmap? = null

    fun init(context: Context) {
        if (previewDir == null) {
            val app = context.applicationContext
            previewDir = File(app.filesDir, "tab_previews").apply { mkdirs() }
            legacyCacheDir = File(app.cacheDir, "tab_previews")
        }
    }

    private fun bumpVersion() {
        _versionFlow.update { it + 1L }
    }

    private fun scaleForPreview(source: Bitmap): Bitmap {
        if (source.isRecycled) return source
        val w = source.width
        val h = source.height
        if (w <= MAX_PREVIEW_WIDTH || w <= 0 || h <= 0) {
            return try {
                source.copy(Bitmap.Config.ARGB_8888, false) ?: source
            } catch (_: Exception) {
                source
            }
        }
        val targetH = (h.toFloat() * MAX_PREVIEW_WIDTH / w.toFloat()).toInt().coerceAtLeast(1)
        return try {
            Bitmap.createScaledBitmap(source, MAX_PREVIEW_WIDTH, targetH, true)
        } catch (_: Exception) {
            source
        }
    }

    /**
     * Stores a real live screenshot of the Normal Home screen (wallpaper + shortcuts + stats)
     * permanently on disk so blank Normal tabs display the authentic Home screen in the Tabs Tray.
     */
    fun putHomeSnapshot(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        val stored = scaleForPreview(bitmap)
        cache[HOME_SNAPSHOT_KEY] = stored
        missingOnDisk.remove(HOME_SNAPSHOT_KEY)
        bumpVersion()
        writeBitmapToDisk(HOME_SNAPSHOT_KEY, stored)
    }

    fun getHomeSnapshotAsync(): Bitmap? {
        return getOrLoadAsync(HOME_SNAPSHOT_KEY, isPrivate = false)
    }

    /**
     * Stores a tab preview bitmap.
     * Private tab previews live strictly in RAM (zero disk writes, never exposed as lastActiveTabBitmap).
     */
    fun put(tabId: String, bitmap: Bitmap, isPrivate: Boolean = false) {
        if (tabId.isBlank() || bitmap.isRecycled) return
        val stored = scaleForPreview(bitmap)

        cache[tabId] = stored
        missingOnDisk.remove(tabId)

        if (isPrivate) {
            privateTabIds.add(tabId)
            bumpVersion()
            return
        }

        privateTabIds.remove(tabId)
        setLastActiveBitmap(stored)
        bumpVersion()
        writeBitmapToDisk(tabId, stored)
    }

    private fun writeBitmapToDisk(key: String, stored: Bitmap) {
        val dir = previewDir ?: return
        val copy = try {
            stored.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: Exception) {
            null
        } ?: return

        diskExecutor.execute {
            try {
                if (!dir.exists()) dir.mkdirs()
                val tmp = File(dir, "$key.webp.tmp")
                val target = File(dir, "$key.webp")
                val fmt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                FileOutputStream(tmp).use { out ->
                    copy.compress(fmt, 82, out)
                }
                if (tmp.isFile && tmp.length() > 0L) {
                    if (target.exists()) target.delete()
                    tmp.renameTo(target)
                }
            } catch (_: Exception) {
            } finally {
                if (!copy.isRecycled) copy.recycle()
            }
        }
    }

    /**
     * Fast non-blocking lookup: returns RAM bitmap immediately if present,
     * otherwise schedules a background permanent-disk decode that bumps [versionFlow] when ready.
     */
    fun getOrLoadAsync(tabId: String, isPrivate: Boolean = false): Bitmap? {
        val mem = cache[tabId]
        if (mem != null && !mem.isRecycled) return mem

        if (isPrivate || tabId in privateTabIds || tabId in missingOnDisk) return null

        val dir = previewDir ?: return null
        if (!loadingFromDisk.add(tabId)) return null

        diskExecutor.execute {
            try {
                var file = File(dir, "$tabId.webp")
                if (!file.isFile || file.length() == 0L) {
                    // Seamless one-time fallback check in legacy cacheDir
                    val legacy = legacyCacheDir?.let { File(it, "$tabId.webp") }
                    if (legacy != null && legacy.isFile && legacy.length() > 0L) {
                        try {
                            legacy.copyTo(file, overwrite = true)
                            legacy.delete()
                        } catch (_: Exception) {
                            file = legacy
                        }
                    }
                }
                if (!file.isFile || file.length() == 0L) {
                    missingOnDisk.add(tabId)
                } else {
                    val decoded = BitmapFactory.decodeFile(file.absolutePath)
                    if (decoded != null) {
                        cache[tabId] = decoded
                        bumpVersion()
                    } else {
                        missingOnDisk.add(tabId)
                    }
                }
            } catch (_: Exception) {
                missingOnDisk.add(tabId)
            } finally {
                loadingFromDisk.remove(tabId)
            }
        }
        return null
    }

    fun get(tabId: String): Bitmap? {
        val mem = cache[tabId]
        if (mem != null && !mem.isRecycled) return mem
        return getOrLoadAsync(tabId, isPrivate = tabId in privateTabIds)
    }

    fun setLastActiveBitmap(bitmap: Bitmap?) {
        lastActiveTabBitmap = bitmap
    }

    fun getLastActiveBitmap(): Bitmap? {
        val bmp = lastActiveTabBitmap
        return if (bmp != null && !bmp.isRecycled) bmp else null
    }

    fun remove(tabId: String) {
        if (tabId == HOME_SNAPSHOT_KEY) return
        privateTabIds.remove(tabId)
        missingOnDisk.remove(tabId)
        loadingFromDisk.remove(tabId)
        val old = cache.remove(tabId)
        if (old != null && old === lastActiveTabBitmap) {
            lastActiveTabBitmap = null
        }
        bumpVersion()
        val dir = previewDir ?: return
        diskExecutor.execute {
            try { File(dir, "$tabId.webp").delete() } catch (_: Exception) {}
        }
    }

    fun clearPrivate(ids: Collection<String> = emptyList()) {
        val targets = (privateTabIds + ids).toSet()
        targets.forEach { id ->
            privateTabIds.remove(id)
            missingOnDisk.remove(id)
            loadingFromDisk.remove(id)
            val old = cache.remove(id)
            if (old != null && old === lastActiveTabBitmap) {
                lastActiveTabBitmap = null
            }
        }
        if (targets.isNotEmpty()) {
            bumpVersion()
        }
    }

    fun pruneToValidIds(validTabIds: Set<String>) {
        val dir = previewDir ?: return
        diskExecutor.execute {
            try {
                dir.listFiles()?.forEach { f ->
                    val id = f.nameWithoutExtension
                    if (id != HOME_SNAPSHOT_KEY && id !in validTabIds) {
                        f.delete()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun trimMemory() {
        // Safely drop RAM references without force-recycling bitmaps currently held by Compose frames;
        // permanent disk files reload seamlessly on next tray open.
        val privateSnapshots = privateTabIds.mapNotNull { id -> cache[id]?.let { id to it } }.toMap()
        cache.clear()
        cache.putAll(privateSnapshots)
        loadingFromDisk.clear()
        missingOnDisk.clear()
        lastActiveTabBitmap = null
        bumpVersion()
    }

    fun clear() {
        cache.clear()
        privateTabIds.clear()
        loadingFromDisk.clear()
        missingOnDisk.clear()
        lastActiveTabBitmap = null
        bumpVersion()
        val dir = previewDir ?: return
        diskExecutor.execute {
            try {
                dir.listFiles()?.forEach { f ->
                    if (f.nameWithoutExtension != HOME_SNAPSHOT_KEY) f.delete()
                }
            } catch (_: Exception) {}
        }
    }
}
