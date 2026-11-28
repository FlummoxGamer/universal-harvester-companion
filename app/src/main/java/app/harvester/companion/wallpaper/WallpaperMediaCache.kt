package app.harvester.companion.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import app.harvester.companion.UHLog
import app.harvester.companion.core.network.UhHttpClient
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Single-flight remote wallpaper cache:
 * - Tracks live download progress and reactive local-ready notifications for Preview.
 * - Promotes assets cached during Preview straight into wallpaper storage on Apply
 *   with zero redundant re-downloads and zero ongoing background network streaming.
 * - Uses dual-mirror fallback (jsDelivr -> raw.githubusercontent.com) and Semaphore(2)
 *   throttling for motion thumbnails so fresh installs never rate-limit or hang.
 */
object WallpaperMediaCache {

    private const val CACHE_DIR_NAME = "wallpaper_remote_cache"
    private const val THUMB_DIR_NAME = "wallpaper_thumbs"
    private const val MAX_CACHE_BYTES = 180L * 1024L * 1024L // 180 MB cap for preview video cache
    private const val VIDEO_DOWNLOAD_TIMEOUT_MS = 32_000L
    private const val THUMB_EXTRACT_TIMEOUT_MS = 12_000L

    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<File?>>()
    private val inFlightCalls = ConcurrentHashMap<String, Call>()
    private val inFlightThumbs = ConcurrentHashMap<String, CompletableDeferred<File?>>()
    private val thumbSemaphore = Semaphore(2)

    private val downloadClient: OkHttpClient by lazy {
        UhHttpClient.client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(14, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .callTimeout(28, TimeUnit.SECONDS)
            .build()
    }

    // Keyed by urlHash(url): value in 0f..1f for determinate progress, -1f for indeterminate.
    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

    // Bumped whenever a remote video finishes caching to disk so Preview switches to file:// instantly.
    private val _cachedVideoTick = MutableStateFlow(0)
    val cachedVideoTick: StateFlow<Int> = _cachedVideoTick.asStateFlow()

    private fun cacheDir(ctx: Context): File {
        val dir = File(ctx.applicationContext.cacheDir, CACHE_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun thumbDir(ctx: Context): File {
        val dir = File(ctx.applicationContext.filesDir, THUMB_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun urlHash(url: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(WallpaperCatalog.canonicalSourceKey(url).toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun getCachedVideoFile(ctx: Context, url: String): File? {
        val file = File(cacheDir(ctx), "vid_${urlHash(url)}.mp4")
        return if (file.exists() && file.length() > 1024L) file else null
    }

    fun getCachedMotionThumb(ctx: Context, url: String): File? {
        val file = File(thumbDir(ctx), "mthumb_${urlHash(url)}.webp")
        return if (file.exists() && file.length() > 256L) file else null
    }

    @OptIn(ExperimentalCoilApi::class)
    fun hasCoilCachedImage(ctx: Context, resolvedUrl: String): Boolean {
        return try {
            val diskCache = ctx.applicationContext.imageLoader.diskCache ?: return false
            val snapshot = diskCache.openSnapshot(resolvedUrl) ?: return false
            snapshot.use { snap ->
                val src = snap.data.toFile()
                src.exists() && src.length() > 128L
            }
        } catch (_: Exception) {
            false
        }
    }

    fun isRemoteAssetCached(ctx: Context, rawUrl: String, isVideo: Boolean): Boolean {
        val resolvedUrl = WallpaperCatalog.fullResolutionUrl(ctx, rawUrl)
        return if (isVideo) {
            getCachedVideoFile(ctx, rawUrl) != null || getCachedVideoFile(ctx, resolvedUrl) != null
        } else {
            hasCoilCachedImage(ctx, resolvedUrl) || hasCoilCachedImage(ctx, rawUrl)
        }
    }

    /**
     * Extracts a single crisp WebP thumbnail for a remote Motion wallpaper and saves it
     * permanently in `filesDir/wallpaper_thumbs/` (single-flight deduplicated, strictly timed).
     */
    suspend fun getOrExtractMotionThumb(ctx: Context, url: String): File? = withContext(Dispatchers.IO) {
        val appCtx = ctx.applicationContext
        getCachedMotionThumb(appCtx, url)?.let { return@withContext it }

        val hash = urlHash(url)
        val existing = inFlightThumbs[hash]
        if (existing != null) {
            return@withContext withTimeoutOrNull(THUMB_EXTRACT_TIMEOUT_MS) { existing.await() }
        }

        val deferred = CompletableDeferred<File?>()
        val raced = inFlightThumbs.putIfAbsent(hash, deferred)
        if (raced != null) {
            return@withContext withTimeoutOrNull(THUMB_EXTRACT_TIMEOUT_MS) { raced.await() }
        }

        try {
            val result = thumbSemaphore.withPermit {
                withTimeoutOrNull(THUMB_EXTRACT_TIMEOUT_MS) {
                    val dest = File(thumbDir(appCtx), "mthumb_$hash.webp")
                    val cachedVideo = getCachedVideoFile(appCtx, url)
                    val bitmap = if (cachedVideo != null) {
                        extractScaledVideoFrameFromFile(cachedVideo.absolutePath, 500L)
                    } else {
                        extractScaledVideoFrameFromUrlWithFallback(url, 500L)
                    }

                    if (bitmap != null) {
                        try {
                            val tmp = File(dest.parentFile, "${dest.name}.tmp")
                            tmp.outputStream().use { out ->
                                bitmap.compress(WallpaperMediaProcessor.webpPosterFormat(), 88, out)
                            }
                            if (tmp.exists() && tmp.length() > 256L) {
                                if (dest.exists()) dest.delete()
                                if (!tmp.renameTo(dest)) {
                                    tmp.copyTo(dest, overwrite = true)
                                    tmp.delete()
                                }
                                dest
                            } else {
                                tmp.delete()
                                null
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    } else null
                }
            }

            deferred.complete(result)
            result
        } catch (e: Exception) {
            UHLog.w("wallpaper_warn", "Motion thumb extraction failed: ${e.message}")
            deferred.complete(null)
            null
        } finally {
            inFlightThumbs.remove(hash, deferred)
        }
    }

    private fun extractScaledVideoFrameFromFile(path: String, timeMs: Long): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            extractFrameScaled(retriever, timeMs)
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun extractScaledVideoFrameFromUrlWithFallback(url: String, timeMs: Long): Bitmap? {
        for (candidate in WallpaperCatalog.candidateUrls(url)) {
            val retriever = MediaMetadataRetriever()
            val bmp = try {
                retriever.setDataSource(candidate, mapOf("User-Agent" to UhHttpClient.DEFAULT_USER_AGENT))
                extractFrameScaled(retriever, timeMs)
            } catch (_: Exception) {
                null
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
            if (bmp != null) return bmp
        }
        return null
    }

    private fun extractFrameScaled(retriever: MediaMetadataRetriever, timeMs: Long): Bitmap? {
        val raw = retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.getFrameAtTime()
            ?: return null
        val maxSide = 640
        val largest = maxOf(raw.width, raw.height)
        if (largest <= maxSide) return raw
        val scale = maxSide.toFloat() / largest.toFloat()
        val w = (raw.width * scale).roundToInt().coerceAtLeast(1)
        val h = (raw.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(raw, w, h, true)
        if (scaled !== raw) raw.recycle()
        return scaled
    }

    /**
     * Starts (or joins) a single-flight background download for a remote motion wallpaper
     * while the user is previewing it.
     */
    fun prefetchVideoAsync(ctx: Context, url: String) {
        val appCtx = ctx.applicationContext
        if (getCachedVideoFile(appCtx, url) != null) return
        val key = "vid_${urlHash(url)}"
        if (inFlight.containsKey(key)) return
        cacheScope.launch {
            getOrDownloadVideo(appCtx, url)
        }
    }

    /**
     * Cancels an in-flight download cleanly (e.g. if timed out) so a fresh retry can start immediately.
     */
    fun cancelInFlightVideo(url: String) {
        val hash = urlHash(url)
        val key = "vid_$hash"
        inFlightCalls.remove(key)?.cancel()
        inFlight.remove(key)?.complete(null)
        clearProgress(hash)
    }

    /**
     * Returns a complete local cached video file, joining any in-flight preview prefetch
     * with a strict timeout so callers can never hang forever.
     */
    suspend fun getOrDownloadVideo(ctx: Context, url: String): File? = withContext(Dispatchers.IO) {
        val appCtx = ctx.applicationContext
        getCachedVideoFile(appCtx, url)?.let { existing ->
            existing.setLastModified(System.currentTimeMillis())
            return@withContext existing
        }

        val hash = urlHash(url)
        val key = "vid_$hash"
        val existingDeferred = inFlight[key]
        if (existingDeferred != null) {
            val joined = withTimeoutOrNull(VIDEO_DOWNLOAD_TIMEOUT_MS) { existingDeferred.await() }
            if (joined != null) return@withContext joined
            // Stale or hung prefetch — cancel and attempt one fresh clean download
            if (!existingDeferred.isCompleted) {
                UHLog.w("wallpaper_warn", "In-flight video prefetch timed out; resetting for clean retry.")
                cancelInFlightVideo(url)
            } else {
                return@withContext null
            }
        }

        val deferred = CompletableDeferred<File?>()
        val raced = inFlight.putIfAbsent(key, deferred)
        if (raced != null) {
            return@withContext withTimeoutOrNull(VIDEO_DOWNLOAD_TIMEOUT_MS) { raced.await() }
        }

        setProgress(hash, -1f)
        try {
            val dest = File(cacheDir(appCtx), "$key.mp4")
            val part = File(cacheDir(appCtx), "$key.mp4.part")
            val ok = withTimeoutOrNull(VIDEO_DOWNLOAD_TIMEOUT_MS) {
                downloadToFile(url, part, callKey = key) { frac ->
                    setProgress(hash, frac)
                }
            } == true
            if (!ok) {
                inFlightCalls.remove(key)?.cancel()
            }
            val result = if (ok && part.exists() && part.length() > 1024L) {
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) {
                    part.copyTo(dest, overwrite = true)
                    part.delete()
                }
                trimCacheIfNeeded(appCtx)
                _cachedVideoTick.update { it + 1 }
                if (getCachedMotionThumb(appCtx, url) == null) {
                    cacheScope.launch { getOrExtractMotionThumb(appCtx, url) }
                }
                dest
            } else {
                part.delete()
                null
            }
            deferred.complete(result)
            result
        } catch (e: Exception) {
            UHLog.e("wallpaper_error", "Remote video cache failed: ${e.message}")
            inFlightCalls.remove(key)?.cancel()
            deferred.complete(null)
            null
        } finally {
            clearProgress(hash)
            inFlightCalls.remove(key)
            inFlight.remove(key, deferred)
        }
    }

    private fun setProgress(hash: String, value: Float) {
        _downloadProgress.update { current ->
            if (current[hash] == value) current else current + (hash to value)
        }
    }

    private fun clearProgress(hash: String) {
        _downloadProgress.update { current ->
            if (hash !in current) current else current - hash
        }
    }

    /**
     * Attempts to export an already-cached remote image directly from Coil's disk cache
     * (zero re-download, original bytes preserved verbatim).
     */
    @OptIn(ExperimentalCoilApi::class)
    fun tryExportCoilCachedImage(ctx: Context, url: String, destFile: File): Boolean {
        return try {
            val diskCache = ctx.applicationContext.imageLoader.diskCache ?: return false
            val snapshot = diskCache.openSnapshot(url) ?: return false
            snapshot.use { snap ->
                val src = snap.data.toFile()
                if (src.exists() && src.length() > 128L) {
                    src.copyTo(destFile, overwrite = true)
                    return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    fun downloadToFile(
        url: String,
        destFile: File,
        callKey: String? = null,
        onProgress: ((Float) -> Unit)? = null,
    ): Boolean {
        for (candidate in WallpaperCatalog.candidateUrls(url)) {
            if (downloadSingleUrlToFile(candidate, destFile, callKey, onProgress)) {
                return true
            }
            destFile.delete()
        }
        return false
    }

    private fun downloadSingleUrlToFile(
        url: String,
        destFile: File,
        callKey: String? = null,
        onProgress: ((Float) -> Unit)? = null,
    ): Boolean {
        var call: Call? = null
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", UhHttpClient.DEFAULT_USER_AGENT)
                .build()
            call = downloadClient.newCall(request)
            if (callKey != null) {
                inFlightCalls[callKey] = call
            }
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    UHLog.w("wallpaper_warn", "Download HTTP ${response.code} for $url")
                    return false
                }
                val body = response.body ?: return false
                val totalBytes = body.contentLength()
                var downloaded = 0L
                var lastReportedPct = -1
                if (totalBytes <= 0L) onProgress?.invoke(-1f)

                destFile.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (totalBytes > 0L && onProgress != null) {
                                val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                if (pct != lastReportedPct && (pct - lastReportedPct >= 2 || pct == 100)) {
                                    lastReportedPct = pct
                                    onProgress(pct / 100f)
                                }
                            }
                        }
                    }
                }
            }
            destFile.exists() && destFile.length() > 64L
        } catch (e: Exception) {
            UHLog.e("wallpaper_error", "downloadToFile failed ($url): ${e.message}")
            false
        } finally {
            if (callKey != null && call != null) {
                inFlightCalls.remove(callKey, call)
            }
        }
    }

    private fun trimCacheIfNeeded(ctx: Context) {
        try {
            val files = cacheDir(ctx).listFiles { f -> f.isFile && !f.name.endsWith(".part") } ?: return
            var total = files.sumOf { it.length() }
            if (total <= MAX_CACHE_BYTES) return
            files.sortedBy { it.lastModified() }.forEach { file ->
                if (total <= MAX_CACHE_BYTES * 8 / 10) return@forEach
                val size = file.length()
                if (file.delete()) total -= size
            }
        } catch (_: Exception) {}
    }

    fun clearCache(ctx: Context) {
        try {
            cacheDir(ctx).listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {}
    }
}
