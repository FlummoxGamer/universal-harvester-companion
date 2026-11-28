package app.harvester.companion.browser.translate

import app.harvester.companion.UHLog
import app.harvester.companion.core.network.UhHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Fast, zero-key online Google Translate client (`translate.googleapis.com/translate_a/single`)
 * supporting 100+ languages, auto-detection, LRU caching, and batched multi-segment page & OCR translation.
 */
object OnlineTranslateClient {

    data class BatchResult(
        val translations: List<String>,
        val detectedSourceLang: String?,
        val ok: Boolean,
        val errorMessage: String? = null
    )

    private const val MAX_CACHE_ENTRIES = 2500
    // Unique delimiter preserved across Google Translate batches
    private const val SEP_TOKEN = "\n<<<UHSEP91>>>\n"
    private val sepSplitRegex = Regex("\\s*<{2,3}\\s*UHSEP91\\s*>{2,3}\\s*")

    private val httpClient: OkHttpClient by lazy {
        UhHttpClient.client.newBuilder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }

    private fun getCached(key: String): String? = synchronized(cacheLock) { cache[key] }
    private fun putCached(key: String, value: String) = synchronized(cacheLock) { cache[key] = value }

    fun normalizeGoogleCode(code: String): String {
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

    fun normalizeDisplayCode(googleReturnedCode: String?): String? {
        val c = googleReturnedCode?.trim()?.lowercase(Locale.ROOT)?.ifBlank { null } ?: return null
        return when (c) {
            "zh-cn" -> "zh-Hans"
            "zh-tw" -> "zh-Hant"
            "iw" -> "he"
            else -> c
        }
    }

    /**
     * Translates a list of text segments in minimal batched HTTP calls.
     * Preserves exact 1:1 index alignment with [segments].
     */
    suspend fun translateBatch(
        segments: List<String>,
        fromLang: String,
        toLang: String
    ): BatchResult = withContext(Dispatchers.IO) {
        if (segments.isEmpty()) {
            return@withContext BatchResult(emptyList(), null, ok = true)
        }

        val sl = normalizeGoogleCode(fromLang)
        val tl = normalizeGoogleCode(toLang).let { if (it == "auto") "en" else it }

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

        // Group uncached segments into chunks <= 3200 chars & <= 28 items per POST
        val chunks = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var currentChars = 0
        for (idx in uncachedIndices) {
            val len = segments[idx].length + SEP_TOKEN.length
            if (current.isNotEmpty() && (currentChars + len > 3200 || current.size >= 28)) {
                chunks.add(current)
                current = mutableListOf()
                currentChars = 0
            }
            current.add(idx)
            currentChars += len
        }
        if (current.isNotEmpty()) chunks.add(current)

        var detectedLang: String? = null
        var anySuccess = false
        var lastError: String? = null

        for (chunkIndices in chunks) {
            val texts = chunkIndices.map { segments[it] }
            val res = executeChunkRequest(texts, sl, tl)
            if (res.ok && res.translations.size == texts.size) {
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
            } else if (res.ok && texts.size > 1) {
                // Rare delimiter alteration fallback: translate items individually in this small chunk
                for (originalIdx in chunkIndices) {
                    val single = executeSingleRequest(segments[originalIdx], sl, tl)
                    if (single.first != null) {
                        anySuccess = true
                        out[originalIdx] = single.first!!
                        putCached("$sl|$tl|${segments[originalIdx]}", single.first!!)
                        if (detectedLang == null && !single.second.isNullOrBlank()) {
                            detectedLang = single.second
                        }
                    }
                }
            } else {
                lastError = res.errorMessage ?: "Network error"
            }
        }

        if (!anySuccess && uncachedIndices.isNotEmpty()) {
            return@withContext BatchResult(
                translations = out,
                detectedSourceLang = detectedLang,
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

    private fun executeChunkRequest(texts: List<String>, sl: String, tl: String): BatchResult {
        if (texts.size == 1) {
            val (tr, det, err) = executeSingleRequest(texts[0], sl, tl)
            return if (tr != null) {
                BatchResult(listOf(tr), det, ok = true)
            } else {
                BatchResult(texts, null, ok = false, errorMessage = err)
            }
        }

        val joined = texts.joinToString(SEP_TOKEN)
        val (translatedJoined, detected, err) = executeSingleRequest(joined, sl, tl)
        if (translatedJoined == null) {
            return BatchResult(texts, null, ok = false, errorMessage = err)
        }

        val parts = translatedJoined.split(sepSplitRegex).map { it.trim() }
        return if (parts.size == texts.size) {
            BatchResult(parts, detected, ok = true)
        } else {
            // Signal caller to fall back to single-item calls for exact alignment
            BatchResult(emptyList(), detected, ok = true)
        }
    }

    private fun executeSingleRequest(text: String, sl: String, tl: String): Triple<String?, String?, String?> {
        return try {
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=$tl&dt=t"
            val body = FormBody.Builder()
                .add("q", text)
                .build()
            val req = Request.Builder()
                .url(url)
                .post(body)
                .header("User-Agent", UhHttpClient.DEFAULT_USER_AGENT)
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
            UHLog.w("translate_online", "Online translate error: ${e.message}")
            Triple(null, null, e.message ?: "Network unavailable")
        }
    }
}
