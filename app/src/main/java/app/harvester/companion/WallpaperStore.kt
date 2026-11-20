package app.harvester.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.core.graphics.ColorUtils
import app.harvester.companion.wallpaper.VideoClipBounds
import app.harvester.companion.wallpaper.WallpaperCatalog
import app.harvester.companion.wallpaper.WallpaperMediaCache
import app.harvester.companion.wallpaper.WallpaperMediaProcessor
import app.harvester.companion.wallpaper.WallpaperTransform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

object WallpaperStore {
    private const val PREFS_NAME = "uh_wallpapers"
    private const val KEY_CURRENT = "current_wallpaper_path"
    private const val KEY_SOURCE_URL = "current_wallpaper_source_url"
    private const val KEY_TEXT_COLOR = "wallpaper_text_color"
    private const val KEY_IS_VIDEO = "wallpaper_is_video"
    private const val KEY_DIM_ALPHA = "wallpaper_dim_alpha"
    private const val KEY_TOP_DARK = "wallpaper_top_dark"
    private const val KEY_BOTTOM_DARK = "wallpaper_bottom_dark"
    private const val KEY_TRANSFORM_V2 = "wallpaper_transform_v2"
    private const val KEY_ZOOM_PORTRAIT = "wallpaper_zoom_portrait"
    private const val KEY_PAN_X_PORTRAIT = "wallpaper_pan_x_portrait"
    private const val KEY_PAN_Y_PORTRAIT = "wallpaper_pan_y_portrait"
    private const val KEY_ZOOM_LANDSCAPE = "wallpaper_zoom_landscape"
    private const val KEY_PAN_X_LANDSCAPE = "wallpaper_pan_x_landscape"
    private const val KEY_PAN_Y_LANDSCAPE = "wallpaper_pan_y_landscape"
    private const val KEY_RECENTS_ORDER = "wallpaper_recents_order"
    private const val KEY_CLIP_BOUNDS_PREFIX = "video_clip_"

    const val MAX_RECENT_ITEMS = 30
    private const val MAX_NON_ACTIVE_STORAGE_BYTES = 600L * 1024L * 1024L
    const val MAX_DIM_ALPHA = 0.65f

    data class WallpaperState(
        val path: String?,
        val sourceUrl: String? = null,
        val isVideo: Boolean = false,
        val textColor: Int = 0,
        val dimAlpha: Float = 0f,
        val isTopDark: Boolean? = null,
        val isBottomDark: Boolean? = null,
        val portraitTransform: WallpaperTransform = WallpaperTransform.Identity,
        val landscapeTransform: WallpaperTransform = WallpaperTransform.Identity,
        val clipBounds: VideoClipBounds? = null,
    )

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _stateFlow = MutableStateFlow<WallpaperState?>(null)

    private fun getPrefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun wallpaperFlow(ctx: Context): StateFlow<WallpaperState> {
        val current = _stateFlow.value
        if (current == null) {
            val loaded = readState(ctx)
            _stateFlow.value = loaded
            if (!loaded.path.isNullOrBlank() && loaded.path != "default" && loaded.isTopDark == null) {
                computeAndPersistLuminanceAsync(
                    ctx = ctx.applicationContext,
                    path = loaded.path,
                    isVideo = loaded.isVideo,
                    textColor = loaded.textColor,
                    dimAlpha = loaded.dimAlpha,
                )
            }
        }
        @Suppress("UNCHECKED_CAST")
        return _stateFlow.asStateFlow() as StateFlow<WallpaperState>
    }

    private fun readState(ctx: Context): WallpaperState {
        val prefs = getPrefs(ctx)
        var path = prefs.getString(KEY_CURRENT, null)
        var isVideo = prefs.getBoolean(KEY_IS_VIDEO, false)
        var textColor = prefs.getInt(KEY_TEXT_COLOR, 0)
        var sourceUrl = prefs.getString(KEY_SOURCE_URL, null)
        var dimAlpha = prefs.getFloat(KEY_DIM_ALPHA, 0f).coerceIn(0f, MAX_DIM_ALPHA)

        // Guard against deleted/missing local file leaving a broken black Home background
        if (!path.isNullOrBlank() && path != "default" && !path.startsWith("http") && !path.startsWith("content://")) {
            val file = File(path)
            if (!file.exists() || file.length() == 0L) {
                path = "default"
                isVideo = false
                textColor = 0
                sourceUrl = null
                dimAlpha = 0f
                prefs.edit()
                    .putString(KEY_CURRENT, "default")
                    .remove(KEY_SOURCE_URL)
                    .putBoolean(KEY_IS_VIDEO, false)
                    .putInt(KEY_TEXT_COLOR, 0)
                    .putFloat(KEY_DIM_ALPHA, 0f)
                    .putInt(KEY_TOP_DARK, -1)
                    .putInt(KEY_BOTTOM_DARK, -1)
                    .apply()
            }
        }

        val hasCustom = !path.isNullOrBlank() && path != "default"
        val topInt = if (hasCustom) prefs.getInt(KEY_TOP_DARK, -1) else -1
        val bottomInt = if (hasCustom) prefs.getInt(KEY_BOTTOM_DARK, -1) else -1
        return WallpaperState(
            path = path,
            sourceUrl = if (hasCustom) sourceUrl else null,
            isVideo = isVideo,
            textColor = textColor,
            dimAlpha = if (hasCustom) dimAlpha else 0f,
            isTopDark = when (topInt) { 1 -> true; 0 -> false; else -> null },
            isBottomDark = when (bottomInt) { 1 -> true; 0 -> false; else -> null },
            portraitTransform = readTransform(prefs, isLandscape = false),
            landscapeTransform = readTransform(prefs, isLandscape = true),
            clipBounds = if (hasCustom && isVideo) getVideoClipBounds(ctx, path) else null,
        )
    }

    private fun readTransform(
        prefs: android.content.SharedPreferences,
        isLandscape: Boolean,
    ): WallpaperTransform {
        if (!prefs.getBoolean(KEY_TRANSFORM_V2, false)) {
            return WallpaperTransform.Identity
        }
        val zoomKey = if (isLandscape) KEY_ZOOM_LANDSCAPE else KEY_ZOOM_PORTRAIT
        val panXKey = if (isLandscape) KEY_PAN_X_LANDSCAPE else KEY_PAN_X_PORTRAIT
        val panYKey = if (isLandscape) KEY_PAN_Y_LANDSCAPE else KEY_PAN_Y_PORTRAIT
        return WallpaperTransform(
            scale = prefs.getFloat(zoomKey, 1f),
            offsetXFraction = prefs.getFloat(panXKey, 0f),
            offsetYFraction = prefs.getFloat(panYKey, 0f),
        ).clamped()
    }

    fun getWallpaperDir(ctx: Context): File {
        val dir = File(ctx.getExternalFilesDir(null)?.parentFile ?: ctx.filesDir, "wallpapers")
        if (!dir.exists()) dir.mkdirs()
        val nomedia = File(dir, ".nomedia")
        if (!nomedia.exists()) {
            try { nomedia.createNewFile() } catch (_: Exception) {}
        }
        return dir
    }

    fun posterFileFor(videoPath: String): File = File("${videoPath}.poster.webp")

    fun isVideoFileOrUrl(value: String): Boolean {
        val lower = value.lowercase().substringBefore('?')
        return lower.endsWith(".mp4") ||
            lower.endsWith(".webm") ||
            lower.endsWith(".mkv") ||
            lower.endsWith(".mov") ||
            lower.endsWith(".m4v") ||
            lower.endsWith(".3gp") ||
            lower.endsWith(".avi") ||
            File(value).name.startsWith("motion_")
    }

    // -------------------------------------------------------------------------
    // Non-destructive video trim clip bounds
    // -------------------------------------------------------------------------

    fun setVideoClipBounds(ctx: Context, videoPath: String, bounds: VideoClipBounds?) {
        val name = File(videoPath).name
        if (name.isBlank()) return
        val key = KEY_CLIP_BOUNDS_PREFIX + name
        val editor = getPrefs(ctx).edit()
        if (bounds != null && bounds.isValid) {
            editor.putString(key, "${bounds.startMs}:${bounds.endMs}")
        } else {
            editor.remove(key)
        }
        editor.apply()

        val prev = _stateFlow.value
        if (prev != null && prev.path == videoPath) {
            _stateFlow.value = prev.copy(clipBounds = bounds?.takeIf { it.isValid })
        }
    }

    fun getVideoClipBounds(ctx: Context, videoPath: String?): VideoClipBounds? {
        if (videoPath.isNullOrBlank() || videoPath.startsWith("http")) return null
        val name = File(videoPath).name
        if (name.isBlank()) return null
        val raw = getPrefs(ctx).getString(KEY_CLIP_BOUNDS_PREFIX + name, null) ?: return null
        val parts = raw.split(":")
        if (parts.size != 2) return null
        val startMs = parts[0].toLongOrNull() ?: return null
        val endMs = parts[1].toLongOrNull() ?: return null
        return VideoClipBounds(startMs, endMs).takeIf { it.isValid }
    }

    // -------------------------------------------------------------------------
    // Persisted Recents ordering (supports drag-and-drop reordering)
    // -------------------------------------------------------------------------

    private fun readSavedOrder(ctx: Context): List<String> {
        val raw = getPrefs(ctx).getString(KEY_RECENTS_ORDER, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split('\n').filter { it.isNotBlank() }
    }

    private fun writeSavedOrder(ctx: Context, fileNames: List<String>) {
        val distinct = fileNames.filter { it.isNotBlank() }.distinct().take(MAX_RECENT_ITEMS * 2)
        getPrefs(ctx).edit().putString(KEY_RECENTS_ORDER, distinct.joinToString("\n")).apply()
    }

    private fun prependToRecentOrder(ctx: Context, fileName: String) {
        val current = readSavedOrder(ctx).toMutableList()
        current.remove(fileName)
        current.add(0, fileName)
        writeSavedOrder(ctx, current)
    }

    private fun removeFromRecentOrder(ctx: Context, fileName: String) {
        val current = readSavedOrder(ctx).toMutableList()
        if (current.remove(fileName)) {
            writeSavedOrder(ctx, current)
        }
    }

    fun saveRecentOrder(ctx: Context, orderedPaths: List<String>) {
        writeSavedOrder(ctx, orderedPaths.map { File(it).name })
    }

    /**
     * Lossless local image import — keeps original file verbatim (zero compression, zero downscale),
     * deduplicates by content hash, and places it at the top of Recents.
     */
    fun saveWallpaper(ctx: Context, uri: Uri): String? {
        return try {
            val destFile = WallpaperMediaProcessor.importLocalImageLossless(ctx, uri, getWallpaperDir(ctx))
                ?: return null
            val canonical = dedupeWallpapers(ctx, destFile, "wall_")
            prependToRecentOrder(ctx, canonical.name)
            cleanOldWallpapers(ctx)
            canonical.absolutePath
        } catch (e: Exception) {
            UHLog.e("wallpaper_error", "saveWallpaper failed: ${e.message}")
            null
        }
    }

    /**
     * Zero-reencode local video import:
     * - When trimmed ([startSec] and [durationSec] provided): losslessly extracts ONLY the
     *   trimmed slice directly from [uri] via native MediaExtractor + MediaMuxer (never copies
     *   a full 1-hour video, zero re-encoding, zero quality drop) and stores only any small
     *   sub-GOP fine clip bounds if the nearest keyframe preceded [startSec].
     * - When full video (<= 30s): copies the exact original file verbatim untouched.
     */
    fun saveLocalVideo(
        ctx: Context,
        uri: Uri,
        startSec: Float? = null,
        durationSec: Float? = null,
        onProgress: ((Float) -> Unit)? = null,
    ): String? {
        return try {
            val isTrimmed = startSec != null && durationSec != null && durationSec >= 0.5f
            val destFile: File
            val fineClipBounds: VideoClipBounds?

            if (isTrimmed) {
                val sMs = (startSec!! * 1000f).toLong().coerceAtLeast(0L)
                val eMs = ((startSec + durationSec!!) * 1000f).toLong().coerceAtLeast(sMs + 500L)
                val trimmed = WallpaperMediaProcessor.importLocalVideoTrimmedLossless(
                    ctx = ctx,
                    uri = uri,
                    destDir = getWallpaperDir(ctx),
                    startMs = sMs,
                    endMs = eMs,
                    onProgress = onProgress,
                ) ?: return null
                destFile = trimmed.file
                fineClipBounds = trimmed.fineClipBounds
            } else {
                destFile = WallpaperMediaProcessor.importLocalVideoVerbatim(
                    ctx = ctx,
                    uri = uri,
                    destDir = getWallpaperDir(ctx),
                    onProgress = onProgress,
                ) ?: return null
                fineClipBounds = null
            }

            val canonical = dedupeWallpapers(ctx, destFile, "motion_")
            setVideoClipBounds(ctx, canonical.absolutePath, fineClipBounds)
            ensureVideoPoster(
                videoPath = canonical.absolutePath,
                timeMs = fineClipBounds?.startMs ?: 0L,
                forceRegenerate = isTrimmed,
            )
            prependToRecentOrder(ctx, canonical.name)
            cleanOldWallpapers(ctx)
            canonical.absolutePath
        } catch (e: Exception) {
            UHLog.e("wallpaper_error", "saveLocalVideo failed: ${e.message}")
            null
        }
    }

    /**
     * Downloads or promotes a cached remote wallpaper at maximum screen resolution without
     * lossy re-compression or video re-encoding.
     */
    suspend fun downloadAndPrepareWallpaper(
        ctx: Context,
        rawUrl: String,
        onStageChanged: ((isDownloading: Boolean) -> Unit)? = null,
    ): String? =
        withContext(Dispatchers.IO) {
            try {
                val resolvedUrl = WallpaperCatalog.fullResolutionUrl(ctx, rawUrl)
                val hash = WallpaperMediaCache.urlHash(rawUrl)
                val dir = getWallpaperDir(ctx)

                // 1. Reuse already-saved wallpaper in permanent storage if present
                findExistingByHash(dir, hash)?.let { existing ->
                    onStageChanged?.invoke(false)
                    existing.setLastModified(System.currentTimeMillis())
                    prependToRecentOrder(ctx, existing.name)
                    if (isVideoFileOrUrl(existing.absolutePath)) {
                        ensureVideoPoster(existing.absolutePath)
                    }
                    return@withContext existing.absolutePath
                }

                val expectVideo = isVideoFileOrUrl(rawUrl)
                if (expectVideo) {
                    val alreadyCached = WallpaperMediaCache.getCachedVideoFile(ctx, resolvedUrl) != null
                    onStageChanged?.invoke(!alreadyCached)
                    val cachedVideo = WallpaperMediaCache.getOrDownloadVideo(ctx, resolvedUrl)
                        ?: return@withContext null
                    onStageChanged?.invoke(false)
                    val finalVideo = WallpaperMediaProcessor.finalizeRemoteVideoVerbatim(
                        srcFile = cachedVideo,
                        destDir = dir,
                        hash = hash,
                        url = resolvedUrl,
                    )
                    if (finalVideo != null) {
                        ensureVideoPoster(finalVideo.absolutePath)
                        prependToRecentOrder(ctx, finalVideo.name)
                        cleanOldWallpapers(ctx)
                        return@withContext finalVideo.absolutePath
                    }
                    return@withContext null
                }

                // 2b. Remote image: promote directly from Coil disk cache if already loaded in Preview
                val tmpImage = File(ctx.cacheDir, "wp_dl_${hash}_${System.currentTimeMillis()}.tmp")
                val promotedFromCache = WallpaperMediaCache.tryExportCoilCachedImage(ctx, resolvedUrl, tmpImage)
                val ready = if (promotedFromCache) {
                    onStageChanged?.invoke(false)
                    true
                } else {
                    onStageChanged?.invoke(true)
                    val ok = WallpaperMediaCache.downloadToFile(resolvedUrl, tmpImage)
                    onStageChanged?.invoke(false)
                    ok
                }

                if (!ready || !tmpImage.exists() || tmpImage.length() < 64L) {
                    tmpImage.delete()
                    return@withContext null
                }

                if (WallpaperMediaProcessor.hasVideoMagicBytes(tmpImage)) {
                    val finalVideo = WallpaperMediaProcessor.finalizeRemoteVideoVerbatim(
                        srcFile = tmpImage,
                        destDir = dir,
                        hash = hash,
                        url = resolvedUrl,
                    )
                    tmpImage.delete()
                    if (finalVideo != null) {
                        ensureVideoPoster(finalVideo.absolutePath)
                        prependToRecentOrder(ctx, finalVideo.name)
                        cleanOldWallpapers(ctx)
                        return@withContext finalVideo.absolutePath
                    }
                    return@withContext null
                }

                val finalImg = WallpaperMediaProcessor.finalizeRemoteImageLossless(
                    srcFile = tmpImage,
                    destDir = dir,
                    hash = hash,
                    url = resolvedUrl,
                )
                tmpImage.delete()
                if (finalImg != null) {
                    prependToRecentOrder(ctx, finalImg.name)
                    cleanOldWallpapers(ctx)
                    finalImg.absolutePath
                } else {
                    null
                }
            } catch (e: Exception) {
                UHLog.e("wallpaper_error", "downloadAndPrepareWallpaper failed: ${e.message}")
                null
            }
        }

    fun hasSavedRemoteWallpaper(ctx: Context, rawUrl: String): Boolean {
        val hash = WallpaperMediaCache.urlHash(rawUrl)
        return findExistingByHash(getWallpaperDir(ctx), hash) != null
    }

    private fun findExistingByHash(dir: File, hash: String): File? {
        return dir.listFiles { f ->
            f.isFile &&
                (f.name.startsWith("motion_$hash.") || f.name.startsWith("wall_$hash.")) &&
                !f.name.endsWith(".poster.webp") &&
                f.length() > 128L
        }?.firstOrNull()
    }

    fun isRemoteUrlActive(ctx: Context, rawUrl: String, state: WallpaperState): Boolean {
        val activePath = state.path
        if (activePath.isNullOrBlank() || activePath == "default") return false
        val canonical = WallpaperCatalog.canonicalSourceKey(rawUrl)
        if (!state.sourceUrl.isNullOrBlank() && state.sourceUrl == canonical) return true
        val hash = WallpaperMediaCache.urlHash(rawUrl)
        val activeName = File(activePath).name
        return activeName.startsWith("motion_$hash.") || activeName.startsWith("wall_$hash.")
    }

    fun ensureVideoPoster(videoPath: String, timeMs: Long = 0L, forceRegenerate: Boolean = false) {
        val poster = posterFileFor(videoPath)
        if (!forceRegenerate && poster.exists() && poster.length() > 256L) return
        val thumb = WallpaperMediaProcessor.generateVideoPosterBitmap(videoPath, timeMs) ?: return
        try {
            poster.outputStream().use { out ->
                thumb.compress(WallpaperMediaProcessor.webpPosterFormat(), 95, out)
            }
        } catch (_: Exception) {
        } finally {
            thumb.recycle()
        }
    }

    fun generateVideoThumbnail(videoPath: String, timeMs: Long = 0L): Bitmap? =
        WallpaperMediaProcessor.generateVideoPosterBitmap(videoPath, timeMs)

    /**
     * Deduplicates both static and motion wallpapers using size + head/mid/tail content fingerprint.
     */
    private fun dedupeWallpapers(ctx: Context, newest: File, prefix: String): File {
        val hash = getFastContentFingerprint(newest)
        if (hash.isEmpty()) return newest
        val duplicate = getWallpaperDir(ctx).listFiles { f ->
            f.isFile &&
                f.name.startsWith(prefix) &&
                !f.name.endsWith(".poster.webp") &&
                f.absolutePath != newest.absolutePath
        }?.firstOrNull { existing ->
            getFastContentFingerprint(existing) == hash
        }

        if (duplicate != null) {
            duplicate.setLastModified(System.currentTimeMillis())
            newest.delete()
            posterFileFor(newest.absolutePath).delete()
            return duplicate
        }
        return newest
    }

    private fun getFastContentFingerprint(file: File): String {
        return try {
            if (!file.exists() || file.length() == 0L) return ""
            val len = file.length()
            val md = MessageDigest.getInstance("MD5")
            md.update(len.toString().toByteArray())
            java.io.RandomAccessFile(file, "r").use { raf ->
                val chunk = ByteArray(4096)
                val headRead = raf.read(chunk)
                if (headRead > 0) md.update(chunk, 0, headRead)
                if (len > 8192L) {
                    raf.seek(len / 2L)
                    val midRead = raf.read(chunk)
                    if (midRead > 0) md.update(chunk, 0, midRead)
                    raf.seek((len - 4096L).coerceAtLeast(0L))
                    val tailRead = raf.read(chunk)
                    if (tailRead > 0) md.update(chunk, 0, tailRead)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }
    }

    private fun cleanOldWallpapers(ctx: Context) {
        val dir = getWallpaperDir(ctx)
        val currentPath = getCurrentWallpaper(ctx)
        val files = dir.listFiles { f ->
            f.isFile &&
                (f.name.startsWith("wall_") || f.name.startsWith("motion_")) &&
                !f.name.endsWith(".poster.webp")
        } ?: return

        val ordered = orderWallpaperFiles(ctx, files.toList())
        // Evict from the tail (least-recent / bottom of custom order) while never deleting active
        val nonActiveTailFirst = ordered.reversed().filter { it.absolutePath != currentPath }.toMutableList()

        // 1. Enforce max 30 recent items
        var currentCount = files.size
        val iter = nonActiveTailFirst.iterator()
        while (currentCount > MAX_RECENT_ITEMS && iter.hasNext()) {
            val victim = iter.next()
            victim.delete()
            posterFileFor(victim.absolutePath).delete()
            setVideoClipBounds(ctx, victim.absolutePath, null)
            removeFromRecentOrder(ctx, victim.name)
            iter.remove()
            currentCount--
        }

        // 2. Enforce non-active storage budget so large uncompressed 4K clips don't bloat disk forever
        var nonActiveBytes = nonActiveTailFirst.sumOf { it.length() }
        val sizeIter = nonActiveTailFirst.iterator()
        while (nonActiveBytes > MAX_NON_ACTIVE_STORAGE_BYTES && sizeIter.hasNext()) {
            val victim = sizeIter.next()
            val size = victim.length()
            victim.delete()
            posterFileFor(victim.absolutePath).delete()
            setVideoClipBounds(ctx, victim.absolutePath, null)
            removeFromRecentOrder(ctx, victim.name)
            nonActiveBytes -= size
            sizeIter.remove()
        }

        // 3. Clean orphaned posters
        dir.listFiles { f -> f.isFile && f.name.endsWith(".poster.webp") }?.forEach { poster ->
            val parentVideo = File(poster.absolutePath.removeSuffix(".poster.webp"))
            if (!parentVideo.exists()) poster.delete()
        }
    }

    private fun orderWallpaperFiles(ctx: Context, files: List<File>): List<File> {
        val savedOrder = readSavedOrder(ctx)
        if (savedOrder.isEmpty()) {
            return files.sortedByDescending { it.lastModified() }
        }
        val orderRank = savedOrder.withIndex().associate { (index, name) -> name to index }
        val (tracked, untracked) = files.partition { orderRank.containsKey(it.name) }
        val sortedUntracked = untracked.sortedByDescending { it.lastModified() }
        val sortedTracked = tracked.sortedBy { orderRank[it.name] ?: Int.MAX_VALUE }
        return sortedUntracked + sortedTracked
    }

    fun setCurrentWallpaper(
        ctx: Context,
        path: String?,
        textColor: Int,
        isVideo: Boolean = false,
        sourceUrl: String? = null,
        dimAlpha: Float = 0f,
    ) {
        val appCtx = ctx.applicationContext
        val prefs = getPrefs(appCtx)
        val isCustom = !path.isNullOrBlank() && path != "default"
        val safeDim = if (isCustom) dimAlpha.coerceIn(0f, MAX_DIM_ALPHA) else 0f
        val canonicalSource = if (isCustom && !sourceUrl.isNullOrBlank()) {
            WallpaperCatalog.canonicalSourceKey(sourceUrl)
        } else null

        val editor = prefs.edit()
            .putString(KEY_CURRENT, path)
            .putString(KEY_SOURCE_URL, canonicalSource)
            .putInt(KEY_TEXT_COLOR, if (isCustom) textColor else 0)
            .putBoolean(KEY_IS_VIDEO, isCustom && isVideo)
            .putFloat(KEY_DIM_ALPHA, safeDim)

        if (!isCustom) {
            editor.putInt(KEY_TOP_DARK, -1)
                .putInt(KEY_BOTTOM_DARK, -1)
                .putBoolean(KEY_TRANSFORM_V2, true)
                .putFloat(KEY_ZOOM_PORTRAIT, 1f)
                .putFloat(KEY_PAN_X_PORTRAIT, 0f)
                .putFloat(KEY_PAN_Y_PORTRAIT, 0f)
                .putFloat(KEY_ZOOM_LANDSCAPE, 1f)
                .putFloat(KEY_PAN_X_LANDSCAPE, 0f)
                .putFloat(KEY_PAN_Y_LANDSCAPE, 0f)
                .apply()
            _stateFlow.value = WallpaperState(
                path = path,
                sourceUrl = null,
                isVideo = false,
                textColor = 0,
                dimAlpha = 0f,
                isTopDark = null,
                isBottomDark = null,
                portraitTransform = WallpaperTransform.Identity,
                landscapeTransform = WallpaperTransform.Identity,
                clipBounds = null,
            )
            return
        }

        val estDark = if (safeDim >= 0.35f) {
            true
        } else if (textColor != 0) {
            ColorUtils.calculateLuminance(textColor) > 0.5
        } else {
            true
        }

        editor.putInt(KEY_TOP_DARK, if (estDark) 1 else 0)
            .putInt(KEY_BOTTOM_DARK, if (estDark) 1 else 0)
            .apply()

        val portraitTransform = readTransform(prefs, isLandscape = false)
        val landscapeTransform = readTransform(prefs, isLandscape = true)
        val activeClipBounds = if (isVideo) getVideoClipBounds(appCtx, path) else null
        _stateFlow.value = WallpaperState(
            path = path,
            sourceUrl = canonicalSource,
            isVideo = isVideo,
            textColor = textColor,
            dimAlpha = safeDim,
            isTopDark = estDark,
            isBottomDark = estDark,
            portraitTransform = portraitTransform,
            landscapeTransform = landscapeTransform,
            clipBounds = activeClipBounds,
        )

        computeAndPersistLuminanceAsync(appCtx, path!!, isVideo, textColor, safeDim)
    }

    private fun computeAndPersistLuminanceAsync(
        ctx: Context,
        path: String,
        isVideo: Boolean,
        textColor: Int,
        dimAlpha: Float,
    ) {
        ioScope.launch {
            try {
                val clipStart = if (isVideo) getVideoClipBounds(ctx, path)?.startMs ?: 0L else 0L
                if (isVideo) ensureVideoPoster(path, clipStart)
                val poster = if (isVideo) posterFileFor(path) else null
                val bmp = when {
                    poster != null && poster.exists() -> getSampledBitmap(poster.absolutePath, 240, 420)
                    isVideo -> generateVideoThumbnail(path, clipStart)
                    else -> getSampledBitmap(path, 240, 420)
                }
                if (bmp != null) {
                    val topDark = isStripDark(bmp, topRatioStart = 0f, topRatioEnd = 0.14f, dimAlpha = dimAlpha)
                    val bottomDark = isStripDark(bmp, topRatioStart = 0.86f, topRatioEnd = 1.0f, dimAlpha = dimAlpha)
                    bmp.recycle()
                    val prefs = getPrefs(ctx)
                    if (prefs.getString(KEY_CURRENT, null) == path) {
                        prefs.edit()
                            .putInt(KEY_TOP_DARK, if (topDark) 1 else 0)
                            .putInt(KEY_BOTTOM_DARK, if (bottomDark) 1 else 0)
                            .apply()
                        val prev = _stateFlow.value ?: readState(ctx)
                        _stateFlow.value = prev.copy(
                            isTopDark = topDark,
                            isBottomDark = bottomDark,
                        )
                    }
                }
            } catch (e: Exception) {
                UHLog.w("wallpaper_warn", "Failed calculating wallpaper strip luminance: ${e.message}")
            }
        }
    }

    fun isStripDark(
        bmp: Bitmap,
        topRatioStart: Float,
        topRatioEnd: Float,
        dimAlpha: Float = 0f,
    ): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return true
        val startY = (h * topRatioStart).toInt().coerceIn(0, h - 1)
        val endY = (h * topRatioEnd).toInt().coerceIn(startY + 1, h)
        val stepX = (w / 16).coerceAtLeast(1)
        val stepY = ((endY - startY) / 6).coerceAtLeast(1)
        val safeDim = dimAlpha.coerceIn(0f, 1f)
        var lumSum = 0.0
        var count = 0
        var y = startY
        while (y < endY) {
            var x = 0
            while (x < w) {
                val rawPixel = bmp.getPixel(x, y)
                val blended = if (safeDim > 0.01f) {
                    ColorUtils.blendARGB(rawPixel, Color.BLACK, safeDim)
                } else rawPixel
                lumSum += ColorUtils.calculateLuminance(blended)
                count++
                x += stepX
            }
            y += stepY
        }
        val avgLum = if (count > 0) lumSum / count else 0.0
        return avgLum < 0.5
    }

    fun getCurrentWallpaper(ctx: Context): String? = getPrefs(ctx).getString(KEY_CURRENT, null)

    fun isCurrentVideo(ctx: Context): Boolean = getPrefs(ctx).getBoolean(KEY_IS_VIDEO, false)

    fun getTextColor(ctx: Context): Int = getPrefs(ctx).getInt(KEY_TEXT_COLOR, 0)

    fun getDimAlpha(ctx: Context): Float = getPrefs(ctx).getFloat(KEY_DIM_ALPHA, 0f).coerceIn(0f, MAX_DIM_ALPHA)

    fun setWallpaperTransform(ctx: Context, isLandscape: Boolean, transform: WallpaperTransform) {
        val clamped = transform.clamped()
        val zoomKey = if (isLandscape) KEY_ZOOM_LANDSCAPE else KEY_ZOOM_PORTRAIT
        val panXKey = if (isLandscape) KEY_PAN_X_LANDSCAPE else KEY_PAN_X_PORTRAIT
        val panYKey = if (isLandscape) KEY_PAN_Y_LANDSCAPE else KEY_PAN_Y_PORTRAIT
        getPrefs(ctx).edit()
            .putBoolean(KEY_TRANSFORM_V2, true)
            .putFloat(zoomKey, clamped.scale)
            .putFloat(panXKey, clamped.offsetXFraction)
            .putFloat(panYKey, clamped.offsetYFraction)
            .apply()

        val prev = _stateFlow.value
        if (prev != null) {
            _stateFlow.value = if (isLandscape) {
                prev.copy(landscapeTransform = clamped)
            } else {
                prev.copy(portraitTransform = clamped)
            }
        }
    }

    fun resetWallpaperTransforms(ctx: Context) {
        getPrefs(ctx).edit()
            .putBoolean(KEY_TRANSFORM_V2, true)
            .putFloat(KEY_ZOOM_PORTRAIT, 1f)
            .putFloat(KEY_PAN_X_PORTRAIT, 0f)
            .putFloat(KEY_PAN_Y_PORTRAIT, 0f)
            .putFloat(KEY_ZOOM_LANDSCAPE, 1f)
            .putFloat(KEY_PAN_X_LANDSCAPE, 0f)
            .putFloat(KEY_PAN_Y_LANDSCAPE, 0f)
            .apply()
        val prev = _stateFlow.value
        if (prev != null) {
            _stateFlow.value = prev.copy(
                portraitTransform = WallpaperTransform.Identity,
                landscapeTransform = WallpaperTransform.Identity,
            )
        }
    }

    fun getWallpaperTransform(ctx: Context, isLandscape: Boolean): WallpaperTransform =
        readTransform(getPrefs(ctx), isLandscape)

    fun getRecent(ctx: Context): List<String> {
        val dir = getWallpaperDir(ctx)
        val files = dir.listFiles { file ->
            file.isFile &&
                (file.name.startsWith("wall_") || file.name.startsWith("motion_")) &&
                !file.name.endsWith(".poster.webp")
        }?.toList() ?: return emptyList()

        return orderWallpaperFiles(ctx, files)
            .map { it.absolutePath }
            .take(MAX_RECENT_ITEMS)
    }

    /**
     * Deletes a wallpaper, its poster, clip bounds, and recent-order entry. If active,
     * immediately falls back cleanly to "default" so Home never renders a broken blank canvas.
     */
    fun deleteWallpaper(ctx: Context, path: String) {
        try {
            val isDeletingActive = getCurrentWallpaper(ctx) == path
            val file = File(path)
            file.delete()
            posterFileFor(path).delete()
            setVideoClipBounds(ctx, path, null)
            removeFromRecentOrder(ctx, file.name)
            if (isDeletingActive) {
                setCurrentWallpaper(ctx, "default", 0, isVideo = false, sourceUrl = null, dimAlpha = 0f)
            }
        } catch (_: Exception) {}
    }

    fun clearAll(ctx: Context) {
        try {
            getWallpaperDir(ctx).listFiles()?.forEach {
                if (it.name != ".nomedia") it.delete()
            }
            WallpaperMediaCache.clearCache(ctx)
            getPrefs(ctx).edit().clear().apply()
            _stateFlow.value = WallpaperState(
                path = null,
                sourceUrl = null,
                isVideo = false,
                textColor = 0,
                dimAlpha = 0f,
                isTopDark = null,
                isBottomDark = null,
                portraitTransform = WallpaperTransform.Identity,
                landscapeTransform = WallpaperTransform.Identity,
                clipBounds = null,
            )
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
