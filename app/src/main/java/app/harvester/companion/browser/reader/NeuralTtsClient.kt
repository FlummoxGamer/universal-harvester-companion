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
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Online Neural TTS synthesizer for UH Reader Read Aloud.
 *
 * Features:
 * - Current Chromium 143+ Sec-MS-GEC + MUID cookie authentication for reliable online synthesis.
 * - Distinct human-sounding Neural voices across 35+ languages with short human names only.
 * - Strict gender preservation across languages (Male stays Male, Female stays Female).
 * - Default clear deep male narrator (Brian in English; primary male narrator per language).
 * - 2-sentence concurrent lookahead pre-buffering for gapless full-stop transitions.
 * - Instant voice preview demo line localized to the active article language.
 */
object NeuralTtsClient {

    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
    private const val SEC_MS_GEC_VERSION = "1-$CHROMIUM_FULL_VERSION"
    private const val WIN_EPOCH_OFFSET_SEC = 11644473600L
    private const val SYNTH_TIMEOUT_MS = 7500L

    data class NeuralVoiceSpec(
        val shortName: String,
        val displayName: String,
        val gender: ReaderVoiceGender,
        val langTag: String,
        val isMultilingual: Boolean = false
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
    private val demoGeneration = AtomicLong(0L)
    private val prefetchCache = ConcurrentHashMap<String, CompletableDeferred<SynthesizedChunk?>>()
    private val secureRandom = SecureRandom()

    @Volatile
    private var mediaPlayer: MediaPlayer? = null
    @Volatile
    private var demoPlayer: MediaPlayer? = null
    @Volatile
    private var wordTrackerRunnable: Runnable? = null
    @Volatile
    private var activeJob: Job? = null
    @Volatile
    private var demoJob: Job? = null

    /**
     * Adaptive Multilingual Neural voices that speak 40+ languages fluently with natural accent.
     * Brian (deep, clear male narrator) is the global default voice.
     */
    private val multilingualVoices: List<NeuralVoiceSpec> = listOf(
        NeuralVoiceSpec("en-US-BrianMultilingualNeural", "Brian", ReaderVoiceGender.MALE, "en-US", isMultilingual = true),
        NeuralVoiceSpec("en-US-AndrewMultilingualNeural", "Andrew", ReaderVoiceGender.MALE, "en-US", isMultilingual = true),
        NeuralVoiceSpec("en-US-AvaMultilingualNeural", "Ava", ReaderVoiceGender.FEMALE, "en-US", isMultilingual = true),
        NeuralVoiceSpec("en-US-EmmaMultilingualNeural", "Emma", ReaderVoiceGender.FEMALE, "en-US", isMultilingual = true),
    )

    /**
     * Verified high-naturalness Neural voices mapped by ISO-639 base language code.
     * Short human names only; primary clear Male narrator listed first per language.
     */
    private val voiceCatalog: Map<String, List<NeuralVoiceSpec>> = mapOf(
        "en" to listOf(
            NeuralVoiceSpec("en-US-BrianMultilingualNeural", "Brian", ReaderVoiceGender.MALE, "en-US", isMultilingual = true),
            NeuralVoiceSpec("en-US-AndrewMultilingualNeural", "Andrew", ReaderVoiceGender.MALE, "en-US", isMultilingual = true),
            NeuralVoiceSpec("en-GB-RyanNeural", "Ryan", ReaderVoiceGender.MALE, "en-GB"),
            NeuralVoiceSpec("en-US-AvaMultilingualNeural", "Ava", ReaderVoiceGender.FEMALE, "en-US", isMultilingual = true),
            NeuralVoiceSpec("en-US-EmmaMultilingualNeural", "Emma", ReaderVoiceGender.FEMALE, "en-US", isMultilingual = true),
            NeuralVoiceSpec("en-GB-SoniaNeural", "Sonia", ReaderVoiceGender.FEMALE, "en-GB"),
        ),
        "hi" to listOf(
            NeuralVoiceSpec("hi-IN-MadhurNeural", "Madhur", ReaderVoiceGender.MALE, "hi-IN"),
            NeuralVoiceSpec("hi-IN-SwaraNeural", "Swara", ReaderVoiceGender.FEMALE, "hi-IN"),
        ),
        "es" to listOf(
            NeuralVoiceSpec("es-ES-AlvaroNeural", "Alvaro", ReaderVoiceGender.MALE, "es-ES"),
            NeuralVoiceSpec("es-MX-JorgeNeural", "Jorge", ReaderVoiceGender.MALE, "es-MX"),
            NeuralVoiceSpec("es-ES-ElviraNeural", "Elvira", ReaderVoiceGender.FEMALE, "es-ES"),
            NeuralVoiceSpec("es-MX-DaliaNeural", "Dalia", ReaderVoiceGender.FEMALE, "es-MX"),
        ),
        "fr" to listOf(
            NeuralVoiceSpec("fr-FR-RemyMultilingualNeural", "Remy", ReaderVoiceGender.MALE, "fr-FR", isMultilingual = true),
            NeuralVoiceSpec("fr-FR-HenriNeural", "Henri", ReaderVoiceGender.MALE, "fr-FR"),
            NeuralVoiceSpec("fr-FR-VivienneMultilingualNeural", "Vivienne", ReaderVoiceGender.FEMALE, "fr-FR", isMultilingual = true),
            NeuralVoiceSpec("fr-FR-DeniseNeural", "Denise", ReaderVoiceGender.FEMALE, "fr-FR"),
        ),
        "de" to listOf(
            NeuralVoiceSpec("de-DE-FlorianMultilingualNeural", "Florian", ReaderVoiceGender.MALE, "de-DE", isMultilingual = true),
            NeuralVoiceSpec("de-DE-ConradNeural", "Conrad", ReaderVoiceGender.MALE, "de-DE"),
            NeuralVoiceSpec("de-DE-SeraphinaMultilingualNeural", "Seraphina", ReaderVoiceGender.FEMALE, "de-DE", isMultilingual = true),
            NeuralVoiceSpec("de-DE-KatjaNeural", "Katja", ReaderVoiceGender.FEMALE, "de-DE"),
        ),
        "pt" to listOf(
            NeuralVoiceSpec("pt-BR-AntonioNeural", "Antonio", ReaderVoiceGender.MALE, "pt-BR"),
            NeuralVoiceSpec("pt-PT-DuarteNeural", "Duarte", ReaderVoiceGender.MALE, "pt-PT"),
            NeuralVoiceSpec("pt-BR-FranciscaNeural", "Francisca", ReaderVoiceGender.FEMALE, "pt-BR"),
            NeuralVoiceSpec("pt-PT-RaquelNeural", "Raquel", ReaderVoiceGender.FEMALE, "pt-PT"),
        ),
        "it" to listOf(
            NeuralVoiceSpec("it-IT-DiegoNeural", "Diego", ReaderVoiceGender.MALE, "it-IT"),
            NeuralVoiceSpec("it-IT-ElsaNeural", "Elsa", ReaderVoiceGender.FEMALE, "it-IT"),
        ),
        "ru" to listOf(
            NeuralVoiceSpec("ru-RU-DmitryNeural", "Dmitry", ReaderVoiceGender.MALE, "ru-RU"),
            NeuralVoiceSpec("ru-RU-SvetlanaNeural", "Svetlana", ReaderVoiceGender.FEMALE, "ru-RU"),
        ),
        "ja" to listOf(
            NeuralVoiceSpec("ja-JP-KeitaNeural", "Keita", ReaderVoiceGender.MALE, "ja-JP"),
            NeuralVoiceSpec("ja-JP-NanamiNeural", "Nanami", ReaderVoiceGender.FEMALE, "ja-JP"),
        ),
        "ko" to listOf(
            NeuralVoiceSpec("ko-KR-InJoonNeural", "InJoon", ReaderVoiceGender.MALE, "ko-KR"),
            NeuralVoiceSpec("ko-KR-SunHiNeural", "Sun-Hi", ReaderVoiceGender.FEMALE, "ko-KR"),
        ),
        "zh" to listOf(
            NeuralVoiceSpec("zh-CN-YunxiNeural", "Yunxi", ReaderVoiceGender.MALE, "zh-CN"),
            NeuralVoiceSpec("zh-TW-YunJheNeural", "YunJhe", ReaderVoiceGender.MALE, "zh-TW"),
            NeuralVoiceSpec("zh-CN-XiaoxiaoNeural", "Xiaoxiao", ReaderVoiceGender.FEMALE, "zh-CN"),
            NeuralVoiceSpec("zh-TW-HsiaoChenNeural", "HsiaoChen", ReaderVoiceGender.FEMALE, "zh-TW"),
        ),
        "ar" to listOf(
            NeuralVoiceSpec("ar-SA-HamedNeural", "Hamed", ReaderVoiceGender.MALE, "ar-SA"),
            NeuralVoiceSpec("ar-SA-ZariyahNeural", "Zariyah", ReaderVoiceGender.FEMALE, "ar-SA"),
        ),
        "tr" to listOf(
            NeuralVoiceSpec("tr-TR-AhmetNeural", "Ahmet", ReaderVoiceGender.MALE, "tr-TR"),
            NeuralVoiceSpec("tr-TR-EmelNeural", "Emel", ReaderVoiceGender.FEMALE, "tr-TR"),
        ),
        "id" to listOf(
            NeuralVoiceSpec("id-ID-ArdiNeural", "Ardi", ReaderVoiceGender.MALE, "id-ID"),
            NeuralVoiceSpec("id-ID-GadisNeural", "Gadis", ReaderVoiceGender.FEMALE, "id-ID"),
        ),
        "vi" to listOf(
            NeuralVoiceSpec("vi-VN-NamMinhNeural", "NamMinh", ReaderVoiceGender.MALE, "vi-VN"),
            NeuralVoiceSpec("vi-VN-HoaiMyNeural", "HoaiMy", ReaderVoiceGender.FEMALE, "vi-VN"),
        ),
        "th" to listOf(
            NeuralVoiceSpec("th-TH-NiwatNeural", "Niwat", ReaderVoiceGender.MALE, "th-TH"),
            NeuralVoiceSpec("th-TH-PremwadeeNeural", "Premwadee", ReaderVoiceGender.FEMALE, "th-TH"),
        ),
        "nl" to listOf(
            NeuralVoiceSpec("nl-NL-MaartenNeural", "Maarten", ReaderVoiceGender.MALE, "nl-NL"),
            NeuralVoiceSpec("nl-NL-ColetteNeural", "Colette", ReaderVoiceGender.FEMALE, "nl-NL"),
        ),
        "pl" to listOf(
            NeuralVoiceSpec("pl-PL-MarekNeural", "Marek", ReaderVoiceGender.MALE, "pl-PL"),
            NeuralVoiceSpec("pl-PL-ZofiaNeural", "Zofia", ReaderVoiceGender.FEMALE, "pl-PL"),
        ),
        "uk" to listOf(
            NeuralVoiceSpec("uk-UA-OstapNeural", "Ostap", ReaderVoiceGender.MALE, "uk-UA"),
            NeuralVoiceSpec("uk-UA-PolinaNeural", "Polina", ReaderVoiceGender.FEMALE, "uk-UA"),
        ),
        "sv" to listOf(
            NeuralVoiceSpec("sv-SE-MattiasNeural", "Mattias", ReaderVoiceGender.MALE, "sv-SE"),
            NeuralVoiceSpec("sv-SE-SofieNeural", "Sofie", ReaderVoiceGender.FEMALE, "sv-SE"),
        ),
        "bn" to listOf(
            NeuralVoiceSpec("bn-IN-BashkarNeural", "Bashkar", ReaderVoiceGender.MALE, "bn-IN"),
            NeuralVoiceSpec("bn-IN-TanishaaNeural", "Tanishaa", ReaderVoiceGender.FEMALE, "bn-IN"),
        ),
        "ur" to listOf(
            NeuralVoiceSpec("ur-PK-AsadNeural", "Asad", ReaderVoiceGender.MALE, "ur-PK"),
            NeuralVoiceSpec("ur-PK-UzmaNeural", "Uzma", ReaderVoiceGender.FEMALE, "ur-PK"),
        ),
        "ta" to listOf(
            NeuralVoiceSpec("ta-IN-ValluvarNeural", "Valluvar", ReaderVoiceGender.MALE, "ta-IN"),
            NeuralVoiceSpec("ta-IN-PallaviNeural", "Pallavi", ReaderVoiceGender.FEMALE, "ta-IN"),
        ),
        "te" to listOf(
            NeuralVoiceSpec("te-IN-MohanNeural", "Mohan", ReaderVoiceGender.MALE, "te-IN"),
            NeuralVoiceSpec("te-IN-ShrutiNeural", "Shruti", ReaderVoiceGender.FEMALE, "te-IN"),
        ),
        "mr" to listOf(
            NeuralVoiceSpec("mr-IN-ManoharNeural", "Manohar", ReaderVoiceGender.MALE, "mr-IN"),
            NeuralVoiceSpec("mr-IN-AarohiNeural", "Aarohi", ReaderVoiceGender.FEMALE, "mr-IN"),
        ),
        "gu" to listOf(
            NeuralVoiceSpec("gu-IN-NiranjanNeural", "Niranjan", ReaderVoiceGender.MALE, "gu-IN"),
            NeuralVoiceSpec("gu-IN-DhwaniNeural", "Dhwani", ReaderVoiceGender.FEMALE, "gu-IN"),
        ),
        "el" to listOf(
            NeuralVoiceSpec("el-GR-NestorasNeural", "Nestoras", ReaderVoiceGender.MALE, "el-GR"),
            NeuralVoiceSpec("el-GR-AthinaNeural", "Athina", ReaderVoiceGender.FEMALE, "el-GR"),
        ),
        "he" to listOf(
            NeuralVoiceSpec("he-IL-AvriNeural", "Avri", ReaderVoiceGender.MALE, "he-IL"),
            NeuralVoiceSpec("he-IL-HilaNeural", "Hila", ReaderVoiceGender.FEMALE, "he-IL"),
        ),
        "fa" to listOf(
            NeuralVoiceSpec("fa-IR-FaridNeural", "Farid", ReaderVoiceGender.MALE, "fa-IR"),
            NeuralVoiceSpec("fa-IR-DilaraNeural", "Dilara", ReaderVoiceGender.FEMALE, "fa-IR"),
        ),
        "cs" to listOf(
            NeuralVoiceSpec("cs-CZ-AntoninNeural", "Antonin", ReaderVoiceGender.MALE, "cs-CZ"),
            NeuralVoiceSpec("cs-CZ-VlastaNeural", "Vlasta", ReaderVoiceGender.FEMALE, "cs-CZ"),
        ),
        "ro" to listOf(
            NeuralVoiceSpec("ro-RO-EmilNeural", "Emil", ReaderVoiceGender.MALE, "ro-RO"),
            NeuralVoiceSpec("ro-RO-AlinaNeural", "Alina", ReaderVoiceGender.FEMALE, "ro-RO"),
        ),
        "hu" to listOf(
            NeuralVoiceSpec("hu-HU-TamasNeural", "Tamas", ReaderVoiceGender.MALE, "hu-HU"),
            NeuralVoiceSpec("hu-HU-NoemiNeural", "Noemi", ReaderVoiceGender.FEMALE, "hu-HU"),
        ),
        "fi" to listOf(
            NeuralVoiceSpec("fi-FI-HarriNeural", "Harri", ReaderVoiceGender.MALE, "fi-FI"),
            NeuralVoiceSpec("fi-FI-NooraNeural", "Noora", ReaderVoiceGender.FEMALE, "fi-FI"),
        ),
        "da" to listOf(
            NeuralVoiceSpec("da-DK-JeppeNeural", "Jeppe", ReaderVoiceGender.MALE, "da-DK"),
            NeuralVoiceSpec("da-DK-ChristelNeural", "Christel", ReaderVoiceGender.FEMALE, "da-DK"),
        ),
        "nb" to listOf(
            NeuralVoiceSpec("nb-NO-FinnNeural", "Finn", ReaderVoiceGender.MALE, "nb-NO"),
            NeuralVoiceSpec("nb-NO-PernilleNeural", "Pernille", ReaderVoiceGender.FEMALE, "nb-NO"),
        ),
        "ms" to listOf(
            NeuralVoiceSpec("ms-MY-OsmanNeural", "Osman", ReaderVoiceGender.MALE, "ms-MY"),
            NeuralVoiceSpec("ms-MY-YasminNeural", "Yasmin", ReaderVoiceGender.FEMALE, "ms-MY"),
        ),
        "fil" to listOf(
            NeuralVoiceSpec("fil-PH-AngeloNeural", "Angelo", ReaderVoiceGender.MALE, "fil-PH"),
            NeuralVoiceSpec("fil-PH-BlessicaNeural", "Blessica", ReaderVoiceGender.FEMALE, "fil-PH"),
        ),
    )

    private val demoLinesByLang: Map<String, String> = mapOf(
        "en" to "Hello, this is how your article will sound when read aloud.",
        "hi" to "नमस्ते, यह आवाज़ आपके लेख को स्पष्ट और सहज रूप से पढ़ेगी।",
        "es" to "Hola, así sonará este artículo cuando lo escuches en voz alta.",
        "fr" to "Bonjour, voici comment votre article sonnera lors de la lecture à voix haute.",
        "de" to "Hallo, so klingt dieser Artikel, wenn er laut vorgelesen wird.",
        "pt" to "Olá, é assim que o seu artigo vai soar durante a leitura em voz alta.",
        "it" to "Ciao, ecco come suonerà questo articolo durante la lettura ad alta voce.",
        "ru" to "Здравствуйте, так будет звучать эта статья при чтении вслух.",
        "ja" to "こんにちは。記事を読み上げると、このような声で再生されます。",
        "ko" to "안녕하세요. 기사를 소리 내어 읽을 때 이런 목소리로 들립니다.",
        "zh" to "你好，这是朗读文章时您听到的声音效果。",
        "ar" to "مرحباً، هكذا سيبدو صوت قراءة هذا المقال بصوت عالٍ.",
        "tr" to "Merhaba, makaleniz sesli okunduğunda bu şekilde duyulacaktır.",
        "id" to "Halo, beginilah suara artikel Anda saat dibacakan dengan keras.",
        "vi" to "Xin chào, đây là giọng đọc bài viết dành cho bạn.",
        "th" to "สวัสดี นี่คือเสียงอ่านบทความของคุณ",
        "nl" to "Hallo, zo klinkt dit artikel wanneer het wordt voorgelezen.",
        "pl" to "Cześć, tak zabrzmi ten artykuł podczas czytania na głos.",
        "uk" to "Привіт, ось так звучатиме ця стаття під час читання вголос.",
        "sv" to "Hej, så här låter den här artikeln när den läses upp.",
        "bn" to "হ্যালো, আপনার নিবন্ধটি পড়ার সময় এই কণ্ঠে শোনা যাবে।",
        "ur" to "سلام، آپ کا مضمون پڑھتے وقت یہ آواز سنائی دے گی۔",
        "ta" to "வணக்கம், உங்கள் கட்டுரை இவ்வாறு வாசிக்கப்படும்.",
        "te" to "నమస్కారం, మీ వ్యాసం ఈ విధంగా వినిపిస్తుంది.",
        "mr" to "नमस्कार, तुमचा लेख वाचताना हा आवाज असा ऐकू येईल.",
        "gu" to "નમસ્તે, તમારો લેખ વાંચતી વખતે આ અવાજ આ રીતે સંભળાશે.",
    )

    fun demoLineForLanguage(langTag: String): String {
        val base = normalizeBaseLang(langTag)
        return demoLinesByLang[base] ?: demoLinesByLang["en"]!!
    }

    /**
     * Returns native neural voices for [langTag] followed by adaptive multilingual neural voices
     * (deduplicated), using short human names only.
     */
    fun voicesForLanguage(langTag: String): List<ReaderVoiceOption> {
        val base = normalizeBaseLang(langTag)
        val nativeSpecs = voiceCatalog[base].orEmpty()
        val combined = LinkedHashMap<String, NeuralVoiceSpec>()
        for (spec in nativeSpecs) {
            combined[spec.shortName] = spec
        }
        // Include adaptive multilingual voices so users can also keep a favorite narrator across languages
        for (multi in multilingualVoices) {
            if (!combined.containsKey(multi.shortName)) {
                combined[multi.shortName] = multi
            }
        }
        return combined.values.map { spec ->
            ReaderVoiceOption(
                name = "neural:${spec.shortName}",
                label = spec.displayName,
                gender = spec.gender,
                langTag = spec.langTag,
                quality = 500,
                isNeural = true
            )
        }
    }

    /**
     * Resolves the best Neural voice for [langTag] while STRICTLY preserving the user's selected gender:
     * - If the user picked an explicit voice matching this language OR a multilingual voice of the
     *   same gender, keep that voice.
     * - Otherwise switch automatically to the native neural voice for [langTag] that matches the
     *   user's selected gender (Male stays Male, Female stays Female).
     * - Default (AUTO / Male) picks the clear deep male narrator (Brian in English, native male elsewhere).
     */
    fun resolveVoiceSpec(langTag: String, prefs: ReaderPrefsState): NeuralVoiceSpec {
        val base = normalizeBaseLang(langTag)
        val nativePool = voiceCatalog[base] ?: voiceCatalog["en"]!!
        val targetGender = when (prefs.voiceGender) {
            ReaderVoiceGender.FEMALE -> ReaderVoiceGender.FEMALE
            ReaderVoiceGender.MALE, ReaderVoiceGender.AUTO -> ReaderVoiceGender.MALE
        }

        if (prefs.voiceName.startsWith("neural:")) {
            val wanted = prefs.voiceName.removePrefix("neural:")
            // 1. Exact match in the native language pool
            nativePool.firstOrNull { it.shortName == wanted }?.let { return it }
            // 2. If user explicitly picked a multilingual voice, honor it across languages
            multilingualVoices.firstOrNull { it.shortName == wanted }?.let { return it }
            // 3. Otherwise, infer gender from previously selected voice and pick native voice of SAME gender
            val prevSpec = findSpecByShortName(wanted)
            val lockedGender = prevSpec?.gender ?: targetGender
            return nativePool.firstOrNull { it.gender == lockedGender }
                ?: multilingualVoices.firstOrNull { it.gender == lockedGender }
                ?: nativePool.first()
        }

        return nativePool.firstOrNull { it.gender == targetGender }
            ?: multilingualVoices.firstOrNull { it.gender == targetGender }
            ?: nativePool.first()
    }

    fun findSpecByShortName(shortName: String): NeuralVoiceSpec? {
        for (list in voiceCatalog.values) {
            list.firstOrNull { it.shortName == shortName }?.let { return it }
        }
        return multilingualVoices.firstOrNull { it.shortName == shortName }
    }

    fun stop() {
        generation.incrementAndGet()
        activeJob?.cancel()
        activeJob = null
        stopWordTracker()
        releasePlayer()
        clearPrefetchCache()
    }

    fun stopDemo() {
        demoGeneration.incrementAndGet()
        demoJob?.cancel()
        demoJob = null
        releaseDemoPlayer()
    }

    /**
     * Plays a short localized demo sentence using the selected online neural voice so the user
     * immediately hears what the voice sounds like on tap.
     */
    fun speakDemoLine(
        context: Context,
        voiceShortName: String,
        articleLangTag: String,
        speed: Float,
        onStarted: () -> Unit = {},
        onFinished: () -> Unit = {},
        onFallbackNeeded: () -> Unit = {}
    ) {
        val dGen = demoGeneration.incrementAndGet()
        demoJob?.cancel()
        releaseDemoPlayer()

        val spec = findSpecByShortName(voiceShortName)
        val effectiveLangTag = if (spec != null && !spec.isMultilingual) {
            spec.langTag
        } else {
            articleLangTag.ifBlank { spec?.langTag ?: "en-US" }
        }
        val ssmlLang = spec?.langTag ?: effectiveLangTag.ifBlank { "en-US" }
        val demoText = demoLineForLanguage(articleLangTag.ifBlank { effectiveLangTag })
        val ratePct = ((speed - 1.0f) * 100f).roundToInt().coerceIn(-40, 100)
        val appCtx = context.applicationContext

        demoJob = scope.launch {
            val chunk = synthesizeOnce(appCtx, demoText, voiceShortName, ssmlLang, ratePct)
            if (demoGeneration.get() != dGen) {
                runCatching { chunk?.audioFile?.delete() }
                return@launch
            }
            if (chunk == null || !chunk.audioFile.exists() || chunk.audioFile.length() < 64L) {
                mainHandler.post {
                    if (demoGeneration.get() == dGen) onFallbackNeeded()
                }
                return@launch
            }

            mainHandler.post {
                if (demoGeneration.get() != dGen) {
                    runCatching { chunk.audioFile.delete() }
                    return@post
                }
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
                            runCatching { chunk.audioFile.delete() }
                            releaseDemoPlayer()
                            if (demoGeneration.get() == dGen) onFinished()
                        }
                        setOnErrorListener { _, _, _ ->
                            runCatching { chunk.audioFile.delete() }
                            releaseDemoPlayer()
                            if (demoGeneration.get() == dGen) onFallbackNeeded()
                            true
                        }
                        prepare()
                        start()
                    }
                    demoPlayer = mp
                    onStarted()
                } catch (e: Exception) {
                    UHLog.w("reader_tts", "Demo playback failed: ${e.message}")
                    runCatching { chunk.audioFile.delete() }
                    releaseDemoPlayer()
                    if (demoGeneration.get() == dGen) onFallbackNeeded()
                }
            }
        }
    }

    /**
     * Synthesizes and plays [sentence] while concurrently pre-buffering BOTH [nextSentence] and
     * [nextNextSentence] so sentence handoffs are completely gapless.
     */
    fun speakSentence(
        context: Context,
        sentence: String,
        nextSentence: String?,
        nextNextSentence: String? = null,
        langTag: String,
        prefs: ReaderPrefsState,
        onWordProgress: (charOffset: Int, charLength: Int) -> Unit,
        onCompleted: () -> Unit,
        onFallbackNeeded: () -> Unit
    ) {
        stopDemo()
        val gen = generation.incrementAndGet()
        activeJob?.cancel()
        stopWordTracker()
        releasePlayer()

        val spec = resolveVoiceSpec(langTag, prefs)
        val ratePct = ((prefs.ttsSpeed - 1.0f) * 100f).roundToInt().coerceIn(-40, 100)
        val appCtx = context.applicationContext

        activeJob = scope.launch {
            val chunk = fetchOrSynthesize(appCtx, sentence, spec.shortName, spec.langTag, ratePct)
            if (generation.get() != gen) return@launch

            if (chunk == null || !chunk.audioFile.exists() || chunk.audioFile.length() < 64L) {
                mainHandler.post {
                    if (generation.get() == gen) onFallbackNeeded()
                }
                return@launch
            }

            // Gapless 2-sentence concurrent pre-buffering while current sentence plays
            if (!nextSentence.isNullOrBlank()) {
                prefetch(appCtx, nextSentence, spec.shortName, spec.langTag, ratePct)
            }
            if (!nextNextSentence.isNullOrBlank()) {
                prefetch(appCtx, nextNextSentence, spec.shortName, spec.langTag, ratePct)
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

    private fun prefetch(ctx: Context, text: String, voiceShortName: String, ssmlLang: String, ratePct: Int) {
        val key = cacheKey(text, voiceShortName, ratePct)
        if (prefetchCache.containsKey(key)) return
        val deferred = CompletableDeferred<SynthesizedChunk?>()
        if (prefetchCache.putIfAbsent(key, deferred) != null) return
        if (prefetchCache.size > 6) {
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
            val res = synthesizeOnce(ctx, text, voiceShortName, ssmlLang, ratePct)
            deferred.complete(res)
        }
    }

    private suspend fun fetchOrSynthesize(
        ctx: Context,
        text: String,
        voiceShortName: String,
        ssmlLang: String,
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
        return synthesizeOnce(ctx, text, voiceShortName, ssmlLang, ratePct)
    }

    private suspend fun synthesizeOnce(
        ctx: Context,
        rawText: String,
        voiceShortName: String,
        ssmlLang: String,
        ratePct: Int
    ): SynthesizedChunk? {
        val text = rawText.trim()
        if (text.isEmpty()) return null

        val resultDeferred = CompletableDeferred<SynthesizedChunk?>()
        val connId = UUID.randomUUID().toString().replace("-", "")
        val reqId = UUID.randomUUID().toString().replace("-", "")
        val gec = generateSecMsGec()
        val muid = generateMuid()
        val wsUrl = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
            "&Sec-MS-GEC=$gec" +
            "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION" +
            "&ConnectionId=$connId"

        val request = Request.Builder()
            .url(wsUrl)
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Cookie", "muid=$muid;")
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CHROMIUM_FULL_VERSION Safari/537.36 Edg/$CHROMIUM_FULL_VERSION"
            )
            .build()

        val audioBuffer = ByteArrayOutputStream(24 * 1024)
        val words = mutableListOf<WordMark>()
        val rateSign = if (ratePct >= 0) "+${ratePct}%" else "${ratePct}%"
        val escapedText = escapeXml(text)
        val cleanSsmlLang = ssmlLang.ifBlank { "en-US" }
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$cleanSsmlLang'>" +
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
                    UHLog.w("reader_tts", "Neural TTS WS failed (${response?.code}): ${t.message}")
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
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.release() }
        }
    }

    private fun releaseDemoPlayer() {
        val mp = demoPlayer
        demoPlayer = null
        if (mp != null) {
            runCatching { if (mp.isPlaying) mp.stop() }
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

    private fun generateMuid(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        val sb = StringBuilder(32)
        for (b in bytes) {
            sb.append(String.format(Locale.US, "%02X", b))
        }
        return sb.toString()
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
