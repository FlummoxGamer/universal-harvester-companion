package app.harvester.companion

import android.content.Context
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File
import java.util.WeakHashMap
import java.util.zip.ZipInputStream

object UserscriptManager {

    private val handlers = WeakHashMap<WebView, MutableList<WebViewCompat.ScriptHandler>>()

    data class Script(
        val name: String,
        val displayName: String,
        val description: String,
        val version: String,
        val patterns: List<String>,
        val runAt: String,
        val body: String
    )

    fun loadScripts(ctx: Context): List<Script> {
        val dir = File(ctx.filesDir, "scripts")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("js", true) }
            ?.mapNotNull { f ->
                try { parse(f.nameWithoutExtension, f.readText()) } catch (_: Exception) { null }
            } ?: emptyList()
    }

    fun parse(name: String, body: String): Script {
        var displayName = name
        var description = ""
        var version = ""
        var runAt = "document-start"
        val patterns = mutableListOf<String>()

        var lineNo = 0
        for (line in body.lineSequence()) {
            if (lineNo++ > 500) break
            val t = line.trim()
            if (t.contains("==/UserScript==")) break
            when {
                t.startsWith("// @name") -> displayName = t.substringAfter("@name").trim()
                t.startsWith("// @description") -> description = t.substringAfter("@description").trim()
                t.startsWith("// @version") -> version = t.substringAfter("@version").trim()
                t.startsWith("// @run-at") -> runAt = t.substringAfter("@run-at").trim()
                t.startsWith("// @match") -> patterns.add(t.substringAfter("@match").trim())
                t.startsWith("// @include") -> patterns.add(t.substringAfter("@include").trim())
            }
        }
        if (patterns.isEmpty()) patterns.add("*")
        return Script(name, displayName, description, version, patterns, runAt, body)
    }

    fun install(ctx: Context, name: String, body: String): Boolean {
        return try {
            val dir = File(ctx.filesDir, "scripts").apply { mkdirs() }
            val safe = name.replace(Regex("""[^a-zA-Z0-9_\-]"""), "_").take(80)
            File(dir, "$safe.js").writeText(body)
            UserscriptStore.markDirty(ctx)
            true
        } catch (_: Exception) { false }
    }

    fun installFromText(ctx: Context, body: String): Boolean {
        val parsed = parse("script_" + System.currentTimeMillis(), body)
        return install(ctx, parsed.name, body)
    }

    fun installFromUrl(ctx: Context, url: String, callback: (Boolean) -> Unit) {
        Thread {
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.connect()
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val parsed = parse("script_" + System.currentTimeMillis(), body)
                    val ok = install(ctx, parsed.name, body)
                    callback(ok)
                } else callback(false)
                conn.disconnect()
            } catch (e: Exception) {
                UHLog.throwable("userscript", "install url failed", e)
                callback(false)
            }
        }.start()
    }

    fun installFromUri(ctx: Context, uri: Uri): Boolean {
        return try {
            val input = ctx.contentResolver.openInputStream(uri) ?: return false
            val bytes = input.use { it.readBytes() }
            if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
                installFromZipBytes(ctx, bytes)
            } else {
                val text = String(bytes, Charsets.UTF_8)
                val parsed = parse("script_" + System.currentTimeMillis(), text)
                install(ctx, parsed.name, text)
            }
        } catch (e: Exception) {
            UHLog.throwable("userscript", "install uri failed", e)
            false
        }
    }

    private fun installFromZipBytes(ctx: Context, bytes: ByteArray): Boolean {
        return try {
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val n = entry.name.lowercase()
                    if (n.endsWith(".user.js") || n.endsWith(".js")) {
                        val body = zip.bufferedReader().use { it.readText() }
                        val parsed = parse(File(entry.name).nameWithoutExtension, body)
                        return install(ctx, parsed.name, body)
                    }
                    entry = zip.nextEntry
                }
            }
            false
        } catch (_: Exception) { false }
    }

    fun uninstall(ctx: Context, name: String): Boolean {
        return try {
            File(File(ctx.filesDir, "scripts"), "$name.js").delete().also {
                UserscriptStore.forget(ctx, name)
            }
        } catch (_: Exception) { false }
    }

    fun registerDocumentStart(view: WebView, ctx: Context) {
        clearDocumentStart(view)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            UHLog.w("userscript", "DOCUMENT_START_SCRIPT unsupported — falling back")
            return
        }
        val list = mutableListOf<WebViewCompat.ScriptHandler>()
        try {
            list.add(WebViewCompat.addDocumentStartJavaScript(view, BRIDGE_BOOT, setOf("*")))
        } catch (_: Exception) {}

        val enabled = SessionRecorder.ALL_CATEGORIES.filter { SessionRecorder.isEnabled(it) }
        for (hook in enabled) {
            val body = loadHook(ctx, hook)
            if (body.isNotBlank()) {
                try { list.add(WebViewCompat.addDocumentStartJavaScript(view, body, setOf("*"))) } catch (_: Exception) {}
            }
        }

        val scripts = loadScripts(ctx)
        for (script in scripts) {
            if (!UserscriptStore.isEnabled(ctx, script.name)) continue
            if (script.runAt != "document-start") continue
            try {
                list.add(WebViewCompat.addDocumentStartJavaScript(view, buildWrapper(script), setOf("*")))
            } catch (e: Exception) {
                UHLog.throwable("userscript", "register ${script.name} failed", e)
            }
        }
        handlers[view] = list
        UHLog.d("userscript", "registered ${list.size} document-start scripts")
    }

    fun injectOnPageFinished(ctx: Context, view: WebView, url: String) {
        val scripts = loadScripts(ctx)
        for (script in scripts) {
            if (!UserscriptStore.isEnabled(ctx, script.name)) continue
            if (script.runAt == "document-start") continue
            if (!matchesPatterns(script.patterns, url)) continue
            val wrapped = buildWrapper(script)
            view.evaluateJavascript(wrapped) { result ->
                if (result != null && result.contains("error", true)) {
                    UHLog.w("userscript", "eval result ${script.name}: ${result.take(200)}")
                }
            }
            UHLog.d("userscript", "injected ${script.name} (${script.runAt}) into $url")
        }
    }

    private fun clearDocumentStart(view: WebView) {
        handlers.remove(view)?.forEach { h ->
            try { WebViewCompat.removeDocumentStartJavaScript(view, h) } catch (_: Exception) {}
        }
    }

    private val hookCache = mutableMapOf<String, String>()

    private fun loadHook(ctx: Context, name: String): String {
        synchronized(hookCache) { hookCache[name]?.let { return it } }
        val body = try {
            ctx.assets.open("hooks/$name.js").bufferedReader().use { it.readText() }
        } catch (_: Exception) { "" }
        if (body.isNotBlank()) synchronized(hookCache) { hookCache[name] = body }
        return body
    }

    private const val BRIDGE_BOOT = """
(function(){
  if (window.__uh_bridge_ready) return;
  window.__uh_bridge_ready = true;
  var B = window.UH_BRIDGE;
  if (!B) return;
  window.GM_getValue = function(k, d) { return B.GM_getValue(k, d == null ? null : String(d)); };
  window.GM_setValue = function(k, v) { B.GM_setValue(k, String(v)); };
  window.GM_deleteValue = function(k) { B.GM_deleteValue(k); };
  window.GM_listValues = function() { return JSON.parse(B.GM_listValues()); };
  window.GM_log = function(m) { B.GM_log(String(m)); };
})();
"""

    private fun buildWrapper(script: Script): String {
        val patsJson = script.patterns.joinToString(",") {
            "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
        val preamble =
            "(function(){try{" +
            "var u=location.href;" +
            "var pats=[" + patsJson + "];" +
            "function gm(p,u){var pi=0,ui=0,s=-1,m=0;" +
            "while(ui<u.length){" +
            "if(pi<p.length&&p[pi]===u[ui]){pi++;ui++}" +
            "else if(pi<p.length&&p[pi]==='*'){s=pi++;m=ui}" +
            "else if(s>=0){pi=s+1;ui=++m}" +
            "else return false}" +
            "while(pi<p.length&&p[pi]==='*')pi++;return pi===p.length}" +
            "var ok=false;for(var i=0;i<pats.length;i++){try{if(gm(pats[i],u)){ok=true;break}}catch(e){}}" +
            "if(!ok)return;" +
            "try{"
        val postamble =
            "}catch(e){" +
            "if(window.UH_BRIDGE&&window.UH_BRIDGE.report)window.UH_BRIDGE.report(u,'userscript_err',String((e&&e.message)||e));" +
            "}" +
            "}catch(e){if(window.UH_BRIDGE&&window.UH_BRIDGE.report)window.UH_BRIDGE.report(location.href,'wrapper_err',String((e&&e.message)||e))}})();"
        return preamble + script.body + postamble
    }

    private fun matchesPatterns(patterns: List<String>, url: String): Boolean {
        if (patterns.isEmpty()) return true
        return patterns.any { globMatch(it, url) }
    }

    private fun globMatch(pattern: String, url: String): Boolean {
        var pi = 0; var ui = 0; var star = -1; var mark = 0
        while (ui < url.length) {
            when {
                pi < pattern.length && pattern[pi] == url[ui] -> { pi++; ui++ }
                pi < pattern.length && pattern[pi] == '*' -> { star = pi++; mark = ui }
                star >= 0 -> { pi = star + 1; ui = ++mark }
                else -> return false
            }
        }
        while (pi < pattern.length && pattern[pi] == '*') pi++
        return pi == pattern.length
    }
}
