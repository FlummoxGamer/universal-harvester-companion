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
    fun capture(type: String, data: String) {
        val ts = System.currentTimeMillis()
        DownloadHelper.saveTextToDownloads(ctx, "${type}_$ts.txt", data)
    }

    @JavascriptInterface
    fun report(url: String, kind: String, detail: String) {
        UHLog.d("capture", "$kind $url — $detail")
    }
}
