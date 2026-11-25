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
                    strictFingerprint: cfg.strictFingerprint === true,
                    fpSessionSalt: cfg.fpSessionSalt || "",
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

    // Relay scriptlet-blocked popunders/meta-refresh redirects to native toast & stats
    document.addEventListener("UH_SCRIPTLET_BLOCKED_REDIRECT", (ev) => {
        try {
            const d = ev && ev.detail;
            if (!d || !d.targetUrl) return;
            api.runtime.sendMessage({
                type: "UH_BLOCKED_REDIRECT",
                sourceHost: d.sourceHost || currentNormalizedHost(),
                targetUrl: d.targetUrl,
                isPopupTab: d.isPopupTab === true
            }).catch(() => {});
        } catch (_e) {}
    });

    // Write synchronous boot snapshot BEFORE loading scriptlets.js so guards arm at line 1
    (function loadScriptletsRunner() {
        try {
            let bootCfg = {
                shieldLevel: 2,
                paused: false,
                blockPopups: true,
                backgroundPlay: true,
                webrtcPolicy: 1,
                antiFingerprint: true,
                strictFingerprint: false,
                annoyances: true,
                dnt: false,
                fpSessionSalt: ""
            };
            try {
                const cached = sessionStorage.getItem("__uh_boot_cfg");
                if (cached) {
                    const parsed = JSON.parse(cached);
                    if (parsed && typeof parsed === "object") {
                        bootCfg = Object.assign(bootCfg, parsed);
                    }
                }
            } catch (_e) {}
            if (document.documentElement) {
                document.documentElement.dataset.uhBoot = JSON.stringify(bootCfg);
            }
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

    // :style(...) filter rules injection
    function applyCustomStyleRules(styleRules, enabled) {
        const id = "__uh_shield_style_rules";
        const existing = document.getElementById(id);
        if (!enabled || !Array.isArray(styleRules) || styleRules.length === 0) {
            if (existing) existing.remove();
            return;
        }
        const lines = [];
        for (let i = 0; i < styleRules.length; i++) {
            const r = styleRules[i];
            if (r && r.sel && r.css) {
                lines.push(r.sel + " { " + r.css + " }");
            }
        }
        if (lines.length === 0) {
            if (existing) existing.remove();
            return;
        }
        const styleEl = existing || document.createElement("style");
        styleEl.id = id;
        styleEl.textContent = lines.join("\n");
        (document.head || document.documentElement).appendChild(styleEl);
    }

    // :remove() DOM node stripping
    let activeRemoveSelectors = [];
    function applyRemoveSelectors(selectors, enabled) {
        activeRemoveSelectors = (enabled && Array.isArray(selectors)) ? selectors : [];
        if (activeRemoveSelectors.length === 0) return;
        runRemoveSelectors();
    }

    function runRemoveSelectors() {
        if (!activeRemoveSelectors.length) return;
        for (let i = 0; i < activeRemoveSelectors.length; i++) {
            try {
                const nodes = document.querySelectorAll(activeRemoveSelectors[i]);
                for (let j = 0; j < nodes.length; j++) {
                    if (nodes[j] && nodes[j] !== document.body && nodes[j] !== document.documentElement) {
                        nodes[j].remove();
                    }
                }
            } catch (_e) {}
        }
    }

    // -------------------------------------------------------------------------
    // Deep Ad-Slot, Multi-Wrapper & First-Party Banner Collapsing (Zero Blank Gaps)
    // -------------------------------------------------------------------------
    const FIRST_PARTY_AD_HREF_RE = /(doubleclick|googlesyndication|adservice|popads|propellerads|adsterra|exoclick|monetag|clickadu|hilltopads|outbrain|taboola|mgid|revcontent|\/adclick|\/click\.php|\/banner_click|\/out\.php\?|\/go\/bet|\/go\/casino|jaya9|babu88|krikya|baji(?:live|999|88)|bj88|crickex|jeebet|jeetbuzz|mcw\d*|nagad88|1xbet|melbet|mostbet|betwinner|parimatch|[?&](?:af|btag)=[a-z0-9_-]{4,})/i;
    const FIRST_PARTY_AD_IMG_RE = /(\/ads\/|\/banners?\/|\/promo[-_]?banner|ad[-_]?banner|300x250|728x90|320x50|160x600|468x60|jaya9|babu88|krikya|baji|crickex|1xbet|melbet|mostbet|betwinner|casino[-_]?banner)/i;
    const AD_SLOT_PLACEHOLDER_SELECTOR = [
        "ins.adsbygoogle",
        "[id^='div-gpt-ad']",
        "[id^='google_ads_iframe']",
        "[id*='advert' i]",
        "[data-ad]",
        "[data-ad-slot]",
        "[data-ad-unit]",
        "[data-ad-name]",
        "[data-google-query-id]",
        "[data-testid*='ad-' i]",
        "[data-testid*='advert' i]",
        "[class*='ad-slot' i]",
        "[class*='ad_slot' i]",
        "[class*='adSlot' i]",
        "[class*='adUnit' i]",
        "[class*='ad-unit' i]",
        "[class*='ad-wrapper' i]",
        "[class*='ad_wrapper' i]",
        "[class*='ad-container' i]",
        "[class*='ad_container' i]",
        "[class*='ad-placeholder' i]",
        "[class*='ad-banner' i]",
        "[class*='advert' i]",
        "[class*='leaderboard-ad' i]",
        "[class*='billboard-ad' i]",
        "[aria-label='Advertisement' i]",
        "[aria-label='Sponsored' i]"
    ].join(", ");

    const ORPHAN_AD_LABEL_RE = /^(advertisement|advertising|sponsored|sponsored content|sponsored post|promoted|paid content|ad|ads|continue reading below|scroll to continue with content|story continues below)$/i;

    function isCaptchaHost() {
        try {
            const h = currentNormalizedHost();
            const p = (window.location.pathname || "").toLowerCase();
            if (p.startsWith("/cdn-cgi/") && !p.startsWith("/cdn-cgi/rum")) return true;
            return h === "challenges.cloudflare.com" ||
                h === "hcaptcha.com" || h.endsWith(".hcaptcha.com") ||
                h === "recaptcha.net" || h.endsWith(".recaptcha.net") ||
                h === "arkoselabs.com" || h.endsWith(".arkoselabs.com") ||
                h === "funcaptcha.com" || h.endsWith(".funcaptcha.com") ||
                h === "geetest.com" || h.endsWith(".geetest.com") ||
                h === "friendlycaptcha.com" || h.endsWith(".friendlycaptcha.com") ||
                h === "captcha-delivery.com" || h.endsWith(".captcha-delivery.com");
        } catch (_e) {
            return false;
        }
    }

    function forceZeroBox(el) {
        el.dataset.uhCollapsed = "1";
        el.style.setProperty("display", "none", "important");
        el.style.setProperty("visibility", "hidden", "important");
        el.style.setProperty("width", "0", "important");
        el.style.setProperty("height", "0", "important");
        el.style.setProperty("min-width", "0", "important");
        el.style.setProperty("min-height", "0", "important");
        el.style.setProperty("max-height", "0", "important");
        el.style.setProperty("margin", "0", "important");
        el.style.setProperty("padding", "0", "important");
        el.style.setProperty("border", "0", "important");
        el.style.setProperty("overflow", "hidden", "important");
    }

    function collapseElement(el) {
        if (!el || el.nodeType !== 1 || el.dataset.uhCollapsed === "1") return;
        forceZeroBox(el);
        collapseEmptyAdAncestors(el.parentElement);
    }

    function looksLikeAdWrapper(el) {
        if (!el || el === document.body || el === document.documentElement) return false;
        const tag = el.tagName;
        if (tag === "MAIN" || tag === "HEADER" || tag === "FOOTER" || tag === "NAV" || tag === "ARTICLE") return false;
        if (tag === "INS" || tag === "ASIDE") return true;
        if (tag === "A" || tag === "PICTURE" || tag === "FIGURE") return true;
        const idCls = ((el.id || "") + " " + (typeof el.className === "string" ? el.className : "")).toLowerCase();
        if (
            /(^|[\s_-])(ad|ads|advert|advertisement|banner|sponsor|sponsored|promo|dfp|gpt|taboola|outbrain|mgid|adsbygoogle|adslot|adbox|adunit|adwrap|leaderboard|billboard|skyscraper|outstream)([\s_-]|$)/.test(idCls) ||
            el.hasAttribute("data-ad") ||
            el.hasAttribute("data-ad-slot") ||
            el.hasAttribute("data-ad-unit") ||
            el.hasAttribute("data-google-query-id") ||
            /^(advertisement|sponsored)$/i.test((el.getAttribute("aria-label") || "").trim())
        ) {
            return true;
        }
        // Generic Tailwind/React DIV/SECTION wrapper holding ONLY collapsed ad slots or an "Advertisement" label
        if ((tag === "DIV" || tag === "SECTION") && el.children.length <= 5) {
            const text = (el.textContent || "").trim();
            if (text.length === 0 || ORPHAN_AD_LABEL_RE.test(text)) {
                return true;
            }
        }
        return false;
    }

    function hasOtherVisibleContent(container) {
        const text = (container.textContent || "").trim();
        if (text.length > 36 && !ORPHAN_AD_LABEL_RE.test(text)) {
            return true;
        }
        if (container.querySelector("video, audio, form, input, textarea, select, canvas, .cf-turnstile, .h-captcha, .g-recaptcha")) {
            return true;
        }
        for (let i = 0; i < container.children.length; i++) {
            const ch = container.children[i];
            if (ch.dataset && ch.dataset.uhCollapsed === "1") continue;
            if (ch.tagName === "SCRIPT" || ch.tagName === "STYLE" || ch.tagName === "NOSCRIPT" || ch.tagName === "TEMPLATE") continue;
            const chText = (ch.textContent || "").trim();
            if (chText.length > 0 && ORPHAN_AD_LABEL_RE.test(chText) && !ch.querySelector("img, video, a[href], button")) {
                continue;
            }
            if (chText.length > 36 && !ORPHAN_AD_LABEL_RE.test(chText)) {
                return true;
            }
            try {
                const cs = getComputedStyle(ch);
                if (cs.display === "none" || cs.visibility === "hidden" || parseFloat(cs.opacity) === 0) continue;
                const rect = ch.getBoundingClientRect();
                if (rect.width <= 4 || rect.height <= 4) continue;
                // An empty tall box (no real text and no visible media/links) is an empty ad placeholder, not real content!
                const hasRealMedia = !!ch.querySelector("img[src]:not([data-uh-collapsed='1']), video, picture, svg, a[href], button");
                if (chText.length === 0 && !hasRealMedia) continue;
                return true;
            } catch (_e) {}
        }
        return false;
    }

    function collapseEmptyAdAncestors(parent) {
        let cur = parent;
        let depth = 0;
        while (cur && depth < 6 && cur !== document.body && cur !== document.documentElement) {
            if (!looksLikeAdWrapper(cur)) break;
            if (hasOtherVisibleContent(cur)) break;
            forceZeroBox(cur);
            cur = cur.parentElement;
            depth++;
        }
    }

    // Collapses orphan "Advertisement" / "Sponsored" text labels and their empty Tailwind/React ad wrappers
    // (fixes PCMag, Forbes, IGN & ad-test sites where ads are blocked but "Advertisement" captions stay behind)
    function sweepOrphanAdLabels() {
        const shieldOn = C.pageProtectState && (C.pageProtectState.shieldLevel || 0) > 0 && !C.pageProtectState.paused;
        if (!shieldOn || !document.body || isCaptchaHost()) return;
        try {
            const candidates = document.querySelectorAll("span, p, div, small, label, strong, em, figcaption, h5, h6");
            const limit = Math.min(candidates.length, 450);
            for (let i = 0; i < limit; i++) {
                const el = candidates[i];
                if (!el || (el.dataset && el.dataset.uhCollapsed === "1")) continue;
                if (el.children.length > 0) continue;
                const txt = (el.textContent || "").trim();
                if (txt.length < 2 || txt.length > 34) continue;
                if (!ORPHAN_AD_LABEL_RE.test(txt)) continue;
                if (el.closest("a[href], button, nav, header, footer")) continue;

                // Walk up up to 4 wrapper levels to find the outer ad wrapper containing ONLY this label + empty/collapsed slots
                let target = el;
                let cur = el.parentElement;
                let depth = 0;
                while (cur && depth < 4 && cur !== document.body && cur !== document.documentElement) {
                    const tag = cur.tagName;
                    if (tag === "MAIN" || tag === "ARTICLE" || tag === "HEADER" || tag === "NAV" || tag === "FOOTER") break;
                    if (hasOtherVisibleContent(cur)) break;
                    target = cur;
                    cur = cur.parentElement;
                    depth++;
                }
                collapseElement(target);
            }
        } catch (_e) {}
    }

    function sweepEmptyAdPlaceholders() {
        const shieldOn = C.pageProtectState && (C.pageProtectState.shieldLevel || 0) > 0 && !C.pageProtectState.paused;
        if (!shieldOn || !document.body || isCaptchaHost()) return;
        try {
            const slots = document.querySelectorAll(AD_SLOT_PLACEHOLDER_SELECTOR);
            const limit = Math.min(slots.length, 220);
            for (let i = 0; i < limit; i++) {
                const el = slots[i];
                if (!el || el === document.body || el === document.documentElement) continue;
                if (el.dataset && el.dataset.uhCollapsed === "1") {
                    // Re-walk ancestors in case a CSS-hidden slot's outer wrapper still renders
                    collapseEmptyAdAncestors(el.parentElement);
                    continue;
                }
                // If already hidden by CSS cosmetic rules, walk up and collapse empty outer ad wrappers!
                let cssHidden = false;
                try {
                    const cs = getComputedStyle(el);
                    cssHidden = cs.display === "none" || cs.visibility === "hidden";
                } catch (_e) {}
                if (cssHidden) {
                    el.dataset.uhCollapsed = "1";
                    collapseEmptyAdAncestors(el.parentElement);
                    continue;
                }
                // Otherwise collapse if slot is empty or only holds broken/hidden/caption nodes
                if (!hasOtherVisibleContent(el)) {
                    collapseElement(el);
                }
            }
            sweepOrphanAdLabels();
        } catch (_e) {}
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
            sweepEmptyAdPlaceholders();
        } catch (_e) {}
        if (collapsedCount > 0) {
            api.runtime.sendMessage({
                type: "UH_CONTENT_BLOCKED_AD",
                host: currentNormalizedHost()
            }).catch(() => {});
        }
    }

    // -------------------------------------------------------------------------
    // Annoyance Killer Pack (Google One-Tap, Open-in-App, Newsletters, Chat Bubbles, Sponsored Cards)
    // -------------------------------------------------------------------------
    const ANNOYANCE_DIRECT_SELECTORS = [
        // 1) Sign in with Google One-Tap popups
        "#credential_picker_container",
        "#credential_picker_iframe",
        "div[data-id='credential_picker_container']",
        "iframe[src*='accounts.google.com/gsi/']",
        // 2) Open in App / Smart App Banners
        "[id*='smartbanner' i]",
        "[class*='smartbanner' i]",
        "[id*='open-in-app' i]",
        "[class*='open-in-app' i]",
        "[data-testid*='app-banner' i]",
        "[class*='app-upsell' i]",
        "shreddit-experience-tree [slot='upsell']",
        "xpromo-app-selector",
        // 3) Floating support chat bubbles
        "#intercom-container",
        ".intercom-lightweight-app",
        "#drift-widget",
        "#drift-frame-controller",
        "#hubspot-messages-iframe-container",
        ".crisp-client",
        "#tawk-bubble-container",
        "[id^='tawk_']",
        ".zEWidget-launcher",
        "iframe[title*='chat widget' i]",
        "iframe[title*='live chat' i]",
        "iframe[title*='Messaging window' i]",
        "#tidio-chat",
        "#fc_frame",
        ".fb_dialog_content"
    ].join(", ");

    const OPEN_IN_APP_TEXT_RE = /(open in (the )?app|get the app|download (the )?app|continue in (the )?app|switch to (the )?app|use (the )?app)/i;
    const NEWSLETTER_TEXT_RE = /(subscribe to our newsletter|join our newsletter|sign up for our newsletter|get our daily newsletter|enter your email to subscribe|don't miss a story)/i;
    const SPONSORED_BADGE_RE = /^(sponsored|promoted|paid partner|paid post|partner content|advertisement|ad)$/i;

    function runAnnoyanceKillers() {
        if (!isTopFrame || !document.body || isCaptchaHost()) return;
        const st = C.pageProtectState;
        const shieldOn = st && (st.shieldLevel || 0) > 0 && !st.paused;
        if (!shieldOn || st.annoyances === false) return;

        let killed = 0;

        // A) Direct selector sweep (Google One-Tap, smartbanners, floating chat widgets)
        try {
            const directNodes = document.querySelectorAll(ANNOYANCE_DIRECT_SELECTORS);
            for (let i = 0; i < directNodes.length; i++) {
                const el = directNodes[i];
                if (el && el.dataset && el.dataset.uhCollapsed !== "1") {
                    collapseElement(el);
                    killed++;
                }
            }
        } catch (_e) {}

        // B) Fixed/sticky "Open in App" bars & Newsletter subscription popups
        try {
            const vw = window.innerWidth || 0;
            const vh = window.innerHeight || 0;
            if (vw >= 200 && vh >= 200) {
                const nodes = document.querySelectorAll("div, section, aside, dialog");
                const limit = Math.min(nodes.length, 220);
                for (let i = 0; i < limit; i++) {
                    const el = nodes[i];
                    if (!el || (el.dataset && el.dataset.uhCollapsed === "1")) continue;
                    const cs = getComputedStyle(el);
                    if (cs.display === "none" || cs.visibility === "hidden") continue;
                    if (cs.position !== "fixed" && cs.position !== "sticky") continue;
                    const z = parseInt(cs.zIndex, 10) || 0;
                    if (z < 50) continue;

                    const idCls = ((el.id || "") + " " + (typeof el.className === "string" ? el.className : "")).toLowerCase();
                    if (SAFE_MODAL_RE.test(idCls)) continue;
                    if (el.querySelector("video, audio, input[type='password'], .cf-turnstile")) continue;

                    const rect = el.getBoundingClientRect();
                    const text = (el.textContent || "").trim().slice(0, 320);
                    if (!text) continue;

                    // Open-in-App top/bottom bar (compact banner < 150px height)
                    if (rect.height > 28 && rect.height <= 150 && rect.width >= vw * 0.55 && OPEN_IN_APP_TEXT_RE.test(text)) {
                        collapseElement(el);
                        killed++;
                        continue;
                    }

                    // Newsletter modal overlay (large fixed dialog asking for email subscription)
                    if (
                        z >= 200 &&
                        rect.width >= vw * 0.45 &&
                        rect.height >= vh * 0.25 &&
                        NEWSLETTER_TEXT_RE.test(text) &&
                        !/(sign in|log in|create account|checkout|shipping)/i.test(text)
                    ) {
                        collapseElement(el);
                        restoreScrollLock();
                        killed++;
                    }
                }
            }
        } catch (_e) {}

        // C) First-party sponsored feed cards & native ad tiles
        try {
            const cards = document.querySelectorAll("article, li, [data-testid*='card' i], [class*='feed-item' i], [class*='story-card' i], [class*='promo-card' i], shreddit-ad-post");
            const limit = Math.min(cards.length, 140);
            for (let i = 0; i < limit; i++) {
                const card = cards[i];
                if (!card || (card.dataset && card.dataset.uhCollapsed === "1")) continue;
                if (card.tagName === "SHREDDIT-AD-POST") {
                    collapseElement(card);
                    killed++;
                    continue;
                }
                const badges = card.querySelectorAll("span, small, label, a, div, p");
                const bLimit = Math.min(badges.length, 24);
                let isSponsored = false;
                for (let j = 0; j < bLimit; j++) {
                    const b = badges[j];
                    if (b.children.length > 0) continue;
                    const t = (b.textContent || "").trim();
                    if (t.length >= 2 && t.length <= 20 && SPONSORED_BADGE_RE.test(t)) {
                        isSponsored = true;
                        break;
                    }
                }
                if (isSponsored) {
                    collapseElement(card);
                    killed++;
                }
            }
        } catch (_e) {}

        if (killed > 0) {
            api.runtime.sendMessage({
                type: "UH_CONTENT_BLOCKED_AD",
                host: currentNormalizedHost()
            }).catch(() => {});
        }
    }

    // -------------------------------------------------------------------------
    // Strict Interstitial & Countdown Overlay Ad-Wall Buster + Scroll Unlock
    // -------------------------------------------------------------------------
    const INTERSTITIAL_ID_CLASS_RE = /(interstitial|ad[-_]?wall|adblock[-_]?(modal|wall|overlay|notice|detect)|disable[-_]?adblock|popup[-_]?ad|overlay[-_]?ad|fc-ab-root|sp-message-container|splash[-_]?ad|fullpage[-_]?ad)/i;
    const INTERSTITIAL_TEXT_RE = /(disable your ad ?blocker|turn off ad ?blocker|whitelist this site|skip ad in \d+|continue in \d+\s*(s|sec|seconds)|wait \d+\s*(s|sec|seconds)|ad closes in \d+|advertisement|sponsored offer)/i;
    const SAFE_MODAL_RE = /(login|sign[\s_-]?in|sign[\s_-]?up|register|checkout|payment|cart|search|menu|nav|player|video|cookie|consent|captcha|turnstile|verify)/i;

    function restoreScrollLock() {
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

    function bustInterstitialAdWalls() {
        if (!isTopFrame || isCaptchaHost()) return;
        const shieldOn = C.pageProtectState && (C.pageProtectState.shieldLevel || 0) > 0 && !C.pageProtectState.paused;
        if (!shieldOn || !document.body) return;
        const vw = window.innerWidth || 0;
        const vh = window.innerHeight || 0;
        if (vw < 200 || vh < 200) return;

        let busted = 0;
        try {
            const candidates = document.querySelectorAll("div, section, aside, dialog, ins");
            const limit = Math.min(candidates.length, 260);
            for (let i = 0; i < limit; i++) {
                const el = candidates[i];
                if (!el || (el.dataset && el.dataset.uhCollapsed === "1")) continue;
                const cs = getComputedStyle(el);
                if (cs.display === "none" || cs.visibility === "hidden") continue;
                if (cs.position !== "fixed" && cs.position !== "sticky") continue;
                const z = parseInt(cs.zIndex, 10) || 0;
                if (z < 500) continue;

                const rect = el.getBoundingClientRect();
                if (rect.width < vw * 0.45 || rect.height < vh * 0.35) continue;

                // Strict safety guardrails: never break media players, login/auth forms, or verification widgets
                if (el.querySelector("video, audio, form, input[type='password'], input[type='email'], .cf-turnstile")) {
                    continue;
                }
                const idCls = ((el.id || "") + " " + (typeof el.className === "string" ? el.className : "")).toLowerCase();
                if (SAFE_MODAL_RE.test(idCls)) continue;

                const text = (el.textContent || "").trim().slice(0, 400);
                const isTransparentTrap = (parseFloat(cs.opacity) < 0.08 || cs.backgroundColor === "rgba(0, 0, 0, 0)" || cs.backgroundColor === "transparent") && text.length === 0;
                const matchesAdWall = INTERSTITIAL_ID_CLASS_RE.test(idCls) || (text.length > 0 && INTERSTITIAL_TEXT_RE.test(text));

                if (isTransparentTrap || matchesAdWall) {
                    el.dataset.uhCollapsed = "1";
                    el.style.setProperty("display", "none", "important");
                    el.style.setProperty("visibility", "hidden", "important");
                    el.style.setProperty("pointer-events", "none", "important");
                    busted++;
                }
            }
        } catch (_e) {}

        if (busted > 0) {
            restoreScrollLock();
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
            runRemoveSelectors();
            scanAndCollapseAdBanners(document);
            bustInterstitialAdWalls();
            runAnnoyanceKillers();
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
                runRemoveSelectors();
                scanAndCollapseAdBanners(document);
                bustInterstitialAdWalls();
                runAnnoyanceKillers();
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
    C.applyCustomStyleRules = applyCustomStyleRules;
    C.applyRemoveSelectors = applyRemoveSelectors;
    C.scheduleCollapseSweep = scheduleCollapseSweep;
    C.setupProceduralCosmetics = setupProceduralCosmetics;
    C.collapseElement = collapseElement;
})();
