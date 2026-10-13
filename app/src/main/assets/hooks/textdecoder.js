(function () {
  if (window.__uh_hook_textdecoder) return;
  window.__uh_hook_textdecoder = true;
  var B = window.UH_BRIDGE;
  if (!B) return;
  try {
    if (window.TextDecoder && TextDecoder.prototype) {
      var TDP = TextDecoder.prototype;
      var orig = TDP.decode;
      if (orig) {
        TDP.decode = function () {
          var r = orig.apply(this, arguments);
          try { B.captureTextDecoder(String(r)); } catch (e) {}
          return r;
        };
      }
    }
  } catch (e) {}
})();
