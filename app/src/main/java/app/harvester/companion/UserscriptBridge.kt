package app.harvester.companion

import android.content.Context
import android.webkit.JavascriptInterface
import org.json.JSONObject

class UserscriptBridge(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("uh_gm", Context.MODE_PRIVATE)

    @JavascriptInterface
    fun GM_getValue(key: String, defaultValue: String?): String? =
        prefs.getString(key, defaultValue)

    @JavascriptInterface
    fun GM_setValue(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    @JavascriptInterface
    fun GM_deleteValue(key: String) {
        prefs.edit().remove(key).apply()
    }

    @JavascriptInterface
    fun GM_listValues(): String {
        val out = JSONObject()
        for ((k, v) in prefs.all) out.put(k, v?.toString() ?: "")
        return out.toString()
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
        SessionRecorder.record("crypto") {
            put("algo", algorithm)
            put("data", data)
        }
    }

    @JavascriptInterface
    fun captureAtob(decoded: String) {
        SessionRecorder.record("atob") {
            put("data", decoded)
            put("size", decoded.length)
        }
    }

    @JavascriptInterface
    fun captureTextDecoder(text: String) {
        SessionRecorder.record("textdecoder") {
            put("data", text)
            put("size", text.length)
        }
    }

    @JavascriptInterface
    fun report(url: String, kind: String, detail: String) {
        UHLog.d("capture", "$kind $url — $detail")
    }
}
