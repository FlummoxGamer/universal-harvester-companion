package app.harvester.companion

import android.content.Context
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File
import java.util.WeakHashMap
import java.util.zip.ZipInputStream

object UserscriptManager {

    private val handlers = WeakHashMap<WebView, MutableList<ScriptHandler>>()
    private val hookCache = mutableMapOf<String, String>()

    data class Script(
        val name: String,
        val displayName: String,
        val namespace: String,
        val description: String,
        val version: String,
        val author: String,
        val homepage: String,
        val icon: String,
        val patterns: List<String>,
        val excludes: List<String>,
        val grants: List<String>,
        val connects: List<String>,
        val requires: List<String>,
        val resources: Map<String, String>,
        val runAt: String,
        val body: String,
        val usesSystemjs: Boolean,
        val fileSize: Long
    )

    fun loadScripts(ctx: Context): List<Script> {
        val dir = File(ctx.filesDir, "scripts")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("js", true) }
            ?.mapNotNull { f ->
                try { parse(f.nameWithoutExtension, f.readText(), f.length()) } catch (_: Exception) { null }
            } ?: emptyList()
    }

    fun loadScript(ctx: Context, name: String): Script? {
        val f = File(File(ctx.filesDir, "scripts"), "$name.js")
        if (!f.isFile) return null
        return try { parse(name, f.readText(), f.length()) } catch (_: Exception) { null }
    }

    fun parse(name: String, body: String, fileSize: Long = 0L): Script {
        var displayName = name
        var namespace = ""
        var description = ""
        var version = ""
        var author = ""
        var homepage = ""
        var icon = ""
        var runAt = "document-start"
        val patterns = mutableListOf<String>()
        val excludes = mutableListOf<String>()
        val grants = mutableListOf<String>()
        val connects = mutableListOf<String>()
        val requires = mutableListOf<String>()
        val resources = mutableMapOf<String, String>()

        var lineNo = 0
        for (line in body.lineSequence()) {
            if (lineNo++ > 500) break
            val t = line.trim()
            if (t.contains("==/UserScript==")) break
            when {
                t.startsWith("// @name") -> displayName = t.substringAfter("@name").trim()
                t.startsWith("// @namespace") -> namespace = t.substringAfter("@namespace").trim()
                t.startsWith("// @description") -> description = t.substringAfter("@description").trim()
                t.startsWith("// @version") -> version = t.substringAfter("@version").trim()
                t.startsWith("// @author") -> author = t.substringAfter("@author").trim()
                t.startsWith("// @homepageURL") -> homepage = t.substringAfter("@homepageURL").trim()
                t.startsWith("// @homepage") -> if (homepage.isEmpty()) homepage = t.substringAfter("@homepage").trim()
                t.startsWith("// @icon") -> icon = t.substringAfter("@icon").trim()
                t.startsWith("// @run-at") -> runAt = t.substringAfter("@run-at").trim()
                t.startsWith("// @match") -> patterns.add(t.substringAfter("@match").trim())
                t.startsWith("// @include") -> patterns.add(t.substringAfter("@include").trim())
                t.startsWith("// @exclude-match") -> excludes.add(t.substringAfter("@exclude-match").trim())
                t.startsWith("// @exclude") -> excludes.add(t.substringAfter("@exclude").trim())
                t.startsWith("// @grant") -> grants.add(t.substringAfter("@grant").trim())
                t.startsWith("// @connect") -> connects.add(t.substringAfter("@connect").trim())
                t.startsWith("// @require") -> requires.add(t.substringAfter("@require").trim())
                t.startsWith("// @resource") -> {
                    val rest = t.substringAfter("@resource").trim()
                    val parts = rest.split(Regex("\\s+"), 2)
                    if (parts.size == 2) resources[parts[0]] = parts[1]
                }
            }
        }
        if (patterns.isEmpty()) patterns.add("*")

        val usesSystemjs = body.contains("System.register") && !grants.contains("none")

        return Script(
            name = name,
            displayName = displayName,
            namespace = namespace,
            description = description,
            version = version,
            author = author,
            homepage = homepage,
            icon = icon,
            patterns = patterns,
            excludes = excludes,
            grants = grants,
            connects = connects,
            requires = requires,
            resources = resources,
            runAt = runAt,
            body = body,
            usesSystemjs = usesSystemjs,
            fileSize = fileSize
        )
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
            var n: String? = null
            var b: String? = null
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && b == null) {
                    val en = entry.name.lowercase()
                    if (en.endsWith(".user.js") || en.endsWith(".js")) {
                        n = File(entry.name).nameWithoutExtension
                        b = zip.bufferedReader().use { it.readText() }
                    }
                    entry = zip.nextEntry
                }
            }
            val nn = n
            val bb = b
            if (nn != null && bb != null) {
                val parsed = parse(nn, bb)
                install(ctx, parsed.name, bb)
            } else false
        } catch (_: Exception) { false }
    }

    fun uninstall(ctx: Context, name: String): Boolean {
        return try {
            File(File(ctx.filesDir, "scripts"), "$name.js").delete().also {
                UserscriptStore.forget(ctx, name)
            }
        } catch (_: Exception) { false }
    }

    fun saveEdited(ctx: Context, name: String, body: String): Boolean = install(ctx, name, body)

    fun registerDocumentStart(view: WebView, ctx: Context) {
        clearDocumentStart(view)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            UHLog.w("userscript", "DOCUMENT_START_SCRIPT unsupported")
            return
        }
        val list = mutableListOf<ScriptHandler>()
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
            val wrapped = buildWrapper(ctx, script)
            if (wrapped.isBlank()) continue
            try {
                list.add(WebViewCompat.addDocumentStartJavaScript(view, wrapped, setOf("*")))
                UHLog.d("userscript", "registered ${script.name} sysjs=${script.usesSystemjs} grants=${script.grants.size} connects=${script.connects.size}")
            } catch (e: Exception) {
                UHLog.throwable("userscript", "register ${script.name} failed", e)
            }
        }
        handlers[view] = list
        UHLog.d("userscript", "registered ${list.size} document-start scripts")
    }

    fun injectOnPageFinished(ctx: Context, view: WebView, url: String) {
        if (isBadUrl(url)) return
        val scripts = loadScripts(ctx)
        for (script in scripts) {
            if (!UserscriptStore.isEnabled(ctx, script.name)) continue
            if (script.runAt == "document-start") continue
            if (!matchesPatterns(script.patterns, url)) continue
            if (matchesPatterns(script.excludes, url)) continue
            val wrapped = buildWrapper(ctx, script)
            if (wrapped.isBlank()) continue
            view.evaluateJavascript(wrapped) { result ->
                if (result != null && result.contains("error", true)) {
                    UHLog.w("userscript", "eval ${script.name}: ${result.take(200)}")
                }
            }
            UHLog.d("userscript", "injected ${script.name} (${script.runAt}) into $url")
        }
    }

    private fun isBadUrl(url: String): Boolean {
        if (url.isBlank()) return true
        if (url == "about:blank") return true
        if (url.startsWith("chrome-error://")) return true
        if (url.startsWith("chrome://")) return true
        if (url.startsWith("data:")) return true
        if (url.startsWith("blob:")) return true
        if (url.startsWith("javascript:")) return true
        return false
    }

    private fun clearDocumentStart(view: WebView) {
        val prior = handlers.remove(view) ?: return
        for (h in prior) {
            try { h.remove() } catch (_: Exception) {}
        }
    }

    private fun loadAsset(ctx: Context, path: String): String {
        synchronized(hookCache) { hookCache[path]?.let { return it } }
        val body = try {
            ctx.assets.open(path).bufferedReader().use { it.readText() }
        } catch (_: Exception) { "" }
        if (body.isNotBlank()) synchronized(hookCache) { hookCache[path] = body }
        return body
    }

    private fun loadHook(ctx: Context, name: String): String = loadAsset(ctx, "hooks/$name.js")

    private const val BRIDGE_BOOT = """
(function(){
  if (window.__uh_bridge_ready) return;
  window.__uh_bridge_ready = true;
  var B = window.UH_BRIDGE;
  if (!B) return;

  window.unsafeWindow = window;

  window.GM_info = {
    script: { name: '', namespace: '', version: '' },
    scriptHandler: 'Harvester',
    version: '1.0',
    platform: { browserName: 'Android WebView' }
  };

  window.GM_getValue = function(k, d) {
    var v = B.GM_getValue(String(k), d == null ? null : JSON.stringify(d));
    if (v == null) return d;
    try { return JSON.parse(v); } catch(e) { return v; }
  };
  window.GM_setValue = function(k, v) {
    B.GM_setValue(String(k), JSON.stringify(v));
  };
  window.GM_deleteValue = function(k) { B.GM_deleteValue(String(k)); };
  window.GM_listValues = function() { return JSON.parse(B.GM_listValues()); };

  window.GM_addValueChangeListener = function(name, cb) {
    var id = B.GM_addValueChangeListener(String(name));
    window.__uh_vcl[id] = cb;
    return id;
  };
  window.GM_removeValueChangeListener = function(id) {
    delete window.__uh_vcl[id];
    B.GM_removeValueChangeListener(String(id));
  };
  window.__uh_vcl = window.__uh_vcl || {};
  window.__uh_fire_vcl = function(id, name, oldV, newV, remote) {
    var cb = window.__uh_vcl[id];
    if (cb) try { cb(name, oldV, newV, remote); } catch(e) {}
  };

  window.__uh_xhr = window.__uh_xhr || {};
  window.GM_xmlhttpRequest = function(details) {
    var id = 'xhr_' + Math.random().toString(36).slice(2);
    var rec = { onload: details.onload, onerror: details.onerror,
                onprogress: details.onprogress, onabort: details.onabort,
                abort: function(){ B.GM_xhrAbort(id); } };
    window.__uh_xhr[id] = rec;
    try {
      B.GM_xmlhttpRequest(
        id,
        String(details.method || 'GET'),
        String(details.url),
        details.headers ? JSON.stringify(details.headers) : '',
        details.data != null ? String(details.data) : '',
        details.responseType ? String(details.responseType) : '',
        details.timeout ? String(details.timeout) : '0'
      );
    } catch(e) {
      if (rec.onerror) try { rec.onerror({ error: String(e) }); } catch(_){}
    }
    return rec;
  };
  window.__uh_xhr_done = function(id, status, responseText, responseHeaders, finalUrl) {
    var rec = window.__uh_xhr[id];
    if (!rec) return;
    delete window.__uh_xhr[id];
    if (rec.onload) try {
      rec.onload({
        readyState: 4, status: status, statusText: String(status),
        responseText: responseText, response: responseText,
        responseHeaders: responseHeaders, finalUrl: finalUrl
      });
    } catch(e) {}
  };
  window.__uh_xhr_error = function(id, error) {
    var rec = window.__uh_xhr[id];
    if (!rec) return;
    delete window.__uh_xhr[id];
    if (rec.onerror) try { rec.onerror({ error: error, readyState: 4, status: 0 }); } catch(e) {}
  };

  window.GM_download = function(details) {
    var url = typeof details === 'string' ? details : details.url;
    var name = typeof details === 'object' && details.name ? details.name : null;
    B.GM_download(String(url), name ? String(name) : '');
  };

  window.GM_openInTab = function(url, options) {
    var active = !(options && options.active === false);
    B.GM_openInTab(String(url), active);
  };

  window.GM_setClipboard = function(text, type) {
    B.GM_setClipboard(String(text));
  };

  window.GM_notification = function(details, ondone) {
    var title = typeof details === 'string' ? details : (details.title || '');
    var text = typeof details === 'object' ? (details.text || details.body || '') : '';
    B.GM_notification(String(title), String(text));
  };

  window.__uh_menu = window.__uh_menu || {};
  window.GM_registerMenuCommand = function(name, fn, accessKey) {
    var id = 'menu_' + Math.random().toString(36).slice(2);
    window.__uh_menu[id] = fn;
    B.GM_registerMenuCommand(String(name), id, accessKey ? String(accessKey) : '');
    return id;
  };
  window.GM_unregisterMenuCommand = function(id) {
    delete window.__uh_menu[id];
    B.GM_unregisterMenuCommand(String(id));
  };
  window.__uh_runMenuCommand = function(id) {
    var fn = window.__uh_menu[id];
    if (fn) try { fn(); } catch(e) {}
  };

  window.GM_addStyle = function(css) {
    var s = document.createElement('style');
    s.textContent = String(css);
    (document.head || document.documentElement).appendChild(s);
    return s;
  };

  window.GM_addElement = function(parent, tag, attrs) {
    var el = document.createElement(tag);
    if (attrs) { for (var k in attrs) {
      if (k === 'textContent') el.textContent = attrs[k];
      else if (k === 'innerHTML') el.innerHTML = attrs[k];
      else el.setAttribute(k, attrs[k]);
    }}
    var p = typeof parent === 'string' ? document.querySelector(parent) : parent;
    (p || document.body || document.documentElement).appendChild(el);
    return el;
  };

  window.GM_log = function(m) { B.GM_log(String(m)); };
})();
"""

    private fun buildWrapper(ctx: Context, script: Script): String {
        if (script.grants.contains("none") && !script.usesSystemjs) {
            // plain script — no bridge needed
            val patsJson = script.patterns.joinToString(",") {
                "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            }
            return "(function(){var u=location.href;var pats=[$patsJson];" +
                "function gm(p,u){var pi=0,ui=0,s=-1,m=0;while(ui<u.length){" +
                "if(pi<p.length&&p[pi]===u[ui]){pi++;ui++}" +
                "else if(pi<p.length&&p[pi]==='*'){s=pi++;m=ui}" +
                "else if(s>=0){pi=s+1;ui=++m}else return false}" +
                "while(pi<p.length&&p[pi]==='*')pi++;return pi===p.length}" +
                "var ok=false;for(var i=0;i<pats.length;i++){try{if(gm(pats[i],u)){ok=true;break}}catch(e){}}" +
                "if(!ok)return;\n" + script.body + "\n})();"
        }

        val patsJson = script.patterns.joinToString(",") {
            "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
        val excludesJson = script.excludes.joinToString(",") {
            "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
        val preamble =
            "(function(){try{" +
            "var u=location.href;" +
            "var pats=[$patsJson];var exs=[$excludesJson];" +
            "function gm(p,u){var pi=0,ui=0,s=-1,m=0;" +
            "while(ui<u.length){" +
            "if(pi<p.length&&p[pi]===u[ui]){pi++;ui++}" +
            "else if(pi<p.length&&p[pi]==='*'){s=pi++;m=ui}" +
            "else if(s>=0){pi=s+1;ui=++m}" +
            "else return false}" +
            "while(pi<p.length&&p[pi]==='*')pi++;return pi===p.length}" +
            "var ok=false;for(var i=0;i<pats.length;i++){try{if(gm(pats[i],u)){ok=true;break}}catch(e){}}" +
            "if(!ok)return;" +
            "for(var j=0;j<exs.length;j++){try{if(gm(exs[j],u))return}catch(e){}}" +
            "try{"

        val systemjsPrelude = if (script.usesSystemjs) loadAsset(ctx, "hooks/system.min.js") else ""
        val requirePrelude = buildRequirePrelude(script)

        val postamble =
            "}catch(e){" +
            "if(window.UH_BRIDGE&&window.UH_BRIDGE.report)window.UH_BRIDGE.report(u,'userscript_err',String((e&&e.message)||e));" +
            "}" +
            "}catch(e){if(window.UH_BRIDGE&&window.UH_BRIDGE.report)window.UH_BRIDGE.report(location.href,'wrapper_err',String((e&&e.message)||e))}})();"

        return preamble + systemjsPrelude + "\n" + requirePrelude + "\n" + script.body + "\n" + postamble
    }

    private fun buildRequirePrelude(script: Script): String {
        if (script.requires.isEmpty()) return ""
        val sb = StringBuilder()
        for (url in script.requires) {
            val safeUrl = url.replace("\\", "\\\\").replace("\"", "\\\"")
            sb.append("try{var __uh_req=new XMLHttpRequest();__uh_req.open('GET','")
            sb.append(safeUrl)
            sb.append("',false);__uh_req.send();if(__uh_req.responseText){")
            sb.append("(0,eval)(__uh_req.responseText);}}catch(e){}\n")
        }
        return sb.toString()
    }

    private fun matchesPatterns(patterns: List<String>, url: String): Boolean {
        if (patterns.isEmpty()) return false
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
