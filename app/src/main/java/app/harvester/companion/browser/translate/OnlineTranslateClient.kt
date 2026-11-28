package app.harvester.companion.browser.translate

import app.harvester.companion.UHLog
import app.harvester.companion.core.network.UhHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * High-speed online translation client for UH Translate.
 *
 * Uses a multi-segment native batch endpoint (`translate_a/t` with multi-`q` form parameters)
 * that returns exact 1:1 JSON arrays with zero delimiter splitting, backed by a secondary mirror
 * and single-item GET fallback, plus an in-memory LRU cache and parallel chunk dispatch.
 */
object OnlineTranslateClient {

    data class BatchResult(
        val translations: List<String>,
        val detectedSourceLang: String?,
        val ok: Boolean,
        val errorMessage: String? = null
    )

    private const val MAX_CACHE_ENTRIES = 4000
    private const val MAX_CONCURRENT_CHUNKS = 3
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"

    private val BATCH_ENDPOINTS = listOf(
        "https://translate.googleapis.com/translate_a/t?client=dict-chrome-ex",
        "https://clients5.google.com/translate_a/t?client=dict-chrome-ex"
    )

    private val httpClient: OkHttpClient by lazy {
        UhHttpClient.client.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }

    private fun getCached(key: String): String? = synchronized(cacheLock) { cache[key] }
    private fun putCached(key: String, value: String) = synchronized(cacheLock) { cache[key] = value }

    fun normalizeApiCode(code: String): String {
        val c = code.trim().lowercase(Locale.ROOT)
        return when (c) {
            "", "auto" -> "auto"
            "zh-hans", "zh-cn", "zh" -> "zh-CN"
            "zh-hant", "zh-tw", "zh-hk" -> "zh-TW"
            "he", "iw" -> "iw"
            "fil", "tl" -> "tl"
            "nb", "no" -> "no"
            else -> c.substringBefore("_")
        }
    }

    fun normalizeDisplayCode(returnedCode: String?): String? {
        val c = returnedCode?.trim()?.lowercase(Locale.ROOT)?.ifBlank { null } ?: return null
        return when (c) {
            "zh-cn" -> "zh-Hans"
            "zh-tw" -> "zh-Hant"
            "iw" -> "he"
            else -> c
        }
    }

    /**
     * Translates [segments] with exact 1:1 alignment using parallel native multi-segment requests.
     */
    suspend fun translateBatch(
        segments: List<String>,
        fromLang: String,
        toLang: String
    ): BatchResult = withContext(Dispatchers.IO) {
        if (segments.isEmpty()) {
            return@withContext BatchResult(emptyList(), null, ok = true)
        }

        val sl = normalizeApiCode(fromLang)
        val tl = normalizeApiCode(toLang).let { if (it == "auto") "en" else it }

        val out = MutableList(segments.size) { segments[it] }
        val uncachedIndices = mutableListOf<Int>()
        for (i in segments.indices) {
            val s = segments[i]
            if (s.isBlank()) continue
            val cached = getCached("$sl|$tl|$s")
            if (cached != null) {
                out[i] = cached
            } else {
                uncachedIndices.add(i)
            }
        }

        if (uncachedIndices.isEmpty()) {
            return@withContext BatchResult(out, null, ok = true)
        }

        // Pack into compact batches (<= 2800 chars, <= 24 segments) for fast low-latency responses
        val chunks = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var currentChars = 0
        for (idx in uncachedIndices) {
            val len = segments[idx].length + 4
            if (current.isNotEmpty() && (currentChars + len > 2800 || current.size >= 24)) {
                chunks.add(current)
                current = mutableListOf()
                currentChars = 0
            }
            current.add(idx)
            currentChars += len
        }
        if (current.isNotEmpty()) chunks.add(current)

        val sem = Semaphore(MAX_CONCURRENT_CHUNKS)
        val chunkResults = coroutineScope {
            chunks.map { chunkIndices ->
                async {
                    sem.withPermit {
                        val texts = chunkIndices.map { segments[it] }
                        chunkIndices to executeNativeMultiBatch(texts, sl, tl)
                    }
                }
            }.awaitAll()
        }

        var detectedLang: String? = null
        var anySuccess = false
        var lastError: String? = null

        for ((chunkIndices, res) in chunkResults) {
            if (res.ok && res.translations.size == chunkIndices.size) {
                anySuccess = true
                if (detectedLang == null && !res.detectedSourceLang.isNullOrBlank()) {
                    detectedLang = res.detectedSourceLang
                }
                for (j in chunkIndices.indices) {
                    val originalIdx = chunkIndices[j]
                    val tr = res.translations[j]
                    out[originalIdx] = tr
                    putCached("$sl|$tl|${segments[originalIdx]}", tr)
                }
            } else {
                lastError = res.errorMessage ?: "Network error"
            }
        }

        if (!anySuccess && uncachedIndices.isNotEmpty()) {
            return@withContext BatchResult(
                translations = out,
                detectedSourceLang = normalizeDisplayCode(detectedLang),
                ok = false,
                errorMessage = lastError ?: "Could not reach translation service"
            )
        }

        BatchResult(
            translations = out,
            detectedSourceLang = normalizeDisplayCode(detectedLang),
            ok = true
        )
    }

    /**
     * Tries the multi-`q` native batch endpoint across primary + mirror hosts,
     * falling back to GET `translate_a/single` per item if both mirrors reject the batch.
     */
    private fun executeNativeMultiBatch(texts: List<String>, sl: String, tl: String): BatchResult {
        var lastErr: String? = null

        for (base in BATCH_ENDPOINTS) {
            val res = postMultiQBatch(base, texts, sl, tl)
            if (res.ok && res.translations.size == texts.size) {
                return res
            }
            lastErr = res.errorMessage
        }

        // Resilient fallback: translate segments via GET `translate_a/single`
        val fallbackOut = MutableList(texts.size) { texts[it] }
        var detected: String? = null
        var okCount = 0
        for (i in texts.indices) {
            val (tr, det, err) = executeSingleGetRequest(texts[i], sl, tl)
            if (tr != null) {
                fallbackOut[i] = tr
                okCount++
                if (detected == null && !det.isNullOrBlank()) detected = det
            } else if (lastErr == null) {
                lastErr = err
            }
        }
        return if (okCount > 0) {
            BatchResult(fallbackOut, detected, ok = true)
        } else {
            BatchResult(texts, null, ok = false, errorMessage = lastErr ?: "Translation failed")
        }
    }

    private fun postMultiQBatch(
        endpointBase: String,
        texts: List<String>,
        sl: String,
        tl: String
    ): BatchResult {
        return try {
            val url = "$endpointBase&sl=${encodeParam(sl)}&tl=${encodeParam(tl)}"
            val formBuilder = FormBody.Builder()
            for (t in texts) {
                formBuilder.add("q", t)
            }
            val req = Request.Builder()
                .url(url)
                .post(formBuilder.build())
                .header("User-Agent", BROWSER_UA)
                .header("Accept", "application/json, text/plain, */*")
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return BatchResult(texts, null, ok = false, errorMessage = "HTTP ${resp.code}")
                }
                val raw = resp.body?.string().orEmpty().trim()
                if (raw.isBlank()) {
                    return BatchResult(texts, null, ok = false, errorMessage = "Empty response")
                }

                val root = JSONArray(raw)
                // Case A: single text item with sl=auto -> [["translated", "en"]] or ["translated", "en"]
                if (texts.size == 1) {
                    val first = root.opt(0)
                    if (first is String) {
                        val det = root.optString(1, "").ifBlank { null }
                        return BatchResult(listOf(first), det, ok = true)
                    } else if (first is JSONArray) {
                        val tr = first.optString(0, texts[0])
                        val det = first.optString(1, "").ifBlank { null }
                        return BatchResult(listOf(tr), det, ok = true)
                    }
                }

                if (root.length() != texts.size) {
                    return BatchResult(texts, null, ok = false, errorMessage = "Mismatched batch length")
                }

                val translated = ArrayList<String>(texts.size)
                var detected: String? = null
                for (i in 0 until root.length()) {
                    val item = root.opt(i)
                    when (item) {
                        is JSONArray -> {
                            translated.add(item.optString(0, texts[i]))
                            if (detected == null) {
                                val det = item.optString(1, "").ifBlank { null }
                                if (det != null) detected = det
                            }
                        }
                        is String -> translated.add(item)
                        else -> translated.add(texts[i])
                    }
                }
                BatchResult(translated, detected, ok = true)
            }
        } catch (e: Exception) {
            UHLog.w("translate_online", "Batch endpoint error ($endpointBase): ${e.message}")
            BatchResult(texts, null, ok = false, errorMessage = e.message ?: "Network error")
        }
    }

    private fun executeSingleGetRequest(text: String, sl: String, tl: String): Triple<String?, String?, String?> {
        return try {
            val encoded = encodeParam(text.take(4000))
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=${encodeParam(sl)}&tl=${encodeParam(tl)}&dt=t&q=$encoded"
            val req = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", BROWSER_UA)
                .header("Accept", "application/json, text/plain, */*")
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Triple(null, null, "HTTP ${resp.code}")
                }
                val raw = resp.body?.string().orEmpty()
                if (raw.isBlank()) return Triple(null, null, "Empty response")

                val root = JSONArray(raw)
                val sentencesArr = root.optJSONArray(0) ?: return Triple(null, null, "Malformed payload")
                val sb = StringBuilder(text.length + 32)
                for (i in 0 until sentencesArr.length()) {
                    val pair = sentencesArr.optJSONArray(i) ?: continue
                    val piece = pair.optString(0, "")
                    if (piece.isNotEmpty() && piece != "null") {
                        sb.append(piece)
                    }
                }
                val detected = root.optString(2, "").ifBlank { null }
                Triple(sb.toString(), detected, null)
            }
        } catch (e: Exception) {
            UHLog.w("translate_online", "Single GET translate error: ${e.message}")
            Triple(null, null, e.message ?: "Network unavailable")
        }
    }

    private fun encodeParam(value: String): String =
        runCatching { URLEncoder.encode(value, "UTF-8") }.getOrDefault(value)
}
