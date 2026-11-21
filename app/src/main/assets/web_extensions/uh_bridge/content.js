const api = typeof browser !== "undefined" ? browser : chrome;

const isTopFrame = (function () {
    try { return window.self === window.top; } catch (_e) { return true; }
})();

const pendingPackets = [];
let injectReady = false;
let devToolsScriptsLoaded = false;

// -----------------------------------------------------------------------------
// 1. Direct Native Bridge Helper (CSP-immune extension isolated world)
// -----------------------------------------------------------------------------
function sendNativeCall(payload) {
    return api.runtime.sendMessage({
        type: "UH_NATIVE_CALL",
        pageUrl: window.location.href,
        payload: payload
    }).catch(() => null);
}

// -----------------------------------------------------------------------------
// 2. Fast Parallel DOM Translator + Instant 0ms Restore (CSP-immune)
// -----------------------------------------------------------------------------
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
        if (nodes.length >= 1200) break;
    }
    // Prioritize viewport-visible text nodes first for instant above-the-fold paint
    const vh = window.innerHeight || 800;
    const visible = [];
    const offscreen = [];
    for (let i = 0; i < nodes.length; i++) {
        const p = nodes[i].parentElement;
        let inView = false;
        try {
            const r = p.getBoundingClientRect();
            inView = r.width > 0 && r.height > 0 && r.bottom >= -120 && r.top <= vh + 240;
        } catch (_e) {}
        if (inView) visible.push(nodes[i]);
        else offscreen.push(nodes[i]);
    }
    return visible.concat(offscreen);
}

async function translateNodeBatch(nodes, fromLang, toLang, gen, includeTitle) {
    if (!nodes.length && !includeTitle) return { applied: 0, ok: true, error: null };
    const texts = [];
    for (let i = 0; i < nodes.length; i++) {
        const n = nodes[i];
        if (!origTextMap.has(n)) {
            origTextMap.set(n, n.nodeValue);
            translatedNodes.push(n);
        }
        texts.push(origTextMap.get(n));
    }
    const titleIndex = includeTitle && origDocTitle ? texts.length : -1;
    if (titleIndex >= 0) {
        texts.push(origDocTitle);
    }

    const resp = await sendNativeCall({
        type: "FAST_TRANSLATE_BATCH",
        fromLang: fromLang,
        toLang: toLang,
        texts: texts
    });

    if (gen !== translateGeneration) return { applied: 0, ok: true, error: null };
    if (!resp || resp.ok === false || !Array.isArray(resp.translations)) {
        return {
            applied: 0,
            ok: false,
            error: (resp && resp.error) ? resp.error : "Could not reach translation service."
        };
    }

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
    if (titleIndex >= 0 && typeof resp.translations[titleIndex] === "string" && resp.translations[titleIndex].trim()) {
        try { document.title = resp.translations[titleIndex]; } catch (_e) {}
    }
    return { applied: applied, ok: true, error: null };
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
                if (newNodes.length >= 220) break;
            }
            if (newNodes.length > 0) {
                await translateNodeBatch(newNodes, fromLang, toLang, gen, false);
            }
        }, 420);
    });
    translateObserver.observe(document.body, { childList: true, subtree: true });
}

async function runFastPageTranslate(fromLang, toLang) {
    const started = performance.now();
    const gen = ++translateGeneration;
    activeTranslateConfig = { fromLang: fromLang || "auto", toLang: toLang || "en" };

    if (origDocTitle === null) origDocTitle = document.title;

    const root = document.body || document.documentElement;
    const allNodes = collectTranslatableNodes(root, false);
    if (!allNodes.length) {
        sendNativeCall({
            type: "FAST_TRANSLATE_DONE",
            success: true,
            count: 0,
            durationMs: Math.round(performance.now() - started),
            fromLang: activeTranslateConfig.fromLang,
            toLang: activeTranslateConfig.toLang,
            error: ""
        });
        return;
    }

    const totalNodes = allNodes.length;
    let processedNodes = 0;
    let totalApplied = 0;

    // Wave 1: immediate viewport-first 60 nodes + document.title for sub-400ms first paint
    const firstWave = allNodes.slice(0, 60);
    const rest = allNodes.slice(60);

    const firstRes = await translateNodeBatch(
        firstWave,
        activeTranslateConfig.fromLang,
        activeTranslateConfig.toLang,
        gen,
        true
    );
    if (gen !== translateGeneration) return;

    if (!firstRes.ok) {
        activeTranslateConfig = null;
        sendNativeCall({
            type: "FAST_TRANSLATE_DONE",
            success: false,
            count: 0,
            durationMs: Math.round(performance.now() - started),
            fromLang: fromLang || "auto",
            toLang: toLang || "en",
            error: firstRes.error || "Could not reach translation service."
        });
        return;
    }

    processedNodes += firstWave.length;
    totalApplied += firstRes.applied;

    if (rest.length > 0) {
        sendNativeCall({
            type: "FAST_TRANSLATE_PROGRESS",
            done: processedNodes,
            total: totalNodes,
            applied: totalApplied
        });
    }

    // Remaining nodes: dispatch in pairs of 140-node waves concurrently for ~1-2s full page finish
    const remainingSlices = [];
    for (let offset = 0; offset < rest.length; offset += 140) {
        remainingSlices.push(rest.slice(offset, offset + 140));
    }

    for (let i = 0; i < remainingSlices.length; i += 2) {
        if (gen !== translateGeneration) return;
        const pair = remainingSlices.slice(i, i + 2);
        const pairResults = await Promise.all(
            pair.map((slice) =>
                translateNodeBatch(
                    slice,
                    activeTranslateConfig.fromLang,
                    activeTranslateConfig.toLang,
                    gen,
                    false
                )
            )
        );
        if (gen !== translateGeneration) return;
        for (let k = 0; k < pair.length; k++) {
            processedNodes += pair[k].length;
            totalApplied += pairResults[k].applied;
        }
        sendNativeCall({
            type: "FAST_TRANSLATE_PROGRESS",
            done: processedNodes,
            total: totalNodes,
            applied: totalApplied
        });
    }

    if (gen !== translateGeneration) return;
    startTranslateObserver(activeTranslateConfig.fromLang, activeTranslateConfig.toLang, gen);

    sendNativeCall({
        type: "FAST_TRANSLATE_DONE",
        success: true,
        count: totalApplied,
        durationMs: Math.round(performance.now() - started),
        fromLang: activeTranslateConfig.fromLang,
        toLang: activeTranslateConfig.toLang,
        error: ""
    });
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
        try { document.title = origDocTitle; } catch (_e) {}
        origDocTitle = null;
    }
}

// -----------------------------------------------------------------------------
// 3. ML Kit Image OCR Text Overlay Translation (CSP-immune)
// -----------------------------------------------------------------------------
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
        sendNativeCall({
            type: "IMAGE_TRANSLATE_DONE",
            imageCount: 0,
            overlayCount: 0
        });
        return;
    }

    const resp = await sendNativeCall({
        type: "TRANSLATE_IMAGES_OCR",
        fromLang: fromLang || "auto",
        toLang: toLang || "en",
        images: candidates
    });

    if (!resp || !Array.isArray(resp.results)) {
        sendNativeCall({
            type: "IMAGE_TRANSLATE_DONE",
            imageCount: candidates.length,
            overlayCount: 0
        });
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

    sendNativeCall({
        type: "IMAGE_TRANSLATE_DONE",
        imageCount: candidates.length,
        overlayCount: overlayCount
    });
}

// -----------------------------------------------------------------------------
// 4. Userscripts & DevTools Loader
// -----------------------------------------------------------------------------
function dispatchRunPacket(packet) {
    if (!packet) return;
    const targetRunAt = packet.runAt || "document-start";
    const runNow = () => {
        window.postMessage({ type: "UH_RUN_PACKET", packet: packet }, "*");
    };
    if (targetRunAt === "document-start") {
        runNow();
    } else if (targetRunAt === "document-body") {
        if (document.body) {
            runNow();
        } else {
            const obs = new MutationObserver(() => {
                if (document.body) {
                    obs.disconnect();
                    runNow();
                }
            });
            obs.observe(document.documentElement || document, { childList: true, subtree: true });
        }
    } else if (targetRunAt === "document-end") {
        if (document.readyState === "interactive" || document.readyState === "complete") {
            runNow();
        } else {
            document.addEventListener("DOMContentLoaded", runNow, { once: true });
        }
    } else {
        if (document.readyState === "complete") {
            setTimeout(runNow, 0);
        } else {
            window.addEventListener("load", () => setTimeout(runNow, 0), { once: true });
        }
    }
}

function loadPageScript(relPath) {
    return new Promise((resolve) => {
        try {
            const s = document.createElement("script");
            s.src = api.runtime.getURL(relPath);
            s.onload = () => { s.remove(); resolve(true); };
            s.onerror = () => { s.remove(); resolve(false); };
            (document.head || document.documentElement).appendChild(s);
        } catch (_e) {
            resolve(false);
        }
    });
}

async function ensureAndToggleDevTools(darkTheme) {
    if (!isTopFrame) return;
    if (!devToolsScriptsLoaded) {
        const files = [
            "devtools/eruda.min.js",
            "devtools/uh_devtools_pro.js"
        ];
        for (const f of files) {
            await loadPageScript(f);
        }
        devToolsScriptsLoaded = true;
    }
    window.postMessage({ type: "UH_TOGGLE_DEVTOOLS", darkTheme: darkTheme !== false }, "*");
}

window.addEventListener("message", (event) => {
    if (!event.data) return;
    if (event.data.type === "UH_INJECT_READY") {
        injectReady = true;
        api.runtime.sendMessage({
            type: "UH_GET_SCRIPTS",
            url: window.location.href,
            isTop: isTopFrame
        }).then((response) => {
            if (!response) return;
            if (isTopFrame && typeof response.globalZoomPercent === "number" && response.globalZoomPercent !== 100) {
                window.postMessage({ type: "UH_APPLY_GLOBAL_ZOOM", percent: response.globalZoomPercent }, "*");
            }
            if (response.bootPayload) {
                window.postMessage({ type: "UH_BOOT_DATA", bootPayload: response.bootPayload }, "*");
            }
            if (Array.isArray(response.packets)) {
                for (const p of response.packets) {
                    dispatchRunPacket(p);
                }
            } else if (response.scripts) {
                window.postMessage({ type: "UH_RUN_SCRIPTS", scripts: response.scripts }, "*");
            }
            while (pendingPackets.length > 0) {
                dispatchRunPacket(pendingPackets.shift());
            }
        }).catch(() => {});
    } else if (event.data.type === "UH_NATIVE_CALL") {
        api.runtime.sendMessage({
            type: "UH_NATIVE_CALL",
            pageUrl: window.location.href,
            payload: event.data.payload
        }).then((response) => {
            if (event.data.payload && event.data.payload.id) {
                window.postMessage({
                    type: "UH_NATIVE_RESPONSE",
                    id: event.data.payload.id,
                    response: response
                }, "*");
            }
        }).catch(() => {
            if (event.data.payload && event.data.payload.id) {
                window.postMessage({
                    type: "UH_NATIVE_RESPONSE",
                    id: event.data.payload.id,
                    response: null
                }, "*");
            }
        });
    }
});

api.runtime.onMessage.addListener((msg) => {
    if (!msg || !msg.action) return;
    if (msg.action === "RUN_MENU_COMMAND" && msg.commandId) {
        window.postMessage({ type: "UH_RUN_MENU_COMMAND", commandId: msg.commandId }, "*");
    } else if (msg.action === "RELOAD_SCRIPTS") {
        api.runtime.sendMessage({
            type: "UH_GET_SCRIPTS",
            url: window.location.href,
            isTop: isTopFrame
        }).then((response) => {
            if (response && Array.isArray(response.packets)) {
                for (const p of response.packets) dispatchRunPacket(p);
            }
        }).catch(() => {});
    } else if (msg.action === "TOGGLE_DEVTOOLS") {
        ensureAndToggleDevTools(msg.darkTheme !== false);
    } else if (msg.action === "APPLY_GLOBAL_ZOOM" && isTopFrame) {
        window.postMessage({ type: "UH_APPLY_GLOBAL_ZOOM", percent: msg.percent || 100 }, "*");
    } else if (msg.action === "FAST_TRANSLATE_PAGE" && isTopFrame) {
        runFastPageTranslate(msg.fromLang || "auto", msg.toLang || "en");
    } else if (msg.action === "FAST_TRANSLATE_RESTORE" && isTopFrame) {
        restoreFastPageTranslate();
    } else if (msg.action === "TRANSLATE_IMAGES" && isTopFrame) {
        runImageOcrTranslation(msg.fromLang || "auto", msg.toLang || "en");
    } else if (msg.action === "CLEAR_IMAGE_TRANSLATIONS" && isTopFrame) {
        clearImageTranslationOverlays();
    }
});

const injectScript = document.createElement("script");
injectScript.src = api.runtime.getURL("inject.js");
(document.head || document.documentElement).appendChild(injectScript);
injectScript.remove();
