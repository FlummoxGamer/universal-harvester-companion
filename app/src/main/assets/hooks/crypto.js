(function () {
  if (window.__uh_hook_crypto) return;
  window.__uh_hook_crypto = true;
  var B = window.UH_BRIDGE;
  if (!B) return;
  try {
    if (window.SubtleCrypto && SubtleCrypto.prototype) {
      var SP = SubtleCrypto.prototype;
      ['encrypt', 'decrypt', 'sign', 'verify', 'digest', 'deriveBits', 'deriveKey']
        .forEach(function (fn) {
          var orig = SP[fn];
          if (!orig) return;
          SP[fn] = function () {
            try {
              var alg = arguments[0];
              var name = (alg && alg.name) ? alg.name : String(alg);
              B.captureCrypto(name, '');
            } catch (e) {}
            return orig.apply(this, arguments);
          };
        });
    }
  } catch (e) {}
})();
