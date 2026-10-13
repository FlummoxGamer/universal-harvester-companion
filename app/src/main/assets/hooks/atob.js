(function () {
  if (window.__uh_hook_atob) return;
  window.__uh_hook_atob = true;
  var B = window.UH_BRIDGE;
  if (!B) return;
  try {
    var orig = window.atob;
    window.atob = function (s) {
      var r = orig(s);
      try { B.captureAtob(r); } catch (e) {}
      return r;
    };
  } catch (e) {}
})();
