(function () {
    if (window.__uh_bridge_inject_ready) return;
    window.__uh_bridge_inject_ready = true;

    // -------------------------------------------------------------------------
    // 1. On-Demand Network Capture (ONLY hooked when DevTools or Capture starts
    //    so window.fetch / XMLHttpRequest stay pristine [native code] for
    //    Cloudflare Turnstile, anti-bot scripts, and streaming players)
    // -------------------------------------------------------------------------
    const MAX_NET_ENTRIES = 200;
    const earlyNetLog = (window.__uhEarlyNetLog = window.__uhEarlyNetLog || []);
    const netListeners = (window.__uhNetListeners = window.__uhNetListeners || []);

    function notifyNetListeners() {
        for (let i = 0; i < netListeners.length; i++) {
            try { netListeners[i](); } catch (_e) {}
        }
    }

    function installEarlyNetworkCapture() {
        if (window.__uhEarlyNetHooked) return;
        window.__uhEarlyNetHooked = true;

        const origFetch = window.fetch;
        if (typeof origFetch === "function") {
            window.fetch = function (input, init) {
                const start = performance.now();
                let reqUrl = "";
                try {
                    reqUrl = typeof input === "string"
                        ? input
                        : (input && input.url ? input.url : String(input));
                } catch (_e) {
                    reqUrl = "";
                }
                const method = (((init && init.method) || (input && input.method) || "GET") + "").toUpperCase();
                const reqHeaders = {};
                try {
                    const hdrs = (init && init.headers) || (input && input.headers);
                    if (hdrs instanceof Headers) {
                        hdrs.forEach((v, k) => { reqHeaders[k] = v; });
                    } else if (Array.isArray(hdrs)) {
                        hdrs.forEach((pair) => { if (pair && pair.length >= 2) reqHeaders[pair[0]] = pair[1]; });
                    } else if (hdrs && typeof hdrs === "object") {
                        Object.assign(reqHeaders, hdrs);
                    }
                } catch (_e) {}
                const reqBody = init && typeof init.body === "string" ? init.body.slice(0, 4000) : "";

                const entry = {
                    id: Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
                    kind: "fetch",
                    method: method,
                    url: reqUrl,
                    status: 0,
                    statusText: "Pending",
                    durationMs: 0,
                    sizeBytes: 0,
                    contentType: "",
                    reqHeaders: reqHeaders,
                    reqBody: reqBody,
                    resHeaders: {},
                    resBody: "",
                    startedAt: new Date().toLocaleTimeString(),
                    error: null
                };
                earlyNetLog.unshift(entry);
                if (earlyNetLog.length > MAX_NET_ENTRIES) earlyNetLog.pop();
                notifyNetListeners();

                return origFetch.apply(this, arguments).then((resp) => {
                    entry.status = resp.status;
                    entry.statusText = resp.statusText || String(resp.status);
                    entry.durationMs = Math.round(performance.now() - start);
                    try {
                        entry.contentType = resp.headers.get("content-type") || "";
                        resp.headers.forEach((v, k) => { entry.resHeaders[k] = v; });
                        const ct = entry.contentType.toLowerCase();
                        const isTextLike = !ct || ct.includes("json") || ct.includes("text") || ct.includes("xml") || ct.includes("javascript") || ct.includes("urlencoded");
                        if (isTextLike && resp.clone) {
                            resp.clone().text().then((txt) => {
                                entry.sizeBytes = txt.length;
                                entry.resBody = txt.slice(0, 12000);
                                notifyNetListeners();
                            }).catch(() => {});
                        }
                    } catch (_e) {}
                    notifyNetListeners();
                    return resp;
                }).catch((err) => {
                    entry.status = -1;
                    entry.statusText = "ERR";
                    entry.durationMs = Math.round(performance.now() - start);
                    entry.error = String(err && err.message ? err.message : err);
                    notifyNetListeners();
                    throw err;
                });
            };
        }

        if (typeof XMLHttpRequest !== "undefined" && XMLHttpRequest.prototype) {
            const origOpen = XMLHttpRequest.prototype.open;
            const origSend = XMLHttpRequest.prototype.send;
            const origSetHeader = XMLHttpRequest.prototype.setRequestHeader;
            XMLHttpRequest.prototype.open = function (method, url) {
                this.__uhMeta = {
                    method: String(method || "GET").toUpperCase(),
                    url: String(url || ""),
                    reqHeaders: {}
                };
                return origOpen.apply(this, arguments);
            };
            XMLHttpRequest.prototype.setRequestHeader = function (k, v) {
                if (this.__uhMeta) this.__uhMeta.reqHeaders[k] = v;
                return origSetHeader.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function (body) {
                const meta = this.__uhMeta || { method: "GET", url: "", reqHeaders: {} };
                const start = performance.now();
                const entry = {
                    id: Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
                    kind: "xhr",
                    method: meta.method,
                    url: meta.url,
                    status: 0,
                    statusText: "Pending",
                    durationMs: 0,
                    sizeBytes: 0,
                    contentType: "",
                    reqHeaders: Object.assign({}, meta.reqHeaders),
                    reqBody: typeof body === "string" ? body.slice(0, 4000) : "",
                    resHeaders: {},
                    resBody: "",
                    startedAt: new Date().toLocaleTimeString(),
                    error: null
                };
                earlyNetLog.unshift(entry);
                if (earlyNetLog.length > MAX_NET_ENTRIES) earlyNetLog.pop();
                notifyNetListeners();

                this.addEventListener("loadend", () => {
                    entry.status = this.status;
                    entry.statusText = this.statusText || String(this.status);
                    entry.durationMs = Math.round(performance.now() - start);
                    try {
                        const rawHdrs = this.getAllResponseHeaders() || "";
                        rawHdrs.trim().split(/[\r\n]+/).forEach((line) => {
                            const idx = line.indexOf(":");
                            if (idx > 0) entry.resHeaders[line.slice(0, idx).trim().toLowerCase()] = line.slice(idx + 1).trim();
                        });
                        entry.contentType = entry.resHeaders["content-type"] || "";
                        if (this.responseType === "" || this.responseType === "text") {
                            const txt = String(this.responseText || "");
                            entry.sizeBytes = txt.length;
                            entry.resBody = txt.slice(0, 12000);
                        }
                    } catch (_e) {}
                    notifyNetListeners();
                });
                return origSend.apply(this, arguments);
            };
        }
    }

    // Expose opt-in hook activator for DevTools / Capture only
    window.__uhEnableNetworkCapture = installEarlyNetworkCapture;

    // -------------------------------------------------------------------------
    // 2. Userscript Safe Executor & Global Zoom
    // -------------------------------------------------------------------------
    function execCodeSafe(code) {
        if (!code) return;
        let blocked = false;
        const marker = "__uh_exec_" + Math.random().toString(36).slice(2);
        const onCsp = (e) => {
            if (e.violatedDirective && e.violatedDirective.indexOf("script-src") !== -1) {
                blocked = true;
            }
        };
        document.addEventListener("securitypolicyviolation", onCsp);
        try {
            const s = document.createElement("script");
            s.textContent = "window['" + marker + "']=1;\n" + code;
            (document.head || document.documentElement).appendChild(s);
            s.remove();
        } catch (_e) {
            blocked = true;
        }
        document.removeEventListener("securitypolicyviolation", onCsp);

        if (blocked || !window[marker]) {
            try {
                const fn = new Function(code);
                fn.call(window);
            } catch (e) {
                try {
                    const blob = new Blob([code], { type: "application/javascript" });
                    const bUrl = URL.createObjectURL(blob);
                    const s2 = document.createElement("script");
                    s2.src = bUrl;
                    (document.head || document.documentElement).appendChild(s2);
                    setTimeout(() => {
                        s2.remove();
                        URL.revokeObjectURL(bUrl);
                    }, 100);
                } catch (_e2) {
                    console.error("UH Bridge fallback exec failed", e);
                }
            }
        }
        try { delete window[marker]; } catch (_e) {}
    }

    function applyGlobalZoom(percent) {
        try {
            const clamped = Math.max(50, Math.min(300, Number(percent) || 100));
            let styleEl = document.getElementById("__uh_global_zoom_style");
            if (clamped === 100) {
                if (styleEl) styleEl.remove();
                if (document.documentElement) {
                    document.documentElement.style.removeProperty("zoom");
                }
                return;
            }
            const factor = (clamped / 100).toFixed(2);
            if (!styleEl) {
                styleEl = document.createElement("style");
                styleEl.id = "__uh_global_zoom_style";
                (document.head || document.documentElement).appendChild(styleEl);
            }
            styleEl.textContent = "html { zoom: " + factor + " !important; -webkit-text-size-adjust: none; }";
        } catch (_e) {}
    }

    // -------------------------------------------------------------------------
    // 3. Message Router
    // -------------------------------------------------------------------------
    window.addEventListener("message", (event) => {
        if (!event.data) return;
        const d = event.data;
        if (d.type === "UH_BOOT_DATA" && d.bootPayload) {
            execCodeSafe(d.bootPayload);
        } else if (d.type === "UH_RUN_PACKET" && d.packet) {
            execCodeSafe(d.packet.code);
        } else if (d.type === "UH_RUN_SCRIPTS" && d.scripts) {
            execCodeSafe(d.scripts);
        } else if (d.type === "UH_RUN_MENU_COMMAND" && d.commandId) {
            if (typeof window.__uh_runMenuCommand === "function") {
                try { window.__uh_runMenuCommand(d.commandId); } catch (_e) {}
            }
        } else if (d.type === "UH_TOGGLE_DEVTOOLS") {
            installEarlyNetworkCapture();
            if (typeof window.__uhToggleDevTools === "function") {
                try { window.__uhToggleDevTools(d.darkTheme !== false); } catch (_e) {}
            }
        } else if (d.type === "UH_APPLY_GLOBAL_ZOOM") {
            applyGlobalZoom(d.percent);
        }
    });

    window.postMessage({ type: "UH_INJECT_READY" }, "*");
})();
