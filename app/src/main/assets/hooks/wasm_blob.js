(function () {
  if (window.__uh_hook_wasm_blob) return;
  window.__uh_hook_wasm_blob = true;
  var B = window.UH_BRIDGE;
  if (!B) return;
  try {
    var orig = WebAssembly.instantiate;
    WebAssembly.instantiate = function (src, imports) {
      try {
        var buf = (src instanceof ArrayBuffer) ? src :
                  (src && src.buffer) ? src.buffer : null;
        if (buf) {
          var u8 = new Uint8Array(buf);
          var s = '';
          for (var i = 0; i < u8.length; i++) s += String.fromCharCode(u8[i]);
          B.captureWasm(btoa(s));
        }
      } catch (e) { B.report(location.href, 'wasm_err', String(e)); }
      return orig.apply(this, arguments);
    };
  } catch (e) {}
})();
