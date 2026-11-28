package app.harvester.companion.browser.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import app.harvester.companion.UHLog
import app.harvester.companion.core.network.UhHttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Free online Neural TTS synthesizer powered by Microsoft Edge Read Aloud Neural voices.
 *
 * Features:
 * - Curated catalog of high-naturalness Neural voices across 35+ languages with verified Male/Female gender.
 * - Parses exact `WordBoundary` millisecond timestamps for real-time word highlighting & instant Book Page auto-turn.
 * - Background prefetches the next sentence while the current sentence plays to eliminate awkward pauses on full stops.
 * - Graceful automatic fallback callback so ReaderModeManager seamlessly switches to on-device Android TTS if offline.
 */
object NeuralTtsClient {

    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val CHROMIUM_FULL_VERSION = "130.0.2849.68"
    private const val SEC_MS_GEC_VERSION = "1-$CHROMIUM_FULL_VERSION"
    private const val WIN_EPOCH_OFFSET_SEC = 11644473600L
    private const val SYNTH_TIMEOUT_MS = 6500L

    data class NeuralVoiceSpec(
        val shortName: String,
        val displayName: String,
        val gender: ReaderVoiceGender,
        val langTag: String
    )

    data class WordMark(
        val startMs: Long,
        val charOffset: Int,
        val charLength: Int,
        val wordText: String
    )

    data class SynthesizedChunk(
        val audioFile: File,
        val words: List<WordMark>
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)
    private val prefetchCache = ConcurrentHashMap<String, CompletableDeferred<SynthesizedChunk?>>()

    @Volatile
    private var mediaPlayer: MediaPlayer? = null
    @Volatile
    private var wordTrackerRunnable: Runnable? = null
    @Volatile
    private var activeJob: Job? = null

    /**
     * Verified high-naturalness Neural voices mapped by ISO-639 base language code.
     * Every entry has verified gender — zero guessing or random assignment.
     */
    private val voiceCatalog: Map<String, List<NeuralVoiceSpec>> = mapOf(
        "en" to listOf(
            NeuralVoiceSpec("en-US-AvaMultilingualNeural", "Ava (Natural)", ReaderVoiceGender.FEMALE, "en-US"),
            NeuralVoiceSpec("en-US-AndrewMultilingualNeural", "Andrew (Natural)", ReaderVoiceGender.MALE, "en-US"),
            NeuralVoiceSpec("en-US-EmmaMultilingualNeural", "Emma (Warm)", ReaderVoiceGender.FEMALE, "en-US"),
            NeuralVoiceSpec("en-US-BrianMultilingualNeural", "Brian (Deep)", ReaderVoiceGender.MALE, "en-US"),
            NeuralVoiceSpec("en-GB-SoniaNeural", "Sonia (UK)", ReaderVoiceGender.FEMALE, "en-GB"),
            NeuralVoiceSpec("en-GB-RyanNeural", "Ryan (UK)", ReaderVoiceGender.MALE, "en-GB")
        ),
        "hi" to listOf(
            NeuralVoiceSpec("hi-IN-SwaraNeural", "Swara", ReaderVoiceGender.FEMALE, "hi-IN"),
            NeuralVoiceSpec("hi-IN-MadhurNeural", "Madhur", ReaderVoiceGender.MALE, "hi-IN")
        ),
        "es" to listOf(
            NeuralVoiceSpec("es-ES-ElviraNeural", "Elvira", ReaderVoiceGender.FEMALE, "es-ES"),
            NeuralVoiceSpec("es-ES-AlvaroNeural", "Alvaro", ReaderVoiceGender.MALE, "es-ES"),
            NeuralVoiceSpec("es-MX-DaliaNeural", "Dalia (MX)", ReaderVoiceGender.FEMALE, "es-MX"),
            NeuralVoiceSpec("es-MX-JorgeNeural", "Jorge (MX)", ReaderVoiceGender.MALE, "es-MX")
        ),
        "fr" to listOf(
            NeuralVoiceSpec("fr-FR-VivienneMultilingualNeural", "Vivienne", ReaderVoiceGender.FEMALE, "fr-FR"),
            NeuralVoiceSpec("fr-FR-RemyMultilingualNeural", "Remy", ReaderVoiceGender.MALE, "fr-FR"),
            NeuralVoiceSpec("fr-FR-DeniseNeural", "Denise", ReaderVoiceGender.FEMALE, "fr-FR"),
            NeuralVoiceSpec("fr-FR-HenriNeural", "Henri", ReaderVoiceGender.MALE, "fr-FR")
        ),
        "de" to listOf(
            NeuralVoiceSpec("de-DE-SeraphinaMultilingualNeural", "Seraphina", ReaderVoiceGender.FEMALE, "de-DE"),
            NeuralVoiceSpec("de-DE-FlorianMultilingualNeural", "Florian", ReaderVoiceGender.MALE, "de-DE"),
            NeuralVoiceSpec("de-DE-KatjaNeural", "Katja", ReaderVoiceGender.FEMALE, "de-DE"),
            NeuralVoiceSpec("de-DE-ConradNeural", "Conrad", ReaderVoiceGender.MALE, "de-DE")
        ),
        "pt" to listOf(
            NeuralVoiceSpec("pt-BR-FranciscaNeural", "Francisca", ReaderVoiceGender.FEMALE, "pt-BR"),
            NeuralVoiceSpec("pt-BR-AntonioNeural", "Antonio", ReaderVoiceGender.MALE, "pt-BR"),
            NeuralVoiceSpec("pt-PT-RaquelNeural", "Raquel (PT)", ReaderVoiceGender.FEMALE, "pt-PT"),
            NeuralVoiceSpec("pt-PT-DuarteNeural", "Duarte (PT)", ReaderVoiceGender.MALE, "pt-PT")
        ),
        "it" to listOf(
            NeuralVoiceSpec("it-IT-ElsaNeural", "Elsa", ReaderVoiceGender.FEMALE, "it-IT"),
            NeuralVoiceSpec("it-IT-DiegoNeural", "Diego", ReaderVoiceGender.MALE, "it-IT")
        ),
        "ru" to listOf(
            NeuralVoiceSpec("ru-RU-SvetlanaNeural", "Svetlana", ReaderVoiceGender.FEMALE, "ru-RU"),
            NeuralVoiceSpec("ru-RU-DmitryNeural", "Dmitry", ReaderVoiceGender.MALE, "ru-RU")
        ),
        "ja" to listOf(
            NeuralVoiceSpec("ja-JP-NanamiNeural", "Nanami", ReaderVoiceGender.FEMALE, "ja-JP"),
            NeuralVoiceSpec("ja-JP-KeitaNeural", "Keita", ReaderVoiceGender.MALE, "ja-JP")
        ),
        "ko" to listOf(
            NeuralVoiceSpec("ko-KR-SunHiNeural", "Sun-Hi", ReaderVoiceGender.FEMALE, "ko-KR"),
            NeuralVoiceSpec("ko-KR-InJoonNeural", "InJoon", ReaderVoiceGender.MALE, "ko-KR")
        ),
        "zh" to listOf(
            NeuralVoiceSpec("zh-CN-XiaoxiaoNeural", "Xiaoxiao", ReaderVoiceGender.FEMALE, "zh-CN"),
            NeuralVoiceSpec("zh-CN-YunxiNeural", "Yunxi", ReaderVoiceGender.MALE, "zh-CN"),
            NeuralVoiceSpec("zh-TW-HsiaoChenNeural", "HsiaoChen (TW)", ReaderVoiceGender.FEMALE, "zh-TW"),
            NeuralVoiceSpec("zh-TW-YunJheNeural", "YunJhe (TW)", ReaderVoiceGender.MALE, "zh-TW")
        ),
        "ar" to listOf(
            NeuralVoiceSpec("ar-SA-ZariyahNeural", "Zariyah", ReaderVoiceGender.FEMALE, "ar-SA"),
            NeuralVoiceSpec("ar-SA-HamedNeural", "Hamed", ReaderVoiceGender.MALE, "ar-SA")
        ),
        "tr" to listOf(
            NeuralVoiceSpec("tr-TR-EmelNeural", "Emel", ReaderVoiceGender.FEMALE, "tr-TR"),
            NeuralVoiceSpec("tr-TR-AhmetNeural", "Ahmet", ReaderVoiceGender.MALE, "tr-TR")
        ),
        "id" to listOf(
            NeuralVoiceSpec("id-ID-GadisNeural", "Gadis", ReaderVoiceGender.FEMALE, "id-ID"),
            NeuralVoiceSpec("id-ID-ArdiNeural", "Ardi", ReaderVoiceGender.MALE, "id-ID")
        ),
        "vi" to listOf(
            NeuralVoiceSpec("vi-VN-HoaiMyNeural", "HoaiMy", ReaderVoiceGender.FEMALE, "vi-VN"),
            NeuralVoiceSpec("vi-VN-NamMinhNeural", "NamMinh", ReaderVoiceGender.MALE, "vi-VN")
        ),
        "th" to listOf(
            NeuralVoiceSpec("th-TH-PremwadeeNeural", "Premwadee", ReaderVoiceGender.FEMALE, "th-TH"),
            NeuralVoiceSpec("th-TH-NiwatNeural", "Niwat", ReaderVoiceGender.MALE, "th-TH")
        ),
        "nl" to listOf(
            NeuralVoiceSpec("nl-NL-ColetteNeural", "Colette", ReaderVoiceGender.FEMALE, "nl-NL"),
            NeuralVoiceSpec("nl-NL-MaartenNeural", "Maarten", ReaderVoiceGender.MALE, "nl-NL")
        ),
        "pl" to listOf(
            NeuralVoiceSpec("pl-PL-ZofiaNeural", "Zofia", ReaderVoiceGender.FEMALE, "pl-PL"),
            NeuralVoiceSpec("pl-PL-MarekNeural", "Marek", ReaderVoiceGender.MALE, "pl-PL")
        ),
        "uk" to listOf(
            NeuralVoiceSpec("uk-UA-PolinaNeural", "Polina", ReaderVoiceGender.FEMALE, "uk-UA"),
            NeuralVoiceSpec("uk-UA-OstapNeural", "Ostap", ReaderVoiceGender.MALE, "uk-UA")
        ),
        "sv" to listOf(
            NeuralVoiceSpec("sv-SE-SofieNeural", "Sofie", ReaderVoiceGender.FEMALE, "sv-SE"),
            NeuralVoiceSpec("sv-SE-MattiasNeural", "Mattias", ReaderVoiceGender.MALE, "sv-SE")
        ),
        "bn" to listOf(
            NeuralVoiceSpec("bn-IN-TanishaaNeural", "Tanishaa", ReaderVoiceGender.FEMALE, "bn-IN"),
            NeuralVoiceSpec("bn-IN-BashkarNeural", "Bashkar", ReaderVoiceGender.MALE, "bn-IN")
        ),
        "ur" to listOf(
            NeuralVoiceSpec("ur-PK-UzmaNeural", "Uzma", ReaderVoiceGender.FEMALE, "ur-PK"),
            NeuralVoiceSpec("ur-PK-AsadNeural", "Asad", ReaderVoiceGender.MALE, "ur-PK")
        ),
        "ta" to listOf(
            NeuralVoiceSpec("ta-IN-PallaviNeural", "Pallavi", ReaderVoiceGender.FEMALE, "ta-IN"),
            NeuralVoiceSpec("ta-IN-ValluvarNeural", "Valluvar", ReaderVoiceGender.MALE, "ta-IN")
        ),
        "te" to listOf(
            NeuralVoiceSpec("te-IN-ShrutiNeural", "Shruti", ReaderVoiceGender.FEMALE, "te-IN"),
            NeuralVoiceSpec("te-IN-MohanNeural", "Mohan", ReaderVoiceGender.MALE, "te-IN")
        ),
        "mr" to listOf(
            NeuralVoiceSpec("mr-IN-AarohiNeural", "Aarohi", ReaderVoiceGender.FEMALE, "mr-IN"),
            NeuralVoiceSpec("mr-IN-ManoharNeural", "Manohar", ReaderVoiceGender.MALE, "mr-IN")
        ),
        "gu" to listOf(
            NeuralVoiceSpec("gu-IN-DhwaniNeural", "Dhwani", ReaderVoiceGender.FEMALE, "gu-IN"),
            NeuralVoiceSpec("gu-IN-NiranjanNeural", "Niranjan", ReaderVoiceGender.MALE, "gu-IN")
        ),
        "el" to listOf(
            NeuralVoiceSpec("el-GR-AthinaNeural", "Athina", ReaderVoiceGender.FEMALE, "el-GR"),
            NeuralVoiceSpec("el-GR-NestorasNeural", "Nestoras", ReaderVoiceGender.MALE, "el-GR")
        ),
        "he" to listOf(
            NeuralVoiceSpec("he-IL-HilaNeural", "Hila", ReaderVoiceGender.FEMALE, "he-IL"),
            NeuralVoiceSpec("he-IL-AvriNeural", "Avri", ReaderVoiceGender.MALE, "he-IL")
        ),
        "fa" to listOf(
            NeuralVoiceSpec("fa-IR-DilaraNeural", "Dilara", ReaderVoiceGender.FEMALE, "fa-IR"),
            NeuralVoiceSpec("fa-IR-FaridNeural", "Farid", ReaderVoiceGender.MALE, "fa-IR")
        ),
        "cs" to listOf(
            NeuralVoiceSpec("cs-CZ-VlastaNeural", "Vlasta", ReaderVoiceGender.FEMALE, "cs-CZ"),
            NeuralVoiceSpec("cs-CZ-AntoninNeural", "Antonin", ReaderVoiceGender.MALE, "cs-CZ")
        ),
        "ro" to listOf(
            NeuralVoiceSpec("ro-RO-AlinaNeural", "Alina", ReaderVoiceGender.FEMALE, "ro-RO"),
            NeuralVoiceSpec("ro-RO-EmilNeural", "Emil", ReaderVoiceGender.MALE, "ro-RO")
        ),
        "hu" to listOf(
            NeuralVoiceSpec("hu-HU-NoemiNeural", "Noemi", ReaderVoiceGender.FEMALE, "hu-HU"),
            NeuralVoiceSpec("hu-HU-TamasNeural", "Tamas", ReaderVoiceGender.MALE, "hu-HU")
        ),
        "fi" to listOf(
            NeuralVoiceSpec("fi-FI-NooraNeural", "Noora", ReaderVoiceGender.FEMALE, "fi-FI"),
            NeuralVoiceSpec("fi-FI-HarriNeural", "Harri", ReaderVoiceGender.MALE, "fi-FI")
        ),
        "da" to listOf(
            NeuralVoiceSpec("da-DK-ChristelNeural", "Christel", ReaderVoiceGender.FEMALE, "da-DK"),
            NeuralVoiceSpec("da-DK-JeppeNeural", "Jeppe", ReaderVoiceGender.MALE, "da-DK")
        ),
        "nb" to listOf(
            NeuralVoiceSpec("nb-NO-PernilleNeural", "Pernille", ReaderVoiceGender.FEMALE, "nb-NO"),
            NeuralVoiceSpec("nb-NO-FinnNeural", "Finn", ReaderVoiceGender.MALE, "nb-NO")
        ),
        "ms" to listOf(
            NeuralVoiceSpec("ms-MY-YasminNeural", "Yasmin", ReaderVoiceGender.FEMALE, "ms-MY"),
            NeuralVoiceSpec("ms-MY-OsmanNeural", "Osman", ReaderVoiceGender.MALE, "ms-MY")
        ),
        "fil" to listOf(
            NeuralVoiceSpec("fil-PH-BlessicaNeural", "Blessica", ReaderVoiceGender.FEMALE, "fil-PH"),
            NeuralVoiceSpec("fil-PH-AngeloNeural", "Angelo", ReaderVoiceGender.MALE, "fil-PH")
        )
    )

    fun voicesForLanguage(langTag: String): List<ReaderVoiceOption> {
        val base = normalizeBaseLang(langTag)
        val specs = voiceCatalog[base] ?: voiceCatalog["en"].orEmpty()
        return specs.map { spec ->
            ReaderVoiceOption(
                name = "neural:${spec.shortName}",
                label = "${spec.displayName} · AI",
                gender = spec.gender,
                langTag = spec.langTag,
                quality = 500,
                isNeural = true
            )
        }
    }

    fun resolveVoiceSpec(langTag: String, prefs: ReaderPrefsState): NeuralVoiceSpec {
        val base = normalizeBaseLang(langTag)
        val pool = voiceCatalog[base] ?: voiceCatalog["en"]!!

        if (prefs.voiceName.startsWith("neural:")) {
            val wanted = prefs.voiceName.removePrefix("neural:")
            pool.firstOrNull { it.shortName == wanted }?.let { return it }
            // Also check all languages if user explicitly picked a multilingual voice
            voiceCatalog.values.flatten().firstOrNull { it.shortName == wanted }?.let {
                if (it.shortName.contains("Multilingual", ignoreCase = true) ||
                    normalizeBaseLang(it.langTag) == base
                ) {
                    return it
                }
            }
        }

        return when (prefs.voiceGender) {
            ReaderVoiceGender.FEMALE -> pool.firstOrNull { it.gender == ReaderVoiceGender.FEMALE } ?: pool.first()
            ReaderVoiceGender.MALE -> pool.firstOrNull { it.gender == ReaderVoiceGender.MALE } ?: pool.first()
            ReaderVoiceGender.AUTO -> pool.first()
        }
    }

    fun stop() {
        generation.incrementAndGet()
        activeJob?.cancel()
        activeJob = null
        stopWordTracker()
        releasePlayer()
        clearPrefetchCache()
    }

    /**
     * Synthesizes and plays [sentence] (while prefetching [nextSentence] if provided).
     * Calls [onWordProgress] as each word starts speaking, [onCompleted] when done,
     * or [onFallbackNeeded] if network/service is unavailable.
     */
    fun speakSentence(
        context: Context,
        sentence: String,
        nextSentence: String?,
        langTag: String,
        prefs: ReaderPrefsState,
        onWordProgress: (charOffset: Int, charLength: Int) -> Unit,
        onCompleted: () -> Unit,
        onFallbackNeeded: () -> Unit
    ) {
        val gen = generation.incrementAndGet()
        activeJob?.cancel()
        stopWordTracker()
        releasePlayer()

        val spec = resolveVoiceSpec(langTag, prefs)
        val ratePct = ((prefs.ttsSpeed - 1.0f) * 100f).roundToInt().coerceIn(-40, 100)
        val appCtx = context.applicationContext

        activeJob = scope.launch {
            val chunk = fetchOrSynthesize(appCtx, sentence, spec.shortName, ratePct)
            if (generation.get() != gen) return@launch

            if (chunk == null || !chunk.audioFile.exists() || chunk.audioFile.length() < 64L) {
                mainHandler.post {
                    if (generation.get() == gen) onFallbackNeeded()
                }
                return@launch
            }

            // Prefetch next sentence immediately while current plays -> near-zero pause at full stops!
            if (!nextSentence.isNullOrBlank()) {
                prefetch(appCtx, nextSentence, spec.shortName, ratePct)
            }

            mainHandler.post {
                if (generation.get() != gen) return@post
                playChunk(
                    gen = gen,
                    chunk = chunk,
                    sourceText = sentence,
                    onWordProgress = onWordProgress,
                    onCompleted = onCompleted,
                    onFallbackNeeded = onFallbackNeeded
                )
            }
        }
    }

    private fun prefetch(ctx: Context, text: String, voiceShortName: String, ratePct: Int) {
        val key = cacheKey(text, voiceShortName, ratePct)
        if (prefetchCache.containsKey(key)) return
        val deferred = CompletableDeferred<SynthesizedChunk?>()
        if (prefetchCache.putIfAbsent(key, deferred) != null) return
        // Keep cache tiny (max 4 audio chunks)
        if (prefetchCache.size > 4) {
            val oldest = prefetchCache.keys.firstOrNull { it != key }
            if (oldest != null) {
                prefetchCache.remove(oldest)?.let { d ->
                    if (d.isCompleted) {
                        runCatching { d.getCompleted()?.audioFile?.delete() }
                    }
                }
            }
        }
        scope.launch {
            val res = synthesizeOnce(ctx, text, voiceShortName, ratePct)
            deferred.complete(res)
        }
    }

    private suspend fun fetchOrSynthesize(
        ctx: Context,
        text: String,
        voiceShortName: String,
        ratePct: Int
    ): SynthesizedChunk? {
        val key = cacheKey(text, voiceShortName, ratePct)
        val existing = prefetchCache.remove(key)
        if (existing != null) {
            val cached = withTimeoutOrNull(SYNTH_TIMEOUT_MS) { existing.await() }
            if (cached != null && cached.audioFile.exists() && cached.audioFile.length() >= 64L) {
                return cached
            }
        }
        return synthesizeOnce(ctx, text, voiceShortName, ratePct)
    }

    private suspend fun synthesizeOnce(
        ctx: Context,
        rawText: String,
        voiceShortName: String,
        ratePct: Int
    ): SynthesizedChunk? {
        val text = rawText.trim()
        if (text.isEmpty()) return null

        val resultDeferred = CompletableDeferred<SynthesizedChunk?>()
        val connId = UUID.randomUUID().toString().replace("-", "")
        val reqId = UUID.randomUUID().toString().replace("-", "")
        val gec = generateSecMsGec()
        val wsUrl = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
            "&Sec-MS-GEC=$gec" +
            "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION" +
            "&ConnectionId=$connId"

        val request = Request.Builder()
            .url(wsUrl)
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CHROMIUM_FULL_VERSION Safari/537.36 Edg/$CHROMIUM_FULL_VERSION"
            )
            .build()

        val audioBuffer = ByteArrayOutputStream(24 * 1024)
        val words = mutableListOf<WordMark>()
        val rateSign = if (ratePct >= 0) "+${ratePct}%" else "${ratePct}%"
        val escapedText = escapeXml(text)
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='$voiceShortName'>" +
            "<prosody pitch='+0Hz' rate='$rateSign' volume='+0%'>$escapedText</prosody>" +
            "</voice></speak>"

        var ws: WebSocket? = null
        try {
            ws = UhHttpClient.client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val configMsg = "X-Timestamp:${utcTimestamp()}\r\n" +
                        "Content-Type:application/json; charset=utf-8\r\n" +
                        "Path:speech.config\r\n\r\n" +
                        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"true\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
                    val ssmlMsg = "X-RequestId:$reqId\r\n" +
                        "Content-Type:application/ssml+xml\r\n" +
                        "X-Timestamp:${utcTimestamp()}Z\r\n" +
                        "Path:ssml\r\n\r\n" +
                        ssml
                    webSocket.send(configMsg)
                    webSocket.send(ssmlMsg)
                }

                override fun onMessage(webSocket: WebSocket, textMsg: String) {
                    if (textMsg.contains("Path:audio.metadata")) {
                        val idx = textMsg.indexOf("\r\n\r\n")
                        if (idx >= 0) {
                            parseWordMetadata(textMsg.substring(idx + 4), words)
                        }
                    } else if (textMsg.contains("Path:turn.end")) {
                        val bytes = audioBuffer.toByteArray()
                        if (bytes.size >= 64) {
                            val dir = File(ctx.cacheDir, "uh_neural_tts").apply { mkdirs() }
                            val file = File(dir, "chunk_${reqId}.mp3")
                            runCatching { file.writeBytes(bytes) }
                            val aligned = alignWordOffsetsToText(words, text)
                            resultDeferred.complete(SynthesizedChunk(file, aligned))
                        } else {
                            resultDeferred.complete(null)
                        }
                        runCatching { webSocket.close(1000, "done") }
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val arr = bytes.toByteArray()
                    if (arr.size < 2) return
                    val headerLen = ((arr[0].toInt() and 0xFF) shl 8) or (arr[1].toInt() and 0xFF)
                    val audioStart = 2 + headerLen
                    if (audioStart < arr.size) {
                        val headerStr = String(arr, 2, headerLen.coerceAtMost(arr.size - 2), Charsets.UTF_8)
                        if (headerStr.contains("Path:audio")) {
                            audioBuffer.write(arr, audioStart, arr.size - audioStart)
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    UHLog.w("reader_tts", "Neural TTS WS failed: ${t.message}")
                    if (!resultDeferred.isCompleted) resultDeferred.complete(null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!resultDeferred.isCompleted) resultDeferred.complete(null)
                }
            })

            val out = withTimeoutOrNull(SYNTH_TIMEOUT_MS) { resultDeferred.await() }
            if (out == null) {
                runCatching { ws.cancel() }
            }
            return out
        } catch (e: Exception) {
            UHLog.w("reader_tts", "Neural TTS error: ${e.message}")
            runCatching { ws?.cancel() }
            return null
        }
    }

    private fun playChunk(
        gen: Long,
        chunk: SynthesizedChunk,
        sourceText: String,
        onWordProgress: (Int, Int) -> Unit,
        onCompleted: () -> Unit,
        onFallbackNeeded: () -> Unit
    ) {
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(chunk.audioFile.absolutePath)
                setOnCompletionListener {
                    stopWordTracker()
                    runCatching { chunk.audioFile.delete() }
                    releasePlayer()
                    if (generation.get() == gen) {
                        onCompleted()
                    }
                }
                setOnErrorListener { _, _, _ ->
                    stopWordTracker()
                    runCatching { chunk.audioFile.delete() }
                    releasePlayer()
                    if (generation.get() == gen) {
                        onFallbackNeeded()
                    }
                    true
                }
                prepare()
                start()
            }
            mediaPlayer = mp

            // Drive real-time word-level progress synchronized to audio playback clock
            startWordTracker(gen, mp, chunk.words, sourceText, onWordProgress)
        } catch (e: Exception) {
            UHLog.w("reader_tts", "Neural MediaPlayer failed: ${e.message}")
            runCatching { chunk.audioFile.delete() }
            releasePlayer()
            if (generation.get() == gen) onFallbackNeeded()
        }
    }

    private fun startWordTracker(
        gen: Long,
        mp: MediaPlayer,
        words: List<WordMark>,
        sourceText: String,
        onWordProgress: (Int, Int) -> Unit
    ) {
        stopWordTracker()
        if (words.isEmpty() && sourceText.isEmpty()) return

        var lastEmittedIdx = -1
        val runnable = object : Runnable {
            override fun run() {
                if (generation.get() != gen) return
                val posMs = try {
                    if (mp.isPlaying) mp.currentPosition.toLong() else -1L
                } catch (_: Exception) {
                    -1L
                }
                if (posMs < 0L) return

                if (words.isNotEmpty()) {
                    var activeIdx = -1
                    for (i in words.indices) {
                        if (words[i].startMs <= posMs + 35L) {
                            activeIdx = i
                        } else {
                            break
                        }
                    }
                    if (activeIdx >= 0 && activeIdx != lastEmittedIdx) {
                        lastEmittedIdx = activeIdx
                        val w = words[activeIdx]
                        onWordProgress(w.charOffset, w.charLength)
                    }
                } else {
                    // Fallback proportional word tracking if a voice omitted WordBoundary metadata
                    val durMs = runCatching { mp.duration.toLong() }.getOrDefault(0L)
                    if (durMs > 100L) {
                        val frac = (posMs.toFloat() / durMs.toFloat()).coerceIn(0f, 0.99f)
                        val offset = (frac * sourceText.length).toInt().coerceIn(0, (sourceText.length - 1).coerceAtLeast(0))
                        if (offset != lastEmittedIdx) {
                            lastEmittedIdx = offset
                            onWordProgress(offset, 1)
                        }
                    }
                }
                mainHandler.postDelayed(this, 48L)
            }
        }
        wordTrackerRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun stopWordTracker() {
        wordTrackerRunnable?.let { mainHandler.removeCallbacks(it) }
        wordTrackerRunnable = null
    }

    private fun releasePlayer() {
        val mp = mediaPlayer
        mediaPlayer = null
        if (mp != null) {
            runCatching {
                if (mp.isPlaying) mp.stop()
            }
            runCatching { mp.release() }
        }
    }

    private fun clearPrefetchCache() {
        val entries = prefetchCache.values.toList()
        prefetchCache.clear()
        entries.forEach { d ->
            if (d.isCompleted) {
                runCatching { d.getCompleted()?.audioFile?.delete() }
            } else {
                d.cancel()
            }
        }
    }

    private fun parseWordMetadata(jsonStr: String, out: MutableList<WordMark>) {
        try {
            val root = JSONObject(jsonStr)
            val meta = root.optJSONArray("Metadata") ?: return
            for (i in 0 until meta.length()) {
                val item = meta.optJSONObject(i) ?: continue
                if (item.optString("Type") != "WordBoundary") continue
                val data = item.optJSONObject("Data") ?: continue
                val offsetTicks = data.optLong("Offset", 0L)
                val startMs = offsetTicks / 10_000L
                val textObj = data.optJSONObject("text") ?: continue
                val wordText = textObj.optString("Text", "")
                if (wordText.isNotEmpty()) {
                    out.add(
                        WordMark(
                            startMs = startMs,
                            charOffset = 0,
                            charLength = wordText.length,
                            wordText = wordText
                        )
                    )
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Maps sequential WordBoundary word strings onto exact character offsets inside [sourceText]
     * so JS `Range` can highlight the exact word and trigger instant Book Page flips.
     */
    private fun alignWordOffsetsToText(rawMarks: List<WordMark>, sourceText: String): List<WordMark> {
        if (rawMarks.isEmpty()) return emptyList()
        val lowerSrc = sourceText.lowercase(Locale.ROOT)
        var cursor = 0
        val aligned = ArrayList<WordMark>(rawMarks.size)
        for (m in rawMarks) {
            val needle = m.wordText.trim().lowercase(Locale.ROOT)
            if (needle.isEmpty()) continue
            var found = lowerSrc.indexOf(needle, cursor)
            if (found < 0 && cursor > 0) {
                found = lowerSrc.indexOf(needle, (cursor - 8).coerceAtLeast(0))
            }
            if (found >= 0) {
                aligned.add(m.copy(charOffset = found, charLength = needle.length))
                cursor = (found + needle.length).coerceAtMost(sourceText.length)
            } else {
                aligned.add(m.copy(charOffset = cursor.coerceAtMost((sourceText.length - 1).coerceAtLeast(0)), charLength = 1))
            }
        }
        return aligned
    }

    private fun generateSecMsGec(): String {
        val nowSec = System.currentTimeMillis() / 1000L
        var ticks = nowSec + WIN_EPOCH_OFFSET_SEC
        ticks -= (ticks % 300L)
        val fileTime = ticks * 10_000_000L
        val raw = "$fileTime$TRUSTED_CLIENT_TOKEN"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.US_ASCII))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format(Locale.US, "%02X", b))
        }
        return sb.toString()
    }

    private fun utcTimestamp(): String {
        val fmt = java.text.SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date())
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun cacheKey(text: String, voice: String, ratePct: Int): String =
        "$voice|$ratePct|${text.hashCode()}|${text.length}"

    fun normalizeBaseLang(langTag: String): String {
        val base = langTag.trim().lowercase(Locale.ROOT).substringBefore("-").substringBefore("_")
        return when (base) {
            "in" -> "id"
            "iw" -> "he"
            "tl" -> "fil"
            "no" -> "nb"
            "" -> "en"
            else -> base
        }
    }
}
