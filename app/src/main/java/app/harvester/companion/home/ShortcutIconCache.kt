package app.harvester.companion.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.harvester.companion.UHLog
import app.harvester.companion.core.network.UhHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object ShortcutIconCache {
    private const val DIR_NAME = "shortcut_icons"
    private const val TTL_MS = 24L * 60L * 60L * 1000L // Daily background freshness check
    private const val HIGH_RES_PX = 96
    private const val ACCEPTABLE_RES_PX = 64
    private const val MAX_SYNC_DECODE_BYTES = 512L * 1024L

    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val bitmapCache = LruCache<String, ImageBitmap>(128)
    private val _versionTick = MutableStateFlow(0)
    val versionTick: StateFlow<Int> = _versionTick.asStateFlow()

    private val fastClient by lazy {
        UhHttpClient.client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Persistent icon storage in `filesDir/shortcut_icons` so Android cache eviction never
     * wipes Home shortcut icons.
     */
    private fun storageDir(ctx: Context): File {
        val dir = File(ctx.applicationContext.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun legacyCacheDir(ctx: Context): File = File(ctx.applicationContext.cacheDir, DIR_NAME)

    private fun keyFor(pageUrl: String): String {
        val normalized = normalizeHostOrUrl(pageUrl)
        val md = MessageDigest.getInstance("MD5")
        return md.digest(normalized.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun normalizeHostOrUrl(raw: String): String {
        return try {
            val uri = URI(raw.trim())
            val scheme = uri.scheme?.lowercase() ?: "https"
            val host = uri.host?.lowercase()?.removePrefix("www.") ?: raw.trim().lowercase()
            val path = uri.path?.takeIf { it.isNotBlank() && it != "/" } ?: ""
            "$scheme://$host$path"
        } catch (_: Exception) {
            raw.trim().lowercase()
        }
    }

    private fun iconFile(ctx: Context, key: String): File = File(storageDir(ctx), "icon_$key.png")
    private fun metaFile(ctx: Context, key: String): File = File(storageDir(ctx), "icon_$key.json")

    /**
     * Transparently migrates any previously saved icon from ephemeral `cacheDir` into
     * persistent `filesDir` on first access.
     */
    private fun ensureMigrated(ctx: Context, key: String): File {
        val dest = iconFile(ctx, key)
        if (dest.exists() && dest.length() > 0L) return dest

        val legacyIcon = File(legacyCacheDir(ctx), "icon_$key.png")
        if (legacyIcon.exists() && legacyIcon.length() > 0L) {
            runCatching {
                legacyIcon.copyTo(dest, overwrite = true)
                legacyIcon.delete()
                val legacyMeta = File(legacyCacheDir(ctx), "icon_$key.json")
                if (legacyMeta.exists()) {
                    legacyMeta.copyTo(metaFile(ctx, key), overwrite = true)
                    legacyMeta.delete()
                }
            }
        }
        return dest
    }

    fun getImmediateCachedFile(ctx: Context, pageUrl: String): File? {
        if (pageUrl.isBlank()) return null
        val key = keyFor(pageUrl)
        val f = ensureMigrated(ctx, key)
        return if (f.exists() && f.length() > 0L) f else null
    }

    /**
     * Synchronous Frame-1 bitmap lookup: returns from in-memory LruCache in ~0ms, or decodes
     * the tiny pre-baked local PNG directly on cold launch so Home widgets never flash
     * monograms or reload animations.
     */
    fun getImmediateImageBitmap(ctx: Context, pageUrl: String): ImageBitmap? {
        if (pageUrl.isBlank()) return null
        val key = keyFor(pageUrl)
        val f = ensureMigrated(ctx, key)
        if (!f.exists() || f.length() <= 32L) return null

        val cacheKey = "$key@${f.lastModified()}"
        bitmapCache.get(cacheKey)?.let { return it }

        if (f.length() > MAX_SYNC_DECODE_BYTES) return null
        return runCatching {
            val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return null
            val imageBitmap = bmp.asImageBitmap()
            bitmapCache.put(cacheKey, imageBitmap)
            imageBitmap
        }.getOrNull()
    }

    fun invalidate(ctx: Context, pageUrl: String) {
        if (pageUrl.isBlank()) return
        val key = keyFor(pageUrl)
        runCatching {
            val f = iconFile(ctx, key)
            bitmapCache.remove("$key@${f.lastModified()}")
            f.delete()
        }
        runCatching { metaFile(ctx, key).delete() }
        runCatching { File(legacyCacheDir(ctx), "icon_$key.png").delete() }
        runCatching { File(legacyCacheDir(ctx), "icon_$key.json").delete() }
        _versionTick.value += 1
    }

    fun clearAll(ctx: Context) {
        runCatching {
            storageDir(ctx).listFiles()?.forEach { it.delete() }
            legacyCacheDir(ctx).listFiles()?.forEach { it.delete() }
            bitmapCache.evictAll()
            _versionTick.value += 1
        }
    }

    suspend fun resolveIcon(ctx: Context, pageUrl: String, explicitIconUrl: String? = null): File? {
        if (pageUrl.isBlank()) return null
        val appCtx = ctx.applicationContext
        val key = keyFor(pageUrl)
        val lock = mutexes.getOrPut(key) { Mutex() }

        return lock.withLock {
            withContext(Dispatchers.IO) {
                val file = ensureMigrated(appCtx, key)
                val meta = metaFile(appCtx, key)
                val metaJson = readMeta(meta)
                val fileExists = file.exists() && file.length() > 32L

                // Measure existing resolution accurately (from meta or bounds decode)
                var existingMinDim = if (fileExists) {
                    val mw = metaJson?.optInt("width", 0) ?: 0
                    val mh = metaJson?.optInt("height", 0) ?: 0
                    if (mw > 0 && mh > 0) minOf(mw, mh) else readImageMinDim(file)
                } else 0

                // One-time pre-bake of corner transparency for legacy icons so cold starts skip CPU flood-fill
                if (fileExists && metaJson?.optBoolean("baked", false) != true) {
                    bakeExistingIconInPlace(key, file, meta, metaJson)
                    existingMinDim = readImageMinDim(file)
                }

                val fetchedAt = metaJson?.optLong("fetchedAt", 0L) ?: 0L
                val now = System.currentTimeMillis()
                val isFresh = (now - fetchedAt) < TTL_MS

                // 1. Fresh high-quality cache (< 24h & >= 64px) -> zero network traffic
                if (fileExists && isFresh && existingMinDim >= ACCEPTABLE_RES_PX) {
                    return@withContext file
                }

                // 2. Daily background check (>= 24h) on existing high-quality icon
                if (fileExists && existingMinDim >= ACCEPTABLE_RES_PX && metaJson != null) {
                    val sourceUrl = metaJson.optString("sourceUrl")
                    val etag = metaJson.optString("etag")
                    val lastMod = metaJson.optString("lastModified")
                    val prevHash = metaJson.optString("sha256")
                    if (sourceUrl.isNotBlank()) {
                        val checked = checkConditionalUpdate(
                            key = key,
                            sourceUrl = sourceUrl,
                            etag = etag,
                            lastMod = lastMod,
                            prevHash = prevHash,
                            existingMinDim = existingMinDim,
                            destFile = file,
                            metaFile = meta,
                        )
                        if (checked) {
                            return@withContext file
                        }
                    }
                }

                // 3. Multi-source high-resolution discovery (never downgrades existing crisp icons;
                //    also automatically heals previously degraded low-res icons like 32x32 YouTube).
                val candidates = buildCandidateUrls(pageUrl, explicitIconUrl)
                var bestBytes: ByteArray? = null
                var bestBitmap: Bitmap? = null
                var bestUrl: String? = null
                var bestEtag = ""
                var bestLastMod = ""
                var bestMinDim = 0

                for (candidate in candidates) {
                    val fetched = fetchCandidateBitmap(candidate) ?: continue
                    val dim = minOf(fetched.bitmap.width, fetched.bitmap.height)
                    if (dim > bestMinDim) {
                        bestBitmap?.recycle()
                        bestBitmap = fetched.bitmap
                        bestBytes = fetched.bytes
                        bestUrl = candidate
                        bestEtag = fetched.etag
                        bestLastMod = fetched.lastMod
                        bestMinDim = dim
                    } else {
                        fetched.bitmap.recycle()
                    }
                    // Stop early once we have a crisp high-resolution icon (>= 96px)
                    if (bestMinDim >= HIGH_RES_PX) break
                }

                val winnerBmp = bestBitmap
                val winnerBytes = bestBytes
                val winnerUrl = bestUrl
                if (winnerBmp != null && winnerBytes != null && winnerUrl != null) {
                    // Strict never-downgrade guard: never replace an existing icon with lower quality
                    val shouldReplace = !fileExists ||
                        bestMinDim > existingMinDim ||
                        (bestMinDim == existingMinDim && sha256(winnerBytes) != metaJson?.optString("sha256"))

                    if (shouldReplace && (existingMinDim < ACCEPTABLE_RES_PX || bestMinDim >= existingMinDim)) {
                        storeProcessedBitmap(
                            key = key,
                            rawBmp = winnerBmp,
                            destFile = file,
                            metaFile = meta,
                            sourceUrl = winnerUrl,
                            etag = bestEtag,
                            lastMod = bestLastMod,
                            rawSha256 = sha256(winnerBytes),
                        )
                        _versionTick.value += 1
                        return@withContext file
                    } else {
                        winnerBmp.recycle()
                        // Keep the crisp existing icon and bump fetchedAt so we only re-check once a day
                        if (fileExists && metaJson != null) {
                            touchMetaTimestamp(meta, metaJson, existingMinDim)
                        }
                        return@withContext if (fileExists) file else null
                    }
                }

                // Network unavailable or no better candidate -> keep existing icon and refresh timestamp
                if (fileExists) {
                    if (metaJson != null) touchMetaTimestamp(meta, metaJson, existingMinDim)
                    file
                } else {
                    null
                }
            }
        }
    }

    private fun bakeExistingIconInPlace(
        key: String,
        file: File,
        meta: File,
        existingMeta: JSONObject?,
    ) {
        try {
            val raw = BitmapFactory.decodeFile(file.absolutePath) ?: return
            val processed = TransparentCornerTransformation.removeSolidCorners(raw)
            writePngAtomic(processed, file)
            val w = processed.width
            val h = processed.height
            // Keep `processed` alive inside bitmapCache; only recycle `raw` if a new copy was allocated
            bitmapCache.put("$key@${file.lastModified()}", processed.asImageBitmap())
            if (processed !== raw) raw.recycle()

            val json = (existingMeta ?: JSONObject()).apply {
                put("baked", true)
                put("width", w)
                put("height", h)
                if (!has("fetchedAt")) put("fetchedAt", System.currentTimeMillis())
            }
            runCatching { meta.writeText(json.toString()) }
        } catch (_: Exception) {
        }
    }

    private data class FetchedCandidate(
        val bytes: ByteArray,
        val bitmap: Bitmap,
        val etag: String,
        val lastMod: String,
    )

    private fun fetchCandidateBitmap(url: String): FetchedCandidate? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UhHttpClient.DEFAULT_USER_AGENT)
                .build()
            fastClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body?.bytes() ?: return null
                if (bytes.size < 32) return null
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                if (bmp.width < 16 || bmp.height < 16) {
                    bmp.recycle()
                    return null
                }
                FetchedCandidate(
                    bytes = bytes,
                    bitmap = bmp,
                    etag = resp.header("ETag").orEmpty(),
                    lastMod = resp.header("Last-Modified").orEmpty(),
                )
            }
        } catch (e: Exception) {
            UHLog.w("ui_warn", "Icon fetch failed ($url): ${e.message}")
            null
        }
    }

    private fun checkConditionalUpdate(
        key: String,
        sourceUrl: String,
        etag: String,
        lastMod: String,
        prevHash: String,
        existingMinDim: Int,
        destFile: File,
        metaFile: File,
    ): Boolean {
        return try {
            val reqBuilder = Request.Builder()
                .url(sourceUrl)
                .header("User-Agent", UhHttpClient.DEFAULT_USER_AGENT)
            if (etag.isNotBlank()) reqBuilder.header("If-None-Match", etag)
            if (lastMod.isNotBlank()) reqBuilder.header("If-Modified-Since", lastMod)

            fastClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code == 304) {
                    writeMeta(
                        metaFile = metaFile,
                        sourceUrl = sourceUrl,
                        etag = resp.header("ETag") ?: etag,
                        lastMod = resp.header("Last-Modified") ?: lastMod,
                        sha256 = prevHash,
                        width = existingMinDim,
                        height = existingMinDim,
                    )
                    return true
                }
                if (resp.isSuccessful) {
                    val bytes = resp.body?.bytes() ?: return false
                    val newHash = sha256(bytes)
                    if (newHash == prevHash) {
                        writeMeta(
                            metaFile = metaFile,
                            sourceUrl = sourceUrl,
                            etag = resp.header("ETag") ?: etag,
                            lastMod = resp.header("Last-Modified") ?: lastMod,
                            sha256 = prevHash,
                            width = existingMinDim,
                            height = existingMinDim,
                        )
                        return true
                    }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
                    val newMinDim = minOf(bmp.width, bmp.height)
                    // Strict never-downgrade guard
                    if (newMinDim >= existingMinDim && newMinDim >= 16) {
                        storeProcessedBitmap(
                            key = key,
                            rawBmp = bmp,
                            destFile = destFile,
                            metaFile = metaFile,
                            sourceUrl = sourceUrl,
                            etag = resp.header("ETag").orEmpty(),
                            lastMod = resp.header("Last-Modified").orEmpty(),
                            rawSha256 = newHash,
                        )
                        _versionTick.value += 1
                        return true
                    } else {
                        bmp.recycle()
                        return false
                    }
                }
                false
            }
        } catch (_: Exception) {
            // Offline or transient failure: keep existing icon intact
            destFile.exists() && destFile.length() > 32L
        }
    }

    /**
     * Takes ownership of [rawBmp]: processes corners once, writes atomic PNG, retains the
     * resulting bitmap inside [bitmapCache], and recycles [rawBmp] only if a distinct bitmap
     * copy was produced.
     */
    private fun storeProcessedBitmap(
        key: String,
        rawBmp: Bitmap,
        destFile: File,
        metaFile: File,
        sourceUrl: String,
        etag: String,
        lastMod: String,
        rawSha256: String,
    ) {
        val processed = TransparentCornerTransformation.removeSolidCorners(rawBmp)
        writePngAtomic(processed, destFile)
        val w = processed.width
        val h = processed.height
        bitmapCache.put("$key@${destFile.lastModified()}", processed.asImageBitmap())
        if (processed !== rawBmp) rawBmp.recycle()

        writeMeta(
            metaFile = metaFile,
            sourceUrl = sourceUrl,
            etag = etag,
            lastMod = lastMod,
            sha256 = rawSha256,
            width = w,
            height = h,
        )
    }

    private data class HtmlIconCandidate(
        val url: String,
        val declaredSize: Int,
        val isAppleTouch: Boolean,
    )

    private fun buildCandidateUrls(pageUrl: String, explicitIconUrl: String?): List<String> {
        val list = linkedSetOf<String>()
        val uri = try { URI(pageUrl.trim()) } catch (_: Exception) { null }
        val scheme = uri?.scheme?.takeIf { it == "http" || it == "https" } ?: "https"
        val host = uri?.host?.takeIf { it.isNotBlank() }
        val cleanHost = host?.removePrefix("www.")

        val htmlIcons = if (host != null) discoverHtmlIconUrls(pageUrl, scheme, host) else emptyList()

        // 1. High-resolution HTML icons (>= 96px or apple-touch-icon), sorted largest-first
        htmlIcons
            .filter { it.declaredSize >= HIGH_RES_PX || it.isAppleTouch }
            .forEach { list.add(it.url) }

        // 2. Reliable 128px favicon endpoints & standard apple-touch-icon paths
        if (!cleanHost.isNullOrBlank()) {
            list.add("https://www.google.com/s2/favicons?domain=$cleanHost&sz=128")
        }
        if (host != null) {
            list.add("$scheme://$host/apple-touch-icon.png")
            list.add("$scheme://$host/apple-touch-icon-precomposed.png")
        }

        // 3. Explicit iconUrl if provided
        if (!explicitIconUrl.isNullOrBlank() && explicitIconUrl.startsWith("http")) {
            list.add(explicitIconUrl)
        }

        // 4. Lower-resolution HTML icons & generic fallbacks (only used if high-res tier fails)
        htmlIcons
            .filter { it.declaredSize < HIGH_RES_PX && !it.isAppleTouch }
            .forEach { list.add(it.url) }
        if (host != null) {
            list.add("$scheme://$host/favicon.png")
            list.add("$scheme://$host/favicon.ico")
        }
        if (!cleanHost.isNullOrBlank()) {
            list.add("https://icons.duckduckgo.com/ip3/$cleanHost.ico")
        }

        return list.toList()
    }

    private fun discoverHtmlIconUrls(
        pageUrl: String,
        scheme: String,
        host: String,
    ): List<HtmlIconCandidate> {
        val found = mutableListOf<HtmlIconCandidate>()
        try {
            val req = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", UhHttpClient.DEFAULT_USER_AGENT)
                .header("Range", "bytes=0-65535")
                .build()
            fastClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val ctype = resp.header("Content-Type").orEmpty()
                if (!ctype.contains("html", ignoreCase = true) && ctype.isNotBlank()) return emptyList()
                val body = resp.body ?: return emptyList()

                // Read up to 64KB in a loop (a single .read() over TLS often stops at 2-4KB
                // before YouTube's <link sizes="144x144"> tag!)
                val maxBytes = 65536
                val buf = ByteArray(maxBytes)
                var totalRead = 0
                body.byteStream().use { stream ->
                    while (totalRead < maxBytes) {
                        val n = stream.read(buf, totalRead, maxBytes - totalRead)
                        if (n <= 0) break
                        totalRead += n
                    }
                }
                if (totalRead <= 0) return emptyList()

                val snippet = String(buf, 0, totalRead, Charsets.UTF_8)
                val matcher = LINK_TAG_PATTERN.matcher(snippet)
                val baseUri = URI("$scheme://$host")
                while (matcher.find()) {
                    val tag = matcher.group() ?: continue
                    val relMatch = REL_PATTERN.matcher(tag)
                    val rel = if (relMatch.find()) relMatch.group(1)?.lowercase() ?: "" else ""
                    if (rel.contains("icon")) {
                        val hrefMatch = HREF_PATTERN.matcher(tag)
                        val href = if (hrefMatch.find()) hrefMatch.group(1)?.trim() else null
                        if (!href.isNullOrBlank() && !href.startsWith("data:", ignoreCase = true)) {
                            val resolved = runCatching { baseUri.resolve(href).toString() }.getOrNull()
                            if (resolved != null && !resolved.endsWith(".svg", ignoreCase = true)) {
                                val sizeMatch = SIZES_PATTERN.matcher(tag)
                                var declaredSize = if (sizeMatch.find()) {
                                    val w = sizeMatch.group(1)?.toIntOrNull() ?: 0
                                    val h = sizeMatch.group(2)?.toIntOrNull() ?: 0
                                    minOf(w, h)
                                } else {
                                    0
                                }
                                // Infer dimension from filename like favicon_144x144.png if sizes attr absent
                                if (declaredSize == 0) {
                                    val urlSizeMatch = URL_SIZE_PATTERN.matcher(resolved)
                                    if (urlSizeMatch.find()) {
                                        val w = urlSizeMatch.group(1)?.toIntOrNull() ?: 0
                                        val h = urlSizeMatch.group(2)?.toIntOrNull() ?: 0
                                        declaredSize = minOf(w, h)
                                    }
                                }
                                val isAppleTouch = rel.contains("apple-touch-icon")
                                found.add(HtmlIconCandidate(resolved, declaredSize, isAppleTouch))
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        // Sort largest declared resolution first, then apple-touch-icon preference
        return found
            .distinctBy { it.url }
            .sortedWith(
                compareByDescending<HtmlIconCandidate> { it.declaredSize }
                    .thenByDescending { it.isAppleTouch },
            )
            .take(6)
    }

    private fun readImageMinDim(file: File): Int {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) minOf(opts.outWidth, opts.outHeight) else 0
        } catch (_: Exception) {
            0
        }
    }

    private fun writePngAtomic(bmp: Bitmap, destFile: File) {
        val tmp = File(destFile.parentFile, "${destFile.name}.tmp")
        tmp.outputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        if (!tmp.renameTo(destFile)) {
            tmp.copyTo(destFile, overwrite = true)
            tmp.delete()
        }
    }

    private fun readMeta(metaFile: File): JSONObject? {
        if (!metaFile.exists()) return null
        return runCatching { JSONObject(metaFile.readText()) }.getOrNull()
    }

    private fun touchMetaTimestamp(metaFile: File, existing: JSONObject, existingMinDim: Int) {
        runCatching {
            existing.put("fetchedAt", System.currentTimeMillis())
            if (existingMinDim > 0 && existing.optInt("width", 0) <= 0) {
                existing.put("width", existingMinDim)
                existing.put("height", existingMinDim)
            }
            metaFile.writeText(existing.toString())
        }
    }

    private fun writeMeta(
        metaFile: File,
        sourceUrl: String,
        etag: String,
        lastMod: String,
        sha256: String,
        width: Int,
        height: Int,
    ) {
        val json = JSONObject().apply {
            put("fetchedAt", System.currentTimeMillis())
            put("sourceUrl", sourceUrl)
            put("etag", etag)
            put("lastModified", lastMod)
            put("sha256", sha256)
            put("width", width)
            put("height", height)
            put("baked", true)
        }
        runCatching { metaFile.writeText(json.toString()) }
    }

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private val LINK_TAG_PATTERN = Pattern.compile("<link\\b[^>]*>", Pattern.CASE_INSENSITIVE)
    private val REL_PATTERN = Pattern.compile("rel\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
    private val HREF_PATTERN = Pattern.compile("href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
    private val SIZES_PATTERN = Pattern.compile("sizes\\s*=\\s*[\"'](\\d+)x(\\d+)[\"']", Pattern.CASE_INSENSITIVE)
    private val URL_SIZE_PATTERN = Pattern.compile("(\\d{2,4})x(\\d{2,4})", Pattern.CASE_INSENSITIVE)
}
