package app.harvester.companion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import app.harvester.companion.browser.translate.OnlineTranslateClient
import app.harvester.companion.browser.translate.UhTranslationManager
import app.harvester.companion.components.Components
import app.harvester.companion.core.network.UhHttpClient
import app.harvester.companion.media.UhMediaController
import app.harvester.companion.privacy.RequestLogAction
import app.harvester.companion.privacy.RequestLogEntry
import app.harvester.companion.privacy.UhPrivacyController
import app.harvester.companion.privacy.UhShieldEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object GeckoWebExtensionBridge {

    private val installed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var componentsRef: Components? = null
    @Volatile
    private var activeBridgePort: WebExtension.Port? = null
    @Volatile
    private var headerInjectorPort: WebExtension.Port? = null
    @Volatile
    private var chunkSyncJob: Job? = null
    private val chunkSyncGeneration = AtomicLong(0L)
    private val pendingRuleSyncNeeded = AtomicBoolean(false)

    private val _devToolsVisible = MutableStateFlow(false)
    val devToolsVisible: StateFlow<Boolean> = _devToolsVisible.asStateFlow()

    fun init(ctx: Context, runtime: GeckoRuntime, components: Components) {
        if (!installed.compareAndSet(false, true)) return
        appContext = ctx.applicationContext
        componentsRef = components
        app.harvester.companion.browser.reader.ReaderModeManager.init(ctx)

        mainHandler.post {
            installBridgeExtension(runtime)
            installHeaderInjectorExtension(runtime)
        }
    }

    private fun installBridgeExtension(runtime: GeckoRuntime) {
        runtime.webExtensionController
            .ensureBuiltIn("resource://android/assets/web_extensions/uh_bridge/", "uh-bridge@harvester.app")
            .accept({ ext ->
                if (ext != null) {
                    mainHandler.post {
                        ext.setMessageDelegate(bridgeMessageDelegate, "uhApp")
                        runtime.webExtensionController.setAllowedInPrivateBrowsing(ext, true)
                    }
                    UHLog.d("ext", "Installed built-in uh-bridge extension")
                }
            }, { err ->
                UHLog.w("ext", "Failed ensuring uh-bridge extension: ${err?.message}")
            })
    }

    private fun installHeaderInjectorExtension(runtime: GeckoRuntime) {
        runtime.webExtensionController
            .ensureBuiltIn("resource://android/assets/web_extensions/header_injector/", "header-injector@harvester.app")
            .accept({ ext ->
                if (ext != null) {
                    mainHandler.post {
                        ext.setMessageDelegate(headerMessageDelegate, "uhApp")
                        runtime.webExtensionController.setAllowedInPrivateBrowsing(ext, true)
                    }
                    UHLog.d("ext", "Installed built-in header-injector extension")
                }
            }, { err ->
                UHLog.w("ext", "Failed ensuring header-injector extension: ${err?.message}")
            })
    }

    fun runMenuCommand(commandId: String) {
        val port = activeBridgePort ?: return
        try {
            port.postMessage(JSONObject().apply {
                put("action", "RUN_MENU_COMMAND")
                put("commandId", commandId)
            })
        } catch (e: Exception) {
            UHLog.w("ext", "Failed sending RUN_MENU_COMMAND: ${e.message}")
        }
    }

    fun reloadScriptsOnActiveTab() {
        val port = activeBridgePort ?: return
        try {
            port.postMessage(JSONObject().apply {
                put("action", "RELOAD_SCRIPTS")
            })
        } catch (_: Exception) {}
    }

    fun toggleDevTools(darkTheme: Boolean = true) {
        val port = activeBridgePort ?: return
        try {
            port.postMessage(JSONObject().apply {
                put("action", "TOGGLE_DEVTOOLS")
                put("darkTheme", darkTheme)
            })
        } catch (e: Exception) {
            UHLog.w("ext", "Failed sending TOGGLE_DEVTOOLS: ${e.message}")
        }
    }

    fun applyGlobalZoom(percent: Int) {
        val port = activeBridgePort ?: return
        try {
            port.postMessage(JSONObject().apply {
                put("action", "APPLY_GLOBAL_ZOOM")
                put("percent", percent.coerceIn(50, 300))
            })
        } catch (e: Exception) {
            UHLog.w("ext", "Failed sending APPLY_GLOBAL_ZOOM: ${e.message}")
        }
    }

    fun triggerOnlinePageTranslation(fromLang: String, toLang: String): Boolean {
        val port = activeBridgePort ?: return false
        return try {
            port.postMessage(JSONObject().apply {
                put("action", "ONLINE_TRANSLATE_PAGE")
                put("fromLang", fromLang.ifBlank { "auto" })
                put("toLang", toLang.ifBlank { "en" })
            })
            true
        } catch (e: Exception) {
            UHLog.w("translate_online", "Failed sending ONLINE_TRANSLATE_PAGE: ${e.message}")
            false
        }
    }

    fun restoreOnlinePageTranslation() {
        val port = activeBridgePort ?: return
        try {
            port.postMessage(JSONObject().apply {
                put("action", "RESTORE_ONLINE_TRANSLATION")
            })
        } catch (e: Exception) {
            UHLog.w("translate_online", "Failed sending RESTORE_ONLINE_TRANSLATION: ${e.message}")
        }
    }

    fun pushPrivacyRuntimeConfig(config: JSONObject) {
        val port = activeBridgePort ?: return
        mainHandler.post {
            try {
                port.postMessage(JSONObject().apply {
                    put("action", "APPLY_PRIVACY_CONFIG")
                    put("config", config)
                })
            } catch (e: Exception) {
                UHLog.w("shield", "Failed pushing APPLY_PRIVACY_CONFIG: ${e.message}")
            }
        }
    }

    fun startElementPicker(): Boolean {
        val port = activeBridgePort ?: return false
        mainHandler.post {
            try {
                port.postMessage(JSONObject().apply {
                    put("action", "START_ELEMENT_PICKER")
                })
            } catch (e: Exception) {
                UHLog.w("shield", "Failed starting visual element picker: ${e.message}")
            }
        }
        return true
    }

    fun sendReaderCommand(action: String, prefs: JSONObject? = null) {
        val port = activeBridgePort ?: return
        mainHandler.post {
            try {
                port.postMessage(JSONObject().apply {
                    put("action", action)
                    if (prefs != null) put("prefs", prefs)
                })
            } catch (e: Exception) {
                UHLog.w("reader", "Failed sending $action: ${e.message}")
            }
        }
    }

    fun sendReaderTtsHighlight(index: Int, playing: Boolean = false) {
        val port = activeBridgePort ?: return
        mainHandler.post {
            try {
                port.postMessage(JSONObject().apply {
                    put("action", "READER_TTS_HIGHLIGHT")
                    put("index", index)
                    put("playing", playing)
                })
            } catch (_: Exception) {}
        }
    }

    fun sendReaderTtsWord(index: Int, charOffset: Int, charLength: Int) {
        val port = activeBridgePort ?: return
        mainHandler.post {
            try {
                port.postMessage(JSONObject().apply {
                    put("action", "READER_TTS_WORD")
                    put("index", index)
                    put("charOffset", charOffset)
                    put("charLength", charLength)
                })
            } catch (_: Exception) {}
        }
    }

    fun pushCnameBlockedHost(subdomain: String, target: String, isTracker: Boolean) {
        val port = activeBridgePort ?: return
        mainHandler.post {
            try {
                port.postMessage(JSONObject().apply {
                    put("action", "ADD_CNAME_BLOCK")
                    put("subdomain", subdomain)
                    put("target", target)
                    put("isTracker", isTracker)
                })
            } catch (e: Exception) {
                UHLog.w("shield", "Failed pushing ADD_CNAME_BLOCK: ${e.message}")
            }
        }
    }

    /**
     * Streams BEGIN -> CHUNK* -> COMMIT messages over the persistent GeckoView port
     * in paced batches so 400k-500k+ rules sync reliably without stuttering the UI thread
     * or exceeding Android Binder IPC limits. Cancels any superseded in-flight push.
     */
    fun pushChunkedPrivacyConfig(messages: List<JSONObject>) {
        if (messages.isEmpty()) return
        if (activeBridgePort == null) {
            pendingRuleSyncNeeded.set(true)
            return
        }
        val gen = chunkSyncGeneration.incrementAndGet()
        chunkSyncJob?.cancel()
        chunkSyncJob = scope.launch {
            for (msg in messages) {
                if (chunkSyncGeneration.get() != gen) return@launch
                val port = activeBridgePort
                if (port == null) {
                    pendingRuleSyncNeeded.set(true)
                    return@launch
                }
                mainHandler.post {
                    if (chunkSyncGeneration.get() != gen) return@post
                    try {
                        port.postMessage(msg)
                    } catch (e: Exception) {
                        UHLog.w("shield", "Failed pushing chunk msg: ${e.message}")
                    }
                }
                delay(4L)
            }
            pendingRuleSyncNeeded.set(false)
        }
    }

    fun setHeaderCaptureEnabled(enabled: Boolean) {
        val port = headerInjectorPort ?: return
        mainHandler.post {
            try {
                port.postMessage(JSONObject().apply {
                    put("action", "SET_HEADER_CAPTURE")
                    put("enabled", enabled)
                })
            } catch (e: Exception) {
                UHLog.w("ext", "Failed toggling header capture: ${e.message}")
            }
        }
    }

    fun setPipVideoIsolation(enabled: Boolean) {
        val port = activeBridgePort ?: return
        mainHandler.post {
            try {
                port.postMessage(JSONObject().apply {
                    put("action", "SET_PIP_ISOLATION")
                    put("enabled", enabled)
                })
            } catch (e: Exception) {
                UHLog.w("media", "Failed toggling PiP video isolation: ${e.message}")
            }
        }
    }

    fun sendMediaControlAction(command: String, deltaSec: Double = 0.0, positionSec: Double = 0.0) {
        val port = activeBridgePort ?: return
        mainHandler.post {
            try {
                port.postMessage(JSONObject().apply {
                    put("action", "MEDIA_CONTROL_ACTION")
                    put("command", command)
                    if (deltaSec != 0.0) put("deltaSec", deltaSec)
                    if (positionSec >= 0.0 && command == "SEEK_TO") put("positionSec", positionSec)
                })
            } catch (e: Exception) {
                UHLog.w("media", "Failed sending MEDIA_CONTROL_ACTION ($command): ${e.message}")
            }
        }
    }

    private val headerMessageDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            headerInjectorPort = port
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, p: WebExtension.Port) {}
                override fun onDisconnect(p: WebExtension.Port) {
                    if (headerInjectorPort === p) headerInjectorPort = null
                }
            })
            setHeaderCaptureEnabled(SessionRecorder.isActive())
        }

        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: WebExtension.MessageSender
        ): GeckoResult<Any>? {
            val json = message as? JSONObject ?: return null
            if (json.optString("type") == "HEADER_EVENT") {
                val headersObj = json.optJSONObject("headers")
                if (headersObj != null) {
                    SessionRecorder.record("header") {
                        put("url", json.optString("url"))
                        put("method", json.optString("method"))
                        put("reqType", json.optString("reqType"))
                        put("headers", headersObj)
                    }
                }
            }
            return GeckoResult.fromValue(null)
        }
    }

    private val bridgeMessageDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            activeBridgePort = port
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, p: WebExtension.Port) {}
                override fun onDisconnect(p: WebExtension.Port) {
                    if (activeBridgePort === p) activeBridgePort = null
                }
            })
            val ctx = appContext
            if (ctx != null) {
                scope.launch {
                    try {
                        val chunks = UhShieldEngine.buildChunkedRuntimeMessages(ctx)
                        pushChunkedPrivacyConfig(chunks)
                    } catch (e: Exception) {
                        UHLog.w("shield", "Initial chunked sync failed: ${e.message}")
                    }
                }
            }
        }

        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: WebExtension.MessageSender
        ): GeckoResult<Any>? {
            val ctx = appContext ?: return GeckoResult.fromValue(null)
            val json = message as? JSONObject ?: return GeckoResult.fromValue(null)
            val type = json.optString("type")

            return when (type) {
                "GET_PRIVACY_RUNTIME" -> {
                    val result = GeckoResult<Any>()
                    scope.launch {
                        val payload = UhShieldEngine.buildExtensionRuntimePayload(ctx)
                        mainHandler.post { result.complete(payload) }
                        // Follow up with full chunked rule sync over activeBridgePort
                        try {
                            val chunks = UhShieldEngine.buildChunkedRuntimeMessages(ctx)
                            pushChunkedPrivacyConfig(chunks)
                        } catch (_: Exception) {}
                    }
                    result
                }

                "GET_PAGE_PROTECT" -> {
                    val result = GeckoResult<Any>()
                    scope.launch {
                        val url = json.optString("url")
                        val payload = UhShieldEngine.buildPageProtectPayload(ctx, url)
                        mainHandler.post { result.complete(payload) }
                    }
                    result
                }

                "SHIELD_BLOCKED_BATCH" -> {
                    val entries = json.optJSONArray("entries")
                    if (entries != null) {
                        for (i in 0 until entries.length()) {
                            val item = entries.optJSONObject(i) ?: continue
                            val host = item.optString("host")
                            val ads = item.optInt("ads", 0)
                            val trackers = item.optInt("trackers", 0)
                            if (ads > 0) UhShieldEngine.recordBlockedEvent(host, isTracker = false, count = ads)
                            if (trackers > 0) UhShieldEngine.recordBlockedEvent(host, isTracker = true, count = trackers)
                        }
                    }
                    GeckoResult.fromValue(true)
                }

                "JS_BLOCKED_REPORT" -> {
                    val host = json.optString("host")
                    val total = json.optInt("totalBlocked", 0)
                    val domainsObj = json.optJSONObject("domains")
                    val domainCounts = LinkedHashMap<String, Int>()
                    if (domainsObj != null) {
                        for (key in domainsObj.keys()) {
                            val count = domainsObj.optInt(key, 0)
                            if (key.isNotBlank() && count > 0) {
                                domainCounts[key] = count
                            }
                        }
                    }
                    UhShieldEngine.reportJsBlocked(host, total, domainCounts)
                    GeckoResult.fromValue(true)
                }

                "PAGE_NAVIGATED" -> {
                    val host = json.optString("host")
                    if (host.isNotBlank()) {
                        UhShieldEngine.clearJsBlockedForHost(host)
                    }
                    GeckoResult.fromValue(true)
                }

                "AD_REDIRECT_BLOCKED" -> {
                    val sourceHost = json.optString("sourceHost")
                    val targetUrl = json.optString("targetUrl")
                    val isPopupTab = json.optBoolean("isPopupTab", false)
                    if (targetUrl.isNotBlank()) {
                        UhShieldEngine.reportBlockedRedirect(sourceHost, targetUrl, isPopupTab)
                    }
                    GeckoResult.fromValue(true)
                }

                "REQUEST_LOG_BATCH" -> {
                    val arr = json.optJSONArray("entries")
                    if (arr != null && arr.length() > 0) {
                        val batch = ArrayList<RequestLogEntry>(arr.length())
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val pageHost = o.optString("pageHost")
                            val reqHost = o.optString("reqHost")
                            if (pageHost.isBlank() || reqHost.isBlank()) continue
                            batch.add(
                                RequestLogEntry(
                                    pageHost = pageHost,
                                    reqHost = reqHost,
                                    pathHint = o.optString("pathHint"),
                                    resourceType = o.optString("type"),
                                    action = RequestLogAction.fromWire(o.optString("action")),
                                    ruleSource = o.optString("ruleSource"),
                                    timestampMs = o.optLong("ts", System.currentTimeMillis()),
                                )
                            )
                        }
                        if (batch.isNotEmpty()) {
                            UhShieldEngine.recordRequestLogBatch(batch)
                        }
                    }
                    GeckoResult.fromValue(true)
                }

                "ADD_CUSTOM_RULE" -> {
                    val rule = json.optString("rule")
                    val added = if (rule.isNotBlank()) UhShieldEngine.appendCustomRule(rule) else false
                    if (added) {
                        mainHandler.post {
                            Toast.makeText(ctx, "Element rule saved to UH Shield", Toast.LENGTH_SHORT).show()
                        }
                    }
                    GeckoResult.fromValue(added)
                }

                "RESOLVE_CNAME" -> {
                    val host = json.optString("host")
                    if (host.isNotBlank()) {
                        UhShieldEngine.resolveCnameAsync(host)
                    }
                    GeckoResult.fromValue(true)
                }

                "BOUNCE_TRACKER_EVICT" -> {
                    val host = json.optString("host")
                    if (host.isNotBlank()) {
                        UhPrivacyController.evictBounceTrackerHost(host)
                    }
                    GeckoResult.fromValue(true)
                }

                "MEDIA_STATE_UPDATE" -> {
                    UhMediaController.onWebMediaState(json)
                    GeckoResult.fromValue(true)
                }

                "GET_SCRIPTS" -> {
                    val result = GeckoResult<Any>()
                    scope.launch {
                        val url = json.optString("url")
                        val isTop = json.optBoolean("isTop", true)
                        val payload = UserscriptManager.buildGeckoScriptPayload(ctx, url, isTop)
                        if (isTop) {
                            val zoomPct = SettingsPrefs.getInt(ctx, SettingsPrefs.K_TEXT_SCALE, 100).coerceIn(50, 300)
                            payload.put("globalZoomPercent", zoomPct)
                        }
                        mainHandler.post { result.complete(payload) }
                    }
                    result
                }

                "DEVTOOLS_STATE" -> {
                    _devToolsVisible.value = json.optBoolean("visible", false)
                    GeckoResult.fromValue(true)
                }

                "ONLINE_TRANSLATE_BATCH" -> {
                    val result = GeckoResult<Any>()
                    scope.launch {
                        try {
                            val fromLang = json.optString("fromLang", "auto")
                            val toLang = json.optString("toLang", "en")
                            val textsArr = json.optJSONArray("texts")
                            val inputs = ArrayList<String>(textsArr?.length() ?: 0)
                            if (textsArr != null) {
                                for (i in 0 until textsArr.length()) {
                                    inputs.add(textsArr.optString(i, ""))
                                }
                            }
                            val batch = OnlineTranslateClient.translateBatch(inputs, fromLang, toLang)
                            val outArr = JSONArray()
                            batch.translations.forEach { outArr.put(it) }
                            val resp = JSONObject().apply {
                                put("ok", batch.ok)
                                put("translations", outArr)
                                if (!batch.detectedSourceLang.isNullOrBlank()) {
                                    put("detectedLang", batch.detectedSourceLang)
                                }
                                if (!batch.errorMessage.isNullOrBlank()) {
                                    put("error", batch.errorMessage)
                                }
                            }
                            mainHandler.post { result.complete(resp) }
                        } catch (e: Exception) {
                            val errResp = JSONObject().apply {
                                put("ok", false)
                                put("error", e.message ?: "Translation failed")
                            }
                            mainHandler.post { result.complete(errResp) }
                        }
                    }
                    result
                }

                "ONLINE_TRANSLATE_PROGRESS" -> {
                    val progress = json.optDouble("progress", 0.0).toFloat()
                    mainHandler.post {
                        UhTranslationManager.onOnlinePageTranslationProgress(progress)
                    }
                    GeckoResult.fromValue(true)
                }

                "ONLINE_TRANSLATE_DONE" -> {
                    val ok = json.optBoolean("ok", false)
                    val restored = json.optBoolean("restored", false)
                    val translatedCount = json.optInt("translatedCount", 0)
                    val detectedLang = json.optString("detectedLang", "").ifBlank { null }
                    val toLang = json.optString("toLang", "").ifBlank { null }
                    val error = json.optString("error", "").ifBlank { null }
                    mainHandler.post {
                        UhTranslationManager.onOnlinePageTranslationDone(
                            ok = ok,
                            restored = restored,
                            translatedCount = translatedCount,
                            detectedLang = detectedLang,
                            toLang = toLang,
                            error = error
                        )
                    }
                    GeckoResult.fromValue(true)
                }

                "GM_setValue" -> {
                    val script = json.optString("script")
                    val key = json.optString("key")
                    val value = json.optString("value")
                    if (script.isNotBlank() && key.isNotBlank()) {
                        ctx.getSharedPreferences("uh_gm", Context.MODE_PRIVATE)
                            .edit().putString("$script::$key", value).apply()
                    }
                    GeckoResult.fromValue(true)
                }

                "GM_deleteValue" -> {
                    val script = json.optString("script")
                    val key = json.optString("key")
                    if (script.isNotBlank() && key.isNotBlank()) {
                        ctx.getSharedPreferences("uh_gm", Context.MODE_PRIVATE)
                            .edit().remove("$script::$key").apply()
                    }
                    GeckoResult.fromValue(true)
                }

                "GM_xmlhttpRequest" -> {
                    val result = GeckoResult<Any>()
                    scope.launch {
                        val xhrResult = executeGmXhr(json)
                        mainHandler.post { result.complete(xhrResult) }
                    }
                    result
                }

                "GM_download" -> {
                    val url = json.optString("url")
                    val name = json.optString("name", "download_${System.currentTimeMillis()}.bin")
                    if (url.isNotBlank()) {
                        DownloadHelper.saveFromUrlWithCookies(ctx, url, name, null, null)
                        mainHandler.post { Toast.makeText(ctx, "Download started", Toast.LENGTH_SHORT).show() }
                    }
                    GeckoResult.fromValue(true)
                }

                "SAVE_BLOB" -> {
                    val filename = json.optString("filename", "download.bin")
                    val mime = json.optString("mime", "application/octet-stream")
                    val b64 = json.optString("base64")
                    scope.launch {
                        saveBase64Blob(ctx, filename, mime, b64)
                    }
                    GeckoResult.fromValue(true)
                }

                "GM_openInTab" -> {
                    val url = json.optString("url")
                    if (url.isNotBlank()) {
                        mainHandler.post {
                            componentsRef?.useCases?.tabsUseCases?.addTab(url, selectTab = true)
                        }
                    }
                    GeckoResult.fromValue(true)
                }

                "GM_setClipboard" -> {
                    val text = json.optString("text")
                    mainHandler.post {
                        try {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("userscript", text))
                        } catch (_: Exception) {}
                    }
                    GeckoResult.fromValue(true)
                }

                "GM_notification" -> {
                    showNotification(ctx, json.optString("title"), json.optString("text"))
                    GeckoResult.fromValue(true)
                }

                "GM_registerMenuCommand" -> {
                    val script = json.optString("script")
                    val name = json.optString("name")
                    val id = json.optString("id")
                    if (script.isNotBlank() && id.isNotBlank()) {
                        MenuCommandStore.register(script, id, name, "")
                    }
                    GeckoResult.fromValue(true)
                }

                "GM_unregisterMenuCommand" -> {
                    val script = json.optString("script")
                    val id = json.optString("id")
                    if (script.isNotBlank() && id.isNotBlank()) {
                        MenuCommandStore.unregister(script, id)
                    }
                    GeckoResult.fromValue(true)
                }

                "GM_log" -> {
                    UHLog.d("userscript", json.optString("message"))
                    GeckoResult.fromValue(true)
                }

                "REPORT" -> {
                    UHLog.d("capture", "${json.optString("kind")} ${json.optString("url")} — ${json.optString("detail")}")
                    GeckoResult.fromValue(true)
                }

                "CAPTURE_EVENT" -> {
                    val cat = json.optString("category")
                    val data = json.optJSONObject("data") ?: JSONObject()
                    if (cat.isNotBlank()) {
                        SessionRecorder.record(cat, data)
                    }
                    GeckoResult.fromValue(true)
                }

                "READER_ELIGIBILITY" -> {
                    app.harvester.companion.browser.reader.ReaderModeManager.onEligibilityFromPage(
                        url = json.optString("url"),
                        host = json.optString("host"),
                        eligible = json.optBoolean("eligible", false),
                        active = json.optBoolean("active", false)
                    )
                    GeckoResult.fromValue(true)
                }

                "READER_ENTERED" -> {
                    app.harvester.companion.browser.reader.ReaderModeManager.onReaderEnteredFromPage(
                        url = json.optString("url"),
                        host = json.optString("host"),
                        title = json.optString("title"),
                        readMins = json.optInt("readMins", 1),
                        wordCount = json.optInt("wordCount", 0),
                        lang = json.optString("lang", "en"),
                        sentencesArray = json.optJSONArray("sentences") ?: json.optJSONArray("paragraphs")
                    )
                    GeckoResult.fromValue(true)
                }

                "READER_CONTENT_REFRESHED" -> {
                    app.harvester.companion.browser.reader.ReaderModeManager.onReaderContentRefreshed(
                        url = json.optString("url"),
                        host = json.optString("host"),
                        title = json.optString("title"),
                        readMins = json.optInt("readMins", 1),
                        wordCount = json.optInt("wordCount", 0),
                        lang = json.optString("lang", "en"),
                        sentencesArray = json.optJSONArray("sentences") ?: json.optJSONArray("paragraphs")
                    )
                    GeckoResult.fromValue(true)
                }

                "READER_EXITED" -> {
                    app.harvester.companion.browser.reader.ReaderModeManager.onReaderExitedFromPage()
                    GeckoResult.fromValue(true)
                }

                "READER_OPEN_CONTROLS" -> {
                    app.harvester.companion.browser.reader.ReaderModeManager.openControlsSheet()
                    GeckoResult.fromValue(true)
                }

                "READER_OPEN_TRANSLATE" -> {
                    app.harvester.companion.browser.reader.ReaderModeManager.requestOpenTranslateSheet()
                    GeckoResult.fromValue(true)
                }

                "READER_TOGGLE_FULLSCREEN" -> {
                    app.harvester.companion.browser.reader.ReaderModeManager.toggleFullscreen()
                    GeckoResult.fromValue(true)
                }

                "READER_SET_FULLSCREEN" -> {
                    app.harvester.companion.browser.reader.ReaderModeManager.setFullscreen(
                        json.optBoolean("enabled", false)
                    )
                    GeckoResult.fromValue(true)
                }

                "READER_TOGGLE_PAGE_MODE" -> {
                    app.harvester.companion.browser.reader.ReaderModeManager.togglePageMode()
                    GeckoResult.fromValue(true)
                }

                "READER_TTS_TOGGLE" -> {
                    app.harvester.companion.browser.reader.ReaderModeManager.togglePlayPauseFromPage()
                    GeckoResult.fromValue(true)
                }

                "READER_TTS_FROM_INDEX" -> {
                    app.harvester.companion.browser.reader.ReaderModeManager.playFromSentenceIndex(
                        json.optInt("index", 0)
                    )
                    GeckoResult.fromValue(true)
                }

                "READER_PROGRESS_UPDATE" -> {
                    app.harvester.companion.browser.reader.ReaderModeManager.onReaderProgressUpdate(
                        url = json.optString("url"),
                        scrollRatio = json.optDouble("scrollRatio", 0.0).toFloat(),
                        pageIndex = json.optInt("pageIndex", 0)
                    )
                    GeckoResult.fromValue(true)
                }

                else -> GeckoResult.fromValue(null)
            }
        }
    }

    private fun executeGmXhr(params: JSONObject): JSONObject {
        val url = params.optString("url")
        val method = params.optString("method", "GET").uppercase()
        val headersJson = params.optString("headers")
        val data = params.optString("data")

        val out = JSONObject()
        if (url.isBlank()) {
            out.put("error", "Blank URL")
            return out
        }
        return try {
            val builder = Request.Builder().url(url)
            if (headersJson.isNotBlank()) {
                val hdrs = JSONObject(headersJson)
                for (k in hdrs.keys()) {
                    builder.header(k, hdrs.optString(k))
                }
            }
            if (method in listOf("POST", "PUT", "PATCH", "DELETE")) {
                val reqBody = data.toByteArray(Charsets.UTF_8)
                    .toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaTypeOrNull())
                builder.method(method, reqBody)
            } else {
                builder.method(method, null)
            }
            UhHttpClient.client.newCall(builder.build()).execute().use { resp ->
                val bodyText = resp.body?.string() ?: ""
                val headersSb = StringBuilder()
                for (i in 0 until resp.headers.size) {
                    headersSb.append(resp.headers.name(i)).append(": ").append(resp.headers.value(i)).append("\r\n")
                }
                out.put("status", resp.code)
                out.put("responseText", bodyText)
                out.put("responseHeaders", headersSb.toString())
                out.put("finalUrl", resp.request.url.toString())
            }
            out
        } catch (e: Exception) {
            out.put("error", e.message ?: "Request failed")
            out
        }
    }

    private fun saveBase64Blob(ctx: Context, filename: String, mime: String, b64: String) {
        try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let { ctx.contentResolver.openOutputStream(it)?.use { os -> os.write(bytes) } }
            mainHandler.post { Toast.makeText(ctx, "Saved $filename", Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) {
            UHLog.throwable("blob", "Failed saving blob", e)
        }
    }

    private fun showNotification(ctx: Context, title: String, text: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val chId = "uh_userscript"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(chId) == null) {
                nm.createNotificationChannel(NotificationChannel(chId, "Userscripts", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.Notification.Builder(ctx, chId)
            } else {
                @Suppress("DEPRECATION") android.app.Notification.Builder(ctx)
            }
            builder.setContentTitle(title.ifBlank { UHBrand.NAME })
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
            nm.notify(System.currentTimeMillis().toInt(), builder.build())
        } catch (_: Exception) {}
    }
}
