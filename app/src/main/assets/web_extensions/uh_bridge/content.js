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
// 1B. UH Shield Cosmetics, Cookie Banner Reject, Smart Dark Web & Media Hooks
// -----------------------------------------------------------------------------
let pageProtectState = null;
let cookieBannerTimer = null;

function currentNormalizedHost() {
    try {
        return window.location.hostname.toLowerCase().replace(/^www\./, "");
    } catch (_e) {
        return "";
    }
}

function applyCosmeticStyle(selectors, enabled) {
    const id = "__uh_shield_cosmetic";
    const existing = document.getElementById(id);
    if (!enabled || !Array.isArray(selectors) || selectors.length === 0) {
        if (existing) existing.remove();
        return;
    }
    const chunks = [];
    const batch = 40;
    for (let i = 0; i < selectors.length; i += batch) {
        const part = selectors.slice(i, i + batch).join(", ");
        chunks.push(part + " { display: none !important; visibility: hidden !important; height: 0 !important; min-height: 0 !important; }");
    }
    const styleEl = existing || document.createElement("style");
    styleEl.id = id;
    styleEl.textContent = chunks.join("\n");
    (document.head || document.documentElement).appendChild(styleEl);
}

function isPageAlreadyDark() {
    try {
        const meta = document.querySelector('meta[name="color-scheme"]');
        if (meta && /dark/i.test(meta.content || "") && !/^light$/i.test((meta.content || "").trim())) {
            const bg = getComputedStyle(document.body || document.documentElement).backgroundColor;
            if (isColorDark(bg)) return true;
        }
        const rootBg = getComputedStyle(document.documentElement).backgroundColor;
        if (isColorDark(rootBg)) return true;
        if (document.body) {
            const bodyBg = getComputedStyle(document.body).backgroundColor;
            if (isColorDark(bodyBg)) return true;
        }
    } catch (_e) {}
    return false;
}

function isColorDark(cssColor) {
    if (!cssColor || cssColor === "transparent" || cssColor === "rgba(0, 0, 0, 0)") return false;
    const m = cssColor.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?/);
    if (!m) return false;
    const a = m[4] !== undefined ? parseFloat(m[4]) : 1;
    if (a < 0.35) return false;
    const lum = (0.299 * parseInt(m[1], 10) + 0.587 * parseInt(m[2], 10) + 0.114 * parseInt(m[3], 10)) / 255;
    return lum < 0.38;
}

function applySmartDarkMode(enabled, enhanceDark, bypassed) {
    if (!isTopFrame) return;
    const id = "__uh_smart_dark";
    const existing = document.getElementById(id);
    if (!enabled || bypassed) {
        if (existing) existing.remove();
        return;
    }
    const evaluateAndInject = () => {
        if (isPageAlreadyDark()) {
            const el = document.getElementById(id);
            if (el) el.remove();
            return;
        }
        const inv = enhanceDark ? "0.96" : "0.90";
        const contrast = enhanceDark ? "1.06" : "0.96";
        const bgHex = enhanceDark ? "#000000" : "#121318";
        const css = [
            "html { background-color: " + bgHex + " !important; color-scheme: dark !important; filter: invert(" + inv + ") hue-rotate(180deg) contrast(" + contrast + ") !important; }",
            "img, video, picture, canvas, svg, [style*='background-image'], iframe { filter: invert(1) hue-rotate(180deg) !important; }",
            "img[src$='.svg'], img[src*='logo'] { filter: invert(0.1) !important; }",
            "video:fullscreen, video:-webkit-full-screen { filter: none !important; }"
        ].join("\n");
        const styleEl = document.getElementById(id) || document.createElement("style");
        styleEl.id = id;
        styleEl.textContent = css;
        (document.head || document.documentElement).appendChild(styleEl);
    };
    if (document.body) {
        evaluateAndInject();
    } else {
        evaluateAndInject();
        document.addEventListener("DOMContentLoaded", evaluateAndInject, { once: true });
    }
}

function runCookieBannerAutoReject() {
    if (!isTopFrame) return;
    const rejectSelectors = [
        "#onetrust-reject-all-handler",
        "button[id*='reject' i]",
        "button[class*='reject-all' i]",
        "button[data-testid*='reject' i]",
        "button[aria-label*='reject all' i]",
        "button[aria-label*='decline all' i]",
        ".qc-cmp2-summary-buttons button[mode='secondary']",
        "#CybotCookiebotDialogBodyButtonDecline",
        ".fc-cta-do-not-consent",
        "[data-gdpr-single-choice-accept='false']"
    ];
    for (let i = 0; i < rejectSelectors.length; i++) {
        try {
            const btn = document.querySelector(rejectSelectors[i]);
            if (btn && btn.offsetParent !== null) {
                btn.click();
                return;
            }
        } catch (_e) {}
    }
}

function injectPageRuntimeGuards(cfg) {
    if (!isTopFrame) return;
    try {
        const script = document.createElement("script");
        const bgPlay = cfg.backgroundPlay === true;
        const blockPopups = cfg.blockPopups === true && !cfg.paused && (cfg.shieldLevel || 0) > 0;
        const blockWebRtc = cfg.webrtcPolicy === 3;
        const code = `(function(){
            try {
                if (${bgPlay} && !window.__uhBgPlayInstalled) {
                    window.__uhBgPlayInstalled = true;
                    Object.defineProperty(document, 'hidden', { get: () => false, configurable: true });
                    Object.defineProperty(document, 'visibilityState', { get: () => 'visible', configurable: true });
                    window.addEventListener('visibilitychange', (e) => { e.stopImmediatePropagation(); }, true);
                    window.addEventListener('webkitvisibilitychange', (e) => { e.stopImmediatePropagation(); }, true);
                }
                if (${blockWebRtc} && !window.__uhWebRtcBlocked) {
                    window.__uhWebRtcBlocked = true;
                    const noop = function(){ throw new Error('WebRTC disabled by UH Privacy Guard'); };
                    window.RTCPeerConnection = noop;
                    window.webkitRTCPeerConnection = noop;
                    window.mozRTCPeerConnection = noop;
                }
                if (${blockPopups} && !window.__uhPopupGuardInstalled) {
                    window.__uhPopupGuardInstalled = true;
                    const origOpen = window.open;
                    let lastUserTap = 0;
                    const markTap = () => { lastUserTap = Date.now(); };
                    window.addEventListener('pointerdown', markTap, true);
                    window.addEventListener('click', markTap, true);
                    window.addEventListener('keydown', markTap, true);
                    window.open = function(url, target, features) {
                        const sinceTap = Date.now() - lastUserTap;
                        if (sinceTap > 1400) {
                            return null;
                        }
                        return origOpen.apply(this, arguments);
                    };
                }
            } catch(_e) {}
        })();`;
        script.textContent = code;
        (document.head || document.documentElement).appendChild(script);
        script.remove();
    } catch (_e) {}
}

function applyPageProtectConfig(cfg) {
    if (!cfg) return;
    pageProtectState = cfg;
    const shieldOn = (cfg.shieldLevel || 0) > 0 && !cfg.paused;
    applyCosmeticStyle(cfg.cosmetics || [], shieldOn);
    applySmartDarkMode(cfg.forceDarkWeb === true, cfg.enhanceDark === true, cfg.darkBypassed === true);
    injectPageRuntimeGuards(cfg);
    if (cfg.cookieBanners && !cfg.paused) {
        if (cookieBannerTimer) clearTimeout(cookieBannerTimer);
        cookieBannerTimer = setTimeout(runCookieBannerAutoReject, 900);
    }
}

// Kick off protection immediately at document_start
api.runtime.sendMessage({
    type: "UH_GET_PAGE_PROTECT",
    url: window.location.href
}).then((cfg) => {
    if (cfg) applyPageProtectConfig(cfg);
}).catch(() => {});

// -----------------------------------------------------------------------------
// 2. Multi-Script On-Device Image OCR Translation Overlays (CSP-immune)
// -----------------------------------------------------------------------------
let activeImageOverlays = [];
let imageSyncAttached = false;
let imageResizeObserver = null;

function clearImageTranslationOverlays() {
    activeImageOverlays = [];
    if (imageResizeObserver) {
        try { imageResizeObserver.disconnect(); } catch (_e) {}
        imageResizeObserver = null;
    }
    const layer = document.getElementById("__uh_img_tr_layer");
    if (layer) layer.remove();
}

function syncImageOverlaysPosition() {
    if (!activeImageOverlays.length) return;
    const vh = window.innerHeight || 800;
    for (let i = 0; i < activeImageOverlays.length; i++) {
        const item = activeImageOverlays[i];
        if (!item.img || !document.contains(item.img)) {
            item.container.style.display = "none";
            continue;
        }
        const rect = item.img.getBoundingClientRect();
        if (rect.width < 20 || rect.height < 20 || rect.bottom < -60 || rect.top > vh + 60) {
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
    if (window.visualViewport) {
        window.visualViewport.addEventListener("resize", onMove, { passive: true });
        window.visualViewport.addEventListener("scroll", onMove, { passive: true });
    }
}

function tryExtractCanvasDataUrl(img) {
    try {
        const natW = img.naturalWidth || img.width || 0;
        const natH = img.naturalHeight || img.height || 0;
        if (natW < 40 || natH < 24) return "";
        const maxSide = 1600;
        const scale = Math.min(1, maxSide / Math.max(natW, natH));
        const w = Math.max(1, Math.round(natW * scale));
        const h = Math.max(1, Math.round(natH * scale));
        const canvas = document.createElement("canvas");
        canvas.width = w;
        canvas.height = h;
        const ctx = canvas.getContext("2d");
        if (!ctx) return "";
        ctx.drawImage(img, 0, 0, w, h);
        return canvas.toDataURL("image/jpeg", 0.86);
    } catch (_e) {
        return "";
    }
}

function blobToDataUrl(blob) {
    return new Promise((resolve) => {
        try {
            const reader = new FileReader();
            reader.onloadend = () => resolve(typeof reader.result === "string" ? reader.result : "");
            reader.onerror = () => resolve("");
            reader.readAsDataURL(blob);
        } catch (_e) {
            resolve("");
        }
    });
}

async function tryFetchImageWithCredentials(src) {
    if (!src || (!src.startsWith("http://") && !src.startsWith("https://") && !src.startsWith("blob:"))) {
        return "";
    }
    try {
        const controller = typeof AbortController !== "undefined" ? new AbortController() : null;
        const timer = controller ? setTimeout(() => controller.abort(), 5000) : null;
        const resp = await fetch(src, {
            credentials: "include",
            mode: "cors",
            signal: controller ? controller.signal : undefined
        });
        if (timer) clearTimeout(timer);
        if (!resp || !resp.ok) return "";
        const blob = await resp.blob();
        if (!blob || blob.size < 64 || blob.size > 6 * 1024 * 1024) return "";
        if (blob.type && !blob.type.startsWith("image/")) return "";
        return await blobToDataUrl(blob);
    } catch (_e) {
        return "";
    }
}

async function captureImagePayload(img) {
    const src = img.currentSrc || img.src || "";
    if (src.startsWith("data:image/")) {
        return { imageUrl: "", dataUrl: src };
    }
    // Tier A: fast in-memory canvas extraction (same-origin or CORS-permitted images)
    const canvasData = tryExtractCanvasDataUrl(img);
    if (canvasData) {
        return { imageUrl: src.startsWith("http") ? src : "", dataUrl: canvasData };
    }
    // Tier B: page-context credentialed fetch (carries browser cookies & Referer)
    const fetchedData = await tryFetchImageWithCredentials(src);
    if (fetchedData) {
        return { imageUrl: src.startsWith("http") ? src : "", dataUrl: fetchedData };
    }
    // Tier C: native OkHttp fetch with explicit page Referer header
    if (src.startsWith("http://") || src.startsWith("https://")) {
        return { imageUrl: src, dataUrl: "" };
    }
    return null;
}

async function runImageOcrTranslation(fromLang, toLang) {
    clearImageTranslationOverlays();
    const imgs = Array.from(document.querySelectorAll("img"));
    const vh = window.innerHeight || 800;
    const rawTargets = [];

    for (let i = 0; i < imgs.length && rawTargets.length < 12; i++) {
        const img = imgs[i];
        if (!img.complete || img.naturalWidth < 60 || img.naturalHeight < 32) continue;
        const rect = img.getBoundingClientRect();
        if (rect.width < 72 || rect.height < 36) continue;
        if (rect.bottom < -220 || rect.top > vh * 2.2) continue;
        rawTargets.push(img);
    }

    if (!rawTargets.length) {
        sendNativeCall({
            type: "IMAGE_TRANSLATE_DONE",
            imageCount: 0,
            overlayCount: 0
        });
        return;
    }

    const candidates = [];
    const imgById = new Map();
    const capturedList = await Promise.all(rawTargets.map((img) => captureImagePayload(img)));
    for (let i = 0; i < rawTargets.length; i++) {
        const cap = capturedList[i];
        if (!cap) continue;
        const id = "img_" + candidates.length;
        imgById.set(id, rawTargets[i]);
        candidates.push({
            id: id,
            imageUrl: cap.imageUrl,
            dataUrl: cap.dataUrl
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

    if (typeof ResizeObserver !== "undefined") {
        imageResizeObserver = new ResizeObserver(() => {
            if (activeImageOverlays.length) requestAnimationFrame(syncImageOverlaysPosition);
        });
    }

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

            const bg = blk.bgColor || "rgba(15,23,42,0.92)";
            const fg = blk.textColor || "#F8FAFC";

            const chip = document.createElement("div");
            chip.textContent = blk.translatedText;
            chip.title = "Tap to toggle original text";
            chip.dataset.translated = blk.translatedText;
            chip.dataset.original = blk.originalText || blk.translatedText;
            chip.dataset.showingOriginal = "0";
            chip.style.cssText =
                "position:absolute;box-sizing:border-box;pointer-events:auto;cursor:pointer;" +
                "display:flex;align-items:center;justify-content:center;text-align:center;" +
                "left:" + blk.leftPct + "%;top:" + blk.topPct + "%;" +
                "min-width:" + blk.widthPct + "%;max-width:96%;" +
                "min-height:" + blk.heightPct + "%;" +
                "background:" + bg + ";color:" + fg + ";" +
                "border:1px solid rgba(148,163,184,0.38);border-radius:6px;" +
                "padding:3px 6px;font:600 11.5px/1.25 system-ui,-apple-system,sans-serif;" +
                "backdrop-filter:blur(3px);word-break:break-word;" +
                "box-shadow:0 2px 8px rgba(0,0,0,0.28);transition:opacity 0.15s ease;";

            chip.addEventListener("click", (ev) => {
                ev.preventDefault();
                ev.stopPropagation();
                const isOrig = chip.dataset.showingOriginal === "1";
                if (isOrig) {
                    chip.dataset.showingOriginal = "0";
                    chip.textContent = chip.dataset.translated;
                    chip.style.opacity = "1";
                } else {
                    chip.dataset.showingOriginal = "1";
                    chip.textContent = chip.dataset.original;
                    chip.style.opacity = "0.78";
                }
            });

            boxContainer.appendChild(chip);
            overlayCount++;
        }

        layer.appendChild(boxContainer);
        activeImageOverlays.push({ img: img, container: boxContainer });
        if (imageResizeObserver) {
            try { imageResizeObserver.observe(img); } catch (_e) {}
        }
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
// 3. Userscripts & DevTools Loader
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
    } else if (msg.action === "TRANSLATE_IMAGES" && isTopFrame) {
        runImageOcrTranslation(msg.fromLang || "auto", msg.toLang || "en");
    } else if (msg.action === "CLEAR_IMAGE_TRANSLATIONS" && isTopFrame) {
        clearImageTranslationOverlays();
    } else if (msg.action === "APPLY_LIVE_PAGE_CONFIG" && isTopFrame) {
        const host = currentNormalizedHost();
        const paused = Array.isArray(msg.pausedHosts) && msg.pausedHosts.includes(host);
        const darkBypassed = Array.isArray(msg.darkBypassHosts) && msg.darkBypassHosts.includes(host);
        const merged = Object.assign({}, pageProtectState || {}, {
            shieldLevel: msg.shieldLevel,
            paused: paused,
            cookieBanners: msg.cookieBanners,
            blockPopups: msg.blockPopups,
            backgroundPlay: msg.backgroundPlay,
            webrtcPolicy: msg.webrtcPolicy,
            forceDarkWeb: msg.forceDarkWeb,
            enhanceDark: msg.enhanceDark,
            darkBypassed: darkBypassed
        });
        applyPageProtectConfig(merged);
    }
});

const injectScript = document.createElement("script");
injectScript.src = api.runtime.getURL("inject.js");
(document.head || document.documentElement).appendChild(injectScript);
injectScript.remove();
