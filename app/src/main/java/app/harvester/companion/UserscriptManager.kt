package app.harvester.companion

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UserscriptManager {

    private const val BRIDGE_HOOK = "bridge"

    private val HOOK_ORDER = listOf(
        "wasm_blob",
        "crypto",
        "atob",
        "textdecoder",
        "wasm_memory",
        "wasm_call",
        "worker_fetch",
        "sw_fetch"
    )

    private val hookCache = mutableMapOf<String, String>()

    private fun loadHook(ctx: Context, name: String): String {
        synchronized(hookCache) {
            hookCache[name]?.let { return it }
        }
        val body = try {
            ctx.assets.open("hooks/$name.js").bufferedReader().use { it.readText() }
        } catch (_: Exception) { "" }
        if (body.isNotBlank()) {
            synchronized(hookCache) { hookCache[name] = body }
        }
        return body
    }

    fun injectOnPageStart(ctx: Context, view: WebView, url: String) {
        val bridge = loadHook(ctx, BRIDGE_HOOK)
        if (bridge.isNotBlank()) view.evaluateJavascript(bridge, null)

        for (hook in HOOK_ORDER) {
            if (!SessionRecorder.isEnabled(hook)) continue
            val body = loadHook(ctx, hook)
            if (body.isNotBlank()) view.evaluateJavascript(body, null)
        }

        for (script in loadScripts(ctx)) {
            if (matches(script.body, url)) {
                view.evaluateJavascript(script.body, null)
                UHLog.d("userscript", "injected ${script.name} into $url")
            }
        }
    }

    fun loadScripts(ctx: Context): List<Script> {
        val dir = File(ctx.filesDir, "scripts")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("js", true) }
            ?.mapNotNull { f ->
                try { Script(f.nameWithoutExtension, f.readText()) } catch (_: Exception) { null }
            } ?: emptyList()
    }

    fun install(ctx: Context, name: String, body: String): Boolean {
        return try {
            val dir = File(ctx.filesDir, "scripts").apply { mkdirs() }
            File(dir, "$name.js").writeText(body)
            true
        } catch (_: Exception) { false }
    }

    fun uninstall(ctx: Context, name: String): Boolean {
        return try {
            File(File(ctx.filesDir, "scripts"), "$name.js").delete()
        } catch (_: Exception) { false }
    }

    fun installFromUrl(ctx: Context, url: String, callback: (Boolean) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.connect()
                if (connection.responseCode in 200..299) {
                    val scriptContent = connection.inputStream.bufferedReader().use { it.readText() }
                    val scriptName = extractNameFromScript(scriptContent)
                        ?: "script_${System.currentTimeMillis()}"
                    val success = install(ctx, scriptName, scriptContent)
                    withContext(Dispatchers.Main) { callback(success) }
                } else {
                    withContext(Dispatchers.Main) { callback(false) }
                }
                connection.disconnect()
            } catch (e: Exception) {
                UHLog.throwable("userscript", "install from url failed", e)
                withContext(Dispatchers.Main) { callback(false) }
            }
        }
    }

    private fun extractNameFromScript(script: String): String? {
        val regex = Regex("""@name\s+(.+)$""", RegexOption.MULTILINE)
        val raw = regex.find(script)?.groupValues?.get(1)?.trim() ?: return null
        return raw.replace(Regex("""[^a-zA-Z0-9_\-]"""), "_").take(60)
    }

    private fun matches(body: String, url: String): Boolean {
        var lineNo = 0
        for (line in body.lineSequence()) {
            if (lineNo++ > 500) break
            val t = line.trim()
            if (t.contains("==/UserScript==")) break
            if (t.startsWith("// @match") || t.startsWith("// @include")) {
                val pattern = t.substringAfter("match").substringAfter("include").trim()
                if (patternMatches(pattern, url)) return true
            }
        }
        return false
    }

    private fun patternMatches(pattern: String, url: String): Boolean {
        if (pattern.isBlank()) return false
        val sb = StringBuilder()
        for (c in pattern) {
            when (c) {
                '*' -> sb.append(".*")
                '.', '(', ')', '[', ']', '{', '}', '+', '?', '^', '$', '|', '\\' ->
                    sb.append('\\').append(c)
                else -> sb.append(c)
            }
        }
        return try { Regex(sb.toString()).matches(url) } catch (_: Exception) { false }
    }

    data class Script(val name: String, val body: String)
}
