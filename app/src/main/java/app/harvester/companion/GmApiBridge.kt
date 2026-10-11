package app.harvester.companion

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GmApiBridge(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("uh_gm", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var webView: WebView? = null

    fun attachWebView(wv: WebView) { webView = wv }

    @JavascriptInterface fun setValue(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    @JavascriptInterface fun getValue(key: String): String? = prefs.getString(key, null)

    @JavascriptInterface fun deleteValue(key: String) {
        prefs.edit().remove(key).apply()
    }

    @JavascriptInterface fun listValues(): String {
        val o = JSONObject()
        for ((k, v) in prefs.all) if (v is String) o.put(k, v)
        return o.toString()
    }

    @JavascriptInterface fun log(msg: String) {
        UHLog.d("gm", msg)
    }

    @JavascriptInterface fun saveBlob(name: String, text: String) {
        val safe = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val ok = DownloadHelper.saveTextToDownloads(ctx, safe, text)
        main.post {
            Toast.makeText(ctx, if (ok) "Saved $safe" else "Save failed", Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface fun xhr(requestId: String, method: String, url: String, headersJson: String, body: String?) {
        scope.launch {
            val payload = JSONObject()
            try {
                val headers = try { JSONObject(headersJson) } catch (_: Exception) { JSONObject() }
                val b = Request.Builder().url(url)
                for (k in headers.keys()) b.addHeader(k, headers.optString(k))
                val m = method.uppercase()
                if (m == "GET" || m == "HEAD") b.method(m, null)
                else {
                    val ct = headers.optString("Content-Type", "application/json")
                    b.method(m, (body ?: "").toRequestBody(ct.toMediaTypeOrNull()))
                }
                http.newCall(b.build()).execute().use { resp ->
                    payload.put("requestId", requestId)
                    payload.put("status", resp.code)
                    payload.put("responseText", resp.body?.string() ?: "")
                    val ho = JSONObject()
                    for ((k, v) in resp.headers) ho.put(k, v)
                    payload.put("responseHeaders", ho)
                }
            } catch (e: Exception) {
                UHLog.throwable("gm-xhr", "GM_xmlhttpRequest failed: $url", e)
                payload.put("requestId", requestId)
                payload.put("status", 0)
                payload.put("responseText", "")
                payload.put("error", e.message ?: "unknown")
            }
            dispatch("__uh_gm_xhr_done", payload.toString())
        }
    }

    private fun dispatch(fn: String, jsonArg: String) {
        val quoted = JSONObject.quote(jsonArg)
        val js = "window.$fn && window.$fn($quoted);"
        webView?.post { try { webView?.evaluateJavascript(js, null) } catch (_: Exception) {} }
    }
}
