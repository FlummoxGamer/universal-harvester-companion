(function () {
    if (window.__uh_bridge_inject_ready) return;
    window.__uh_bridge_inject_ready = true;

    // -------------------------------------------------------------------------
    // 1. Early Network Ring Buffer (document_start Fetch + XHR capture)
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

    installEarlyNetworkCapture();

    // -------------------------------------------------------------------------
    // 2. Native Bridge Request/Response Helper
    // -------------------------------------------------------------------------
    const pendingNativeCalls = new Map();

    function callNative(payload, timeoutMs) {
        return new Promise((resolve) => {
            const id = "uh_nc_" + Date.now().toString(36) + "_" + Math.random().toString(36).slice(2, 7);
            const timer = setTimeout(() => {
                pendingNativeCalls.delete(id);
                resolve(null);
            }, timeoutMs || 20000);
            pendingNativeCalls.set(id, (resp) => {
                clearTimeout(timer);
                resolve(resp);
            });
            window.postMessage({
                type: "UH_NATIVE_CALL",
                payload: Object.assign({}, payload, { id: id })
            }, "*");
        });
    }

    // -------------------------------------------------------------------------
    // 3. Fast Parallel DOM Translator + Instant 0ms Restore
    // -------------------------------------------------------------------------
    const SKIP_TAGS = new Set([
        "SCRIPT", "STYLE", "NOSCRIPT", "TEXTAREA", "INPUT", "SELECT",
        "OPTION", "CODE", "PRE", "SVG", "MATH", "CANVAS", "IFRAME", "VIDEO", "AUDIO"
    ]);
    let origTextMap = new WeakMap();
    let translatedNodes = [];
    let origDocTitle = null;
    let activeTranslateConfig = null;
    let translateObserver = null;
    let translateDebounceTimer = null;
    let translateGeneration = 0;

    function isNodeTranslatable(node) {
        if (!node || !node.nodeValue) return false;
        const raw = node.nodeValue.trim();
        if (raw.length < 2) return false;
        // Skip pure numbers / punctuation / URLs
        if (/^[\d\s.,:%$€£¥₹+\-*/=()[\]{}|\\<>@#!?'"~`^&_]+$/.test(raw)) return false;
        const parent = node.parentElement;
        if (!parent) return false;
        if (SKIP_TAGS.has(parent.tagName)) return false;
        if (parent.isContentEditable) return false;
        if (parent.closest && parent.closest("#eruda, #__uh_img_tr_layer, [translate='no'], .notranslate")) return false;
        return true;
    }

    function collectTranslatableNodes(root, onlyNew) {
        const nodes = [];
        if (!root) return nodes;
        const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
            acceptNode(node) {
                if (!isNodeTranslatable(node)) return NodeFilter.FILTER_REJECT;
                if (onlyNew && origTextMap.has(node)) return NodeFilter.FILTER_REJECT;
                return NodeFilter.FILTER_ACCEPT;
            }
        });
        let cur;
        while ((cur = walker.nextNode())) {
            nodes.push(cur);
            if (nodes.length >= 900) break;
        }
        // Prioritize viewport-visible text nodes first so visible text paints fastest
        const vh = window.innerHeight || 800;
        const visible = [];
        const offscreen = [];
        for (let i = 0; i < nodes.length; i++) {
            const p = nodes[i].parentElement;
            let inView = false;
            try {
                const r = p.getBoundingClientRect();
                inView = r.width > 0 && r.height > 0 && r.bottom >= -100 && r.top <= vh + 200;
            } catch (_e) {}
            if (inView) visible.push(nodes[i]);
            else offscreen.push(nodes[i]);
        }
        return visible.concat(offscreen);
    }

    async function translateNodeBatch(nodes, fromLang, toLang, gen) {
        if (!nodes.length) return 0;
        const texts = [];
        for (let i = 0; i < nodes.length; i++) {
            const n = nodes[i];
            if (!origTextMap.has(n)) {
                origTextMap.set(n, n.nodeValue);
                translatedNodes.push(n);
            }
            texts.push(origTextMap.get(n));
        }

        const resp = await callNative({
            type: "FAST_TRANSLATE_BATCH",
            fromLang: fromLang,
            toLang: toLang,
            texts: texts
        }, 25000);

        if (gen !== translateGeneration) return 0;
        if (!resp || !Array.isArray(resp.translations)) return 0;

        let applied = 0;
        for (let i = 0; i < nodes.length && i < resp.translations.length; i++) {
            const tr = resp.translations[i];
            if (typeof tr === "string" && tr.length > 0 && tr !== nodes[i].nodeValue) {
                try {
                    nodes[i].nodeValue = tr;
                    applied++;
                } catch (_e) {}
            }
        }
        return applied;
    }

    function stopTranslateObserver() {
        if (translateObserver) {
            try { translateObserver.disconnect(); } catch (_e) {}
            translateObserver = null;
        }
        if (translateDebounceTimer) {
            clearTimeout(translateDebounceTimer);
            translateDebounceTimer = null;
        }
    }

    function startTranslateObserver(fromLang, toLang, gen) {
        stopTranslateObserver();
        if (!document.body || typeof MutationObserver === "undefined") return;
        const pendingRoots = new Set();
        translateObserver = new MutationObserver((mutations) => {
            if (!activeTranslateConfig || gen !== translateGeneration) return;
            for (let i = 0; i < mutations.length; i++) {
                const m = mutations[i];
                if (m.addedNodes && m.addedNodes.length) {
                    for (let j = 0; j < m.addedNodes.length; j++) {
                        const added = m.addedNodes[j];
                        if (added.nodeType === 1 && !added.closest("#eruda, #__uh_img_tr_layer")) {
                            pendingRoots.add(added);
                        }
                    }
                }
            }
            if (!pendingRoots.size) return;
            if (translateDebounceTimer) clearTimeout(translateDebounceTimer);
            translateDebounceTimer = setTimeout(async () => {
                if (!activeTranslateConfig || gen !== translateGeneration) return;
                const roots = Array.from(pendingRoots);
                pendingRoots.clear();
                let newNodes = [];
                for (let i = 0; i < roots.length; i++) {
                    if (document.contains(roots[i])) {
                        newNodes = newNodes.concat(collectTranslatableNodes(roots[i], true));
                    }
                    if (newNodes.length >= 200) break;
                }
                if (newNodes.length > 0) {
                    await translateNodeBatch(newNodes, fromLang, toLang, gen);
                }
            }, 450);
        });
        translateObserver.observe(document.body, { childList: true, subtree: true });
    }

    async function runFastPageTranslate(fromLang, toLang) {
        const started = performance.now();
        const gen = ++translateGeneration;
        activeTranslateConfig = { fromLang: fromLang || "auto", toLang: toLang || "en" };

        if (origDocTitle === null) origDocTitle = document.title;

        const allNodes = collectTranslatableNodes(document.body, false);
        if (!allNodes.length) {
            window.postMessage({
                type: "UH_NATIVE_CALL",
                payload: {
                    type: "FAST_TRANSLATE_DONE",
                    success: true,
                    count: 0,
                    durationMs: Math.round(performance.now() - started),
                    fromLang: activeTranslateConfig.fromLang,
                    toLang: activeTranslateConfig.toLang
                }
            }, "*");
            return;
        }

        // First wave: immediate above-the-fold nodes (first 80) for ultra-snappy perceived speed
        const firstWave = allNodes.slice(0, 80);
        const rest = allNodes.slice(80);
        let totalApplied = await translateNodeBatch(firstWave, activeTranslateConfig.fromLang, activeTranslateConfig.toLang, gen);

        // Translate page title alongside first wave if needed
        if (origDocTitle && gen === translateGeneration) {
            callNative({
                type: "FAST_TRANSLATE_BATCH",
                fromLang: activeTranslateConfig.fromLang,
                toLang: activeTranslateConfig.toLang,
                texts: [origDocTitle]
            }, 8000).then((r) => {
                if (gen === translateGeneration && r && Array.isArray(r.translations) && r.translations[0]) {
                    document.title = r.translations[0];
                }
            });
        }

        // Remaining waves in chunks of 160
        for (let offset = 0; offset < rest.length; offset += 160) {
            if (gen !== translateGeneration) return;
            const slice = rest.slice(offset, offset + 160);
            totalApplied += await translateNodeBatch(slice, activeTranslateConfig.fromLang, activeTranslateConfig.toLang, gen);
        }

        if (gen !== translateGeneration) return;
        startTranslateObserver(activeTranslateConfig.fromLang, activeTranslateConfig.toLang, gen);

        window.postMessage({
            type: "UH_NATIVE_CALL",
            payload: {
                type: "FAST_TRANSLATE_DONE",
                success: totalApplied > 0 || allNodes.length > 0,
                count: totalApplied,
                durationMs: Math.round(performance.now() - started),
                fromLang: activeTranslateConfig.fromLang,
                toLang: activeTranslateConfig.toLang
            }
        }, "*");
    }

    function restoreFastPageTranslate() {
        translateGeneration++;
        activeTranslateConfig = null;
        stopTranslateObserver();
        for (let i = 0; i < translatedNodes.length; i++) {
            const n = translatedNodes[i];
            const orig = origTextMap.get(n);
            if (typeof orig === "string") {
                try { n.nodeValue = orig; } catch (_e) {}
            }
        }
        translatedNodes = [];
        origTextMap = new WeakMap();
        if (origDocTitle !== null) {
            document.title = origDocTitle;
            origDocTitle = null;
        }
    }

    // -------------------------------------------------------------------------
    // 4. ML Kit Image OCR Text Overlay Translation
    // -------------------------------------------------------------------------
    let activeImageOverlays = [];
    let imageSyncAttached = false;

    function clearImageTranslationOverlays() {
        activeImageOverlays = [];
        const layer = document.getElementById("__uh_img_tr_layer");
        if (layer) layer.remove();
    }

    function syncImageOverlaysPosition() {
        if (!activeImageOverlays.length) return;
        for (let i = 0; i < activeImageOverlays.length; i++) {
            const item = activeImageOverlays[i];
            if (!item.img || !document.contains(item.img)) {
                item.container.style.display = "none";
                continue;
            }
            const rect = item.img.getBoundingClientRect();
            if (rect.width < 20 || rect.height < 20 || rect.bottom < -50 || rect.top > window.innerHeight + 50) {
                item.container.style.display = "none";
                continue;
            }
            item.container.style.display = "block";
            item.container.style.left = rect.left + "px";
            item.container.style.top = rect.top + "px";
            item.container.style.width = rect.width + "px";
            item.container.style.height = rect.height + "px";
        }
    }

    function ensureImageOverlaySync() {
        if (imageSyncAttached) return;
        imageSyncAttached = true;
        const onMove = () => {
            if (activeImageOverlays.length) requestAnimationFrame(syncImageOverlaysPosition);
        };
        window.addEventListener("scroll", onMove, { passive: true, capture: true });
        window.addEventListener("resize", onMove, { passive: true });
    }

    function tryExtractImageDataUrl(img) {
        try {
            const w = Math.min(img.naturalWidth || img.width, 1024);
            const h = Math.min(img.naturalHeight || img.height, 1024);
            if (w < 40 || h < 24) return "";
            const canvas = document.createElement("canvas");
            canvas.width = w;
            canvas.height = h;
            const ctx = canvas.getContext("2d");
            if (!ctx) return "";
            ctx.drawImage(img, 0, 0, w, h);
            return canvas.toDataURL("image/jpeg", 0.82);
        } catch (_e) {
            return "";
        }
    }

    async function runImageOcrTranslation(fromLang, toLang) {
        clearImageTranslationOverlays();
        const imgs = Array.from(document.querySelectorAll("img"));
        const vh = window.innerHeight || 800;
        const candidates = [];
        const imgById = new Map();

        for (let i = 0; i < imgs.length && candidates.length < 10; i++) {
            const img = imgs[i];
            if (!img.complete || img.naturalWidth < 64 || img.naturalHeight < 36) continue;
            const rect = img.getBoundingClientRect();
            if (rect.width < 80 || rect.height < 40) continue;
            if (rect.bottom < -200 || rect.top > vh * 2.2) continue;

            const src = img.currentSrc || img.src || "";
            let dataUrl = "";
            if (!src || src.startsWith("blob:") || src.startsWith("data:")) {
                dataUrl = src.startsWith("data:") ? src : tryExtractImageDataUrl(img);
            }
            if (!src && !dataUrl) continue;

            const id = "img_" + candidates.length;
            imgById.set(id, img);
            candidates.push({
                id: id,
                imageUrl: src.startsWith("http") ? src : "",
                dataUrl: dataUrl
            });
        }

        if (!candidates.length) {
            window.postMessage({
                type: "UH_NATIVE_CALL",
                payload: { type: "IMAGE_TRANSLATE_DONE", imageCount: 0, overlayCount: 0 }
            }, "*");
            return;
        }

        const resp = await callNative({
            type: "TRANSLATE_IMAGES_OCR",
            fromLang: fromLang || "auto",
            toLang: toLang || "en",
            images: candidates
        }, 30000);

        if (!resp || !Array.isArray(resp.results)) {
            window.postMessage({
                type: "UH_NATIVE_CALL",
                payload: { type: "IMAGE_TRANSLATE_DONE", imageCount: candidates.length, overlayCount: 0 }
            }, "*");
            return;
        }

        const layer = document.createElement("div");
        layer.id = "__uh_img_tr_layer";
        layer.style.cssText = "position:fixed;inset:0;pointer-events:none;z-index:2147483640;overflow:hidden;";

        let overlayCount = 0;
        for (let i = 0; i < resp.results.length; i++) {
            const res = resp.results[i];
            const img = imgById.get(res.id);
            if (!img || !Array.isArray(res.blocks) || !res.blocks.length) continue;

            const boxContainer = document.createElement("div");
            boxContainer.style.cssText = "position:fixed;pointer-events:none;overflow:hidden;";

            for (let b = 0; b < res.blocks.length; b++) {
                const blk = res.blocks[b];
                if (!blk.translatedText) continue;
                const chip = document.createElement("div");
                chip.textContent = blk.translatedText;
                chip.style.cssText =
                    "position:absolute;box-sizing:border-box;" +
                    "left:" + blk.leftPct + "%;top:" + blk.topPct + "%;" +
                    "min-width:" + blk.widthPct + "%;max-width:96%;" +
                    "min-height:" + blk.heightPct + "%;" +
                    "background:rgba(10,15,26,0.86);color:#F1F5F9;" +
                    "border:1px solid rgba(69,123,157,0.75);border-radius:4px;" +
                    "padding:2px 5px;font:600 11px/1.25 system-ui,-apple-system,sans-serif;" +
                    "backdrop-filter:blur(4px);word-break:break-word;box-shadow:0 2px 6px rgba(0,0,0,0.35);";
                boxContainer.appendChild(chip);
                overlayCount++;
            }

            layer.appendChild(boxContainer);
            activeImageOverlays.push({ img: img, container: boxContainer });
        }

        if (overlayCount > 0) {
            (document.body || document.documentElement).appendChild(layer);
            ensureImageOverlaySync();
            syncImageOverlaysPosition();
        }

        window.postMessage({
            type: "UH_NATIVE_CALL",
            payload: {
                type: "IMAGE_TRANSLATE_DONE",
                imageCount: candidates.length,
                overlayCount: overlayCount
            }
        }, "*");
    }

    // -------------------------------------------------------------------------
    // 5. Userscript Safe Executor & Global Zoom
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
    // 6. Message Router
    // -------------------------------------------------------------------------
    window.addEventListener("message", (event) => {
        if (!event.data) return;
        const d = event.data;
        if (d.type === "UH_NATIVE_RESPONSE" && d.id && pendingNativeCalls.has(d.id)) {
            const cb = pendingNativeCalls.get(d.id);
            pendingNativeCalls.delete(d.id);
            try { cb(d.response); } catch (_e) {}
            return;
        }
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
            if (typeof window.__uhToggleDevTools === "function") {
                try { window.__uhToggleDevTools(d.darkTheme !== false); } catch (_e) {}
            }
        } else if (d.type === "UH_APPLY_GLOBAL_ZOOM") {
            applyGlobalZoom(d.percent);
        } else if (d.type === "UH_FAST_TRANSLATE_START") {
            runFastPageTranslate(d.fromLang, d.toLang);
        } else if (d.type === "UH_FAST_TRANSLATE_RESTORE") {
            restoreFastPageTranslate();
        } else if (d.type === "UH_TRANSLATE_IMAGES_START") {
            runImageOcrTranslation(d.fromLang, d.toLang);
        } else if (d.type === "UH_CLEAR_IMAGE_TRANSLATIONS") {
            clearImageTranslationOverlays();
        }
    });

    window.postMessage({ type: "UH_INJECT_READY" }, "*");
})();
