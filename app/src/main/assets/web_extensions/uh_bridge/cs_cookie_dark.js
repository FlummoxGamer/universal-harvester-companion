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
    // Global True AMOLED Website Dark Mode (Light Force-Dark + Already-Dark Deepener)
    // -------------------------------------------------------------------------
    function isColorDark(cssColor) {
        if (!cssColor || cssColor === "transparent" || cssColor === "rgba(0, 0, 0, 0)") return false;
        const m = cssColor.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?/);
        if (!m) return false;
        const a = m[4] !== undefined ? parseFloat(m[4]) : 1;
        if (a < 0.35) return false;
        const lum = (0.299 * parseInt(m[1], 10) + 0.587 * parseInt(m[2], 10) + 0.114 * parseInt(m[3], 10)) / 255;
        return lum < 0.38;
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

            const amEl = document.getElementById(amoledId);
            if (amEl) amEl.remove();

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
