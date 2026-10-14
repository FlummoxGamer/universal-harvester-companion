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
        val noFrames: Boolean,
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
        var noFrames = false
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
                t.startsWith("// @name") -> if (displayName == name || displayName.isBlank()) displayName = t.substringAfter("@name").trim()
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
                t.startsWith("// @noframes") -> noFrames = true
                t.startsWith("// @resource") -> {
                    val rest = t.substringAfter("@resource").trim()
                    val parts = rest.split(Regex("\\s+"), 2)
                    if (parts.size == 2) resources[parts[0]] = parts[1]
                }
            }
        }
        if (patterns.isEmpty()) patterns.add("*")

        val usesSystemjs = body.contains("System.register")

        return Script(
            name = name, displayName = displayName, namespace = namespace,
            description = description, version = version, author = author,
            homepage = homepage, icon = icon, patterns = patterns, excludes = excludes,
            grants = grants, connects = connects, requires = requires, resources = resources,
            runAt = runAt, body = body, usesSystemjs = usesSystemjs, noFrames = noFrames,
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
                    callback(install(ctx, parsed.name, body))
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
            if (n != null && b != null) {
                val parsed = parse(n!!, b!!)
                install(ctx, parsed.name, b!!)
            } else false
        } catch (_: Exception) { false }
    }

    fun uninstall(ctx: Context, name: String): Boolean {
        return try {
            File(File(ctx.filesDir, "scripts"), "$name.js").delete().also {
                UserscriptStore.forget(ctx, name)
                MenuCommandStore.clearScript(name)
            }
        } catch (_: Exception) { false }
    }

    fun saveEdited(ctx: Context, name: String, body: String): Boolean {
        MenuCommandStore.clearScript(name)
        return install(ctx, name, body)
    }

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
                prefetchResources(ctx, script)
                UHLog.d("userscript", "registered ${script.name} sysjs=${script.usesSystemjs} grants=${script.grants.size}")
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
            view.evaluateJavascript(wrapped, null)
            UHLog.d("userscript", "injected ${script.name} (${script.runAt}) into $url")
        }
    }

    private fun isBadUrl(url: String): Boolean {
        if (url.isBlank() || url == "about:blank") return true
        if (url.startsWith("chrome-error://")) return true
        if (url.startsWith("chrome://")) return true
        if (url.startsWith("data:")) return true
        if (url.startsWith("blob:")) return true
        if (url.startsWith("javascript:")) return true
        return false
    }

    private fun clearDocumentStart(view: WebView) {
        val prior = handlers.remove(view) ?: return
        for (h in prior) { try { h.remove() } catch (_: Exception) {} }
    }

    private fun loadAsset(ctx: Context, path: String): String {
        synchronized(hookCache) { hookCache[path]?.let { return it } }
        val body = try { ctx.assets.open(path).bufferedReader().use { it.readText() } } catch (_: Exception) { "" }
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
  window.__uh_xhr = window.__uh_xhr || {};
  window.__uh_vcl = window.__uh_vcl || {};
  window.__uh_menu = window.__uh_menu || {};

  window.__uh_gm = function(scriptName) {
    var sn = scriptName;
    var wrap = {
      GM_getValue: function(k, d) {
        var v = B.GM_getValueNs(sn, String(k));
        if (v == null || v === '') return d;
        try { return JSON.parse(v); } catch(e) { return v; }
      },
      GM_setValue: function(k, v) { B.GM_setValueNs(sn, String(k), JSON.stringify(v)); },
      GM_deleteValue: function(k) { B.GM_deleteValueNs(sn, String(k)); },
      GM_listValues: function() { return JSON.parse(B.GM_listValuesNs(sn)); },
      GM_addValueChangeListener: function(k, cb) {
        var id = B.GM_addValueChangeListenerNs(sn, String(k));
        window.__uh_vcl[id] = cb;
        return id;
      },
      GM_removeValueChangeListener: function(id) {
        delete window.__uh_vcl[id];
        B.GM_removeValueChangeListener(String(id));
      },
      GM_xmlhttpRequest: function(d) {
        var id = 'xhr_' + Math.random().toString(36).slice(2);
        window.__uh_xhr[id] = d;
        try {
          B.GM_xmlhttpRequest(
            sn, id,
            String(d.method || 'GET'),
            String(d.url),
            d.headers ? JSON.stringify(d.headers) : '',
            d.data != null ? String(d.data) : '',
            d.responseType ? String(d.responseType) : '',
            d.timeout ? String(d.timeout) : '0',
            d.user ? String(d.user) : '',
            d.password ? String(d.password) : ''
          );
        } catch(e) { if (d.onerror) try{d.onerror({error:String(e)})}catch(_){} }
        return { abort: function(){ B.GM_xhrAbort(id); } };
      },
      GM_download: function(d) {
        var url = typeof d === 'string' ? d : d.url;
        var name = typeof d === 'object' && d.name ? d.name : '';
        B.GM_download(sn, String(url), String(name));
      },
      GM_openInTab: function(url, opts) {
        var active = !(opts && opts.active === false);
        B.GM_openInTab(sn, String(url), active);
      },
      GM_setClipboard: function(t) { B.GM_setClipboard(String(t)); },
      GM_notification: function(d) {
        var title = typeof d === 'string' ? d : (d.title || '');
        var text = typeof d === 'object' ? (d.text || d.body || '') : '';
        B.GM_notification(sn, String(title), String(text));
      },
      GM_registerMenuCommand: function(name, fn) {
        var id = 'menu_' + Math.random().toString(36).slice(2);
        window.__uh_menu[id] = fn;
        B.GM_registerMenuCommand(sn, String(name), id, '');
        return id;
      },
      GM_unregisterMenuCommand: function(id) {
        delete window.__uh_menu[id];
        B.GM_unregisterMenuCommand(sn, String(id));
      },
      GM_addStyle: function(css) {
        var s = document.createElement('style');
        s.textContent = String(css);
        (document.head || document.documentElement).appendChild(s);
        return s;
      },
      GM_addElement: function(parent, tag, attrs) {
        var el = document.createElement(tag);
        if (attrs) for (var k in attrs) {
          if (k === 'textContent') el.textContent = attrs[k];
          else if (k === 'innerHTML') el.innerHTML = attrs[k];
          else el.setAttribute(k, attrs[k]);
        }
        var p = typeof parent === 'string' ? document.querySelector(parent) : parent;
        (p || document.body || document.documentElement).appendChild(el);
        return el;
      },
      GM_cookie: {
        list: function(details, cb) {
          var url = (details && details.url) || location.href;
          var raw = B.GM_cookieGet(url);
          var arr = [];
          try { arr = JSON.parse(raw); } catch(e) {}
          if (cb) cb(arr);
          return Promise.resolve(arr);
        },
        set: function(details, cb) {
          var url = (details && details.url) || location.href;
          B.GM_cookieSet(url, String(details.name), String(details.value));
          if (cb) cb();
          return Promise.resolve();
        },
        delete: function(details, cb) {
          var url = (details && details.url) || location.href;
          B.GM_cookieDelete(url, String(details.name));
          if (cb) cb();
          return Promise.resolve();
        }
      },
      GM_getResourceText: function(name) { return B.GM_getResourceText(sn, String(name)); },
      GM_getResourceURL: function(name) { return B.GM_getResourceURL(sn, String(name)); },
      GM_log: function(m) { B.GM_log(String(m)); }
    };
    // async GM.* namespace
    var async = {
      getValue: function(k, d) { return Promise.resolve(wrap.GM_getValue(k, d)); },
      setValue: function(k, v) { wrap.GM_setValue(k, v); return Promise.resolve(); },
      deleteValue: function(k) { wrap.GM_deleteValue(k); return Promise.resolve(); },
      listValues: function() { return Promise.resolve(wrap.GM_listValues()); },
      xmlHttpRequest: function(d) {
        return new Promise(function(resolve, reject) {
          var orig = Object.assign({}, d);
          orig.onload = function(r) { resolve(r); };
          orig.onerror = function(e) { reject(e); };
          wrap.GM_xmlhttpRequest(orig);
        });
      },
      addValueChangeListener: function(k, cb) { return wrap.GM_addValueChangeListener(k, cb); },
      removeValueChangeListener: function(id) { wrap.GM_removeValueChangeListener(id); },
      registerMenuCommand: function(n, f) { return wrap.GM_registerMenuCommand(n, f); },
      unregisterMenuCommand: function(id) { wrap.GM_unregisterMenuCommand(id); },
      setClipboard: function(t) { wrap.GM_setClipboard(t); return Promise.resolve(); },
      download: function(d) { wrap.GM_download(d); return Promise.resolve(); },
      openInTab: function(u, o) { wrap.GM_openInTab(u, o); return Promise.resolve(); },
      notification: function(d) { wrap.GM_notification(d); return Promise.resolve(); },
      cookie: wrap.GM_cookie,
      getResourceText: function(n) { return Promise.resolve(wrap.GM_getResourceText(n)); },
      getResourceURL: function(n) { return Promise.resolve(wrap.GM_getResourceURL(n)); },
      info: null // set per script
    };
    return { wrap: wrap, async: async };
  };

  window.__uh_xhr_done = function(id, status, responseText, responseHeaders, finalUrl) {
    var d = window.__uh_xhr[id];
    if (!d) return;
    delete window.__uh_xhr[id];
    var resp = responseText;
    if (d.responseType === 'json') try { resp = JSON.parse(responseText); } catch(e) {}
    if (d.onload) try { d.onload({
      readyState: 4, status: status, statusText: String(status),
      responseText: responseText, response: resp,
      responseHeaders: responseHeaders, finalUrl: finalUrl
    }); } catch(e) {}
  };

  window.__uh_xhr_error = function(id, error) {
    var d = window.__uh_xhr[id];
    if (!d) return;
    delete window.__uh_xhr[id];
    if (d.onerror) try { d.onerror({ error: error, readyState: 4, status: 0 }); } catch(e) {}
  };

  window.__uh_fire_vcl = function(id, key, oldV, newV, remote) {
    var cb = window.__uh_vcl[id];
    if (cb) try { cb(key, oldV, newV, remote); } catch(e) {}
  };

  window.__uh_runMenuCommand = function(id) {
    var fn = window.__uh_menu[id];
    if (fn) try { fn(); } catch(e) {}
  };
})();
"""

        private fun buildWrapper(ctx: Context, script: Script): String {
        val patsJson = script.patterns.joinToString(",") {
            "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
        val exclJson = script.excludes.joinToString(",") {
            "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
        val metaJson = buildScriptMetaJson(script)

        val preamble = """
(function(){try{
var u=location.href;
var pats=[$patsJson];var exs=[$exclJson];
function gm(p,u){var pi=0,ui=0,s=-1,m=0;while(ui<u.length){
if(pi<p.length&&p[pi]===u[ui]){pi++;ui++}
else if(pi<p.length&&p[pi]==='*'){s=pi++;m=ui}
else if(s>=0){pi=s+1;ui=++m}
else return false}
while(pi<p.length&&p[pi]==='*')pi++;return pi===p.length}
var ok=false;for(var i=0;i<pats.length;i++){try{if(gm(pats[i],u)){ok=true;break}}catch(e){}}
if(!ok)return;
for(var j=0;j<exs.length;j++){try{if(gm(exs[j],u))return}catch(e){}}

var __s = $metaJson;
var __ns = __s.name;
var __bundle = window.__uh_gm(__ns);
if (!__bundle) return;

var GM_getValue=__bundle.wrap.GM_getValue;
var GM_setValue=__bundle.wrap.GM_setValue;
var GM_deleteValue=__bundle.wrap.GM_deleteValue;
var GM_listValues=__bundle.wrap.GM_listValues;
var GM_addValueChangeListener=__bundle.wrap.GM_addValueChangeListener;
var GM_removeValueChangeListener=__bundle.wrap.GM_removeValueChangeListener;
var GM_xmlhttpRequest=__bundle.wrap.GM_xmlhttpRequest;
var GM_download=__bundle.wrap.GM_download;
var GM_openInTab=__bundle.wrap.GM_openInTab;
var GM_setClipboard=__bundle.wrap.GM_setClipboard;
var GM_notification=__bundle.wrap.GM_notification;
var GM_registerMenuCommand=__bundle.wrap.GM_registerMenuCommand;
var GM_unregisterMenuCommand=__bundle.wrap.GM_unregisterMenuCommand;
var GM_addStyle=__bundle.wrap.GM_addStyle;
var GM_addElement=__bundle.wrap.GM_addElement;
var GM_cookie=__bundle.wrap.GM_cookie;
var GM_getResourceText=__bundle.wrap.GM_getResourceText;
var GM_getResourceURL=__bundle.wrap.GM_getResourceURL;
var GM_log=__bundle.wrap.GM_log;

var unsafeWindow=window;
var GM_info={
  script: __s,
  scriptHandler: "Violentmonkey",
  version: "2.49.0",
  platform: { browserName: "Chrome", browserVersion: "153",
              os: "Android", osVersion: "10", arch: "arm64" }
};
var GM=__bundle.async;
try { GM.info = GM_info; } catch(e) {}

// expose on window for scripts using fully-qualified access
try {
  window.GM_getValue=GM_getValue; window.GM_setValue=GM_setValue;
  window.GM_deleteValue=GM_deleteValue; window.GM_listValues=GM_listValues;
  window.GM_addValueChangeListener=GM_addValueChangeListener;
  window.GM_removeValueChangeListener=GM_removeValueChangeListener;
  window.GM_xmlhttpRequest=GM_xmlhttpRequest; window.GM_download=GM_download;
  window.GM_openInTab=GM_openInTab; window.GM_setClipboard=GM_setClipboard;
  window.GM_notification=GM_notification;
  window.GM_registerMenuCommand=GM_registerMenuCommand;
  window.GM_unregisterMenuCommand=GM_unregisterMenuCommand;
  window.GM_addStyle=GM_addStyle; window.GM_addElement=GM_addElement;
  window.GM_cookie=GM_cookie;
  window.GM_getResourceText=GM_getResourceText;
  window.GM_getResourceURL=GM_getResourceURL;
  window.GM_log=GM_log;
  window.GM=GM; window.GM_info=GM_info; window.unsafeWindow=window;
} catch(e) {}

// intercept System assignment so we can track register calls
try {
  var __sys;
  Object.defineProperty(window, "System", {
    configurable: true,
    get: function(){ return __sys; },
    set: function(v){
      __sys = v;
      try {
        if (v && v.register && !v.__uh_patched) {
          v.__uh_patched = true;
          var __orig = v.register;
          v.register = function(name, deps, fn){
            if (typeof name !== "string") { fn = deps; deps = name;
              name = "__anon_" + Math.random().toString(36).slice(2); }
            try {
              window.__uh_sys_regs = window.__uh_sys_regs || {};
              window.__uh_sys_regs[name] = true;
              window.UH_BRIDGE.report(location.href, "sys_reg", name);
            } catch(e){}
            return __orig.call(this, name, deps, fn);
          };
          window.UH_BRIDGE.report(location.href, "sys_patched", "yes");
        }
      } catch(e){}
    }
  });
} catch(e){}

// env diagnostics
try {
  window.UH_BRIDGE.report(u, "wrap_enter",
    __ns + " top=" + (window.top === window.self) +
    " ready=" + document.readyState +
    " uaW=" + (typeof unsafeWindow));
} catch(e){}

try{
"""
        val postamble = """
}catch(e){
  try{window.UH_BRIDGE.report(u,"userscript_err",String((e&&e.message)||e));}catch(_){}
}
try {
  window.UH_BRIDGE.report(u, "wrap_body_done",
    __ns + " sys=" + (typeof System) +
    " regs=" + Object.keys(window.__uh_sys_regs||{}).length);
} catch(e){}

// auto-import fallback: if any modules were registered but the bundle
// didn't self-import within a tick, try importing each one
if (typeof System !== "undefined" && System.import) {
  setTimeout(function(){
    try {
      var names = Object.keys(window.__uh_sys_regs||{});
      for (var k=0; k<names.length; k++) {
        (function(n){
          try { System.import(n).catch(function(e){
            try{window.UH_BRIDGE.report(location.href,"sys_import_err_"+n,
              String((e&&e.message)||e));}catch(_){}
          }); } catch(e){}
        })(names[k]);
      }
    } catch(e){}
  }, 50);
}

}catch(e){
  try{window.UH_BRIDGE.report(location.href,"wrapper_err",
    String((e&&e.message)||e));}catch(_){}
}})();
"""

        val requirePrelude = buildRequirePrelude(script)
        return preamble + requirePrelude + "\n" + script.body + "\n" + postamble
    }

    // prefetch all @resource assets for a script (non-blocking)
    fun prefetchResources(ctx: Context, script: Script) {
        if (script.resources.isEmpty()) return
        Thread {
            for ((name, url) in script.resources) {
                try { ResourceStore.ensureFetched(ctx, script.name, name, url) } catch (_: Exception) {}
            }
        }.start()
    }

    private fun buildScriptMetaJson(script: Script): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"name\":").append(jsonStr(script.name)).append(",")
        sb.append("\"displayName\":").append(jsonStr(script.displayName)).append(",")
        sb.append("\"namespace\":").append(jsonStr(script.namespace)).append(",")
        sb.append("\"description\":").append(jsonStr(script.description)).append(",")
        sb.append("\"version\":").append(jsonStr(script.version)).append(",")
        sb.append("\"author\":").append(jsonStr(script.author)).append(",")
        sb.append("\"homepage\":").append(jsonStr(script.homepage)).append(",")
        sb.append("\"icon\":").append(jsonStr(script.icon)).append(",")
        sb.append("\"runAt\":").append(jsonStr(script.runAt)).append(",")
        sb.append("\"matches\":[").append(script.patterns.joinToString(",") { jsonStr(it) }).append("],")
        sb.append("\"grants\":[").append(script.grants.joinToString(",") { jsonStr(it) }).append("],")
        sb.append("\"connects\":[").append(script.connects.joinToString(",") { jsonStr(it) }).append("],")
        sb.append("\"resources\":{")
        var first = true
        for ((k, v) in script.resources) {
            if (!first) sb.append(",")
            first = false
            sb.append(jsonStr(k)).append(":").append(jsonStr(v))
        }
        sb.append("}}")
        return sb.toString()
    }

    private fun jsonStr(s: String): String {
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

    private fun buildRequirePrelude(script: Script): String {
        if (script.requires.isEmpty()) return ""
        val sb = StringBuilder()
        for (url in script.requires) {
            val safeUrl = url.replace("\\", "\\\\").replace("\"", "\\\"")
            sb.append("try{var __uh_req=new XMLHttpRequest();__uh_req.open('GET','")
            sb.append(safeUrl)
            sb.append("',false);__uh_req.send();if(__uh_req.responseText){(0,eval)(__uh_req.responseText);}}catch(e){}\n")
        }
        return sb.toString()
    }
        val patsJson = script.patterns.joinToString(",") {
            "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
        val exclJson = script.excludes.joinToString(",") {
            "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }

        val grantNames = listOf(
            "GM_getValue","GM_setValue","GM_deleteValue","GM_listValues",
            "GM_addValueChangeListener","GM_removeValueChangeListener",
            "GM_xmlhttpRequest","GM_download","GM_openInTab","GM_setClipboard",
            "GM_notification","GM_registerMenuCommand","GM_unregisterMenuCommand",
            "GM_addStyle","GM_addElement","GM_cookie",
            "GM_getResourceText","GM_getResourceURL","GM_log"
        ).joinToString(",") { "\"$it\"" }

        val scriptMetaJson = buildScriptMetaJson(script)

        val preamble = """
(function(){try{
var u=location.href;
var pats=[$patsJson];var exs=[$exclJson];
function gm(p,u){var pi=0,ui=0,s=-1,m=0;while(ui<u.length){
if(pi<p.length&&p[pi]===u[ui]){pi++;ui++}
else if(pi<p.length&&p[pi]==='*'){s=pi++;m=ui}
else if(s>=0){pi=s+1;ui=++m}
else return false}
while(pi<p.length&&p[pi]==='*')pi++;return pi===p.length}
var ok=false;for(var i=0;i<pats.length;i++){try{if(gm(pats[i],u)){ok=true;break}}catch(e){}}
if(!ok)return;
for(var j=0;j<exs.length;j++){try{if(gm(exs[j],u))return}catch(e){}}

var __s = $scriptMetaJson;
var __ns = __s.name;
var __bundle = window.__uh_gm(__ns);
var GM_getValue=__bundle.wrap.GM_getValue;
var GM_setValue=__bundle.wrap.GM_setValue;
var GM_deleteValue=__bundle.wrap.GM_deleteValue;
var GM_listValues=__bundle.wrap.GM_listValues;
var GM_addValueChangeListener=__bundle.wrap.GM_addValueChangeListener;
var GM_removeValueChangeListener=__bundle.wrap.GM_removeValueChangeListener;
var GM_xmlhttpRequest=__bundle.wrap.GM_xmlhttpRequest;
var GM_download=__bundle.wrap.GM_download;
var GM_openInTab=__bundle.wrap.GM_openInTab;
var GM_setClipboard=__bundle.wrap.GM_setClipboard;
var GM_notification=__bundle.wrap.GM_notification;
var GM_registerMenuCommand=__bundle.wrap.GM_registerMenuCommand;
var GM_unregisterMenuCommand=__bundle.wrap.GM_unregisterMenuCommand;
var GM_addStyle=__bundle.wrap.GM_addStyle;
var GM_addElement=__bundle.wrap.GM_addElement;
var GM_cookie=__bundle.wrap.GM_cookie;
var GM_getResourceText=__bundle.wrap.GM_getResourceText;
var GM_getResourceURL=__bundle.wrap.GM_getResourceURL;
var GM_log=__bundle.wrap.GM_log;

var unsafeWindow=window;
var GM_info={
  script: __s,
  scriptHandler: "Violentmonkey",
  version: "2.49.0",
  platform: { browserName: "Chrome", browserVersion: "153", os: "Android", osVersion: "10", arch: "arm64" }
};

window.GM_info=GM_info;
window.unsafeWindow=window;

// register-tracking for SystemJS auto-kick
if (window.System && window.System.register) {
  if (!window.__uh_sys_registered) window.__uh_sys_registered = {};
  if (!window.__uh_sys_patched) {
    window.__uh_sys_patched = true;
    var __orig = window.System.register;
    window.System.register = function(name, deps, fn) {
      if (typeof name !== 'string') { fn = deps; deps = name; name = '__anon_' + Date.now(); }
      window.__uh_sys_registered[name] = true;
      return __orig.call(this, name, deps, fn);
    };
  }
}

try{
"""
        val postamble = """
}catch(e){
  if(window.UH_BRIDGE&&window.UH_BRIDGE.report)window.UH_BRIDGE.report(u,'userscript_err',String((e&&e.message)||e));
}
// kick SystemJS modules that weren't auto-imported
if (window.System && window.System.import && window.__uh_sys_registered) {
  var keys = Object.keys(window.__uh_sys_registered);
  for (var k=0;k<keys.length;k++) {
    var name=keys[k];
    try { window.System.import(name).catch(function(e){
      if(window.UH_BRIDGE&&window.UH_BRIDGE.report)window.UH_BRIDGE.report(u,'system_import_err_'+name,String((e&&e.message)||e));
    }); } catch(e) {}
  }
}
}catch(e){
  if(window.UH_BRIDGE&&window.UH_BRIDGE.report)window.UH_BRIDGE.report(location.href,'wrapper_err',String((e&&e.message)||e));
}})();
"""

        val requirePrelude = buildRequirePrelude(script)
        return preamble + requirePrelude + "\n" + script.body + "\n" + postamble
    }

    private fun buildScriptMetaJson(script: Script): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"name\":").append(jsonStr(script.name)).append(",")
        sb.append("\"displayName\":").append(jsonStr(script.displayName)).append(",")
        sb.append("\"namespace\":").append(jsonStr(script.namespace)).append(",")
        sb.append("\"description\":").append(jsonStr(script.description)).append(",")
        sb.append("\"version\":").append(jsonStr(script.version)).append(",")
        sb.append("\"author\":").append(jsonStr(script.author)).append(",")
        sb.append("\"homepage\":").append(jsonStr(script.homepage)).append(",")
        sb.append("\"icon\":").append(jsonStr(script.icon)).append(",")
        sb.append("\"runAt\":").append(jsonStr(script.runAt)).append(",")
        sb.append("\"matches\":[").append(script.patterns.joinToString(",") { jsonStr(it) }).append("],")
        sb.append("\"grants\":[").append(script.grants.joinToString(",") { jsonStr(it) }).append("],")
        sb.append("\"connects\":[").append(script.connects.joinToString(",") { jsonStr(it) }).append("],")
        sb.append("\"resources\":{")
        var first = true
        for ((k, v) in script.resources) {
            if (!first) sb.append(",")
            first = false
            sb.append(jsonStr(k)).append(":").append(jsonStr(v))
        }
        sb.append("}")
        sb.append("}")
        return sb.toString()
    }

    private fun jsonStr(s: String): String {
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

    private fun buildRequirePrelude(script: Script): String {
        if (script.requires.isEmpty()) return ""
        val sb = StringBuilder()
        for (url in script.requires) {
            val safeUrl = url.replace("\\", "\\\\").replace("\"", "\\\"")
            sb.append("try{var __uh_req=new XMLHttpRequest();__uh_req.open('GET','")
            sb.append(safeUrl)
            sb.append("',false);__uh_req.send();if(__uh_req.responseText){(0,eval)(__uh_req.responseText);}}catch(e){}\n")
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
