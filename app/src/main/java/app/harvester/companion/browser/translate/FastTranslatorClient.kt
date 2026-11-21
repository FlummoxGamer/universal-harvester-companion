package app.harvester.companion.browser.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import app.harvester.companion.UHLog
import app.harvester.companion.core.network.UhHttpClient
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

data class TranslatedImageBlock(
    val leftPct: Float,
    val topPct: Float,
    val widthPct: Float,
    val heightPct: Float,
    val originalText: String,
    val translatedText: String,
)

data class TranslateBatchResult(
    val translations: List<String>,
    val translatedCount: Int,
    val failedCount: Int,
    val error: String? = null,
)

/**
 * High-speed parallel HTTP POST translator & ML Kit OCR image translator.
 * Uses HTTP POST FormBody (immune to URL-length 414 errors across CJK/Indic/Arabic),
 * in-batch string deduplication, bounded LRU cache, and HTTP/2 connection pooling.
 */
object FastTranslatorClient {

    private const val CHUNK_SEPARATOR = "\n§731§\n"
    private val CHUNK_SPLIT_REGEX = Regex("\\s*§\\s*731\\s*§\\s*")
    private const val MAX_CHUNK_CHARS = 950
    private const val MAX_ITEMS_PER_CHUNK = 14
    private const val MAX_CONCURRENT_BATCHES = 8
    private const val MAX_CACHE_ENTRIES = 2000

    private val fastHttpClient: OkHttpClient by lazy {
        UhHttpClient.client.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    private val cacheLock = Any()
    private val translationCache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }

    private fun getCached(key: String): String? = synchronized(cacheLock) {
        translationCache[key]
    }

    private fun putCached(key: String, value: String) = synchronized(cacheLock) {
        translationCache[key] = value
    }

    suspend fun translateBatch(
        texts: List<String>,
        fromLang: String,
        toLang: String,
    ): List<String> = translateBatchDetailed(texts, fromLang, toLang).translations

    suspend fun translateBatchDetailed(
        texts: List<String>,
        fromLang: String,
        toLang: String,
    ): TranslateBatchResult = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) {
            return@withContext TranslateBatchResult(emptyList(), 0, 0, null)
        }
        val sl = fromLang.trim().ifBlank { "auto" }
        val tl = toLang.trim().ifBlank { "en" }
        if (sl != "auto" && sl.equals(tl, ignoreCase = true)) {
            return@withContext TranslateBatchResult(texts, 0, 0, null)
        }

        val results = texts.toMutableList()
        val translatedCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)

        // Deduplicate identical trimmed strings and resolve LRU cache hits in 0ms
        val uncachedMap = LinkedHashMap<String, MutableList<Int>>()
        texts.forEachIndexed { idx, raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return@forEachIndexed
            val cacheKey = "$sl|$tl|$trimmed"
            val cached = getCached(cacheKey)
            if (cached != null) {
                results[idx] = preserveWhitespace(raw, cached)
                translatedCount.incrementAndGet()
            } else {
                uncachedMap.getOrPut(trimmed) { mutableListOf() }.add(idx)
            }
        }

        if (uncachedMap.isEmpty()) {
            return@withContext TranslateBatchResult(results, translatedCount.get(), 0, null)
        }

        val uniqueItems = uncachedMap.keys.toList()
        val chunks = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var currentChars = 0

        for (item in uniqueItems) {
            val projected = currentChars + item.length + CHUNK_SEPARATOR.length
            if (current.isNotEmpty() && (projected > MAX_CHUNK_CHARS || current.size >= MAX_ITEMS_PER_CHUNK)) {
                chunks.add(current)
                current = mutableListOf()
                currentChars = 0
            }
            current.add(item)
            currentChars += item.length + CHUNK_SEPARATOR.length
        }
        if (current.isNotEmpty()) {
            chunks.add(current)
        }

        val translatedUnique = HashMap<String, String>(uniqueItems.size)
        val mapLock = Any()

        for (wave in chunks.chunked(MAX_CONCURRENT_BATCHES)) {
            coroutineScope {
                wave.map { chunk ->
                    async(Dispatchers.IO) {
                        val translated = translateChunk(chunk, sl, tl)
                        if (translated.size == chunk.size) {
                            chunk.forEachIndexed { localIdx, origTrimmed ->
                                val tr = translated[localIdx].trim()
                                if (tr.isNotEmpty()) {
                                    putCached("$sl|$tl|$origTrimmed", tr)
                                    synchronized(mapLock) { translatedUnique[origTrimmed] = tr }
                                } else {
                                    failedCount.incrementAndGet()
                                }
                            }
                        } else {
                            // Bounded parallel individual fallback (max 4 concurrent) instead of slow sequential loop
                            for (subWave in chunk.chunked(4)) {
                                coroutineScope {
                                    subWave.map { origTrimmed ->
                                        async(Dispatchers.IO) {
                                            val single = translateSingle(origTrimmed, sl, tl)?.trim()
                                            if (!single.isNullOrEmpty()) {
                                                putCached("$sl|$tl|$origTrimmed", single)
                                                synchronized(mapLock) { translatedUnique[origTrimmed] = single }
                                            } else {
                                                failedCount.incrementAndGet()
                                            }
                                        }
                                    }.awaitAll()
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        // Map unique translations back to every original DOM node index
        for ((origTrimmed, indices) in uncachedMap) {
            val tr = translatedUnique[origTrimmed] ?: continue
            for (idx in indices) {
                results[idx] = preserveWhitespace(texts[idx], tr)
                translatedCount.incrementAndGet()
            }
        }

        val trCount = translatedCount.get()
        val errCount = failedCount.get()
        val errorMsg = if (trCount == 0 && errCount > 0) {
            "Could not reach translation service."
        } else {
            null
        }
        TranslateBatchResult(results, trCount, errCount, errorMsg)
    }

    private fun preserveWhitespace(original: String, translated: String): String {
        val leading = original.takeWhile { it.isWhitespace() }
        val trailing = original.takeLastWhile { it.isWhitespace() }
        return leading + translated.trim() + trailing
    }

    suspend fun translateImageWithMlKit(
        context: Context,
        imageUrl: String?,
        dataUrl: String?,
        fromLang: String,
        toLang: String,
    ): List<TranslatedImageBlock> = withContext(Dispatchers.IO) {
        val bitmap = decodeImageBitmap(imageUrl, dataUrl) ?: return@withContext emptyList()
        if (bitmap.width < 32 || bitmap.height < 32) return@withContext emptyList()

        val recognized = recognizeTextWithMlKit(bitmap)
        if (recognized.isEmpty()) return@withContext emptyList()

        val rawTexts = recognized.map { it.text }
        val translatedTexts = translateBatch(rawTexts, fromLang.ifBlank { "auto" }, toLang)

        val imgW = bitmap.width.toFloat().coerceAtLeast(1f)
        val imgH = bitmap.height.toFloat().coerceAtLeast(1f)

        recognized.mapIndexedNotNull { idx, block ->
            val box = block.box ?: return@mapIndexedNotNull null
            val tr = translatedTexts.getOrNull(idx)?.trim().orEmpty()
            if (tr.isBlank()) return@mapIndexedNotNull null
            TranslatedImageBlock(
                leftPct = (box.left / imgW * 100f).coerceIn(0f, 96f),
                topPct = (box.top / imgH * 100f).coerceIn(0f, 96f),
                widthPct = (box.width() / imgW * 100f).coerceIn(4f, 100f),
                heightPct = (box.height() / imgH * 100f).coerceIn(4f, 100f),
                originalText = block.text,
                translatedText = tr,
            )
        }
    }

    private data class RecognizedBox(
        val text: String,
        val box: android.graphics.Rect?,
    )

    private suspend fun recognizeTextWithMlKit(bitmap: Bitmap): List<RecognizedBox> {
        return withContext(Dispatchers.Default) {
            suspendCancellableCoroutine { cont ->
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                recognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        val blocks = visionText.textBlocks.mapNotNull { b ->
                            val clean = b.text.replace("\n", " ").trim()
                            if (clean.length < 2) null else RecognizedBox(clean, b.boundingBox)
                        }
                        try { recognizer.close() } catch (_: Exception) {}
                        if (cont.isActive) cont.resume(blocks)
                    }
                    .addOnFailureListener { err ->
                        UHLog.w("translate_ocr", "ML Kit OCR failed: ${err.message}")
                        try { recognizer.close() } catch (_: Exception) {}
                        if (cont.isActive) cont.resume(emptyList())
                    }
            }
        }
    }

    private fun decodeImageBitmap(imageUrl: String?, dataUrl: String?): Bitmap? {
        if (!dataUrl.isNullOrBlank() && dataUrl.startsWith("data:")) {
            return try {
                val b64 = dataUrl.substringAfter(",", "")
                if (b64.isBlank()) return null
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Exception) {
                null
            }
        }
        if (!imageUrl.isNullOrBlank() && (imageUrl.startsWith("http://") || imageUrl.startsWith("https://"))) {
            return try {
                val req = Request.Builder()
                    .url(imageUrl)
                    .header("User-Agent", UhHttpClient.DEFAULT_USER_AGENT)
                    .build()
                fastHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    val bytes = resp.body?.bytes() ?: return null
                    if (bytes.isEmpty() || bytes.size > 6 * 1024 * 1024) return null
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    private fun translateChunk(items: List<String>, sl: String, tl: String): List<String> {
        if (items.size == 1) {
            val single = translateSingle(items[0], sl, tl)
            return if (single != null) listOf(single) else emptyList()
        }
        val joined = items.joinToString(CHUNK_SEPARATOR)
        val translatedJoined = translateSingle(joined, sl, tl) ?: return emptyList()
        val parts = translatedJoined.split(CHUNK_SPLIT_REGEX)
        return if (parts.size == items.size) parts else emptyList()
    }

    private fun translateSingle(text: String, sl: String, tl: String): String? {
        if (text.isBlank()) return text
        val encSl = try { URLEncoder.encode(sl, "UTF-8") } catch (_: Exception) { sl }
        val encTl = try { URLEncoder.encode(tl, "UTF-8") } catch (_: Exception) { tl }

        // Primary fast endpoint via HTTP POST FormBody (no URL length limit across CJK/Indic/Arabic)
        val primaryUrl = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$encSl&tl=$encTl&dt=t"
        val primary = executePrimaryPost(primaryUrl, text)
        if (primary != null) return primary

        // Secondary fallback mirror via HTTP POST FormBody
        val fallbackUrl = "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=$encSl&tl=$encTl"
        return executeFallbackPost(fallbackUrl, text)
    }

    private fun executePrimaryPost(url: String, text: String): String? {
        return try {
            val formBody = FormBody.Builder()
                .add("q", text)
                .build()
            val req = Request.Builder()
                .url(url)
                .post(formBody)
                .header("User-Agent", UhHttpClient.DEFAULT_USER_AGENT)
                .build()
            fastHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    UHLog.w("translate_fast", "Primary translate HTTP ${resp.code}")
                    return null
                }
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return null
                val root = JSONArray(body)
                val sentences = root.optJSONArray(0) ?: return null
                val sb = StringBuilder()
                for (i in 0 until sentences.length()) {
                    val seg = sentences.optJSONArray(i)
                    val translatedSeg = seg?.optString(0, "")
                    if (!translatedSeg.isNullOrEmpty()) {
                        sb.append(translatedSeg)
                    }
                }
                sb.toString().ifBlank { null }
            }
        } catch (e: Exception) {
            UHLog.w("translate_fast", "Primary translate request failed: ${e.message}")
            null
        }
    }

    private fun executeFallbackPost(url: String, text: String): String? {
        return try {
            val formBody = FormBody.Builder()
                .add("q", text)
                .build()
            val req = Request.Builder()
                .url(url)
                .post(formBody)
                .header("User-Agent", UhHttpClient.DEFAULT_USER_AGENT)
                .build()
            fastHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return null
                val root = JSONArray(body)
                when (val first = root.opt(0)) {
                    is String -> first.ifBlank { null }
                    is JSONArray -> first.optString(0, "").ifBlank { null }
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
