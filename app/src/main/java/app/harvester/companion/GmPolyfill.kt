package app.harvester.companion

object GmPolyfill {
    fun build(): String = """
(function () {
    if (window.__uh_gm_installed) return;
    window.__uh_gm_installed = true;

    var B = window.UH_GMBridge;
    if (!B) return;

    var store = {};
    try { var raw = B.listValues(); if (raw) store = JSON.parse(raw); } catch (e) {}

    window.GM_getValue = function (k, d) {
        return Object.prototype.hasOwnProperty.call(store, k) ? store[k] : d;
    };
    window.GM_setValue = function (k, v) {
        var s = (typeof v === 'string') ? v : JSON.stringify(v);
        store[k] = s;
        try { B.setValue(k, s); } catch (e) {}
    };
    window.GM_deleteValue = function (k) {
        delete store[k];
        try { B.deleteValue(k); } catch (e) {}
    };
    window.GM_listValues = function () { return Object.keys(store); };
    window.GM_log = function (m) { try { B.log(String(m)); } catch (e) {} };
    window.GM_addStyle = function (css) {
        var el = document.createElement('style');
        el.textContent = css;
        (document.head || document.documentElement).appendChild(el);
        return el;
    };
    window.GM_registerMenuCommand = function () {};
    window.GM_notification = function () {};
    window.GM_openInTab = function (u) { window.open(u, '_blank'); };
    window.GM_info = { script: { name: 'Universal Harvester', version: 'native' } };
    window.unsafeWindow = window;

    var xhrCbs = {};
    var xhrSeq = 0;
    window.__uh_gm_xhr_done = function (json) {
        try {
            var data = JSON.parse(json);
            var cb = xhrCbs[data.requestId];
            if (cb) { delete xhrCbs[data.requestId]; try { cb(data); } catch (e) {} }
        } catch (e) {}
    };
    window.GM_xmlhttpRequest = function (opts) {
        var id = 'x' + (++xhrSeq);
        if (opts.onload) xhrCbs[id] = opts.onload;
        var h = opts.headers || {};
        try { B.xhr(id, opts.method || 'GET', opts.url, JSON.stringify(h), opts.data || null); } catch (e) {}
    };

    var origCreate = URL.createObjectURL;
    var blobMap = {};
    URL.createObjectURL = function (obj) {
        var u = origCreate.call(URL, obj);
        try { if (obj instanceof Blob) blobMap[u] = obj; } catch (e) {}
        return u;
    };
    document.addEventListener('click', function (e) {
        try {
            var t = e.target;
            var a = (t && t.closest) ? t.closest('a[download]') : null;
            if (!a) return;
            var href = a.href || '';
            if (href.indexOf('blob:') !== 0) return;
            var blob = blobMap[href];
            if (!blob) return;
            var name = a.getAttribute('download') || 'capture.json';
            var r = new FileReader();
            r.onload = function () {
                try { B.saveBlob(name, r.result); } catch (err) {}
            };
            r.readAsText(blob);
        } catch (err) {}
    }, true);
})();
""".trimIndent()
}
