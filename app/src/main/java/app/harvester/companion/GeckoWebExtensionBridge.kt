package app.harvester.companion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import app.harvester.companion.components.Components
import app.harvester.companion.core.network.UhHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import java.util.concurrent.atomic.AtomicBoolean

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

    fun init(ctx: Context, runtime: GeckoRuntime, components: Components) {
        if (!installed.compareAndSet(false, true)) return
        appContext = ctx.applicationContext
        componentsRef = components

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

    fun triggerTranslatePage(targetLang: String = "en") {
        val port = activeBridgePort ?: return
        try {
            port.postMessage(JSONObject().apply {
                put("action", "TRANSLATE_PAGE")
                put("targetLang", targetLang)
            })
        } catch (_: Exception) {}
    }

    private val headerMessageDelegate = object : WebExtension.MessageDelegate {
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
                "GET_SCRIPTS" -> {
                    val result = GeckoResult<Any>()
                    scope.launch {
                        val url = json.optString("url")
                        val isTop = json.optBoolean("isTop", true)
                        val payload = UserscriptManager.buildGeckoScriptPayload(ctx, url, isTop)
                        result.complete(payload)
                    }
                    result
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
                        result.complete(executeGmXhr(json))
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
