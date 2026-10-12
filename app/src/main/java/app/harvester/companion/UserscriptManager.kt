package app.harvester.companion

import android.content.Context
import android.webkit.WebView
import java.io.File

object UserscriptManager {

    private const val BOOTSTRAP = """
(function() {
  if (window.__uh_bootstrapped) return;
  window.__uh_bootstrapped = true;

  var B = window.UH_BRIDGE;
  if (!B) return;

  // WebAssembly.instantiate / compile
  try {
    var iOrig = WebAssembly.instantiate;
    WebAssembly.instantiate = function(src, imports) {
      try {
        var buf = (src instanceof ArrayBuffer) ? src :
                  (src && src.buffer) ? src.buffer : null;
        if (buf) {
          var u8 = new Uint8Array(buf);
          var s = '';
          for (var i = 0; i < u8.length; i++) s += String.fromCharCode(u8[i]);
          B.capture('wasm', btoa(s));
        }
      } catch(e) { B.report(location.href, 'wasm_err', String(e)); }
      return iOrig.apply(this, arguments);
    };
  } catch(e) {}

  // crypto.subtle
  try {
    if (window.crypto && window.crypto.subtle) {
      ['encrypt','decrypt','sign','verify','digest','deriveBits','deriveKey'].forEach(function(fn) {
        var o = crypto.subtle[fn];
        if (!o) return;
        crypto.subtle[fn] = function() {
          try {
            var alg = arguments[0];
            var name = (alg && alg.name) ? alg.name : String(alg);
            B.report(location.href, 'subtle_' + fn, name);
          } catch(e) {}
          return o.apply(this, arguments);
        };
      });
    }
  } catch(e) {}

  // atob
  try {
    var aOrig = window.atob;
    window.atob = function(s) {
      var r = aOrig(s);
      try { B.report(location.href, 'atob', String(r.length)); } catch(e) {}
      return r;
    };
  } catch(e) {}

  // TextDecoder
  try {
    var TDO = TextDecoder;
    window.TextDecoder = function() {
      var inst = new TDO(...arguments);
      var dOrig = inst.decode;
      inst.decode = function() {
        try { B.report(location.href, 'textdecode', ''); } catch(e) {}
        return dOrig.apply(this, arguments);
      };
      return inst;
    };
  } catch(e) {}
})();
"""

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

    fun injectOnPageStart(ctx: Context, view: WebView, url: String) {
        view.evaluateJavascript(BRIDGE_BOOT, null)
        view.evaluateJavascript(BOOTSTRAP, null)
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

    private fun matches(body: String, url: String): Boolean {
        val lines = body.lineSequence().take(40)
        for (line in lines) {
            val t = line.trim()
            if (t.startsWith("// @match") || t.startsWith("// @include")) {
                val pattern = t.substringAfter("match").substringAfter("include").trim()
                if (patternMatches(pattern, url)) return true
            }
        }
        return false
    }

    private fun patternMatches(pattern: String, url: String): Boolean {
        if (pattern.isBlank()) return false
        val rx = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
        return try { Regex(rx).matches(url) } catch (_: Exception) { false }
    }

    data class Script(val name: String, val body: String)
}
