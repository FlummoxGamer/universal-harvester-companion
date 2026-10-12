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
        val ts = System.currentTimeMillis()
        DownloadHelper.saveTextToDownloads(ctx, "wasm_$ts.txt", base64)
        UHLog.d("capture", "WASM module captured (${base64.length} bytes)")
    }

    @JavascriptInterface
    fun captureCrypto(algorithm: String, data: String) {
        val ts = System.currentTimeMillis()
        val content = "algorithm=$algorithm\ndata=$data\n"
        DownloadHelper.saveTextToDownloads(ctx, "crypto_$ts.txt", content)
        UHLog.d("capture", "crypto.subtle call: $algorithm")
    }

    @JavascriptInterface
    fun captureAtob(decoded: String) {
        val ts = System.currentTimeMillis()
        DownloadHelper.saveTextToDownloads(ctx, "atob_$ts.txt", decoded)
        UHLog.d("capture", "atob decoded ${decoded.length} chars")
    }

    @JavascriptInterface
    fun captureTextDecoder(text: String) {
        val ts = System.currentTimeMillis()
        DownloadHelper.saveTextToDownloads(ctx, "textdecode_$ts.txt", text)
        UHLog.d("capture", "TextDecoder decoded ${text.length} chars")
    }

    @JavascriptInterface
    fun report(url: String, kind: String, detail: String) {
        UHLog.d("capture", "$kind $url — $detail")
    }
}
