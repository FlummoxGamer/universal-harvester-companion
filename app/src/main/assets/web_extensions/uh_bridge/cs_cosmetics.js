// -----------------------------------------------------------------------------
// UH Bridge Content Module 1: Shared State, Scriptlets, Cosmetics & Ad Collapse
// -----------------------------------------------------------------------------
(function () {
    const api = typeof browser !== "undefined" ? browser : chrome;

    const isTopFrame = (function () {
        try { return window.self === window.top; } catch (_e) { return true; }
    })();

    function sendNativeCall(payload) {
        return api.runtime.sendMessage({
            type: "UH_NATIVE_CALL",
            pageUrl: window.location.href,
            payload: payload
        }).catch(() => null);
    }

    function currentNormalizedHost() {
        try {
            return window.location.hostname.toLowerCase().replace(/^www\./, "");
        } catch (_e) {
            return "";
        }
    }

    const C = (globalThis.__uhCs = {
        api,
        isTopFrame,
        pageProtectState: null,
        scriptletsReady: false,
        blockedUrls: new Set(),
        blockedPathHints: new Set(),
        sendNativeCall,
        currentNormalizedHost
    });

    let proceduralObserver = null;
    let proceduralRaf = 0;
    let cosmeticSheet = null;
    let collapseRaf = 0;

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
        C.scriptletsReady = true;
        if (C.pageProtectState) {
            dispatchScriptletConfig(C.pageProtectState);
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

    // -------------------------------------------------------------------------
    // Blocked Ad Element, First-Party GIF/Static Banner & Empty Slot Collapsing
    // -------------------------------------------------------------------------
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
        if (!rawAttr || C.blockedUrls.size === 0) return false;
        if (C.blockedUrls.has(rawAttr)) return true;
        try {
            const abs = new URL(rawAttr, window.location.href).href;
            if (C.blockedUrls.has(abs)) return true;
            const noHash = abs.split("#")[0];
            if (C.blockedUrls.has(noHash)) return true;
        } catch (_e) {}
        for (const hint of C.blockedPathHints) {
            if (rawAttr.includes(hint)) return true;
        }
        return false;
    }

    function scanAndCollapseAdBanners(rootNode) {
        const shieldOn = C.pageProtectState && (C.pageProtectState.shieldLevel || 0) > 0 && !C.pageProtectState.paused;
        if (!shieldOn && C.blockedUrls.size === 0) return;
        const scope = rootNode && rootNode.querySelectorAll ? rootNode : document;
        let collapsedCount = 0;
        try {
            const nodes = scope.querySelectorAll("img, iframe, object, embed, video, source");
            for (let i = 0; i < nodes.length; i++) {
                const el = nodes[i];
                if (el.dataset && el.dataset.uhCollapsed === "1") continue;
                const src = el.currentSrc || el.src || el.getAttribute("src") || el.getAttribute("data-src") || el.getAttribute("data") || "";
                const srcset = el.getAttribute("srcset") || "";
                if (C.blockedUrls.size > 0 && (urlMatchesBlocked(src) || (srcset && urlMatchesBlocked(srcset.split(",")[0].trim().split(/\s+/)[0])))) {
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

    // -------------------------------------------------------------------------
    // Frame-Throttled Procedural Cosmetic Engine (:has-text / :-abp-contains / :upward)
    // -------------------------------------------------------------------------
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

    C.dispatchScriptletConfig = dispatchScriptletConfig;
    C.applyCosmeticStyle = applyCosmeticStyle;
    C.scheduleCollapseSweep = scheduleCollapseSweep;
    C.setupProceduralCosmetics = setupProceduralCosmetics;
})();
