package app.harvester.companion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class UserscriptBridge(
    private val ctx: Context,
    private val viewProvider: () -> WebView?
) {

    private val prefs = ctx.getSharedPreferences("uh_gm", Context.MODE_PRIVATE)
    private val vclId = AtomicLong(1L)
    private val vcl = ConcurrentHashMap<String, MutableList<String>>()  // name -> list of listener ids
    private val xhrAborts = ConcurrentHashMap<String, HttpURLConnection>()

    @JavascriptInterface
    fun GM_getValue(key: String, defaultValue: String?): String? =
        prefs.getString(key, defaultValue)

    @JavascriptInterface
    fun GM_setValue(key: String, value: String) {
        val old = prefs.getString(key, null)
        prefs.edit().putString(key, value).apply()
        // fire listeners
        val ids = vcl[key] ?: return
        val view = viewProvider() ?: return
        val oldJson = if (old == null) "null" else old
        val newJson = value
        val js = StringBuilder()
        for (id in ids) {
            js.append("try{window.__uh_fire_vcl('").append(id).append("','")
                .append(escapeJs(key)).append("',")
                .append(oldJson).append(",").append(newJson).append(",false)}catch(e){}")
        }
        view.post { view.evaluateJavascript(js.toString(), null) }
    }

    @JavascriptInterface
    fun GM_deleteValue(key: String) {
        val old = prefs.getString(key, null)
        prefs.edit().remove(key).apply()
        val ids = vcl[key] ?: return
        val view = viewProvider() ?: return
        val oldJson = if (old == null) "null" else old
        val js = StringBuilder()
        for (id in ids) {
            js.append("try{window.__uh_fire_vcl('").append(id).append("','")
                .append(escapeJs(key)).append("',")
                .append(oldJson).append(",null,false)}catch(e){}")
        }
        view.post { view.evaluateJavascript(js.toString(), null) }
    }

    @JavascriptInterface
    fun GM_listValues(): String {
        val out = JSONObject()
        for ((k, v) in prefs.all) out.put(k, v?.toString() ?: "")
        return out.toString()
    }

    @JavascriptInterface
    fun GM_addValueChangeListener(name: String): String {
        val id = "vcl_" + vclId.incrementAndGet()
        vcl.getOrPut(name) { mutableListOf() }.add(id)
        return id
    }

    @JavascriptInterface
    fun GM_removeValueChangeListener(id: String) {
        for (list in vcl.values) list.remove(id)
    }

    @JavascriptInterface
    fun GM_xmlhttpRequest(
        id: String, method: String, url: String,
        headersJson: String, body: String, responseType: String, timeoutStr: String
    ) {
        Thread {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = 20000
                conn.readTimeout = 20000
                conn.instanceFollowRedirects = true

                try {
                    val hdrs = if (headersJson.isBlank()) JSONObject() else JSONObject(headersJson)
                    for (k in hdrs.keys()) {
                        conn.setRequestProperty(k, hdrs.optString(k))
                    }
                } catch (_: Exception) {}

                val cookie = try { CookieManager.getInstance().getCookie(url) } catch (_: Exception) { null }
                if (!cookie.isNullOrBlank() && conn.getRequestProperty("Cookie") == null) {
                    conn.setRequestProperty("Cookie", cookie)
                }
                if (conn.getRequestProperty("User-Agent") == null) {
                    conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 10; RMX1801) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/153.0.8010.57 Mobile Safari/537.36")
                }

                if (body.isNotBlank() && (method == "POST" || method == "PUT" || method == "PATCH")) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }

                xhrAborts[id] = conn

                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                val respHeaders = StringBuilder()
                for ((k, values) in conn.headerFields) {
                    if (k == null) continue
                    for (v in values) respHeaders.append(k).append(": ").append(v).append("\r\n")
                }
                val finalUrl = conn.url.toString()

                xhrAborts.remove(id)
                conn.disconnect()

                val view = viewProvider() ?: return@Thread
                val js = "try{window.__uh_xhr_done('" + id + "'," + status + "," +
                    jsString(text) + "," + jsString(respHeaders.toString()) + "," +
                    jsString(finalUrl) + ")}catch(e){}"
                view.post { view.evaluateJavascript(js, null) }
            } catch (e: Exception) {
                xhrAborts.remove(id)
                val view = viewProvider() ?: return@Thread
                val js = "try{window.__uh_xhr_error('" + id + "'," + jsString(e.message ?: "error") + ")}catch(e){}"
                view.post { view.evaluateJavascript(js, null) }
            }
        }.start()
    }

    @JavascriptInterface
    fun GM_xhrAbort(id: String) {
        xhrAborts.remove(id)?.disconnect()
    }

    @JavascriptInterface
    fun GM_download(url: String, name: String) {
        DownloadHelper.saveFromUrl(ctx, url, null)
    }

    @JavascriptInterface
    fun GM_openInTab(url: String, active: Boolean) {
        val intent = android.content.Intent(ctx, MainActivity::class.java).apply {
            data = android.net.Uri.parse(url)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    @JavascriptInterface
    fun GM_setClipboard(text: String) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("userscript", text))
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun GM_notification(title: String, text: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val chId = "uh_userscript"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (nm.getNotificationChannel(chId) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(chId, "Userscripts", NotificationManager.IMPORTANCE_DEFAULT)
                    )
                }
            }
            val n = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                android.app.Notification.Builder(ctx, chId)
            else @Suppress("DEPRECATION") android.app.Notification.Builder(ctx)
            n.setContentTitle(title.ifBlank { "Harvester" })
            n.setContentText(text)
            n.setSmallIcon(android.R.drawable.ic_dialog_info)
            nm.notify(System.currentTimeMillis().toInt(), n.build())
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun GM_registerMenuCommand(name: String, id: String, accessKey: String) {
        MenuCommandStore.register(id, name, accessKey)
    }

    @JavascriptInterface
    fun GM_unregisterMenuCommand(id: String) {
        MenuCommandStore.unregister(id)
    }

    @JavascriptInterface
    fun GM_log(message: String) {
        UHLog.d("userscript", message)
    }

    @JavascriptInterface
    fun captureWasm(base64: String) {
        SessionRecorder.record("wasm_blob") {
            put("base64", base64)
            put("size", base64.length)
        }
    }

    @JavascriptInterface
    fun captureCrypto(algorithm: String, data: String) {
        SessionRecorder.record("crypto") { put("algo", algorithm); put("data", data) }
    }

    @JavascriptInterface
    fun captureAtob(decoded: String) {
        SessionRecorder.record("atob") { put("data", decoded); put("size", decoded.length) }
    }

    @JavascriptInterface
    fun captureTextDecoder(text: String) {
        SessionRecorder.record("textdecoder") { put("data", text); put("size", text.length) }
    }

    @JavascriptInterface
    fun report(url: String, kind: String, detail: String) {
        UHLog.d("capture", "$kind $url — $detail")
    }

    private fun jsString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    private fun escapeJs(s: String): String {
        return s.replace("\\", "\\\\").replace("'", "\\'")
    }
}
