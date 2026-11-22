package app.harvester.companion.browser.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.util.Base64
import app.harvester.companion.UHLog
import app.harvester.companion.core.network.UhHttpClient
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

data class TranslatedImageBlock(
    val leftPct: Float,
    val topPct: Float,
    val widthPct: Float,
    val heightPct: Float,
    val originalText: String,
    val translatedText: String,
    val bgColor: String = "rgba(15,23,42,0.92)",
    val textColor: String = "#F8FAFC",
)

/**
 * Best-in-slot 100% on-device Image OCR + Neural Translation engine:
 * - Multi-script Google ML Kit Vision v2 OCR (Latin, Japanese, Chinese, Korean, Devanagari)
 *   with pooled recognizers and automatic script fallback.
 * - On-device Google ML Kit Language Identification + Neural Translation (59 languages),
 *   zero scraping timeouts, zero API keys, bounded translator & string caches.
 * - Referer-aware downsampled image decoder + border pixel color sampling for natural overlays.
 */
object FastTranslatorClient {

    private const val MAX_DECODE_DIM = 1600
    private const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
    private const val MAX_CACHE_ENTRIES = 1500
    private const val MAX_CACHED_TRANSLATORS = 10

    private val imageHttpClient: OkHttpClient by lazy {
        UhHttpClient.client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    // Pooled ML Kit v2 multi-script recognizers (avoids per-image native init/teardown overhead)
    private val latinRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val japaneseRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }
    private val chineseRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val koreanRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }
    private val devanagariRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }
    private val languageIdentifier: LanguageIdentifier by lazy {
        LanguageIdentification.getClient()
    }

    private val translatorLock = Any()
    private val translatorPool = object : LinkedHashMap<String, Translator>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Translator>?): Boolean {
            if (size > MAX_CACHED_TRANSLATORS) {
                try { eldest?.value?.close() } catch (_: Exception) {}
                return true
            }
            return false
        }
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

    private fun getOrCreateTranslator(srcMlKit: String, dstMlKit: String): Translator {
        val key = "$srcMlKit->$dstMlKit"
        return synchronized(translatorLock) {
            translatorPool.getOrPut(key) {
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(srcMlKit)
                    .setTargetLanguage(dstMlKit)
                    .build()
                Translation.getClient(options)
            }
        }
    }

    private data class RecognizedBox(
        val text: String,
        val box: Rect,
        val recognizedLangHint: String?,
    )

    suspend fun translateImageWithMlKit(
        @Suppress("UNUSED_PARAMETER") context: Context,
        imageUrl: String?,
        dataUrl: String?,
        pageUrl: String?,
        fromLang: String,
        toLang: String,
    ): List<TranslatedImageBlock> = withContext(Dispatchers.IO) {
        val bitmap = decodeImageBitmap(imageUrl, dataUrl, pageUrl) ?: return@withContext emptyList()
        try {
            if (bitmap.width < 32 || bitmap.height < 32) return@withContext emptyList()

            val recognized = recognizeMultiScript(bitmap, fromLang)
            if (recognized.isEmpty()) return@withContext emptyList()

            val targetMlKit = mapToMlKitLanguage(toLang) ?: TranslateLanguage.ENGLISH
            val explicitSrcMlKit = if (fromLang.isBlank() || fromLang.equals("auto", ignoreCase = true)) {
                null
            } else {
                mapToMlKitLanguage(fromLang)
            }

            val imgW = bitmap.width.toFloat().coerceAtLeast(1f)
            val imgH = bitmap.height.toFloat().coerceAtLeast(1f)

            val output = ArrayList<TranslatedImageBlock>(recognized.size)
            for (block in recognized) {
                val srcMlKit = explicitSrcMlKit
                    ?: identifyLanguageOnDevice(block.text)
                    ?: block.recognizedLangHint?.let { mapToMlKitLanguage(it) }
                    ?: guessScriptFallbackLang(block.text)
                    ?: continue

                if (srcMlKit == targetMlKit) continue

                val translated = translateTextOnDevice(block.text, srcMlKit, targetMlKit)
                if (translated.isNullOrBlank() || translated.equals(block.text, ignoreCase = true)) {
                    continue
                }

                val (bgCss, fgCss) = sampleBlockColors(bitmap, block.box)
                output.add(
                    TranslatedImageBlock(
                        leftPct = (block.box.left / imgW * 100f).coerceIn(0f, 95f),
                        topPct = (block.box.top / imgH * 100f).coerceIn(0f, 95f),
                        widthPct = (block.box.width() / imgW * 100f).coerceIn(5f, 98f),
                        heightPct = (block.box.height() / imgH * 100f).coerceIn(4f, 98f),
                        originalText = block.text,
                        translatedText = translated,
                        bgColor = bgCss,
                        textColor = fgCss,
                    )
                )
            }
            output
        } finally {
            try { bitmap.recycle() } catch (_: Exception) {}
        }
    }

    private suspend fun recognizeMultiScript(bitmap: Bitmap, fromLang: String): List<RecognizedBox> {
        val norm = fromLang.trim().lowercase(Locale.ROOT)
        val orderedRecognizers: List<TextRecognizer> = when {
            norm.startsWith("ja") -> listOf(japaneseRecognizer, latinRecognizer)
            norm.startsWith("zh") -> listOf(chineseRecognizer, latinRecognizer)
            norm.startsWith("ko") -> listOf(koreanRecognizer, latinRecognizer)
            norm.startsWith("hi") || norm.startsWith("mr") || norm.startsWith("ne") || norm.startsWith("sa") ->
                listOf(devanagariRecognizer, latinRecognizer)
            norm.isNotBlank() && norm != "auto" -> listOf(latinRecognizer, japaneseRecognizer)
            else -> {
                // Auto mode: Japanese recognizer reads Japanese + CJK Hanzi + Latin simultaneously;
                // fallback to Chinese, Korean, Devanagari if nothing meaningful is detected.
                listOf(japaneseRecognizer, chineseRecognizer, koreanRecognizer, devanagariRecognizer)
            }
        }

        var bestBlocks: List<RecognizedBox> = emptyList()
        var bestScore = 0
        for (rec in orderedRecognizers) {
            val found = runRecognizer(rec, bitmap)
            val score = scoreRecognition(found)
            if (score > bestScore) {
                bestScore = score
                bestBlocks = found
            }
            // Stop early as soon as we have solid readable text blocks
            if (bestScore >= 12) break
        }
        return bestBlocks
    }

    private fun scoreRecognition(blocks: List<RecognizedBox>): Int {
        if (blocks.isEmpty()) return 0
        var chars = 0
        for (b in blocks) {
            for (ch in b.text) {
                if (Character.isLetter(ch)) chars++
            }
        }
        return chars
    }

    private suspend fun runRecognizer(recognizer: TextRecognizer, bitmap: Bitmap): List<RecognizedBox> {
        return withContext(Dispatchers.Default) {
            suspendCancellableCoroutine { cont ->
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                recognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        val blocks = visionText.textBlocks.mapNotNull { b ->
                            val rect = b.boundingBox ?: return@mapNotNull null
                            val clean = b.text.replace(Regex("\\s+"), " ").trim()
                            if (clean.length < 2 || !clean.any { Character.isLetter(it) }) {
                                null
                            } else {
                                RecognizedBox(
                                    text = clean,
                                    box = rect,
                                    recognizedLangHint = b.recognizedLanguage.takeIf { !it.isNullOrBlank() && it != "und" },
                                )
                            }
                        }
                        if (cont.isActive) cont.resume(blocks)
                    }
                    .addOnFailureListener { err ->
                        UHLog.w("translate_ocr", "ML Kit OCR pass failed: ${err.message}")
                        if (cont.isActive) cont.resume(emptyList())
                    }
            }
        }
    }

    private suspend fun identifyLanguageOnDevice(text: String): String? {
        return withContext(Dispatchers.Default) {
            suspendCancellableCoroutine { cont ->
                languageIdentifier.identifyLanguage(text)
                    .addOnSuccessListener { tag ->
                        val mapped = if (tag.isNullOrBlank() || tag == "und") {
                            null
                        } else {
                            mapToMlKitLanguage(tag)
                        }
                        if (cont.isActive) cont.resume(mapped)
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resume(null)
                    }
            }
        }
    }

    private suspend fun translateTextOnDevice(
        text: String,
        srcMlKit: String,
        dstMlKit: String,
    ): String? {
        val cacheKey = "$srcMlKit|$dstMlKit|$text"
        getCached(cacheKey)?.let { return it }

        val translator = getOrCreateTranslator(srcMlKit, dstMlKit)
        val conditions = DownloadConditions.Builder().build()

        val ready = suspendCancellableCoroutine<Boolean> { cont ->
            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener { if (cont.isActive) cont.resume(true) }
                .addOnFailureListener { err ->
                    UHLog.w("translate_ocr", "ML Kit model download ($srcMlKit->$dstMlKit) failed: ${err.message}")
                    if (cont.isActive) cont.resume(false)
                }
        }
        if (!ready) return null

        val result = suspendCancellableCoroutine<String?> { cont ->
            translator.translate(text)
                .addOnSuccessListener { out -> if (cont.isActive) cont.resume(out?.trim()) }
                .addOnFailureListener { err ->
                    UHLog.w("translate_ocr", "ML Kit translate failed: ${err.message}")
                    if (cont.isActive) cont.resume(null)
                }
        }
        if (!result.isNullOrBlank()) {
            putCached(cacheKey, result)
        }
        return result
    }

    private fun mapToMlKitLanguage(rawTag: String): String? {
        val clean = rawTag.trim().lowercase(Locale.ROOT)
        if (clean.isBlank() || clean == "auto" || clean == "und") return null
        TranslateLanguage.fromLanguageTag(clean)?.let { return it }
        val base = clean.substringBefore("-").substringBefore("_")
        return TranslateLanguage.fromLanguageTag(base)
    }

    private fun guessScriptFallbackLang(text: String): String? {
        var kana = 0
        var cjk = 0
        var hangul = 0
        var devanagari = 0
        var cyrillic = 0
        var arabic = 0
        for (ch in text) {
            val code = ch.code
            when {
                code in 0x3040..0x30FF -> kana++
                code in 0x4E00..0x9FFF -> cjk++
                code in 0xAC00..0xD7AF -> hangul++
                code in 0x0900..0x097F -> devanagari++
                code in 0x0400..0x04FF -> cyrillic++
                code in 0x0600..0x06FF -> arabic++
            }
        }
        return when {
            kana > 0 -> TranslateLanguage.JAPANESE
            hangul > 0 -> TranslateLanguage.KOREAN
            cjk > 0 -> TranslateLanguage.CHINESE
            devanagari > 0 -> TranslateLanguage.HINDI
            cyrillic > 0 -> TranslateLanguage.RUSSIAN
            arabic > 0 -> TranslateLanguage.ARABIC
            else -> null
        }
    }

    /**
     * Samples pixels right around the perimeter of [box] to render natural,
     * contrast-adaptive overlays that blend cleanly into comics, posters, and graphics.
     */
    private fun sampleBlockColors(bitmap: Bitmap, box: Rect): Pair<String, String> {
        return try {
            val w = bitmap.width
            val h = bitmap.height
            val pad = 3
            val left = (box.left - pad).coerceIn(0, w - 1)
            val right = (box.right + pad).coerceIn(0, w - 1)
            val top = (box.top - pad).coerceIn(0, h - 1)
            val bottom = (box.bottom + pad).coerceIn(0, h - 1)
            val midX = ((left + right) / 2).coerceIn(0, w - 1)
            val midY = ((top + bottom) / 2).coerceIn(0, h - 1)

            val coords = arrayOf(
                left to top, midX to top, right to top,
                left to midY, right to midY,
                left to bottom, midX to bottom, right to bottom,
            )
            var rSum = 0
            var gSum = 0
            var bSum = 0
            var count = 0
            for ((x, y) in coords) {
                val px = bitmap.getPixel(x, y)
                if (Color.alpha(px) > 120) {
                    rSum += Color.red(px)
                    gSum += Color.green(px)
                    bSum += Color.blue(px)
                    count++
                }
            }
            if (count == 0) return "rgba(15,23,42,0.92)" to "#F8FAFC"
            val r = (rSum / count).coerceIn(0, 255)
            val g = (gSum / count).coerceIn(0, 255)
            val b = (bSum / count).coerceIn(0, 255)
            val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
            val fg = if (luminance > 0.54) "#0F172A" else "#F8FAFC"
            "rgba($r,$g,$b,0.93)" to fg
        } catch (_: Exception) {
            "rgba(15,23,42,0.92)" to "#F8FAFC"
        }
    }

    private fun decodeImageBitmap(imageUrl: String?, dataUrl: String?, pageUrl: String?): Bitmap? {
        if (!dataUrl.isNullOrBlank() && dataUrl.startsWith("data:")) {
            try {
                val b64 = dataUrl.substringAfter(",", "")
                if (b64.isNotBlank()) {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    decodeSampledBytes(bytes)?.let { return it }
                }
            } catch (_: Exception) {}
        }

        if (!imageUrl.isNullOrBlank() && (imageUrl.startsWith("http://") || imageUrl.startsWith("https://"))) {
            return try {
                val builder = Request.Builder()
                    .url(imageUrl)
                    .header("User-Agent", UhHttpClient.DEFAULT_USER_AGENT)
                    .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                if (!pageUrl.isNullOrBlank() && (pageUrl.startsWith("http://") || pageUrl.startsWith("https://"))) {
                    builder.header("Referer", pageUrl)
                    try {
                        val u = URI(pageUrl)
                        if (!u.scheme.isNullOrBlank() && !u.host.isNullOrBlank()) {
                            builder.header("Origin", "${u.scheme}://${u.host}")
                        }
                    } catch (_: Exception) {}
                }
                imageHttpClient.newCall(builder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    val bytes = resp.body?.bytes() ?: return null
                    if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
                    decodeSampledBytes(bytes)
                }
            } catch (e: Exception) {
                UHLog.w("translate_ocr", "Image download failed ($imageUrl): ${e.message}")
                null
            }
        }
        return null
    }

    private fun decodeSampledBytes(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val rawW = bounds.outWidth
        val rawH = bounds.outHeight
        if (rawW <= 0 || rawH <= 0) return null

        var sample = 1
        val maxSide = max(rawW, rawH)
        while (maxSide / sample > MAX_DECODE_DIM) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = min(sample, 16)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
