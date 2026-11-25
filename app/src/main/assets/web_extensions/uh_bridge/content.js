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
// 1B. UH Shield v5: adoptedStyleSheets Cosmetics, rAF-Throttled Procedural &
//     First-Party GIF/Banner Ad Collapse, Shadow-DOM Cookie Reject,
//     True AMOLED Website Dark Mode & Media/Video-Only PiP Bridge
// -----------------------------------------------------------------------------
let pageProtectState = null;
let scriptletsReady = false;
let cookieBannerObserver = null;
let cookieBannerStopTimer = null;
let cookieBannerRejected = false;
let proceduralObserver = null;
let proceduralRaf = 0;
let cosmeticSheet = null;
const blockedUrls = new Set();
const blockedPathHints = new Set();
let collapseRaf = 0;

function currentNormalizedHost() {
    try {
        return window.location.hostname.toLowerCase().replace(/^www\./, "");
    } catch (_e) {
        return "";
    }
}

function dispatchScriptletConfig(cfg) {
    if (!cfg) return;
    try {
        document.dispatchEvent(new CustomEvent("UH_APPLY_SCRIPTLET_CONFIG", {
            detail: {
                shieldLevel: cfg.shieldLevel || 0,
                paused: cfg.paused === true,
                blockPopups: cfg.blockPopups === true,
                backgroundPlay: cfg.backgroundPlay === true,
                webrtcPolicy: cfg.webrtcPolicy || 0,
                antiFingerprint: cfg.antiFingerprint === true,
                scriptlets: Array.isArray(cfg.scriptlets) ? cfg.scriptlets : []
            }
        }));
    } catch (_e) {}
}

document.addEventListener("UH_SCRIPTLETS_READY", () => {
    scriptletsReady = true;
    if (pageProtectState) {
        dispatchScriptletConfig(pageProtectState);
    }
});

// Load CSP-safe MAIN-world scriptlets runner immediately at document_start
(function loadScriptletsRunner() {
    try {
        const s = document.createElement("script");
        s.src = api.runtime.getURL("scriptlets.js");
        s.onload = () => s.remove();
        s.onerror = () => s.remove();
        (document.head || document.documentElement).appendChild(s);
    } catch (_e) {}
})();

function applyCosmeticStyle(selectors, enabled) {
    const id = "__uh_shield_cosmetic";
    const existing = document.getElementById(id);
    if (!enabled || !Array.isArray(selectors) || selectors.length === 0) {
        if (existing) existing.remove();
        if (cosmeticSheet && document.adoptedStyleSheets) {
            try {
                document.adoptedStyleSheets = document.adoptedStyleSheets.filter((s) => s !== cosmeticSheet);
            } catch (_e) {}
            cosmeticSheet = null;
        }
        return;
    }

    const chunks = [];
    const batch = 50;
    for (let i = 0; i < selectors.length; i += batch) {
        const part = selectors.slice(i, i + batch).join(", ");
        chunks.push(part + " { display: none !important; visibility: hidden !important; height: 0 !important; min-height: 0 !important; max-height: 0 !important; margin: 0 !important; padding: 0 !important; border: 0 !important; overflow: hidden !important; }");
    }
    const cssText = chunks.join("\n");

    // Prefer zero-reflow adoptedStyleSheets when supported
    if (typeof CSSStyleSheet !== "undefined" && document.adoptedStyleSheets) {
        try {
            if (!cosmeticSheet) {
                cosmeticSheet = new CSSStyleSheet();
                document.adoptedStyleSheets = [...document.adoptedStyleSheets, cosmeticSheet];
            }
            cosmeticSheet.replaceSync(cssText);
            if (existing) existing.remove();
            return;
        } catch (_e) {}
    }

    const styleEl = existing || document.createElement("style");
    styleEl.id = id;
    styleEl.textContent = cssText;
    (document.head || document.documentElement).appendChild(styleEl);
}

// -----------------------------------------------------------------------------
// Blocked Ad Element, First-Party GIF/Static Banner & Empty Slot Collapsing
// -----------------------------------------------------------------------------
const FIRST_PARTY_AD_HREF_RE = /(doubleclick|googlesyndication|adservice|popads|propellerads|adsterra|exoclick|monetag|clickadu|hilltopads|outbrain|taboola|mgid|revcontent|\/adclick|\/click\.php|\/banner_click|\/out\.php\?|\/go\/bet|\/go\/casino|1xbet|melbet|mostbet|betwinner|parimatch)/i;
const FIRST_PARTY_AD_IMG_RE = /(\/ads\/|\/banners?\/|\/promo[-_]?banner|ad[-_]?banner|300x250|728x90|320x50|160x600|468x60|1xbet|melbet|mostbet|betwinner|casino[-_]?banner)/i;

function collapseElement(el) {
    if (!el || el.nodeType !== 1 || el.dataset.uhCollapsed === "1") return;
    el.dataset.uhCollapsed = "1";
    el.style.setProperty("display", "none", "important");
    el.style.setProperty("visibility", "hidden", "important");
    el.style.setProperty("width", "0", "important");
    el.style.setProperty("height", "0", "important");
    el.style.setProperty("min-height", "0", "important");
    el.style.setProperty("margin", "0", "important");
    el.style.setProperty("padding", "0", "important");
    el.style.setProperty("border", "0", "important");
    collapseEmptyAdAncestors(el.parentElement);
}

function looksLikeAdWrapper(el) {
    if (!el || el === document.body || el === document.documentElement) return false;
    const tag = el.tagName;
    if (tag === "INS" || tag === "ASIDE") return true;
    if (tag === "A" || tag === "PICTURE" || tag === "FIGURE") return true;
    const idCls = ((el.id || "") + " " + (typeof el.className === "string" ? el.className : "")).toLowerCase();
    if (
        /(^|[\s_-])(ad|ads|advert|banner|sponsor|promo|dfp|gpt|taboola|outbrain|mgid|adsbygoogle)([\s_-]|$)/.test(idCls) ||
        el.hasAttribute("data-ad") ||
        el.hasAttribute("data-ad-slot") ||
        el.hasAttribute("data-ad-unit") ||
        el.hasAttribute("data-google-query-id")
    ) {
        return true;
    }
    return false;
}

function hasOtherVisibleContent(container) {
    const text = (container.textContent || "").trim();
    if (text.length > 24 && !/^(advertisement|sponsored|ad|ads|promoted)$/i.test(text)) {
        return true;
    }
    for (let i = 0; i < container.children.length; i++) {
        const ch = container.children[i];
        if (ch.dataset && ch.dataset.uhCollapsed === "1") continue;
        if (ch.tagName === "SCRIPT" || ch.tagName === "STYLE" || ch.tagName === "NOSCRIPT") continue;
        try {
            const cs = getComputedStyle(ch);
            if (cs.display === "none" || cs.visibility === "hidden") continue;
            const rect = ch.getBoundingClientRect();
            if (rect.width > 4 && rect.height > 4) return true;
        } catch (_e) {}
    }
    return false;
}

function collapseEmptyAdAncestors(parent) {
    let cur = parent;
    let depth = 0;
    while (cur && depth < 4 && cur !== document.body && cur !== document.documentElement) {
        if (!looksLikeAdWrapper(cur)) break;
        if (hasOtherVisibleContent(cur)) break;
        cur.dataset.uhCollapsed = "1";
        cur.style.setProperty("display", "none", "important");
        cur.style.setProperty("height", "0", "important");
        cur.style.setProperty("min-height", "0", "important");
        cur.style.setProperty("margin", "0", "important");
        cur.style.setProperty("padding", "0", "important");
        cur = cur.parentElement;
        depth++;
    }
}

function urlMatchesBlocked(rawAttr) {
    if (!rawAttr || blockedUrls.size === 0) return false;
    if (blockedUrls.has(rawAttr)) return true;
    try {
        const abs = new URL(rawAttr, window.location.href).href;
        if (blockedUrls.has(abs)) return true;
        const noHash = abs.split("#")[0];
        if (blockedUrls.has(noHash)) return true;
    } catch (_e) {}
    for (const hint of blockedPathHints) {
        if (rawAttr.includes(hint)) return true;
    }
    return false;
}

function scanAndCollapseAdBanners(rootNode) {
    const shieldOn = pageProtectState && (pageProtectState.shieldLevel || 0) > 0 && !pageProtectState.paused;
    if (!shieldOn && blockedUrls.size === 0) return;
    const scope = rootNode && rootNode.querySelectorAll ? rootNode : document;
    let collapsedCount = 0;
    try {
        const nodes = scope.querySelectorAll("img, iframe, object, embed, video, source");
        for (let i = 0; i < nodes.length; i++) {
            const el = nodes[i];
            if (el.dataset && el.dataset.uhCollapsed === "1") continue;
            const src = el.currentSrc || el.src || el.getAttribute("src") || el.getAttribute("data-src") || el.getAttribute("data") || "";
            const srcset = el.getAttribute("srcset") || "";
            if (blockedUrls.size > 0 && (urlMatchesBlocked(src) || (srcset && urlMatchesBlocked(srcset.split(",")[0].trim().split(/\s+/)[0])))) {
                const target = el.tagName === "SOURCE" && el.parentElement ? el.parentElement : el;
                collapseElement(target);
                continue;
            }
            // Collapse first-party GIF / static ad banners wrapped in ad/betting click links
            if (shieldOn && el.tagName === "IMG") {
                const anchor = el.closest("a[href]");
                const href = anchor ? (anchor.href || "") : "";
                if ((href && FIRST_PARTY_AD_HREF_RE.test(href)) || (src && FIRST_PARTY_AD_IMG_RE.test(src))) {
                    collapseElement(anchor || el);
                    collapsedCount++;
                }
            }
        }
    } catch (_e) {}
    if (collapsedCount > 0) {
        api.runtime.sendMessage({
            type: "UH_CONTENT_BLOCKED_AD",
            host: currentNormalizedHost()
        }).catch(() => {});
    }
}

function scheduleCollapseSweep() {
    if (collapseRaf) return;
    collapseRaf = requestAnimationFrame(() => {
        collapseRaf = 0;
        scanAndCollapseAdBanners(document);
    });
}

document.addEventListener("error", (ev) => {
    const el = ev && ev.target;
    if (!el || el.nodeType !== 1) return;
    if (el.tagName === "IMG" || el.tagName === "IFRAME" || el.tagName === "OBJECT") {
        const src = el.currentSrc || el.src || el.getAttribute("src") || "";
        if (urlMatchesBlocked(src)) {
            collapseElement(el);
        }
    }
}, true);

// -----------------------------------------------------------------------------
// Frame-Throttled Procedural Cosmetic Engine (:has-text / :-abp-contains / :upward)
// -----------------------------------------------------------------------------
function runProceduralCosmetics(rules) {
    if (!Array.isArray(rules) || rules.length === 0) return;
    for (let i = 0; i < rules.length; i++) {
        const r = rules[i];
        if (!r || !r.sel || !r.text) continue;
        let nodes;
        try {
            nodes = document.querySelectorAll(r.sel);
        } catch (_e) {
            continue;
        }
        let re = null;
        if (r.re) {
            try { re = new RegExp(r.text, "i"); } catch (_e) { continue; }
        }
        const needle = r.re ? "" : r.text.toLowerCase();
        for (let j = 0; j < nodes.length; j++) {
            const el = nodes[j];
            if (el.dataset && el.dataset.uhProcHidden === "1") continue;
            const txt = (el.textContent || "").trim();
            const matched = re ? re.test(txt) : txt.toLowerCase().includes(needle);
            if (!matched) continue;
            let target = el;
            let up = r.up || 0;
            while (up > 0 && target.parentElement && target.parentElement !== document.body) {
                target = target.parentElement;
                up--;
            }
            target.dataset.uhProcHidden = "1";
            target.style.setProperty("display", "none", "important");
        }
    }
}

function setupProceduralCosmetics(rules, enabled) {
    if (proceduralObserver) {
        try { proceduralObserver.disconnect(); } catch (_e) {}
        proceduralObserver = null;
    }
    if (!enabled) return;

    let lastRun = 0;
    const trigger = () => {
        if (proceduralRaf) return;
        proceduralRaf = requestAnimationFrame(() => {
            proceduralRaf = 0;
            const now = performance.now();
            if (now - lastRun < 250) return;
            lastRun = now;
            if (Array.isArray(rules) && rules.length > 0) {
                runProceduralCosmetics(rules);
            }
            scanAndCollapseAdBanners(document);
        });
    };

    if (document.body) {
        trigger();
    } else {
        document.addEventListener("DOMContentLoaded", trigger, { once: true });
    }

    proceduralObserver = new MutationObserver((mutations) => {
        for (let i = 0; i < mutations.length; i++) {
            if (mutations[i].addedNodes && mutations[i].addedNodes.length > 0) {
                trigger();
                break;
            }
        }
    });
    proceduralObserver.observe(document.documentElement || document, { childList: true, subtree: true });
}

// -----------------------------------------------------------------------------
// Upgraded Shadow-DOM + CMP Cookie Banner Auto-Rejector
// -----------------------------------------------------------------------------
const COOKIE_REJECT_SELECTORS = [
    "#onetrust-reject-all-handler",
    "#CybotCookiebotDialogBodyButtonDecline",
    ".qc-cmp2-summary-buttons button[mode='secondary']",
    ".fc-cta-do-not-consent",
    "#didomi-notice-disagree-button",
    "button.didomi-components-button[aria-label*='Disagree' i]",
    "button[data-testid='uc-deny-all-button']",
    "button[data-testid='reject-all']",
    "button[data-testid*='reject' i]",
    "button[data-cky-tag='reject-button']",
    ".cmplz-deny",
    ".brlbs-btn-refuse-all",
    ".cmpboxbtnno",
    ".osano-cm-denyAll",
    ".klaro .cn-decline",
    "button[id*='reject-all' i]",
    "button[id*='rejectAll' i]",
    "button[id*='decline-all' i]",
    "button[class*='reject-all' i]",
    "button[class*='decline-all' i]",
    "button[aria-label*='reject all' i]",
    "button[aria-label*='decline all' i]",
    "button[aria-label*='necessary only' i]",
    "[data-gdpr-single-choice-accept='false']"
];

const COOKIE_TEXT_REJECT_RE = /^(reject all|decline all|deny all|refuse all|only necessary|necessary only|essential only|reject non-essential|continue without accepting|do not consent)$/i;

function restorePageScrollLock() {
    try {
        const html = document.documentElement;
        const body = document.body;
        if (html && getComputedStyle(html).overflow === "hidden") {
            html.style.setProperty("overflow", "auto", "important");
        }
        if (body && getComputedStyle(body).overflow === "hidden") {
            body.style.setProperty("overflow", "auto", "important");
        }
    } catch (_e) {}
}

function searchAndClickRejectInRoot(root, depth) {
    if (!root || depth > 4) return false;
    for (let i = 0; i < COOKIE_REJECT_SELECTORS.length; i++) {
        try {
            const btn = root.querySelector(COOKIE_REJECT_SELECTORS[i]);
            if (btn && btn.offsetParent !== null) {
                btn.click();
                return true;
            }
        } catch (_e) {}
    }

    try {
        const btns = root.querySelectorAll("button, a[role='button']");
        for (let i = 0; i < btns.length && i < 120; i++) {
            const b = btns[i];
            if (b.offsetParent === null) continue;
            const txt = (b.textContent || "").trim().replace(/\s+/g, " ");
            if (txt.length >= 6 && txt.length <= 36 && COOKIE_TEXT_REJECT_RE.test(txt)) {
                const container = b.closest("[id*='cookie' i], [class*='cookie' i], [id*='consent' i], [class*='consent' i], [id*='gdpr' i], [class*='gdpr' i], [role='dialog'], [aria-modal='true']");
                if (container || root !== document) {
                    b.click();
                    return true;
                }
            }
        }
    } catch (_e) {}

    try {
        const hosts = root.querySelectorAll("#usercentrics-root, [id*='consent' i], [id*='cookie' i], [id*='cmp' i]");
        let checked = 0;
        for (let i = 0; i < hosts.length && checked < 20; i++) {
            const h = hosts[i];
            if (h.shadowRoot) {
                checked++;
                if (searchAndClickRejectInRoot(h.shadowRoot, depth + 1)) {
                    return true;
                }
            }
        }
    } catch (_e) {}

    return false;
}

function runCookieBannerAutoReject() {
    if (!isTopFrame || cookieBannerRejected) return;
    if (searchAndClickRejectInRoot(document, 0)) {
        cookieBannerRejected = true;
        if (cookieBannerObserver) {
            try { cookieBannerObserver.disconnect(); } catch (_e) {}
            cookieBannerObserver = null;
        }
        setTimeout(restorePageScrollLock, 250);
    }
}

function startCookieBannerWatcher(enabled) {
    if (!isTopFrame) return;
    if (cookieBannerObserver) {
        try { cookieBannerObserver.disconnect(); } catch (_e) {}
        cookieBannerObserver = null;
    }
    if (cookieBannerStopTimer) {
        clearTimeout(cookieBannerStopTimer);
        cookieBannerStopTimer = null;
    }
    if (!enabled || cookieBannerRejected) return;

    let debounce = null;
    const kick = () => {
        if (cookieBannerRejected || debounce) return;
        debounce = setTimeout(() => {
            debounce = null;
            runCookieBannerAutoReject();
        }, 350);
    };

    setTimeout(runCookieBannerAutoReject, 600);
    setTimeout(runCookieBannerAutoReject, 1800);

    cookieBannerObserver = new MutationObserver(kick);
    cookieBannerObserver.observe(document.documentElement || document, { childList: true, subtree: true });

    cookieBannerStopTimer = setTimeout(() => {
        if (cookieBannerObserver) {
            try { cookieBannerObserver.disconnect(); } catch (_e) {}
            cookieBannerObserver = null;
        }
    }, 10000);
}

// -----------------------------------------------------------------------------
// Global True AMOLED Website Dark Mode (Light Force-Dark + Already-Dark Deepener)
// -----------------------------------------------------------------------------
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

function applySmartDarkMode(forceDarkEnabled, amoledEnabled, bypassed) {
    if (!isTopFrame) return;
    const id = "__uh_smart_dark";
    const amoledId = "__uh_amoled_deep";
    const existing = document.getElementById(id);
    const existingAmoled = document.getElementById(amoledId);

    if (bypassed || (!forceDarkEnabled && !amoledEnabled)) {
        if (existing) existing.remove();
        if (existingAmoled) existingAmoled.remove();
        return;
    }

    const evaluateAndInject = () => {
        const alreadyDark = isPageAlreadyDark();

        if (alreadyDark) {
            // Never invert already-dark sites; when AMOLED is on, deepen dark-grey base surfaces to #000000
            const invEl = document.getElementById(id);
            if (invEl) invEl.remove();

            if (amoledEnabled) {
                const amoledCss = [
                    "html, body { background-color: #000000 !important; color-scheme: dark !important; }",
                    ":root { --yt-spec-base-background: #000000 !important; --yt-spec-raised-background: #0A0A0A !important; --bgColor-default: #000000 !important; --color-canvas-default: #000000 !important; }",
                    "ytd-app, ytm-app, #app, #root, #__next, main { background-color: #000000 !important; }"
                ].join("\n");
                const el = document.getElementById(amoledId) || document.createElement("style");
                el.id = amoledId;
                el.textContent = amoledCss;
                (document.head || document.documentElement).appendChild(el);
            } else {
                const el = document.getElementById(amoledId);
                if (el) el.remove();
            }
            return;
        }

        // Page is bright: remove amoled-only deepener
        const amEl = document.getElementById(amoledId);
        if (amEl) amEl.remove();

        // Apply Force Dark (or full AMOLED global inversion when AMOLED Dark Mode is enabled)
        if (!forceDarkEnabled && !amoledEnabled) {
            const invEl = document.getElementById(id);
            if (invEl) invEl.remove();
            return;
        }

        const inv = amoledEnabled ? "1" : "0.90";
        const contrast = amoledEnabled ? "1.05" : "0.96";
        const bgHex = amoledEnabled ? "#000000" : "#121318";
        const css = [
            "html { background-color: " + bgHex + " !important; color-scheme: dark !important; filter: invert(" + inv + ") hue-rotate(180deg) contrast(" + contrast + ") !important; }",
            "img, video, picture, canvas, svg, [style*='background-image'], iframe { filter: invert(1) hue-rotate(180deg) !important; }",
            "img[src$='.svg'], img[src*='logo'] { filter: invert(0.1) !important; }",
            "video:fullscreen, video:-webkit-full-screen, .__uh_pip_video_only { filter: none !important; }"
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

function applyPageProtectConfig(cfg) {
    if (!cfg) return;
    pageProtectState = cfg;
    const shieldOn = (cfg.shieldLevel || 0) > 0 && !cfg.paused;
    applyCosmeticStyle(cfg.cosmetics || [], shieldOn);
    setupProceduralCosmetics(cfg.procedural || [], shieldOn);
    applySmartDarkMode(cfg.forceDarkWeb === true, cfg.enhanceDark === true, cfg.darkBypassed === true);
    if (scriptletsReady) {
        dispatchScriptletConfig(cfg);
    }
    startCookieBannerWatcher(cfg.cookieBanners === true && !cfg.paused);
}

api.runtime.sendMessage({
    type: "UH_GET_PAGE_PROTECT",
    url: window.location.href
}).then((cfg) => {
    if (cfg) applyPageProtectConfig(cfg);
}).catch(() => {});

// -----------------------------------------------------------------------------
// 1C. Rich Media Metadata Enricher + Video-Only PiP Isolation Engine
// -----------------------------------------------------------------------------
let activeMediaEl = null;
let pipIsolatedVideo = null;
let pipSavedStyle = null;
let pipSavedParent = null;
let pipSavedNextSibling = null;
let pipSavedScrollX = 0;
let pipSavedScrollY = 0;
let mediaReportTimer = null;

function findPrimaryMediaElement() {
    const videos = Array.from(document.querySelectorAll("video"));
    // Prefer actively playing video with largest visible area
    let best = null;
    let bestScore = -1;
    for (let i = 0; i < videos.length; i++) {
        const v = videos[i];
        const rect = v.getBoundingClientRect();
        const area = Math.max(0, rect.width) * Math.max(0, rect.height);
        const playingBonus = (!v.paused && !v.ended && v.readyState >= 2) ? 10000000 : 0;
        const score = playingBonus + area + ((v.videoWidth || 0) * (v.videoHeight || 0));
        if (score > bestScore && (v.readyState >= 1 || !v.paused || area > 10000)) {
            bestScore = score;
            best = v;
        }
    }
    if (best) return best;
    const audios = Array.from(document.querySelectorAll("audio"));
    for (let i = 0; i < audios.length; i++) {
        if (!audios[i].paused && !audios[i].ended) return audios[i];
    }
    return audios[0] || null;
}

function extractCleanMediaMetadata(mediaEl) {
    const ms = navigator.mediaSession && navigator.mediaSession.metadata;
    const host = currentNormalizedHost();
    let title = (ms && ms.title) ? String(ms.title).trim() : "";
    let artist = (ms && ms.artist) ? String(ms.artist).trim() : "";
    let artworkUrl = "";

    if (ms && Array.isArray(ms.artwork) && ms.artwork.length > 0) {
        artworkUrl = ms.artwork[ms.artwork.length - 1].src || ms.artwork[0].src || "";
    }

    if (!title) {
        const ogTitle = document.querySelector('meta[property="og:title"], meta[name="twitter:title"]');
        title = (ogTitle && ogTitle.content) ? ogTitle.content.trim() : "";
    }
    if (!title) {
        const h1 = document.querySelector("h1.ytd-watch-metadata, h1.slim-video-information-title, h1");
        if (h1) title = (h1.textContent || "").trim();
    }
    if (!title) {
        title = (document.title || "").trim();
    }
    // Strip common site suffixes like " - YouTube", " | Watch Online"
    title = title.replace(/\s*[-–|·]\s*(YouTube|Vimeo|Twitch|Dailymotion|Facebook|Instagram|TikTok|X)$/i, "").trim();
    if (!title || /^https?:\/\//i.test(title)) {
        title = host ? ("Playing on " + host) : "Media Playback";
    }

    if (!artist) {
        const channelEl = document.querySelector("#channel-name a, .slim-owner-channel-name, meta[name='author'], meta[property='og:site_name']");
        if (channelEl) {
            artist = (channelEl.content || channelEl.textContent || "").trim();
        }
    }
    if (!artist) {
        artist = host || "UH Browser";
    }

    // Artwork fallbacks: YouTube thumbnail -> video poster -> og:image -> link icon
    if (!artworkUrl && (host === "youtube.com" || host === "m.youtube.com" || host === "youtu.be")) {
        try {
            const u = new URL(window.location.href);
            const vid = u.searchParams.get("v") || (host === "youtu.be" ? u.pathname.slice(1) : "");
            if (vid && /^[a-zA-Z0-9_-]{6,16}$/.test(vid)) {
                artworkUrl = "https://i.ytimg.com/vi/" + vid + "/hqdefault.jpg";
            }
        } catch (_e) {}
    }
    if (!artworkUrl && mediaEl && mediaEl.poster) {
        artworkUrl = mediaEl.poster;
    }
    if (!artworkUrl) {
        const ogImg = document.querySelector('meta[property="og:image"], meta[name="twitter:image"]');
        if (ogImg && ogImg.content) artworkUrl = ogImg.content;
    }
    if (artworkUrl && !artworkUrl.startsWith("http") && !artworkUrl.startsWith("data:")) {
        try { artworkUrl = new URL(artworkUrl, window.location.href).href; } catch (_e) {}
    }

    return { title, artist, artworkUrl };
}

function reportMediaStateNow() {
    const el = findPrimaryMediaElement();
    activeMediaEl = el;
    if (!el) return;
    const isVideo = el.tagName === "VIDEO";
    const meta = extractCleanMediaMetadata(el);
    const dur = isFinite(el.duration) && el.duration > 0 ? el.duration : 0;
    const pos = isFinite(el.currentTime) && el.currentTime >= 0 ? el.currentTime : 0;
    api.runtime.sendMessage({
        type: "UH_MEDIA_STATE",
        title: meta.title,
        artist: meta.artist,
        artworkUrl: meta.artworkUrl,
        pageUrl: window.location.href,
        playing: !el.paused && !el.ended,
        hasVideo: isVideo,
        durationSec: dur,
        positionSec: pos,
        videoWidth: isVideo ? (el.videoWidth || el.clientWidth || 16) : 0,
        videoHeight: isVideo ? (el.videoHeight || el.clientHeight || 9) : 0
    }).catch(() => {});
}

function scheduleMediaReport() {
    if (mediaReportTimer) return;
    mediaReportTimer = setTimeout(() => {
        mediaReportTimer = null;
        reportMediaStateNow();
    }, 250);
}

// Listen cleanly for HTML5 media play/pause/seek/loadedmetadata events
["play", "playing", "pause", "ended", "loadedmetadata", "durationchange", "seeked"].forEach((evt) => {
    document.addEventListener(evt, (e) => {
        const t = e && e.target;
        if (t && (t.tagName === "VIDEO" || t.tagName === "AUDIO")) {
            activeMediaEl = t;
            scheduleMediaReport();
        }
    }, true);
});

// Periodic lightweight position sync ONLY while actively playing (every 2s)
setInterval(() => {
    if (activeMediaEl && !activeMediaEl.paused && !activeMediaEl.ended) {
        reportMediaStateNow();
    }
}, 2000);

function handleMediaControlAction(msg) {
    const el = activeMediaEl || findPrimaryMediaElement();
    if (!el) return;
    try {
        switch (msg.command) {
            case "PLAY":
                el.play().catch(() => {});
                break;
            case "PAUSE":
                el.pause();
                break;
            case "TOGGLE_PLAY_PAUSE":
                if (el.paused) el.play().catch(() => {}); else el.pause();
                break;
            case "SEEK_RELATIVE": {
                const delta = Number(msg.deltaSec) || 0;
                const dur = isFinite(el.duration) ? el.duration : (el.currentTime + Math.abs(delta));
                el.currentTime = Math.max(0, Math.min(dur, (el.currentTime || 0) + delta));
                break;
            }
            case "SEEK_TO": {
                const target = Number(msg.positionSec);
                if (isFinite(target) && target >= 0) {
                    el.currentTime = target;
                }
                break;
            }
            case "NEXT": {
                const nextBtn = document.querySelector(".ytp-next-button, button[aria-label*='Next' i], a[aria-label*='Next' i]");
                if (nextBtn) nextBtn.click();
                else if (isFinite(el.duration)) el.currentTime = Math.min(el.duration, el.currentTime + 30);
                break;
            }
            case "PREV": {
                if (el.currentTime > 4) {
                    el.currentTime = 0;
                } else {
                    const prevBtn = document.querySelector(".ytp-prev-button, button[aria-label*='Previous' i]");
                    if (prevBtn) prevBtn.click();
                    else el.currentTime = 0;
                }
                break;
            }
        }
        setTimeout(reportMediaStateNow, 120);
    } catch (_e) {}
}

function setPipVideoIsolation(enable) {
    const styleId = "__uh_pip_isolation_style";
    if (enable) {
        if (pipIsolatedVideo) return;
        const video = (activeMediaEl && activeMediaEl.tagName === "VIDEO") ? activeMediaEl : findPrimaryMediaElement();
        if (!video || video.tagName !== "VIDEO") return;

        pipIsolatedVideo = video;
        pipSavedScrollX = window.scrollX || 0;
        pipSavedScrollY = window.scrollY || 0;
        pipSavedStyle = video.getAttribute("style");
        pipSavedParent = video.parentNode;
        pipSavedNextSibling = video.nextSibling;

        let styleEl = document.getElementById(styleId);
        if (!styleEl) {
            styleEl = document.createElement("style");
            styleEl.id = styleId;
            styleEl.textContent = [
                "html.__uh_pip_active, html.__uh_pip_active body { margin: 0 !important; padding: 0 !important; overflow: hidden !important; background: #000000 !important; width: 100vw !important; height: 100vh !important; }",
                "html.__uh_pip_active body > *:not(.__uh_pip_host) { visibility: hidden !important; opacity: 0 !important; pointer-events: none !important; }",
                ".__uh_pip_host { position: fixed !important; inset: 0 !important; width: 100vw !important; height: 100vh !important; background: #000000 !important; z-index: 2147483647 !important; display: flex !important; align-items: center !important; justify-content: center !important; visibility: visible !important; opacity: 1 !important; }",
                ".__uh_pip_video_only { position: fixed !important; inset: 0 !important; width: 100vw !important; height: 100vh !important; max-width: 100vw !important; max-height: 100vh !important; object-fit: contain !important; background: #000000 !important; margin: 0 !important; padding: 0 !important; transform: none !important; visibility: visible !important; opacity: 1 !important; z-index: 2147483647 !important; }"
            ].join("\n");
            (document.head || document.documentElement).appendChild(styleEl);
        }

        const wasPlaying = !video.paused && !video.ended;
        const host = document.createElement("div");
        host.id = "__uh_pip_host";
        host.className = "__uh_pip_host";
        document.documentElement.classList.add("__uh_pip_active");
        video.classList.add("__uh_pip_video_only");
        host.appendChild(video);
        (document.body || document.documentElement).appendChild(host);
        if (wasPlaying && video.paused) {
            video.play().catch(() => {});
        }
    } else {
        document.documentElement.classList.remove("__uh_pip_active");
        const styleEl = document.getElementById(styleId);
        if (styleEl) styleEl.remove();

        const video = pipIsolatedVideo;
        if (video) {
            const wasPlaying = !video.paused && !video.ended;
            video.classList.remove("__uh_pip_video_only");
            if (pipSavedStyle !== null) video.setAttribute("style", pipSavedStyle);
            else video.removeAttribute("style");

            if (pipSavedParent && document.contains(pipSavedParent)) {
                try {
                    if (pipSavedNextSibling && pipSavedNextSibling.parentNode === pipSavedParent) {
                        pipSavedParent.insertBefore(video, pipSavedNextSibling);
                    } else {
                        pipSavedParent.appendChild(video);
                    }
                } catch (_e) {}
            }
            const host = document.getElementById("__uh_pip_host");
            if (host) host.remove();
            if (wasPlaying && video.paused) {
                video.play().catch(() => {});
            }
            window.scrollTo(pipSavedScrollX, pipSavedScrollY);
        }
        pipIsolatedVideo = null;
        pipSavedStyle = null;
        pipSavedParent = null;
        pipSavedNextSibling = null;
    }
}

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
    const canvasData = tryExtractCanvasDataUrl(img);
    if (canvasData) {
        return { imageUrl: src.startsWith("http") ? src : "", dataUrl: canvasData };
    }
    const fetchedData = await tryFetchImageWithCredentials(src);
    if (fetchedData) {
        return { imageUrl: src.startsWith("http") ? src : "", dataUrl: fetchedData };
    }
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
        sendNativeCall({ type: "IMAGE_TRANSLATE_DONE", imageCount: 0, overlayCount: 0 });
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
        candidates.push({ id: id, imageUrl: cap.imageUrl, dataUrl: cap.dataUrl });
    }

    if (!candidates.length) {
        sendNativeCall({ type: "IMAGE_TRANSLATE_DONE", imageCount: 0, overlayCount: 0 });
        return;
    }

    const resp = await sendNativeCall({
        type: "TRANSLATE_IMAGES_OCR",
        fromLang: fromLang || "auto",
        toLang: toLang || "en",
        images: candidates
    });

    if (!resp || !Array.isArray(resp.results)) {
        sendNativeCall({ type: "IMAGE_TRANSLATE_DONE", imageCount: candidates.length, overlayCount: 0 });
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
    if (msg.action === "COLLAPSE_BLOCKED_URL" && msg.url) {
        blockedUrls.add(msg.url);
        blockedUrls.add(msg.url.split("#")[0]);
        try {
            const u = new URL(msg.url);
            if (u.pathname && u.pathname.length > 6) {
                blockedPathHints.add(u.pathname + (u.search || ""));
            }
        } catch (_e) {}
        scheduleCollapseSweep();
    } else if (msg.action === "SET_PIP_ISOLATION") {
        setPipVideoIsolation(msg.enabled === true);
    } else if (msg.action === "MEDIA_CONTROL_ACTION") {
        handleMediaControlAction(msg);
    } else if (msg.action === "RUN_MENU_COMMAND" && msg.commandId) {
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
            antiFingerprint: msg.antiFingerprint === true && !paused,
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
