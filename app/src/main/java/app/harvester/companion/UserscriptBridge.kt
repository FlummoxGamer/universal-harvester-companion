package app.harvester.companion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class UserscriptBridge(private val ctx: Context) {

    @Volatile private var view: WebView? = null
    fun attach(v: WebView) { view = v }
    private fun v() = view

    private val prefs = ctx.getSharedPreferences("uh_gm", Context.MODE_PRIVATE)
    private val vclId = AtomicLong(1L)
    private val vcl = ConcurrentHashMap<String, MutableList<Listener>>()
    private val xhrAborts = ConcurrentHashMap<String, HttpURLConnection>()

    private data class Listener(val id: String, val script: String, val key: String)

    private fun nsKey(script: String, key: String) = "$script::$key"

@Volatile var overlayActive = false

@JavascriptInterface
fun updateOverlayState(active: Boolean) { overlayActive = active }

@JavascriptInterface
fun saveBlob(filename: String, mime: String, base64Data: String, pageUrl: String) {
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        try {
            DownloadEngine.startDownload(ctx, "", filename, mime, base64Data, null, pageUrl)
            android.widget.Toast.makeText(ctx, "Download started", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            UHLog.throwable("blob", "service failed", e)
        }
    }
}
}
    
init {
    prefs.registerOnSharedPreferenceChangeListener(
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, k ->
            val key = k ?: return@OnSharedPreferenceChangeListener
            val parts = key.split("::", limit = 2)
            if (parts.size != 2) return@OnSharedPreferenceChangeListener
            val script = parts[0]
            val userKey = parts[1]
            val newV = p?.getString(key, null)
            fireVcl(script, userKey, null, newV)
        }
    )
}

    private fun fireVcl(script: String, key: String, oldV: String?, newV: String?) {
        val w = v() ?: return
        val matching = vcl.values.flatten().filter { it.script == script && it.key == key }
        if (matching.isEmpty()) return
        val oldJs = oldV ?: "null"
        val newJs = newV ?: "null"
        val sb = StringBuilder()
        for (l in matching) {
            sb.append("try{window.__uh_fire_vcl('").append(l.id).append("','")
                .append(escapeJs(key)).append("',")
                .append(oldJs).append(",").append(newJs).append(",false)}catch(e){}")
        }
        w.post { w.evaluateJavascript(sb.toString(), null) }
    }

    // --- Storage (namespaced per script) ---

    @JavascriptInterface
    fun GM_getValueNs(script: String, key: String): String? =
        prefs.getString(nsKey(script, key), null)

    @JavascriptInterface
    fun GM_setValueNs(script: String, key: String, value: String) {
        prefs.edit().putString(nsKey(script, key), value).apply()
    }

    @JavascriptInterface
    fun GM_deleteValueNs(script: String, key: String) {
        prefs.edit().remove(nsKey(script, key)).apply()
    }

    @JavascriptInterface
    fun GM_listValuesNs(script: String): String {
        val out = JSONObject()
        val prefix = "$script::"
        for ((k, v) in prefs.all) {
            if (k.startsWith(prefix)) out.put(k.substring(prefix.length), v?.toString() ?: "")
        }
        return out.toString()
    }

    @JavascriptInterface
    fun GM_keysForScript(script: String): String {
        val arr = org.json.JSONArray()
        val prefix = "$script::"
        for (k in prefs.all.keys) {
            if (k.startsWith(prefix)) arr.put(k.substring(prefix.length))
        }
        return arr.toString()
    }

    @JavascriptInterface
    fun GM_addValueChangeListenerNs(script: String, key: String): String {
        val id = "vcl_" + vclId.incrementAndGet()
        vcl.computeIfAbsent(script) { mutableListOf() }.add(Listener(id, script, key))
        return id
    }

    @JavascriptInterface
    fun GM_removeValueChangeListener(id: String) {
        for (list in vcl.values) list.removeAll { it.id == id }
    }

    // --- XHR ---

@JavascriptInterface
fun GM_xmlhttpRequest(
    script: String, id: String, method: String, url: String,
    headersJson: String, body: String, responseType: String,
    timeoutStr: String, user: String, password: String
) {
    Thread {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 20000
            conn.readTimeout = timeoutStr.toIntOrNull()?.takeIf { it > 0 } ?: 20000
            conn.instanceFollowRedirects = true

            try {
                val hdrs = if (headersJson.isBlank()) JSONObject() else JSONObject(headersJson)
                for (k in hdrs.keys()) conn.setRequestProperty(k, hdrs.optString(k))
            } catch (_: Exception) {}

            val cookie = try { CookieManager.getInstance().getCookie(url) } catch (_: Exception) { null }
            if (!cookie.isNullOrBlank() && conn.getRequestProperty("Cookie") == null) {
                conn.setRequestProperty("Cookie", cookie)
            }
            if (conn.getRequestProperty("User-Agent") == null) {
                conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 10; RMX1801) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/153.0.8010.57 Mobile Safari/537.36")
            }

            if (body.isNotBlank() && method in listOf("POST", "PUT", "PATCH", "DELETE")) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            xhrAborts[id] = conn

            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val totalLen = conn.contentLengthLong
            val sb = StringBuilder()
            if (stream != null) {
                val buf = ByteArray(16 * 1024)
                var read = 0L
                var lastFire = 0L
                var n: Int
                while (true) {
                    n = stream.read(buf)
                    if (n < 0) break
                    sb.append(String(buf, 0, n, Charsets.UTF_8))
                    read += n
                    val now = System.currentTimeMillis()
                    if (now - lastFire > 200L) {
                        lastFire = now
                        val w = v()
                        if (w != null && totalLen > 0) {
                            val js = "try{window.__uh_xhr_progress('" + id + "'," +
                                read + "," + totalLen + ")}catch(e){}"
                            w.post { w.evaluateJavascript(js, null) }
                        }
                    }
                }
            }
            val text = sb.toString()

            val respHeaders = StringBuilder()
            for ((k, values) in conn.headerFields) {
                if (k == null) continue
                for (vv in values) respHeaders.append(k).append(": ").append(vv).append("\r\n")
            }
            val finalUrl = conn.url.toString()
            xhrAborts.remove(id)
            conn.disconnect()

            val payload = buildPayload(responseType, text)
            val w = v() ?: return@Thread
            val js = "try{window.__uh_xhr_done('" + id + "'," + status + "," +
                jsString(payload) + "," + jsString(respHeaders.toString()) + "," +
                jsString(finalUrl) + ")}catch(e){}"
            w.post { w.evaluateJavascript(js, null) }
        } catch (e: Exception) {
            xhrAborts.remove(id)
            val w = v() ?: return@Thread
            val js = "try{window.__uh_xhr_error('" + id + "'," + jsString(e.message ?: "error") + ")}catch(e){}"
            w.post { w.evaluateJavascript(js, null) }
        }
    }.start()
}

    @JavascriptInterface
    fun GM_xhrAbort(id: String) {
        xhrAborts.remove(id)?.disconnect()
    }

    private fun buildPayload(responseType: String, text: String): String {
        return when (responseType.lowercase()) {
            "json" -> text
            "arraybuffer", "blob" -> {
                val b64 = android.util.Base64.encodeToString(text.toByteArray(Charsets.ISO_8859_1), android.util.Base64.NO_WRAP)
                "b64:" + b64
            }
            "document" -> text
            else -> text
        }
    }

    // --- Downloads / tabs / clipboard / notifications ---

@JavascriptInterface
fun GM_download(script: String, url: String, name: String) {
    try {
        DownloadHelper.saveFromUrlWithCookies(ctx, url, name, null, null)
        val w = v() ?: return
        w.post {
            android.widget.Toast.makeText(ctx, "Download started", android.widget.Toast.LENGTH_SHORT).show()
        }
    } catch (_: Exception) {}
}

    @JavascriptInterface
    fun GM_openInTab(script: String, url: String, active: Boolean) {
        try {
            val intent = android.content.Intent(ctx, MainActivity::class.java).apply {
                data = android.net.Uri.parse(url)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun GM_setClipboard(text: String) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("userscript", text))
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun GM_notification(script: String, title: String, text: String) {
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
    fun GM_registerMenuCommand(script: String, name: String, id: String, accessKey: String) {
        MenuCommandStore.register(script, id, name, accessKey)
    }

    @JavascriptInterface
    fun GM_unregisterMenuCommand(script: String, id: String) {
        MenuCommandStore.unregister(script, id)
    }

    // --- Cookies ---

    @JavascriptInterface
    fun GM_cookieGet(url: String): String {
        return try {
            val c = CookieManager.getInstance().getCookie(url) ?: ""
            val arr = org.json.JSONArray()
            if (c.isNotBlank()) {
                for (pair in c.split(";")) {
                    val kv = pair.trim().split("=", limit = 2)
                    if (kv.size == 2) {
                        val o = JSONObject()
                        o.put("name", kv[0])
                        o.put("value", kv[1])
                        arr.put(o)
                    }
                }
            }
            arr.toString()
        } catch (_: Exception) { "[]" }
    }

    @JavascriptInterface
    fun GM_cookieSet(url: String, name: String, value: String) {
        try { CookieManager.getInstance().setCookie(url, "$name=$value") } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun GM_cookieDelete(url: String, name: String) {
        try { CookieManager.getInstance().setCookie(url, "$name=; Max-Age=0") } catch (_: Exception) {}
    }

    // --- Resources ---

    @JavascriptInterface
    fun GM_getResourceText(script: String, name: String): String? =
        ResourceStore.getText(ctx, script, name)

    @JavascriptInterface
    fun GM_getResourceURL(script: String, name: String): String? =
        ResourceStore.getUrl(ctx, script, name)

    // --- Misc ---

    @JavascriptInterface
    fun GM_log(message: String) {
        UHLog.d("userscript", message)
    }

    @JavascriptInterface
    fun captureWasm(base64: String) {
        SessionRecorder.record("wasm_blob") { put("base64", base64); put("size", base64.length) }
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

@JavascriptInterface
fun openImage(url: String) {
    try {
        val intent = Intent(ctx, ImagePreviewActivity::class.java).apply {
            putExtra("url", url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    } catch (_: Exception) {}
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

    private fun escapeJs(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")
}
