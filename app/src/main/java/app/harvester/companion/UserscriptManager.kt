package app.harvester.companion

import android.content.Context
import android.net.Uri
import org.mozilla.geckoview.GeckoSession
import java.io.File
import java.util.zip.ZipInputStream

object UserscriptManager {

    private var cachedScripts: List<Script>? = null
    private val wrapperCache = mutableMapOf<String, String>()

    fun invalidateCache() {
        cachedScripts = null
        synchronized(wrapperCache) { wrapperCache.clear() }
    }

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
        cachedScripts?.let { return it }
        val dir = File(ctx.filesDir, "scripts")
        val list = if (!dir.isDirectory) emptyList() else dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("js", true) }
            ?.mapNotNull { f ->
                try { parse(f.nameWithoutExtension, f.readText(), f.length()) } catch (_: Exception) { null }
            } ?: emptyList()
        cachedScripts = list
        return list
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
        invalidateCache()
        return try {
            val dir = File(ctx.filesDir, "scripts").apply { mkdirs() }
            val safe = name.replace(Regex("""[^a-zA-Z0-9_\-]"""), "_").take(80)
            File(dir, "$safe.js").writeText(body)
            try {
                val parsed = parse(name, body)
                cacheRequires(ctx, parsed)
            } catch (_: Exception) {}
            UserscriptStore.markDirty(ctx)
            true
        } catch (_: Exception) { false }
    }

    private fun requiresDir(ctx: Context, scriptName: String): File {
        val safe = scriptName.replace(Regex("""[^a-zA-Z0-9_\-]"""), "_").take(80)
        return File(ctx.filesDir, "requires/$safe").apply { mkdirs() }
    }

    private fun cacheRequires(ctx: Context, script: Script) {
        val dir = requiresDir(ctx, script.name)
        try { dir.listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
        var idx = 0
        for (url in script.requires) {
            val target = File(dir, "req_${idx.toString().padStart(3, '0')}.js")
            try {
                if (url.startsWith("data:")) {
                    val body = decodeDataUrl(url) ?: continue
                    target.writeText(body)
                } else {
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 20000
                    conn.readTimeout = 20000
                    conn.connect()
                    if (conn.responseCode in 200..299) {
                        conn.inputStream.use { input ->
                            target.outputStream().use { input.copyTo(it) }
                        }
                    }
                    conn.disconnect()
                }
                UHLog.d("userscript", "require cached ${script.name}[$idx] ${target.length()}B")
            } catch (e: Exception) {
                UHLog.throwable("userscript", "require fetch failed idx=$idx url=$url", e)
            }
            idx++
        }
    }

    private fun decodeDataUrl(url: String): String? {
        return try {
            val comma = url.indexOf(',')
            if (comma < 0) return null
            val meta = url.substring(5, comma)
            val data = url.substring(comma + 1)
            if (meta.contains("base64", ignoreCase = true)) {
                String(android.util.Base64.decode(data, android.util.Base64.DEFAULT), Charsets.UTF_8)
            } else {
                java.net.URLDecoder.decode(data, "UTF-8")
            }
        } catch (_: Exception) { null }
    }

    private fun loadCachedRequires(ctx: Context, script: Script): String {
        val dir = requiresDir(ctx, script.name)
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("req_") }
            ?.sortedBy { it.name } ?: return ""
        val sb = StringBuilder()
        for (f in files) {
            try {
                sb.append("\n/* uh-require: ").append(f.name).append(" */\n")
                sb.append(f.readText())
                sb.append("\n")
            } catch (_: Exception) {}
        }
        return sb.toString()
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
            val nn = n
            val bb = b
            if (nn != null && bb != null) {
                val parsed = parse(nn, bb)
                install(ctx, parsed.name, bb)
            } else false
        } catch (_: Exception) { false }
    }

    fun uninstall(ctx: Context, name: String): Boolean {
        invalidateCache()
        return try {
            File(File(ctx.filesDir, "scripts"), "$name.js").delete().also {
                UserscriptStore.forget(ctx, name)
                MenuCommandStore.clearScript(name)
            }
        } catch (_: Exception) { false }
    }

    fun saveEdited(ctx: Context, name: String, body: String): Boolean {
        invalidateCache()
        MenuCommandStore.clearScript(name)
        return install(ctx, name, body)
    }

    private fun getAllGmData(ctx: Context): String {
        val prefs = ctx.getSharedPreferences("uh_gm", Context.MODE_PRIVATE)
        val root = org.json.JSONObject()
        for ((k, v) in prefs.all) {
            val parts = k.split("::", limit = 2)
            if (parts.size == 2) {
                val script = parts[0]
                val key = parts[1]
                if (!root.has(script)) root.put(script, org.json.JSONObject())
                root.getJSONObject(script).put(key, v?.toString() ?: "")
            }
        }
        return root.toString()
    }

    fun buildInjectionScript(ctx: Context, url: String): String {
        if (isBadUrl(url)) return ""
        val sb = StringBuilder()
        
        sb.append("window.__uh_gm_data = ").append(getAllGmData(ctx)).append(";\n")
        sb.append(BRIDGE_BOOT).append("\n")
        
        val enabled = SessionRecorder.ALL_CATEGORIES.filter { SessionRecorder.isEnabled(it) }
        for (hook in enabled) {
            val body = loadHook(ctx, hook)
            if (body.isNotBlank()) sb.append(body).append("\n")
        }
        
        val scripts = loadScripts(ctx)
        for (script in scripts) {
            if (!UserscriptStore.isEnabled(ctx, script.name)) continue
            if (!matchesPatterns(script.patterns, url)) continue
            if (matchesPatterns(script.excludes, url)) continue
            val wrapped = buildWrapper(ctx, script)
            if (wrapped.isNotBlank()) {
                sb.append(wrapped).append("\n")
                UHLog.d("userscript", "queued ${script.name} (${script.runAt}) for $url")
            }
        }
        
        return sb.toString()
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

    private fun loadAsset(ctx: Context, path: String): String {
        synchronized(hookCache) { hookCache[path]?.let { return it } }
        val body = try { ctx.assets.open(path).bufferedReader().use { it.readText() } } catch (_: Exception) { "" }
        if (body.isNotBlank()) synchronized(hookCache) { hookCache[path] = body }
        return body
    }

    private fun loadHook(ctx: Context, name: String): String = loadAsset(ctx, "hooks/$name.js")

    fun prefetchResources(ctx: Context, script: Script) {
        if (script.resources.isEmpty()) return
        Thread {
            for ((name, url) in script.resources) {
                try { ResourceStore.ensureFetched(ctx, script.name, name, url) } catch (_: Exception) {}
            }
        }.start()
    }

    private const val BRIDGE_BOOT = """
(function(){
  if (window.__uh_bridge_ready) return;
  window.__uh_bridge_ready = true;

  window.unsafeWindow = window;
  window.__uh_xhr = window.__uh_xhr || {};
  window.__uh_vcl = window.__uh_vcl || {};
  window.__uh_menu = window.__uh_menu || {};

  if (!document.currentScript) {
    try {
      var dummyScript = document.createElement('script');
      dummyScript.src = window.location.href.split('#')[0].split('?')[0];
      Object.defineProperty(document, 'currentScript', {
        get: function() {
          if (document.readyState === 'loading') return dummyScript;
          return null;
        },
        configurable: true
      });
    } catch(e) { }
  }

  window.__uh_gm = function(scriptName) {
    var sn = scriptName;
    var wrap = {
      GM_getValue: function(k, d) {
        try {
          var store = window.__uh_gm_data[sn] || {};
          var v = store[String(k)];
          if (v == null || v === '') return d;
          return JSON.parse(v);
        } catch(e) { return d; }
      },
      GM_setValue: function(k, v) { 
        window.postMessage({ type: 'UH_NATIVE_CALL', payload: { type: 'GM_setValue', script: sn, key: String(k), value: JSON.stringify(v) } }, '*'); 
      },
      GM_deleteValue: function(k) { 
        window.postMessage({ type: 'UH_NATIVE_CALL', payload: { type: 'GM_deleteValue', script: sn, key: String(k) } }, '*'); 
      },
      GM_listValues: function() { 
        try { return Object.keys(window.__uh_gm_data[sn] || {}); } catch(e) { return []; }
      },
      GM_addValueChangeListener: function(k, cb) {
        var id = 'vcl_' + Math.random().toString(36).slice(2);
        window.__uh_vcl[id] = cb;
        return id;
      },
      GM_removeValueChangeListener: function(id) {
        delete window.__uh_vcl[id];
      },
      GM_xmlhttpRequest: function(d) {
        var id = 'xhr_' + Math.random().toString(36).slice(2);
        window.__uh_xhr[id] = d;
        window.postMessage({ type: 'UH_NATIVE_CALL', payload: { 
          type: 'GM_xmlhttpRequest', id: id, method: d.method || 'GET', url: d.url, 
          headers: d.headers ? JSON.stringify(d.headers) : '', data: d.data != null ? String(d.data) : '',
          timeout: d.timeout || 0 
        }}, '*');
        return { abort: function(){} };
      },
      GM_download: function(d) {
        if (typeof d === 'string') d = { url: d };
        var url = d.url;
        var name = d.name || ('download_' + Date.now() + '.bin');
        if (url.startsWith('blob:')) {
            var blob = window.__uh_blobs && window.__uh_blobs[url];
            if (blob) {
                var reader = new FileReader();
                reader.onload = function() {
                    var b64 = reader.result.split(',')[1];
                    window.postMessage({ type: 'UH_NATIVE_CALL', payload: { type: 'SAVE_BLOB', filename: name, mime: blob.type || 'application/octet-stream', base64: b64 } }, '*');
                };
                reader.readAsDataURL(blob);
                return;
            }
        }
        if (url.startsWith('data:')) {
            var parts = url.split(',');
            var meta = parts[0];
            var data = parts[1];
            var mimeMatch = meta.match(/data:([^;]+)/);
            var mime = mimeMatch ? mimeMatch[1] : 'application/octet-stream';
            var isBase64 = meta.indexOf('base64') !== -1;
            window.postMessage({ type: 'UH_NATIVE_CALL', payload: { type: 'SAVE_BLOB', filename: name, mime: mime, base64: isBase64 ? data : btoa(data) } }, '*');
            return;
        }
        window.postMessage({ type: 'UH_NATIVE_CALL', payload: { type: 'GM_download', script: sn, url: url, name: name } }, '*');
      },
      GM_openInTab: function(url, opts) {
        window.postMessage({ type: 'UH_NATIVE_CALL', payload: { type: 'GM_openInTab', script: sn, url: String(url) } }, '*');
      },
      GM_setClipboard: function(t) { 
        window.postMessage({ type: 'UH_NATIVE_CALL', payload: { type: 'GM_setClipboard', text: String(t) } }, '*'); 
      },
      GM_notification: function(d) {
        var title = typeof d === 'string' ? d : (d.title || '');
        var text = typeof d === 'object' ? (d.text || d.body || '') : '';
        window.postMessage({ type: 'UH_NATIVE_CALL', payload: { type: 'GM_notification', script: sn, title: String(title), text: String(text) } }, '*');
      },
      GM_registerMenuCommand: function(name, fn) {
        var id = 'menu_' + Math.random().toString(36).slice(2);
        window.__uh_menu[id] = fn;
        window.postMessage({ type: 'UH_NATIVE_CALL', payload: { type: 'GM_registerMenuCommand', script: sn, name: String(name), id: id } }, '*');
        return id;
      },
      GM_unregisterMenuCommand: function(id) {
        delete window.__uh_menu[id];
        window.postMessage({ type: 'UH_NATIVE_CALL', payload: { type: 'GM_unregisterMenuCommand', script: sn, id: String(id) } }, '*');
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
        list: function(details, cb) { if(cb) cb([]); return Promise.resolve([]); },
        set: function(details, cb) { if(cb) cb(); return Promise.resolve(); },
        delete: function(details, cb) { if(cb) cb(); return Promise.resolve(); }
      },
      GM_getResourceText: function(name) { return null; },
      GM_getResourceURL: function(name) { return null; },
      GM_log: function(m) { window.postMessage({ type: 'UH_NATIVE_CALL', payload: { type: 'GM_log', message: String(m) } }, '*'); }
    };
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
      info: null
    };
    return { wrap: wrap, async: async };
  };

window.__uh_xhr_progress = function(id, loaded, total) {
  var d = window.__uh_xhr[id];
  if (!d) return;
  if (d.onprogress) try { d.onprogress({ readyState: 3, loaded: loaded, total: total, lengthComputable: total > 0 }); } catch(e) {}
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

  if (window.System && !window.System.__uh_resolve_patched) {
    window.System.__uh_resolve_patched = true;
    var origResolve = window.System.resolve;
    if (origResolve) {
        window.System.resolve = function(id, parentUrl) {
            if (id && (id.startsWith('./') || id.startsWith('../'))) {
                var regs = window.__uh_sys_regs || {};
                if (regs[id]) {
                    return 'uh-blob://' + id;
                }
            }
            try { return origResolve.apply(this, arguments); } 
            catch(e) { return id; }
        };
    }
}

if (!window.__uh_blob_hooked) {
    window.__uh_blob_hooked = true;
      window.__uh_blobs = {};
      var origCreate = URL.createObjectURL;
      URL.createObjectURL = function(obj) {
          var url = origCreate.call(URL, obj);
          if (obj instanceof Blob) window.__uh_blobs[url] = obj;
          return url;
      };
      window.__uh_force_download = function(url, name, mime) {
          var b64 = null;
          if (url.startsWith('blob:')) {
              var blob = window.__uh_blobs && window.__uh_blobs[url];
              if (blob) {
                  var reader = new FileReader();
                  reader.onload = function() {
                      window.postMessage({ type: 'UH_NATIVE_CALL', payload: { type: 'SAVE_BLOB', filename: name, mime: blob.type || 'application/octet-stream', base64: reader.result.split(',')[1] } }, '*');
                  };
                  reader.readAsDataURL(blob);
                  return;
              }
          }
          if (url.startsWith('data:')) {
              var parts = url.split(',');
              var meta = parts[0];
              var data = parts[1];
              var mimeMatch = meta.match(/data:([^;]+)/);
              var mime = mimeMatch ? mimeMatch[1] : 'application/octet-stream';
              var isBase64 = meta.indexOf('base64') !== -1;
              window.postMessage({ type: 'UH_NATIVE_CALL', payload: { type: 'SAVE_BLOB', filename: name, mime: mime, base64: isBase64 ? data : btoa(data) } }, '*');
              return;
          }
          window.postMessage({ type: 'UH_NATIVE_CALL', payload: { type: 'GM_download', script: 'system', url: url, name: name } }, '*');
      };
      var origRevoke = URL.revokeObjectURL;
      URL.revokeObjectURL = function(url) {
          delete window.__uh_blobs[url];
          return origRevoke.call(URL, url);
      };
      var origClick = HTMLAnchorElement.prototype.click;
      HTMLAnchorElement.prototype.click = function() {
          var href = this.href;
          var name = this.download || ('download_' + Date.now() + '.bin');
          if (href && href.startsWith('blob:')) {
              var blob = window.__uh_blobs[href];
              if (blob) {
                  var reader = new FileReader();
                  reader.onload = function() {
                      var b64 = reader.result.split(',')[1];
                      window.postMessage({ type: 'UH_NATIVE_CALL', payload: { type: 'SAVE_BLOB', filename: name, mime: blob.type || 'application/octet-stream', base64: b64 } }, '*');
                  };
                  reader.readAsDataURL(blob);
                  return;
              }
          }
          if (href && href.startsWith('data:')) {
              var parts = href.split(',');
              var meta = parts[0];
              var data = parts[1];
              var mimeMatch = meta.match(/data:([^;]+)/);
              var mime = mimeMatch ? mimeMatch[1] : 'application/octet-stream';
              var isBase64 = meta.indexOf('base64') !== -1;
              window.postMessage({ type: 'UH_NATIVE_CALL', payload: { type: 'SAVE_BLOB', filename: name, mime: mime, base64: isBase64 ? data : btoa(data) } }, '*');
              return;
          }
          return origClick.apply(this, arguments);
      };
  }

  var __lastOverlayState = false;
  document.addEventListener('touchstart', function(e) {
      var isFab = false;
      var path = e.composedPath ? e.composedPath() : [];
      for (var i=0; i<path.length; i++) {
          var el = path[i];
          if (el.classList && (el.classList.contains('uh-fab') || el.classList.contains('fab') || el.id === 'uh-fab' || el.getAttribute('data-uh-fab') !== null)) {
              isFab = true; break;
          }
      }
      if (isFab !== __lastOverlayState) {
          __lastOverlayState = isFab;
          window.postMessage({ type: 'UH_NATIVE_CALL', payload: { type: 'OVERLAY_STATE', active: isFab } }, '*');
      }
  }, {passive: true, capture: true});
  document.addEventListener('touchend', function() {
      if (__lastOverlayState) { __lastOverlayState = false; window.postMessage({ type: 'UH_NATIVE_CALL', payload: { type: 'OVERLAY_STATE', active: false } }, '*'); }
  }, {passive: true, capture: true});
  document.addEventListener('touchcancel', function() {
      if (__lastOverlayState) { __lastOverlayState = false; window.postMessage({ type: 'UH_NATIVE_CALL', payload: { type: 'OVERLAY_STATE', active: false } }, '*'); }
  }, {passive: true, capture: true});
})();
"""

    private fun buildWrapper(ctx: Context, script: Script): String {
        synchronized(wrapperCache) { wrapperCache[script.name]?.let { return it } }
        val patsJson = script.patterns.joinToString(",") {
            "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
        val exclJson = script.excludes.joinToString(",") {
            "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
        val metaJson = buildScriptMetaJson(script)

        val preamble = buildString {
            append("(function(){try{\n")
            append("var u=location.href;\n")
            append("var pats=[").append(patsJson).append("];var exs=[").append(exclJson).append("];\n")
            append("function gm(p,u){var pi=0,ui=0,s=-1,m=0;while(ui<u.length){\n")
            append("if(pi<p.length&&p[pi]===u[ui]){pi++;ui++}\n")
            append("else if(pi<p.length&&p[pi]==='*'){s=pi++;m=ui}\n")
            append("else if(s>=0){pi=s+1;ui=++m}\n")
            append("else return false}\n")
            append("while(pi<p.length&&p[pi]==='*')pi++;return pi===p.length}\n")
            append("var ok=false;for(var i=0;i<pats.length;i++){try{if(gm(pats[i],u)){ok=true;break}}catch(e){}}\n")
            append("if(!ok)return;\n")
            append("for(var j=0;j<exs.length;j++){try{if(gm(exs[j],u))return}catch(e){}}\n")
            append("var __s=").append(metaJson).append(";\n")
            append("var __ns=__s.name;\n")
            append("var __bundle=window.__uh_gm(__ns);\n")
            append("if(!__bundle)return;\n")
            append("var GM_getValue=__bundle.wrap.GM_getValue;\n")
            append("var GM_setValue=__bundle.wrap.GM_setValue;\n")
            append("var GM_deleteValue=__bundle.wrap.GM_deleteValue;\n")
            append("var GM_listValues=__bundle.wrap.GM_listValues;\n")
            append("var GM_addValueChangeListener=__bundle.wrap.GM_addValueChangeListener;\n")
            append("var GM_removeValueChangeListener=__bundle.wrap.GM_removeValueChangeListener;\n")
            append("var GM_xmlhttpRequest=__bundle.wrap.GM_xmlhttpRequest;\n")
            append("var GM_download=__bundle.wrap.GM_download;\n")
            append("var GM_openInTab=__bundle.wrap.GM_openInTab;\n")
            append("var GM_setClipboard=__bundle.wrap.GM_setClipboard;\n")
            append("var GM_notification=__bundle.wrap.GM_notification;\n")
            append("var GM_registerMenuCommand=__bundle.wrap.GM_registerMenuCommand;\n")
            append("var GM_unregisterMenuCommand=__bundle.wrap.GM_unregisterMenuCommand;\n")
            append("var GM_addStyle=__bundle.wrap.GM_addStyle;\n")
            append("var GM_addElement=__bundle.wrap.GM_addElement;\n")
            append("var GM_cookie=__bundle.wrap.GM_cookie;\n")
            append("var GM_getResourceText=__bundle.wrap.GM_getResourceText;\n")
            append("var GM_getResourceURL=__bundle.wrap.GM_getResourceURL;\n")
            append("var GM_log=__bundle.wrap.GM_log;\n")
            append("var unsafeWindow=window;\n")
            append("var GM_info={script:__s,scriptHandler:'Violentmonkey',version:'2.49.0',platform:{browserName:'Chrome',browserVersion:'153',os:'Android',osVersion:'10',arch:'arm64'}};\n")
            append("var GM=__bundle.async;try{GM.info=GM_info;}catch(e){}\n")
            append("try{\n")
            append("window.GM_getValue=GM_getValue;window.GM_setValue=GM_setValue;\n")
            append("window.GM_deleteValue=GM_deleteValue;window.GM_listValues=GM_listValues;\n")
            append("window.GM_addValueChangeListener=GM_addValueChangeListener;\n")
            append("window.GM_removeValueChangeListener=GM_removeValueChangeListener;\n")
            append("window.GM_xmlhttpRequest=GM_xmlhttpRequest;window.GM_download=GM_download;\n")
            append("window.GM_openInTab=GM_openInTab;window.GM_setClipboard=GM_setClipboard;\n")
            append("window.GM_notification=GM_notification;\n")
            append("window.GM_registerMenuCommand=GM_registerMenuCommand;\n")
            append("window.GM_unregisterMenuCommand=GM_unregisterMenuCommand;\n")
            append("window.GM_addStyle=GM_addStyle;window.GM_addElement=GM_addElement;\n")
            append("window.GM_cookie=GM_cookie;\n")
            append("window.GM_getResourceText=GM_getResourceText;\n")
            append("window.GM_getResourceURL=GM_getResourceURL;\n")
            append("window.GM_log=GM_log;window.GM=GM;window.GM_info=GM_info;window.unsafeWindow=window;\n")
            append("}catch(e){}\n")
            append("try{\n")
            append("var __sys;\n")
            append("Object.defineProperty(window,'System',{configurable:true,get:function(){return __sys;},set:function(v){\n")
            append("__sys=v;\n")
            append("try{\n")
            append("if(v&&v.register&&!v.__uh_patched){\n")
            append("v.__uh_patched=true;\n")
            append("var __orig=v.register;\n")
            append("v.register=function(name,deps,fn){\n")
            append("if(typeof name!=='string'){fn=deps;deps=name;name='__anon_'+Math.random().toString(36).slice(2);}\n")
            append("try{window.__uh_sys_regs=window.__uh_sys_regs||{};window.__uh_sys_regs[name]=true;}catch(e){}\n")
            append("return __orig.call(this,name,deps,fn);\n")
            append("};\n")
            append("}\n")
            append("}catch(e){}\n")
            append("}});\n")
            append("}catch(e){}\n")
            append("try{\n")
        }

        val postamble = buildString {
            append("}catch(e){try{window.postMessage({type:'UH_NATIVE_CALL',payload:{type:'REPORT',url:u,kind:'userscript_err',detail:String((e&&e.message)||e)}},'*');}catch(_){}}\n")
            append("if(typeof System!=='undefined'&&System.import){\n")
            append("setTimeout(function(){try{var names=Object.keys(window.__uh_sys_regs||{});\n")
            append("for(var k=0;k<names.length;k++){(function(n){try{System.import(n).catch(function(e){try{window.postMessage({type:'UH_NATIVE_CALL',payload:{type:'REPORT',url:location.href,kind:'sys_import_err_'+n,detail:String((e&&e.message)||e)}},'*');}catch(_){}});}catch(e){}})(names[k]);}\n")
            append("}catch(e){}},50);\n")
            append("}\n")
            append("}catch(e){try{window.postMessage({type:'UH_NATIVE_CALL',payload:{type:'REPORT',url:location.href,kind:'wrapper_err',detail:String((e&&e.message)||e)}},'*');}catch(_){}}\n")
            append("})();\n")
        }

        val requirePrelude = loadCachedRequires(ctx, script)
        val result = preamble + "\n" + requirePrelude + "\n" + script.body + "\n" + postamble
        synchronized(wrapperCache) { wrapperCache[script.name] = result }
        return result
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
