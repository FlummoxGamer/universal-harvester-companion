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

object TabPreviewCache {
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
    private var lastActiveTabBitmap: Bitmap? = null

    private const val MAX_PREVIEW_WIDTH = 540

    fun init(context: Context) {
        if (previewDir == null) {
            previewDir = File(context.applicationContext.cacheDir, "tab_previews").apply { mkdirs() }
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
     * Stores a tab preview bitmap.
     * Private tab previews live strictly in RAM (zero disk writes, never exposed as lastActiveTabBitmap).
     */
    fun put(tabId: String, bitmap: Bitmap, isPrivate: Boolean = false) {
        if (tabId.isBlank() || bitmap.isRecycled) return
        val stored = scaleForPreview(bitmap)
        if (stored !== bitmap && !bitmap.isRecycled) {
            // Leave caller's bitmap lifecycle alone unless we own it, store compact scaled copy
        }

        val old = cache.put(tabId, stored)
        missingOnDisk.remove(tabId)
        if (old != null && old !== stored && old !== lastActiveTabBitmap && !old.isRecycled) {
            old.recycle()
        }

        if (isPrivate) {
            privateTabIds.add(tabId)
            bumpVersion()
            return
        }

        privateTabIds.remove(tabId)
        setLastActiveBitmap(stored)
        bumpVersion()

        val dir = previewDir ?: return
        val copy = try {
            stored.copy(Bitmap.Config.ARGB_8888, false)
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
                    copy.compress(fmt, 80, out)
                }
            } catch (_: Exception) {
            } finally {
                if (!copy.isRecycled) copy.recycle()
            }
        }
    }

    /**
     * Fast non-blocking lookup: returns RAM bitmap immediately if present,
     * otherwise schedules a single background disk decode that bumps [versionFlow] when ready.
     */
    fun getOrLoadAsync(tabId: String, isPrivate: Boolean = false): Bitmap? {
        val mem = cache[tabId]
        if (mem != null && !mem.isRecycled) return mem

        if (isPrivate || tabId in privateTabIds || tabId in missingOnDisk) return null

        val dir = previewDir ?: return null
        if (!loadingFromDisk.add(tabId)) return null

        diskExecutor.execute {
            try {
                val file = File(dir, "$tabId.webp")
                if (!file.isFile || file.length() == 0L) {
                    missingOnDisk.add(tabId)
                } else {
                    val decoded = BitmapFactory.decodeFile(file.absolutePath)
                    if (decoded != null) {
                        val prev = cache.put(tabId, decoded)
                        if (prev != null && prev !== decoded && prev !== lastActiveTabBitmap && !prev.isRecycled) {
                            prev.recycle()
                        }
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
        val old = lastActiveTabBitmap
        if (old !== bitmap) {
            lastActiveTabBitmap = bitmap
            if (old != null && !cache.values.contains(old) && !old.isRecycled) {
                old.recycle()
            }
        }
    }

    fun getLastActiveBitmap(): Bitmap? {
        val bmp = lastActiveTabBitmap
        return if (bmp != null && !bmp.isRecycled) bmp else null
    }

    fun remove(tabId: String) {
        privateTabIds.remove(tabId)
        missingOnDisk.remove(tabId)
        loadingFromDisk.remove(tabId)
        val old = cache.remove(tabId)
        if (old != null && old === lastActiveTabBitmap) {
            lastActiveTabBitmap = null
        }
        if (old != null && !old.isRecycled) {
            old.recycle()
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
            if (old != null && !old.isRecycled) {
                old.recycle()
            }
        }
        if (targets.isNotEmpty()) {
            bumpVersion()
        }
        val dir = previewDir ?: return
        if (targets.isNotEmpty()) {
            diskExecutor.execute {
                targets.forEach { id ->
                    try { File(dir, "$id.webp").delete() } catch (_: Exception) {}
                }
            }
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
        privateTabIds.clear()
        loadingFromDisk.clear()
        missingOnDisk.clear()
        val old = lastActiveTabBitmap
        lastActiveTabBitmap = null
        if (old != null && !old.isRecycled) {
            old.recycle()
        }
        bumpVersion()
    }

    fun clear() {
        trimMemory()
        val dir = previewDir ?: return
        diskExecutor.execute {
            try { dir.listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
        }
    }
}
