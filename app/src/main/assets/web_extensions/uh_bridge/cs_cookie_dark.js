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
    // Global Smart Semantic CSS Dark Theme Engine (Zero filter:invert / Zero position:fixed bugs)
    // -------------------------------------------------------------------------
    let darkModeObserver = null;
    let darkModeEvalTimer = null;
    let darkModeDomWalkTimer = null;
    let darkModeActiveCfg = { forceDark: false, amoled: false };

    function parseColorRgba(cssColor) {
        if (!cssColor || cssColor === "transparent" || cssColor === "rgba(0, 0, 0, 0)") return null;
        const m = cssColor.match(/rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([\d.]+))?\s*\)/);
        if (!m) return null;
        const a = m[4] !== undefined ? parseFloat(m[4]) : 1;
        if (isNaN(a) || a < 0.35) return null;
        const r = parseInt(m[1], 10);
        const g = parseInt(m[2], 10);
        const b = parseInt(m[3], 10);
        const lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
        const maxC = Math.max(r, g, b);
        const minC = Math.min(r, g, b);
        const sat = maxC > 0 ? (maxC - minC) / maxC : 0;
        return { r, g, b, a, lum, sat };
    }

    function isPageAlreadyDark() {
        try {
            const ownSmart = document.getElementById("__uh_smart_dark");
            const ownAmoled = document.getElementById("__uh_amoled_deep");
            const prevSmartDisabled = ownSmart ? ownSmart.disabled : false;
            const prevAmoledDisabled = ownAmoled ? ownAmoled.disabled : false;
            const htmlEl = document.documentElement;
            const hadClass = htmlEl && htmlEl.classList.contains("__uh-dark-applied");
            if (ownSmart) ownSmart.disabled = true;
            if (ownAmoled) ownAmoled.disabled = true;
            if (hadClass) htmlEl.classList.remove("__uh-dark-applied");

            let detectedDark = false;
            try {
                const bodyEl = document.body;
                const probeElements = [
                    bodyEl,
                    htmlEl,
                    document.querySelector("main, [role='main'], #app, #root, #__next, ytd-app, ytm-app, article")
                ].filter(Boolean);

                const lums = [];
                for (let i = 0; i < probeElements.length; i++) {
                    const c = parseColorRgba(getComputedStyle(probeElements[i]).backgroundColor);
                    if (c !== null) lums.push(c.lum);
                }

                if (lums.length > 0) {
                    if (lums[0] < 0.32) {
                        detectedDark = true;
                    } else if (lums.length >= 2 && lums.every((l) => l < 0.34)) {
                        detectedDark = true;
                    }
                } else if (bodyEl) {
                    const textColor = parseColorRgba(getComputedStyle(bodyEl).color);
                    const metaScheme = document.querySelector('meta[name="color-scheme"]');
                    const metaVal = (metaScheme && metaScheme.content ? metaScheme.content : "").trim().toLowerCase();
                    if (textColor !== null && textColor.lum > 0.72) {
                        detectedDark = true;
                    } else if (metaVal === "dark" || metaVal === "only dark") {
                        detectedDark = true;
                    }
                }
            } finally {
                if (hadClass && htmlEl) htmlEl.classList.add("__uh-dark-applied");
                if (ownSmart) ownSmart.disabled = prevSmartDisabled;
                if (ownAmoled) ownAmoled.disabled = prevAmoledDisabled;
            }
            return detectedDark;
        } catch (_e) {
            return false;
        }
    }

    function clearInlineDarkMarkers() {
        try {
            const marked = document.querySelectorAll("[data-uh-dk]");
            for (let i = 0; i < marked.length; i++) {
                marked[i].removeAttribute("data-uh-dk");
            }
            if (document.documentElement) {
                document.documentElement.classList.remove("__uh-dark-applied", "__uh-dark-amoled");
            }
        } catch (_e) {}
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
        if (darkModeDomWalkTimer) {
            clearTimeout(darkModeDomWalkTimer);
            darkModeDomWalkTimer = null;
        }
    }

    function tagStubbornLightNodes() {
        if (!darkModeActiveCfg.forceDark) return;
        const htmlEl = document.documentElement;
        if (!htmlEl || htmlEl.classList.contains("uh-reader-active") || !htmlEl.classList.contains("__uh-dark-applied")) return;
        try {
            const candidates = document.querySelectorAll(
                "body, main, header, nav, footer, section, article, aside, div, form, table, tr, td, th, ul, ol, li, dl, details, summary, dialog, [role='dialog'], [role='menu'], [role='navigation'], [role='banner'], [role='main']"
            );
            const limit = Math.min(candidates.length, 480);
            for (let i = 0; i < limit; i++) {
                const el = candidates[i];
                if (el.hasAttribute("data-uh-dk")) continue;
                if (el.closest(".uh-reader-root, #__uh_tr_root")) continue;
                const st = getComputedStyle(el);
                if (st.backgroundImage && st.backgroundImage !== "none") continue;
                const bg = parseColorRgba(st.backgroundColor);
                if (bg && bg.lum > 0.72 && bg.sat < 0.28) {
                    el.setAttribute("data-uh-dk", bg.lum > 0.88 ? "bg" : "surf");
                }
            }
        } catch (_e) {}
    }

    function applySmartDarkMode(forceDarkEnabled, amoledEnabled, _bypassed) {
        if (!isTopFrame) return;
        const id = "__uh_smart_dark";
        const amoledId = "__uh_amoled_deep";
        const enabled = forceDarkEnabled === true;
        const amoled = enabled && amoledEnabled === true;
        darkModeActiveCfg = { forceDark: enabled, amoled: amoled };

        const existing = document.getElementById(id);
        const existingAmoled = document.getElementById(amoledId);

        if (!enabled) {
            disconnectDarkObserver();
            if (existing) existing.remove();
            if (existingAmoled) existingAmoled.remove();
            clearInlineDarkMarkers();
            return;
        }

        const evaluateAndInject = () => {
            if (!darkModeActiveCfg.forceDark) return;
            const htmlEl = document.documentElement;
            if (!htmlEl || htmlEl.classList.contains("uh-reader-active")) return;

            const alreadyDark = isPageAlreadyDark();
            if (alreadyDark) {
                const invEl = document.getElementById(id);
                if (invEl) invEl.remove();
                clearInlineDarkMarkers();

                if (darkModeActiveCfg.amoled) {
                    const amoledCss = [
                        "html:not(.uh-reader-active), html:not(.uh-reader-active) body { background-color: #000000 !important; color-scheme: dark !important; }",
                        ":root { --yt-spec-base-background: #000000 !important; --yt-spec-raised-background: #0A0A0A !important; --bgColor-default: #000000 !important; --color-canvas-default: #000000 !important; }",
                        "ytd-app, ytm-app, #app, #root, #__next, main { background-color: #000000 !important; }"
                    ].join("\n");
                    const el = document.getElementById(amoledId) || document.createElement("style");
                    el.id = amoledId;
                    el.textContent = amoledCss;
                    (document.head || htmlEl).appendChild(el);
                } else {
                    const el = document.getElementById(amoledId);
                    if (el) el.remove();
                }
                return;
            }

            const amEl = document.getElementById(amoledId);
            if (amEl) amEl.remove();

            const bgBase = darkModeActiveCfg.amoled ? "#000000" : "#121418";
            const bgSurface = darkModeActiveCfg.amoled ? "#0B0D10" : "#1A1D24";
            const bgElevated = darkModeActiveCfg.amoled ? "#14171D" : "#232732";
            const borderCol = darkModeActiveCfg.amoled ? "#242830" : "#2F3542";
            const textPrimary = "#E8ECF2";
            const textSecondary = "#A6B0C0";
            const linkCol = "#8AB4F8";
            const linkVisited = "#C58AF9";

            htmlEl.classList.add("__uh-dark-applied");
            if (darkModeActiveCfg.amoled) {
                htmlEl.classList.add("__uh-dark-amoled");
            } else {
                htmlEl.classList.remove("__uh-dark-amoled");
            }

            const css = [
                "html.__uh-dark-applied:not(.uh-reader-active) {",
                "  color-scheme: dark !important;",
                "  background-color: " + bgBase + " !important;",
                "  color: " + textPrimary + " !important;",
                "}",
                "html.__uh-dark-applied:not(.uh-reader-active) body {",
                "  background-color: " + bgBase + " !important;",
                "  color: " + textPrimary + " !important;",
                "}",
                "html.__uh-dark-applied:not(.uh-reader-active) main,",
                "html.__uh-dark-applied:not(.uh-reader-active) article,",
                "html.__uh-dark-applied:not(.uh-reader-active) section,",
                "html.__uh-dark-applied:not(.uh-reader-active) aside,",
                "html.__uh-dark-applied:not(.uh-reader-active) header,",
                "html.__uh-dark-applied:not(.uh-reader-active) nav,",
                "html.__uh-dark-applied:not(.uh-reader-active) footer,",
                "html.__uh-dark-applied:not(.uh-reader-active) dialog,",
                "html.__uh-dark-applied:not(.uh-reader-active) table,",
                "html.__uh-dark-applied:not(.uh-reader-active) thead,",
                "html.__uh-dark-applied:not(.uh-reader-active) tbody,",
                "html.__uh-dark-applied:not(.uh-reader-active) tr,",
                "html.__uh-dark-applied:not(.uh-reader-active) th,",
                "html.__uh-dark-applied:not(.uh-reader-active) td,",
                "html.__uh-dark-applied:not(.uh-reader-active) pre,",
                "html.__uh-dark-applied:not(.uh-reader-active) code,",
                "html.__uh-dark-applied:not(.uh-reader-active) blockquote {",
                "  background-color: " + bgSurface + " !important;",
                "  color: " + textPrimary + " !important;",
                "  border-color: " + borderCol + " !important;",
                "}",
                "html.__uh-dark-applied:not(.uh-reader-active) [data-uh-dk='bg'] {",
                "  background-color: " + bgBase + " !important;",
                "  color: " + textPrimary + " !important;",
                "  border-color: " + borderCol + " !important;",
                "}",
                "html.__uh-dark-applied:not(.uh-reader-active) [data-uh-dk='surf'] {",
                "  background-color: " + bgSurface + " !important;",
                "  color: " + textPrimary + " !important;",
                "  border-color: " + borderCol + " !important;",
                "}",
                "html.__uh-dark-applied:not(.uh-reader-active) h1,",
                "html.__uh-dark-applied:not(.uh-reader-active) h2,",
                "html.__uh-dark-applied:not(.uh-reader-active) h3,",
                "html.__uh-dark-applied:not(.uh-reader-active) h4,",
                "html.__uh-dark-applied:not(.uh-reader-active) h5,",
                "html.__uh-dark-applied:not(.uh-reader-active) h6,",
                "html.__uh-dark-applied:not(.uh-reader-active) p,",
                "html.__uh-dark-applied:not(.uh-reader-active) span,",
                "html.__uh-dark-applied:not(.uh-reader-active) li,",
                "html.__uh-dark-applied:not(.uh-reader-active) dd,",
                "html.__uh-dark-applied:not(.uh-reader-active) dt,",
                "html.__uh-dark-applied:not(.uh-reader-active) label,",
                "html.__uh-dark-applied:not(.uh-reader-active) strong,",
                "html.__uh-dark-applied:not(.uh-reader-active) b,",
                "html.__uh-dark-applied:not(.uh-reader-active) em {",
                "  color: " + textPrimary + " !important;",
                "}",
                "html.__uh-dark-applied:not(.uh-reader-active) small,",
                "html.__uh-dark-applied:not(.uh-reader-active) figcaption,",
                "html.__uh-dark-applied:not(.uh-reader-active) time {",
                "  color: " + textSecondary + " !important;",
                "}",
                "html.__uh-dark-applied:not(.uh-reader-active) a:not([role='button']) {",
                "  color: " + linkCol + " !important;",
                "}",
                "html.__uh-dark-applied:not(.uh-reader-active) a:visited:not([role='button']) {",
                "  color: " + linkVisited + " !important;",
                "}",
                "html.__uh-dark-applied:not(.uh-reader-active) input,",
                "html.__uh-dark-applied:not(.uh-reader-active) textarea,",
                "html.__uh-dark-applied:not(.uh-reader-active) select,",
                "html.__uh-dark-applied:not(.uh-reader-active) button {",
                "  background-color: " + bgElevated + " !important;",
                "  color: " + textPrimary + " !important;",
                "  border-color: " + borderCol + " !important;",
                "}",
                "html.__uh-dark-applied:not(.uh-reader-active) ::placeholder {",
                "  color: " + textSecondary + " !important;",
                "}",
                "html.__uh-dark-applied:not(.uh-reader-active) hr {",
                "  border-color: " + borderCol + " !important;",
                "}",
                "html.__uh-dark-applied:not(.uh-reader-active) img,",
                "html.__uh-dark-applied:not(.uh-reader-active) video,",
                "html.__uh-dark-applied:not(.uh-reader-active) picture,",
                "html.__uh-dark-applied:not(.uh-reader-active) canvas,",
                "html.__uh-dark-applied:not(.uh-reader-active) iframe {",
                "  background-color: transparent !important;",
                "  filter: none !important;",
                "}"
            ].join("\n");

            const styleEl = document.getElementById(id) || document.createElement("style");
            styleEl.id = id;
            styleEl.textContent = css;
            (document.head || htmlEl).appendChild(styleEl);

            if (darkModeDomWalkTimer) clearTimeout(darkModeDomWalkTimer);
            darkModeDomWalkTimer = setTimeout(tagStubbornLightNodes, 40);
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

        disconnectDarkObserver();
        try {
            darkModeObserver = new MutationObserver(() => {
                if (darkModeDomWalkTimer) clearTimeout(darkModeDomWalkTimer);
                darkModeDomWalkTimer = setTimeout(tagStubbornLightNodes, 180);
            });
            if (document.documentElement) {
                darkModeObserver.observe(document.documentElement, {
                    attributes: true,
                    attributeFilter: ["class", "data-theme", "data-color-mode", "dark"]
                });
            }
            if (document.body) {
                darkModeObserver.observe(document.body, {
                    childList: true,
                    subtree: true
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
