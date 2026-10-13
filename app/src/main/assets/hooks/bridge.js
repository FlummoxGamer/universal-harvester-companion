(function () {
  if (window.__uh_bridge_ready) return;
  window.__uh_bridge_ready = true;
  var B = window.UH_BRIDGE;
  if (!B) return;
  window.GM_getValue = function (k, d) { return B.GM_getValue(k, d == null ? null : String(d)); };
  window.GM_setValue = function (k, v) { B.GM_setValue(k, String(v)); };
  window.GM_deleteValue = function (k) { B.GM_deleteValue(k); };
  window.GM_listValues = function () { return JSON.parse(B.GM_listValues()); };
  window.GM_log = function (m) { B.GM_log(String(m)); };
})();
