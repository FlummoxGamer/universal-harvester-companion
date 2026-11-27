// -----------------------------------------------------------------------------
// UH Bridge Content Module 2: Cookie Auto-Rejector & Global AMOLED Dark Mode
// -----------------------------------------------------------------------------
(function (C) {
    const api = C.api;
    const isTopFrame = C.isTopFrame;

    let cookieBannerObserver = null;
    let cookieBannerStopTimer = null;
    let cookieBannerRejected = false;

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

    // -------------------------------------------------------------------------
    // Global Dark Reader-Grade Forced Website Dark Mode + AMOLED Deepener
    // -------------------------------------------------------------------------
    let darkModeObserver = null;
    let darkModeEvalTimer = null;
    let darkModeActiveCfg = { forceDark: false, amoled: false, bypassed: false };

    function parseColorLuminance(cssColor) {
        if (!cssColor || cssColor === "transparent" || cssColor === "rgba(0, 0, 0, 0)") return null;
        const m = cssColor.match(/rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([\d.]+))?\s*\)/);
        if (!m) return null;
        const a = m[4] !== undefined ? parseFloat(m[4]) : 1;
        if (isNaN(a) || a < 0.45) return null;
        const r = parseInt(m[1], 10);
        const g = parseInt(m[2], 10);
        const b = parseInt(m[3], 10);
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255;
    }

    function isPageAlreadyDark() {
        try {
            // Temporarily ignore our own injected dark styles while measuring real page luminance
            const ownSmart = document.getElementById("__uh_smart_dark");
            const ownAmoled = document.getElementById("__uh_amoled_deep");
            const prevSmartDisabled = ownSmart ? ownSmart.disabled : false;
            const prevAmoledDisabled = ownAmoled ? ownAmoled.disabled : false;
            if (ownSmart) ownSmart.disabled = true;
            if (ownAmoled) ownAmoled.disabled = true;

            let detectedDark = false;
            try {
                const htmlEl = document.documentElement;
                const bodyEl = document.body;

                const probeElements = [
                    bodyEl,
                    htmlEl,
                    document.querySelector("main, [role='main'], #app, #root, #__next, ytd-app, ytm-app, article")
                ].filter(Boolean);

                const lums = [];
                for (let i = 0; i < probeElements.length; i++) {
                    const bg = getComputedStyle(probeElements[i]).backgroundColor;
                    const lum = parseColorLuminance(bg);
                    if (lum !== null) lums.push(lum);
                }

                if (lums.length > 0) {
                    // Consider site natively dark only when its actual rendered surface is dark
                    if (lums[0] < 0.34) {
                        detectedDark = true;
                    } else if (lums.every((l) => l < 0.36)) {
                        detectedDark = true;
                    }
                } else if (bodyEl) {
                    // Transparent html+body: check text color (light text on dark canvas vs dark text on white default)
                    const textLum = parseColorLuminance(getComputedStyle(bodyEl).color);
                    const metaScheme = document.querySelector('meta[name="color-scheme"]');
                    const metaVal = (metaScheme && metaScheme.content ? metaScheme.content : "").trim().toLowerCase();
                    if (textLum !== null && textLum > 0.72) {
                        detectedDark = true;
                    } else if (metaVal === "dark" || metaVal === "only dark") {
                        detectedDark = true;
                    }
                }
            } finally {
                if (ownSmart) ownSmart.disabled = prevSmartDisabled;
                if (ownAmoled) ownAmoled.disabled = prevAmoledDisabled;
            }
            return detectedDark;
        } catch (_e) {
            return false;
        }
    }

    function disconnectDarkObserver() {
        if (darkModeObserver) {
            try { darkModeObserver.disconnect(); } catch (_e) {}
            darkModeObserver = null;
        }
        if (darkModeEvalTimer) {
            clearTimeout(darkModeEvalTimer);
            darkModeEvalTimer = null;
        }
    }

    function applySmartDarkMode(forceDarkEnabled, amoledEnabled, bypassed) {
        if (!isTopFrame) return;
        const id = "__uh_smart_dark";
        const amoledId = "__uh_amoled_deep";
        darkModeActiveCfg = {
            forceDark: forceDarkEnabled === true,
            amoled: amoledEnabled === true,
            bypassed: bypassed === true
        };

        const existing = document.getElementById(id);
        const existingAmoled = document.getElementById(amoledId);

        if (bypassed || (!forceDarkEnabled && !amoledEnabled)) {
            disconnectDarkObserver();
            if (existing) existing.remove();
            if (existingAmoled) existingAmoled.remove();
            return;
        }

        const evaluateAndInject = () => {
            if (darkModeActiveCfg.bypassed || (!darkModeActiveCfg.forceDark && !darkModeActiveCfg.amoled)) return;
            const alreadyDark = isPageAlreadyDark();

            if (alreadyDark) {
                const invEl = document.getElementById(id);
                if (invEl) invEl.remove();

                if (darkModeActiveCfg.amoled) {
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

            const amEl = document.getElementById(amoledId);
            if (amEl) amEl.remove();

            if (!darkModeActiveCfg.forceDark && !darkModeActiveCfg.amoled) {
                const invEl = document.getElementById(id);
                if (invEl) invEl.remove();
                return;
            }

            // CRITICAL: pre-invert canvas MUST be pure #ffffff so invert(0.92..1.0) produces rich dark #14151a / #000000
            // (never a dark pre-invert background, which would invert to light grey!).
            const inv = darkModeActiveCfg.amoled ? "1" : "0.92";
            const contrast = darkModeActiveCfg.amoled ? "1.04" : "0.96";
            const css = [
                "html:not(.uh-reader-active) {",
                "  background-color: #ffffff !important;",
                "  filter: invert(" + inv + ") hue-rotate(180deg) contrast(" + contrast + ") !important;",
                "  min-height: 100% !important;",
                "}",
                "html:not(.uh-reader-active) body {",
                "  background-color: #ffffff !important;",
                "}",
                // Preserve real media, photos, videos, canvases, embeds cleanly without double-inversion
                "html:not(.uh-reader-active) img, html:not(.uh-reader-active) video, html:not(.uh-reader-active) picture, html:not(.uh-reader-active) canvas, html:not(.uh-reader-active) iframe, html:not(.uh-reader-active) embed, html:not(.uh-reader-active) object, html:not(.uh-reader-active) [style*='background-image'] {",
                "  filter: invert(1) hue-rotate(180deg) !important;",
                "}",
                // Avoid double-inverting nested media inside picture/background-image containers
                "html:not(.uh-reader-active) picture img, html:not(.uh-reader-active) [style*='background-image'] img, html:not(.uh-reader-active) [style*='background-image'] video {",
                "  filter: none !important;",
                "}",
                // Keep fullscreen video & PiP completely untouched
                "video:fullscreen, video:-webkit-full-screen, :fullscreen video, .__uh_pip_video_only {",
                "  filter: none !important;",
                "}"
            ].join("\n");
            const styleEl = document.getElementById(id) || document.createElement("style");
            styleEl.id = id;
            styleEl.textContent = css;
            (document.head || document.documentElement).appendChild(styleEl);
        };

        const scheduleReeval = (delayMs) => {
            if (darkModeEvalTimer) clearTimeout(darkModeEvalTimer);
            darkModeEvalTimer = setTimeout(() => {
                darkModeEvalTimer = null;
                evaluateAndInject();
            }, delayMs);
        };

        evaluateAndInject();
        if (document.readyState === "loading") {
            document.addEventListener("DOMContentLoaded", () => scheduleReeval(80), { once: true });
        }
        window.addEventListener("load", () => scheduleReeval(180), { once: true });
        setTimeout(evaluateAndInject, 650);

        // Watch SPA theme/class changes on <html> & <body> (e.g., Twitter/YouTube/GitHub/Reddit dark switches)
        disconnectDarkObserver();
        try {
            darkModeObserver = new MutationObserver(() => scheduleReeval(220));
            if (document.documentElement) {
                darkModeObserver.observe(document.documentElement, {
                    attributes: true,
                    attributeFilter: ["class", "style", "data-theme", "data-color-mode", "dark"]
                });
            }
            if (document.body) {
                darkModeObserver.observe(document.body, {
                    attributes: true,
                    attributeFilter: ["class", "style", "data-theme"]
                });
            }
        } catch (_e) {}
    }

    function applyPageProtectConfig(cfg) {
        if (!cfg) return;
        C.pageProtectState = cfg;
        try {
            sessionStorage.setItem("__uh_boot_cfg", JSON.stringify({
                shieldLevel: cfg.shieldLevel || 0,
                paused: cfg.paused === true,
                blockPopups: cfg.blockPopups === true,
                backgroundPlay: cfg.backgroundPlay === true,
                webrtcPolicy: cfg.webrtcPolicy || 0,
                antiFingerprint: cfg.antiFingerprint === true,
                strictFingerprint: cfg.strictFingerprint === true,
                dnt: cfg.dnt === true,
                annoyances: cfg.annoyances !== false,
                fpSessionSalt: cfg.fpSessionSalt || ""
            }));
        } catch (_e) {}
        const shieldOn = (cfg.shieldLevel || 0) > 0 && !cfg.paused;
        C.applyCosmeticStyle(cfg.cosmetics || [], shieldOn);
        if (C.applyCustomStyleRules) C.applyCustomStyleRules(cfg.styleRules || [], shieldOn);
        if (C.applyRemoveSelectors) C.applyRemoveSelectors(cfg.removeSelectors || [], shieldOn);
        C.setupProceduralCosmetics(cfg.procedural || [], shieldOn);
        applySmartDarkMode(cfg.forceDarkWeb === true, cfg.enhanceDark === true, cfg.darkBypassed === true);
        if (C.scriptletsReady) {
            C.dispatchScriptletConfig(cfg);
        }
        startCookieBannerWatcher(cfg.cookieBanners === true && !cfg.paused);
    }

    C.applyPageProtectConfig = applyPageProtectConfig;

    api.runtime.sendMessage({
        type: "UH_GET_PAGE_PROTECT",
        url: window.location.href
    }).then((cfg) => {
        if (cfg) applyPageProtectConfig(cfg);
    }).catch(() => {});
})(globalThis.__uhCs);
