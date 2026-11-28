// -----------------------------------------------------------------------------
// UH Bridge Content Module 2: Cookie Auto-Rejector & Dynamic Dark Reader Engine
// -----------------------------------------------------------------------------
(function (C) {
    const api = C.api;
    const isTopFrame = C.isTopFrame;

    // -------------------------------------------------------------------------
    // Part 1: Cookie Consent Banner Auto-Rejector (Top Frame)
    // -------------------------------------------------------------------------
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
    // Part 2: Dynamic Dark Reader Engine
    //   - Layer 1: CSS Custom Property (--*) perceptual remapper
    //   - Layer 2: Author Stylesheet Rule analyzer & darkener
    //   - Layer 3: Uncapped Full-DOM + IntersectionObserver + MutationObserver
    //   - Preserves photos/videos/canvas/brand CTAs & never breaks position:fixed
    // -------------------------------------------------------------------------
    const STYLE_BASE_ID = "__uh_dark_base";
    const STYLE_VARS_ID = "__uh_dark_vars";
    const STYLE_RULES_ID = "__uh_dark_rules";
    const STYLE_AMOLED_ID = "__uh_amoled_deep";

    const SKIP_TAGS = new Set([
        "SCRIPT", "STYLE", "LINK", "META", "NOSCRIPT", "HEAD", "TITLE", "BASE",
        "IMG", "VIDEO", "AUDIO", "PICTURE", "SOURCE", "CANVAS", "IFRAME", "OBJECT", "EMBED",
        "BR", "WBR", "PARAM", "TRACK", "MAP", "AREA"
    ]);

    const COLOR_PARSE_CACHE = new Map();
    const PROCESSED_SHEETS = new WeakSet();

    let darkModeActiveCfg = { forceDark: false, amoled: false };
    let darkMutationObserver = null;
    let darkIntersectionObserver = null;
    let darkEvalTimer = null;
    let darkFrameQueued = false;
    let darkSheetScanTimer = null;
    let darkWorkQueue = [];
    let darkQueuedSet = new WeakSet();
    let darkStyleProbeCtx = null;

    function getPalette(amoled) {
        return amoled
            ? {
                bg: "#000000",
                bgRgb: [0, 0, 0],
                surface: "#0B0D11",
                surfaceRgb: [11, 13, 17],
                elevated: "#151820",
                elevatedRgb: [21, 24, 32],
                border: "#232732",
                text: "#E8EDF4",
                textSecondary: "#A2AEBF",
                link: "#8AB4F8",
                linkVisited: "#C58AF9"
            }
            : {
                bg: "#121418",
                bgRgb: [18, 20, 24],
                surface: "#1A1D24",
                surfaceRgb: [26, 29, 36],
                elevated: "#242833",
                elevatedRgb: [36, 40, 51],
                border: "#303644",
                text: "#E8EDF4",
                textSecondary: "#A6B2C3",
                link: "#8AB4F8",
                linkVisited: "#C58AF9"
            };
    }

    function clamp01(v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    function rgbToHsl(r, g, b) {
        const rn = r / 255, gn = g / 255, bn = b / 255;
        const max = Math.max(rn, gn, bn);
        const min = Math.min(rn, gn, bn);
        const l = (max + min) / 2;
        let h = 0, s = 0;
        if (max !== min) {
            const d = max - min;
            s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
            switch (max) {
                case rn: h = ((gn - bn) / d + (gn < bn ? 6 : 0)) / 6; break;
                case gn: h = ((bn - rn) / d + 2) / 6; break;
                case bn: h = ((rn - gn) / d + 4) / 6; break;
            }
        }
        return [h, s, l];
    }

    function hslToRgb(h, s, l) {
        if (s === 0) {
            const v = Math.round(clamp01(l) * 255);
            return [v, v, v];
        }
        const hue2rgb = (p, q, t) => {
            if (t < 0) t += 1;
            if (t > 1) t -= 1;
            if (t < 1 / 6) return p + (q - p) * 6 * t;
            if (t < 1 / 2) return q;
            if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
            return p;
        };
        const q = l < 0.5 ? l * (1 + s) : l + s - l * s;
        const p = 2 * l - q;
        return [
            Math.round(clamp01(hue2rgb(p, q, h + 1 / 3)) * 255),
            Math.round(clamp01(hue2rgb(p, q, h)) * 255),
            Math.round(clamp01(hue2rgb(p, q, h - 1 / 3)) * 255)
        ];
    }

    function formatRgba(r, g, b, a) {
        const rr = Math.max(0, Math.min(255, Math.round(r)));
        const gg = Math.max(0, Math.min(255, Math.round(g)));
        const bb = Math.max(0, Math.min(255, Math.round(b)));
        if (a !== undefined && a < 0.995) {
            return "rgba(" + rr + ", " + gg + ", " + bb + ", " + Number(a.toFixed(3)) + ")";
        }
        return "rgb(" + rr + ", " + gg + ", " + bb + ")";
    }

    function parseCssColor(raw) {
        if (!raw || typeof raw !== "string") return null;
        const key = raw.trim().toLowerCase();
        if (!key || key === "transparent" || key === "none" || key === "inherit" || key === "initial" || key === "unset" || key === "currentcolor") {
            return null;
        }
        if (COLOR_PARSE_CACHE.has(key)) return COLOR_PARSE_CACHE.get(key);

        let r = 0, g = 0, b = 0, a = 1;
        let parsed = false;

        // rgb() / rgba() (comma or space-separated CSS Color 4)
        const rgbMatch = key.match(/^rgba?\(\s*([\d.]+)(%?)\s*[,\s]\s*([\d.]+)(%?)\s*[,\s]\s*([\d.]+)(%?)(?:\s*[,\/]\s*([\d.]+)(%?))?\s*\)$/);
        if (rgbMatch) {
            r = rgbMatch[2] ? parseFloat(rgbMatch[1]) * 2.55 : parseFloat(rgbMatch[1]);
            g = rgbMatch[4] ? parseFloat(rgbMatch[3]) * 2.55 : parseFloat(rgbMatch[3]);
            b = rgbMatch[6] ? parseFloat(rgbMatch[5]) * 2.55 : parseFloat(rgbMatch[5]);
            if (rgbMatch[7] !== undefined) {
                a = rgbMatch[8] ? parseFloat(rgbMatch[7]) / 100 : parseFloat(rgbMatch[7]);
            }
            parsed = true;
        } else if (key.startsWith("#")) {
            const hex = key.slice(1);
            if (hex.length === 3 || hex.length === 4) {
                r = parseInt(hex[0] + hex[0], 16);
                g = parseInt(hex[1] + hex[1], 16);
                b = parseInt(hex[2] + hex[2], 16);
                a = hex.length === 4 ? parseInt(hex[3] + hex[3], 16) / 255 : 1;
                parsed = !isNaN(r) && !isNaN(g) && !isNaN(b);
            } else if (hex.length === 6 || hex.length === 8) {
                r = parseInt(hex.slice(0, 2), 16);
                g = parseInt(hex.slice(2, 4), 16);
                b = parseInt(hex.slice(4, 6), 16);
                a = hex.length === 8 ? parseInt(hex.slice(6, 8), 16) / 255 : 1;
                parsed = !isNaN(r) && !isNaN(g) && !isNaN(b);
            }
        } else if (key.startsWith("hsl")) {
            const hslMatch = key.match(/^hsla?\(\s*([\d.]+)(?:deg)?\s*[,\s]\s*([\d.]+)%\s*[,\s]\s*([\d.]+)%(?:\s*[,\/]\s*([\d.]+)(%?))?\s*\)$/);
            if (hslMatch) {
                const rgb = hslToRgb(
                    ((parseFloat(hslMatch[1]) % 360) + 360) % 360 / 360,
                    parseFloat(hslMatch[2]) / 100,
                    parseFloat(hslMatch[3]) / 100
                );
                r = rgb[0]; g = rgb[1]; b = rgb[2];
                if (hslMatch[4] !== undefined) {
                    a = hslMatch[5] ? parseFloat(hslMatch[4]) / 100 : parseFloat(hslMatch[4]);
                }
                parsed = true;
            }
        } else if (key === "white") {
            r = 255; g = 255; b = 255; a = 1; parsed = true;
        } else if (key === "black") {
            r = 0; g = 0; b = 0; a = 1; parsed = true;
        } else if (/^[a-z]{3,20}$/.test(key)) {
            // Resolve named CSS color via offscreen canvas 2d
            try {
                if (!darkStyleProbeCtx) {
                    const c = document.createElement("canvas");
                    c.width = 1; c.height = 1;
                    darkStyleProbeCtx = c.getContext("2d");
                }
                if (darkStyleProbeCtx) {
                    darkStyleProbeCtx.fillStyle = "#010203";
                    darkStyleProbeCtx.fillStyle = key;
                    const resolved = darkStyleProbeCtx.fillStyle;
                    if (resolved && resolved !== "#010203" && resolved !== key) {
                        const sub = parseCssColor(resolved);
                        if (sub) {
                            COLOR_PARSE_CACHE.set(key, sub);
                            return sub;
                        }
                    }
                }
            } catch (_e) {}
        }

        if (!parsed || isNaN(r) || isNaN(g) || isNaN(b) || isNaN(a) || a < 0.04) {
            if (COLOR_PARSE_CACHE.size < 2000) COLOR_PARSE_CACHE.set(key, null);
            return null;
        }

        const lum = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
        const hsl = rgbToHsl(r, g, b);
        const res = { r, g, b, a, lum, h: hsl[0], s: hsl[1], l: hsl[2] };
        if (COLOR_PARSE_CACHE.size < 2000) COLOR_PARSE_CACHE.set(key, res);
        return res;
    }

    // Perceptual Dark Reader background transform:
    // Maps bright white/light neutrals & pale tints into authentic elevated dark surfaces,
    // while preserving brand accents (buttons/badges with rich saturation).
    function transformBgColor(c, amoled, preserveVibrantAccent) {
        if (!c || c.a < 0.12) return null;
        // Already dark -> only deepen near-black if AMOLED
        if (c.lum < 0.34) {
            if (amoled && c.lum > 0.02 && c.lum < 0.14 && c.s < 0.22) {
                return formatRgba(0, 0, 0, c.a);
            }
            return null;
        }
        // Preserve rich brand accent colors (e.g. primary blue CTA, green badge, red alert)
        if (preserveVibrantAccent && c.s > 0.45 && c.l < 0.68 && c.l > 0.28) {
            return null;
        }
        // Mid-light to pure white -> invert perceptually into dark elevation ladder
        const baseL = amoled ? 0.0 : 0.078;
        const maxDarkL = amoled ? 0.145 : 0.215;
        // Pure white (l=1.0) maps to baseL; light grey (l=0.92) maps slightly higher (surface);
        // medium-light tint (l=0.70) maps to elevated card/tint.
        const inv = clamp01((1.0 - c.l) / 0.65);
        let newL = baseL + inv * (maxDarkL - baseL);
        // Subtle cool slate tint for pure neutrals, dampened hue preservation for pastel tints
        let newH = c.h;
        let newS = c.s;
        if (c.s < 0.08) {
            newH = 220 / 360;
            newS = amoled ? (newL > 0.02 ? 0.14 : 0.0) : 0.15;
        } else {
            newS = Math.min(c.s * 0.68, 0.46);
            newL = Math.max(newL, amoled ? 0.09 : 0.14);
        }
        const rgb = hslToRgb(newH, newS, newL);
        return formatRgba(rgb[0], rgb[1], rgb[2], c.a);
    }

    // Perceptual Dark Reader foreground/text transform:
    // Lifts dark text into high-contrast readable light shades while preserving link/accent hue.
    function transformTextColor(c) {
        if (!c || c.a < 0.15) return null;
        if (c.lum >= 0.56) return null; // Already light & readable on dark bg
        if (c.s < 0.15) {
            // Neutral ink: #000 -> #E8EDF4, #555/#666 -> #A6B2C3
            const targetL = clamp01(0.92 - c.l * 0.48);
            const rgb = hslToRgb(215 / 360, 0.16, Math.max(0.64, targetL));
            return formatRgba(rgb[0], rgb[1], rgb[2], c.a);
        }
        // Colored text (links, green stock ticks, red error text, purple visited links)
        const liftedL = Math.max(0.68, Math.min(0.84, 0.66 + (0.45 - Math.min(0.45, c.l)) * 0.36));
        const liftedS = Math.min(0.92, Math.max(c.s, 0.55));
        const rgb = hslToRgb(c.h, liftedS, liftedL);
        return formatRgba(rgb[0], rgb[1], rgb[2], c.a);
    }

    // Perceptual Dark Reader border transform
    function transformBorderColor(c, amoled) {
        if (!c || c.a < 0.12) return null;
        if (c.lum < 0.46) return null;
        const targetL = amoled ? 0.18 : 0.24;
        const rgb = hslToRgb(c.s < 0.1 ? 220 / 360 : c.h, c.s < 0.1 ? 0.14 : Math.min(c.s * 0.5, 0.35), targetL);
        return formatRgba(rgb[0], rgb[1], rgb[2], c.a);
    }

    function transformGradientValue(bgImg, amoled) {
        if (!bgImg || bgImg === "none") return null;
        if (bgImg.indexOf("url(") !== -1) return null; // Never alter image backgrounds
        if (bgImg.indexOf("gradient(") === -1) return null;
        let changed = false;
        const out = bgImg.replace(/rgba?\([^)]+\)|#[0-9a-fA-F]{3,8}\b/g, (token) => {
            const parsed = parseCssColor(token);
            if (!parsed) return token;
            const mapped = transformBgColor(parsed, amoled, true);
            if (mapped) {
                changed = true;
                return mapped;
            }
            return token;
        });
        return changed ? out : null;
    }

    function isProtectedUhNode(el) {
        if (!el || el.nodeType !== 1) return true;
        if (el.id === "uh-reader-overlay-root" || el.id === "__uh_tr_root") return true;
        if (el.closest && el.closest("#uh-reader-overlay-root, .uh-reader-root, #__uh_tr_root")) return true;
        return false;
    }

    function isPageAlreadyNativeDark() {
        try {
            const htmlEl = document.documentElement;
            if (!htmlEl) return false;
            const ownIds = [STYLE_BASE_ID, STYLE_VARS_ID, STYLE_RULES_ID, STYLE_AMOLED_ID];
            const prevDisabled = [];
            for (let i = 0; i < ownIds.length; i++) {
                const s = document.getElementById(ownIds[i]);
                prevDisabled.push([s, s ? s.disabled : false]);
                if (s) s.disabled = true;
            }
            const hadClass = htmlEl.classList.contains("__uh-dark-applied");
            if (hadClass) htmlEl.classList.remove("__uh-dark-applied");

            let nativeDark = false;
            try {
                const bodyEl = document.body;
                const mainCandidates = [
                    document.querySelector("main, [role='main'], article, #content, #main, #app, #root, #__next, ytd-app, ytm-app"),
                    bodyEl,
                    htmlEl
                ].filter(Boolean);

                let darkHits = 0;
                let lightHits = 0;

                for (let i = 0; i < mainCandidates.length; i++) {
                    const st = getComputedStyle(mainCandidates[i]);
                    const bg = parseCssColor(st.backgroundColor);
                    if (bg && bg.a >= 0.45) {
                        if (bg.lum < 0.28) darkHits++;
                        else if (bg.lum > 0.62) lightHits++;
                    }
                }

                // Sample actual rendered center viewport elements so a dark <body> behind a white #content wrapper is NOT misclassified as dark!
                if (typeof document.elementsFromPoint === "function" && window.innerWidth > 40 && window.innerHeight > 80) {
                    const pts = [
                        [window.innerWidth * 0.5, window.innerHeight * 0.35],
                        [window.innerWidth * 0.5, window.innerHeight * 0.65],
                        [window.innerWidth * 0.2, window.innerHeight * 0.5]
                    ];
                    for (let p = 0; p < pts.length; p++) {
                        const stack = document.elementsFromPoint(pts[p][0], pts[p][1]);
                        for (let k = 0; k < stack.length; k++) {
                            const node = stack[k];
                            if (SKIP_TAGS.has(node.tagName) || isProtectedUhNode(node)) continue;
                            const bg = parseCssColor(getComputedStyle(node).backgroundColor);
                            if (bg && bg.a >= 0.55) {
                                if (bg.lum < 0.28) darkHits++;
                                else if (bg.lum > 0.62) lightHits++;
                                break; // first opaque background at this point
                            }
                        }
                    }
                }

                if (lightHits > 0) {
                    nativeDark = false;
                } else if (darkHits > 0) {
                    nativeDark = true;
                } else if (bodyEl) {
                    const txt = parseCssColor(getComputedStyle(bodyEl).color);
                    const metaScheme = document.querySelector('meta[name="color-scheme"]');
                    const metaVal = (metaScheme && metaScheme.content ? metaScheme.content : "").trim().toLowerCase();
                    if ((txt && txt.lum > 0.78) && (metaVal === "dark" || metaVal === "only dark")) {
                        nativeDark = true;
                    }
                }
            } finally {
                if (hadClass) htmlEl.classList.add("__uh-dark-applied");
                for (let i = 0; i < prevDisabled.length; i++) {
                    if (prevDisabled[i][0]) prevDisabled[i][0].disabled = prevDisabled[i][1];
                }
            }
            return nativeDark;
        } catch (_e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Layer 1 & Layer 2: CSS Custom Property Remapper + Stylesheet Analyzer
    // -------------------------------------------------------------------------
    const BG_VAR_HINT_RE = /(bg|back|surface|canvas|card|panel|paper|modal|sheet|header|nav|footer|page|body|base|container|box|tile|menu|popup|dialog|tooltip|input|field|fill|white|light|tint|well|elevat|chrome|bar|rail|drawer|sidebar)/i;
    const TEXT_VAR_HINT_RE = /(text|fg|fore|ink|font|title|head|label|caption|body-color|on-surface|on-background|muted|subtle|secondary|primary-color|black|dark|copy|content-color)/i;
    const BORDER_VAR_HINT_RE = /(border|divider|stroke|outline|separator|hairline|ring|line)/i;
    const ACCENT_VAR_HINT_RE = /(accent|brand|primary|blue|green|red|warn|error|danger|success|badge|highlight|btn|button|cta|link)/i;

    function scanStylesheetsForVariablesAndRules(amoled) {
        const htmlEl = document.documentElement;
        if (!htmlEl || htmlEl.classList.contains("uh-reader-active")) return;

        const varOverrides = new Map();
        const ruleOverrides = [];
        let ruleCount = 0;
        const MAX_RULES = 700;

        function inspectVarDeclaration(name, rawVal) {
            if (!name || !name.startsWith("--") || !rawVal) return;
            const trimmed = rawVal.trim();
            if (!trimmed || trimmed.indexOf("var(") !== -1 || trimmed.indexOf("url(") !== -1) return;
            const c = parseCssColor(trimmed);
            if (!c) return;

            if (BORDER_VAR_HINT_RE.test(name)) {
                const mapped = transformBorderColor(c, amoled);
                if (mapped) varOverrides.set(name, mapped);
                return;
            }
            if (TEXT_VAR_HINT_RE.test(name) && !BG_VAR_HINT_RE.test(name)) {
                const mapped = transformTextColor(c);
                if (mapped) varOverrides.set(name, mapped);
                return;
            }
            if (BG_VAR_HINT_RE.test(name)) {
                const mapped = transformBgColor(c, amoled, ACCENT_VAR_HINT_RE.test(name));
                if (mapped) varOverrides.set(name, mapped);
                return;
            }
            // Neutral unnamed/generic color variables: pure near-white -> bg, near-black -> text
            if (c.s < 0.14) {
                if (c.lum > 0.78) {
                    const mapped = transformBgColor(c, amoled, false);
                    if (mapped) varOverrides.set(name, mapped);
                } else if (c.lum < 0.22 && !ACCENT_VAR_HINT_RE.test(name)) {
                    const mapped = transformTextColor(c);
                    if (mapped) varOverrides.set(name, mapped);
                }
            }
        }

        function processRuleList(rules) {
            if (!rules) return;
            for (let i = 0; i < rules.length; i++) {
                const r = rules[i];
                if (!r) continue;
                if (r.type === 4) { // CSSMediaRule
                    try {
                        const mText = r.conditionText || (r.media && r.media.mediaText) || "";
                        if (mText && /print/i.test(mText)) continue;
                        if (!mText || window.matchMedia(mText).matches) {
                            processRuleList(r.cssRules);
                        }
                    } catch (_e) {}
                    continue;
                }
                if (r.cssRules && r.type !== 1) { // @layer, @supports, etc.
                    try { processRuleList(r.cssRules); } catch (_e) {}
                    continue;
                }
                if (r.type !== 1 || !r.style || !r.selectorText) continue;

                const sel = r.selectorText;
                if (sel.indexOf("__uh") !== -1 || sel.indexOf("uh-reader") !== -1) continue;

                const isRootLike = /(^|[,\s])(:root|html|body|\[data-theme|\[data-color)([,\s.:#\[]|$)/i.test(sel);
                const st = r.style;

                if (isRootLike) {
                    for (let j = 0; j < st.length; j++) {
                        const prop = st[j];
                        if (prop && prop.startsWith("--")) {
                            inspectVarDeclaration(prop, st.getPropertyValue(prop));
                        }
                    }
                }

                if (ruleCount >= MAX_RULES) continue;
                const decls = [];
                const bgColVal = st.getPropertyValue("background-color");
                if (bgColVal && bgColVal.indexOf("var(") === -1) {
                    const c = parseCssColor(bgColVal);
                    const mapped = transformBgColor(c, amoled, true);
                    if (mapped) decls.push("background-color: " + mapped + " !important;");
                }
                const bgImgVal = st.getPropertyValue("background-image");
                if (bgImgVal && bgImgVal.indexOf("gradient(") !== -1 && bgImgVal.indexOf("url(") === -1) {
                    const mappedGrad = transformGradientValue(bgImgVal, amoled);
                    if (mappedGrad) decls.push("background-image: " + mappedGrad + " !important;");
                }
                const txtVal = st.getPropertyValue("color");
                if (txtVal && txtVal.indexOf("var(") === -1) {
                    const c = parseCssColor(txtVal);
                    const mapped = transformTextColor(c);
                    if (mapped) decls.push("color: " + mapped + " !important;");
                }
                const brdVal = st.getPropertyValue("border-color") || st.getPropertyValue("border-bottom-color") || st.getPropertyValue("border-top-color");
                if (brdVal && brdVal.indexOf("var(") === -1) {
                    const c = parseCssColor(brdVal);
                    const mapped = transformBorderColor(c, amoled);
                    if (mapped) decls.push("border-color: " + mapped + " !important;");
                }

                if (decls.length > 0) {
                    // Scope selector under html.__uh-dark-applied:not(.uh-reader-active)
                    const scoped = sel.split(",").map((part) => {
                        const p = part.trim();
                        if (!p) return "";
                        if (/^(html|:root)\b/i.test(p)) {
                            return p.replace(/^(html|:root)\b/i, "html.__uh-dark-applied:not(.uh-reader-active)");
                        }
                        return "html.__uh-dark-applied:not(.uh-reader-active) " + p;
                    }).filter(Boolean).join(", ");
                    if (scoped) {
                        ruleOverrides.push(scoped + " { " + decls.join(" ") + " }");
                        ruleCount++;
                    }
                }
            }
        }

        // Also inspect inline CSS variables on <html> and <body>
        try {
            const inlineRoots = [document.documentElement, document.body].filter(Boolean);
            for (let r = 0; r < inlineRoots.length; r++) {
                const st = inlineRoots[r].style;
                if (!st) continue;
                for (let j = 0; j < st.length; j++) {
                    const prop = st[j];
                    if (prop && prop.startsWith("--")) {
                        inspectVarDeclaration(prop, st.getPropertyValue(prop));
                    }
                }
            }
        } catch (_e) {}

        const sheets = document.styleSheets;
        for (let i = 0; i < sheets.length; i++) {
            const sheet = sheets[i];
            const owner = sheet.ownerNode;
            if (owner && owner.id && owner.id.startsWith("__uh_")) continue;
            if (PROCESSED_SHEETS.has(sheet)) continue;
            try {
                const rules = sheet.cssRules;
                if (rules) {
                    processRuleList(rules);
                    PROCESSED_SHEETS.add(sheet);
                }
            } catch (_corsErr) {
                // Cross-origin stylesheet -> Layer 3 DOM walker handles its computed styles seamlessly
            }
        }

        if (varOverrides.size > 0) {
            const lines = [];
            varOverrides.forEach((val, name) => {
                lines.push("  " + name + ": " + val + " !important;");
            });
            const varsCss = "html.__uh-dark-applied:not(.uh-reader-active), html.__uh-dark-applied:not(.uh-reader-active) body {\n" + lines.join("\n") + "\n}";
            const varsEl = document.getElementById(STYLE_VARS_ID) || document.createElement("style");
            varsEl.id = STYLE_VARS_ID;
            varsEl.textContent = varsCss;
            (document.head || htmlEl).appendChild(varsEl);
        }

        if (ruleOverrides.length > 0) {
            const existingRulesEl = document.getElementById(STYLE_RULES_ID);
            const rulesEl = existingRulesEl || document.createElement("style");
            rulesEl.id = STYLE_RULES_ID;
            rulesEl.textContent = (existingRulesEl ? existingRulesEl.textContent + "\n" : "") + ruleOverrides.join("\n");
            (document.head || htmlEl).appendChild(rulesEl);
        }
    }

    // -------------------------------------------------------------------------
    // Layer 3: Uncapped Full-DOM + IntersectionObserver Luminance Converter
    // -------------------------------------------------------------------------
    function saveAndSetStyle(el, prop, value) {
        const attrKey = "data-uh-orig-" + prop;
        if (!el.hasAttribute(attrKey)) {
            const origVal = el.style.getPropertyValue(prop);
            const origPrio = el.style.getPropertyPriority(prop);
            el.setAttribute(attrKey, origVal + "|" + origPrio);
        }
        el.style.setProperty(prop, value, "important");
        el.setAttribute("data-uh-dk-applied", "1");
    }

    function restoreAllInlineDarkOverrides() {
        try {
            const nodes = document.querySelectorAll("[data-uh-dk-applied]");
            const props = [
                "background-color",
                "background-image",
                "color",
                "border-color",
                "border-top-color",
                "border-bottom-color",
                "border-left-color",
                "border-right-color",
                "box-shadow",
                "fill",
                "stroke"
            ];
            for (let i = 0; i < nodes.length; i++) {
                const el = nodes[i];
                for (let p = 0; p < props.length; p++) {
                    const prop = props[p];
                    const attrKey = "data-uh-orig-" + prop;
                    if (el.hasAttribute(attrKey)) {
                        const packed = el.getAttribute(attrKey) || "|";
                        const sep = packed.lastIndexOf("|");
                        const origVal = sep >= 0 ? packed.slice(0, sep) : "";
                        const origPrio = sep >= 0 ? packed.slice(sep + 1) : "";
                        if (origVal) {
                            el.style.setProperty(prop, origVal, origPrio);
                        } else {
                            el.style.removeProperty(prop);
                        }
                        el.removeAttribute(attrKey);
                    }
                }
                el.removeAttribute("data-uh-dk-applied");
            }
            if (document.documentElement) {
                document.documentElement.classList.remove("__uh-dark-applied", "__uh-dark-amoled");
            }
        } catch (_e) {}
    }

    function isLikelyBrandActionButton(el, bgColor) {
        if (!bgColor || bgColor.s < 0.42 || bgColor.l > 0.72) return false;
        const tag = el.tagName;
        if (tag === "BUTTON" || tag === "INPUT" || (tag === "A" && el.getAttribute("role") === "button")) {
            return true;
        }
        const role = el.getAttribute("role");
        if (role === "button" || role === "switch" || role === "tab") return true;
        // Small saturated pill/badge/chip
        if (el.offsetWidth > 0 && el.offsetHeight > 0 && el.offsetWidth < 340 && el.offsetHeight < 76) {
            return true;
        }
        return false;
    }

    function processElementForDark(el) {
        if (!el || el.nodeType !== 1) return;
        const tag = el.tagName;
        if (SKIP_TAGS.has(tag) || isProtectedUhNode(el)) return;

        const htmlEl = document.documentElement;
        if (!htmlEl || !htmlEl.classList.contains("__uh-dark-applied") || htmlEl.classList.contains("uh-reader-active")) {
            return;
        }

        const amoled = darkModeActiveCfg.amoled;
        let st;
        try {
            st = getComputedStyle(el);
        } catch (_e) {
            return;
        }
        if (!st || st.display === "none") return;

        // 1) Monochrome dark SVG icon lifting (so black icons on white bars stay visible)
        if (tag === "SVG" || tag === "PATH" || tag === "CIRCLE" || tag === "RECT" || tag === "POLYGON") {
            const svgHost = tag === "SVG" ? el : (el.ownerSVGElement || el);
            const w = svgHost.clientWidth || svgHost.getBoundingClientRect().width || 0;
            const h = svgHost.clientHeight || svgHost.getBoundingClientRect().height || 0;
            if (w > 0 && w <= 72 && h > 0 && h <= 72) {
                const fillC = parseCssColor(st.fill);
                if (fillC && fillC.lum < 0.25 && fillC.s < 0.2) {
                    saveAndSetStyle(el, "fill", "#E2E8F0");
                }
                const strokeC = parseCssColor(st.stroke);
                if (strokeC && strokeC.lum < 0.25 && strokeC.s < 0.2) {
                    saveAndSetStyle(el, "stroke", "#E2E8F0");
                }
            }
            return;
        }

        // 2) Background color conversion
        const bgImg = st.backgroundImage;
        const hasImageBg = bgImg && bgImg !== "none" && bgImg.indexOf("url(") !== -1;
        const bgColor = parseCssColor(st.backgroundColor);

        if (bgColor && !hasImageBg) {
            const keepAccent = isLikelyBrandActionButton(el, bgColor);
            const mappedBg = transformBgColor(bgColor, amoled, keepAccent);
            if (mappedBg) {
                saveAndSetStyle(el, "background-color", mappedBg);
            }
        }

        // 3) Light CSS gradients (never touches url(...) photos)
        if (bgImg && bgImg !== "none" && !hasImageBg && bgImg.indexOf("gradient(") !== -1) {
            const mappedGrad = transformGradientValue(bgImg, amoled);
            if (mappedGrad) {
                saveAndSetStyle(el, "background-image", mappedGrad);
            }
        }

        // 4) Text color conversion (skip if element sits on a preserved bright brand button)
        const preserveButtonBg = bgColor && isLikelyBrandActionButton(el, bgColor);
        if (!preserveButtonBg && !hasImageBg) {
            const txtColor = parseCssColor(st.color);
            if (txtColor) {
                const mappedTxt = transformTextColor(txtColor);
                if (mappedTxt) {
                    saveAndSetStyle(el, "color", mappedTxt);
                }
            }
        }

        // 5) Bright light borders
        if (st.borderTopWidth && st.borderTopWidth !== "0px") {
            const c = parseCssColor(st.borderTopColor);
            const m = transformBorderColor(c, amoled);
            if (m) saveAndSetStyle(el, "border-top-color", m);
        }
        if (st.borderBottomWidth && st.borderBottomWidth !== "0px") {
            const c = parseCssColor(st.borderBottomColor);
            const m = transformBorderColor(c, amoled);
            if (m) saveAndSetStyle(el, "border-bottom-color", m);
        }
        if (st.borderLeftWidth && st.borderLeftWidth !== "0px") {
            const c = parseCssColor(st.borderLeftColor);
            const m = transformBorderColor(c, amoled);
            if (m) saveAndSetStyle(el, "border-left-color", m);
        }
        if (st.borderRightWidth && st.borderRightWidth !== "0px") {
            const c = parseCssColor(st.borderRightColor);
            const m = transformBorderColor(c, amoled);
            if (m) saveAndSetStyle(el, "border-right-color", m);
        }

        // 6) Neutralize glaring white inset shadows
        if (st.boxShadow && st.boxShadow !== "none" && /rgba?\(\s*2[3-5]\d\s*,\s*2[3-5]\d\s*,\s*2[3-5]\d/.test(st.boxShadow)) {
            saveAndSetStyle(el, "box-shadow", "0 1px 4px rgba(0, 0, 0, 0.5)");
        }
    }

    function drainDarkWorkQueue() {
        darkFrameQueued = false;
        if (!darkModeActiveCfg.forceDark) {
            darkWorkQueue = [];
            return;
        }
        const BATCH = 140;
        let processed = 0;
        while (darkWorkQueue.length > 0 && processed < BATCH) {
            const el = darkWorkQueue.shift();
            if (el && el.isConnected) {
                processElementForDark(el);
                processed++;
            }
        }
        if (darkWorkQueue.length > 0) {
            darkFrameQueued = true;
            requestAnimationFrame(drainDarkWorkQueue);
        }
    }

    function enqueueElement(el, priority) {
        if (!el || el.nodeType !== 1 || SKIP_TAGS.has(el.tagName) || isProtectedUhNode(el)) return;
        if (darkQueuedSet.has(el)) return;
        darkQueuedSet.add(el);
        if (priority) {
            darkWorkQueue.unshift(el);
        } else {
            darkWorkQueue.push(el);
        }
        if (!darkFrameQueued) {
            darkFrameQueued = true;
            requestAnimationFrame(drainDarkWorkQueue);
        }
    }

    function walkAndScheduleTree(root, observeWithIo) {
        if (!root) return;
        const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT);
        const vpH = window.innerHeight || 900;
        let node = root.nodeType === 1 ? root : walker.nextNode();
        let count = 0;
        while (node) {
            if (!SKIP_TAGS.has(node.tagName) && !isProtectedUhNode(node)) {
                if (count < 220) {
                    // Prioritize above-the-fold & structural roots immediately
                    enqueueElement(node, true);
                } else if (observeWithIo && darkIntersectionObserver) {
                    try {
                        darkIntersectionObserver.observe(node);
                    } catch (_e) {
                        enqueueElement(node, false);
                    }
                } else {
                    enqueueElement(node, false);
                }
                count++;
                // Traverse open shadow roots (Reddit shreddit, YouTube polymer, web components)
                if (node.shadowRoot) {
                    walkAndScheduleTree(node.shadowRoot, false);
                }
            }
            node = walker.nextNode();
        }
        // Also ensure elements currently inside viewport are queued right away
        if (vpH > 0 && root === document.body) {
            const immediate = document.querySelectorAll("header, nav, main, article, section, footer, [role='banner'], [role='main'], [role='navigation'], [role='dialog']");
            for (let i = 0; i < immediate.length && i < 120; i++) {
                enqueueElement(immediate[i], true);
            }
        }
    }

    function disconnectDarkObservers() {
        if (darkMutationObserver) {
            try { darkMutationObserver.disconnect(); } catch (_e) {}
            darkMutationObserver = null;
        }
        if (darkIntersectionObserver) {
            try { darkIntersectionObserver.disconnect(); } catch (_e) {}
            darkIntersectionObserver = null;
        }
        if (darkEvalTimer) {
            clearTimeout(darkEvalTimer);
            darkEvalTimer = null;
        }
        if (darkSheetScanTimer) {
            clearTimeout(darkSheetScanTimer);
            darkSheetScanTimer = null;
        }
        darkWorkQueue = [];
        darkQueuedSet = new WeakSet();
    }

    function injectBaseDarkSheet(amoled) {
        const htmlEl = document.documentElement;
        if (!htmlEl) return;
        const p = getPalette(amoled);

        const css = [
            "html.__uh-dark-applied:not(.uh-reader-active) {",
            "  color-scheme: dark !important;",
            "  background-color: " + p.bg + " !important;",
            "  color: " + p.text + " !important;",
            "}",
            "html.__uh-dark-applied:not(.uh-reader-active) body {",
            "  background-color: " + p.bg + " !important;",
            "  color: " + p.text + " !important;",
            "}",
            "html.__uh-dark-applied:not(.uh-reader-active) a:not([role='button']) {",
            "  color: " + p.link + ";",
            "}",
            "html.__uh-dark-applied:not(.uh-reader-active) a:visited:not([role='button']) {",
            "  color: " + p.linkVisited + ";",
            "}",
            "html.__uh-dark-applied:not(.uh-reader-active) input:not([type='checkbox']):not([type='radio']):not([type='range']):not([type='color']),",
            "html.__uh-dark-applied:not(.uh-reader-active) textarea,",
            "html.__uh-dark-applied:not(.uh-reader-active) select {",
            "  background-color: " + p.elevated + " !important;",
            "  color: " + p.text + " !important;",
            "  border-color: " + p.border + " !important;",
            "}",
            "html.__uh-dark-applied:not(.uh-reader-active) ::placeholder {",
            "  color: " + p.textSecondary + " !important;",
            "}",
            "html.__uh-dark-applied:not(.uh-reader-active) hr {",
            "  border-color: " + p.border + " !important;",
            "}",
            "html.__uh-dark-applied:not(.uh-reader-active) ::selection {",
            "  background-color: #2B5EA7 !important;",
            "  color: #FFFFFF !important;",
            "}",
            "html.__uh-dark-applied:not(.uh-reader-active) img,",
            "html.__uh-dark-applied:not(.uh-reader-active) video,",
            "html.__uh-dark-applied:not(.uh-reader-active) picture,",
            "html.__uh-dark-applied:not(.uh-reader-active) canvas,",
            "html.__uh-dark-applied:not(.uh-reader-active) iframe {",
            "  filter: none !important;",
            "}"
        ].join("\n");

        const el = document.getElementById(STYLE_BASE_ID) || document.createElement("style");
        el.id = STYLE_BASE_ID;
        el.textContent = css;
        (document.head || htmlEl).appendChild(el);
    }

    function removeAllDarkSheets() {
        const ids = [STYLE_BASE_ID, STYLE_VARS_ID, STYLE_RULES_ID, STYLE_AMOLED_ID];
        for (let i = 0; i < ids.length; i++) {
            const el = document.getElementById(ids[i]);
            if (el) el.remove();
        }
    }

    function applySmartDarkMode(forceDarkEnabled, amoledEnabled, bypassed) {
        const enabled = forceDarkEnabled === true && bypassed !== true;
        const amoled = enabled && amoledEnabled === true;
        const prevCfg = darkModeActiveCfg;
        darkModeActiveCfg = { forceDark: enabled, amoled: amoled };

        if (!enabled) {
            disconnectDarkObservers();
            removeAllDarkSheets();
            restoreAllInlineDarkOverrides();
            return;
        }

        // If switching between Dark and AMOLED live, clear cached inline styles so surfaces remap cleanly
        if (prevCfg.forceDark && prevCfg.amoled !== amoled) {
            removeAllDarkSheets();
            restoreAllInlineDarkOverrides();
            darkQueuedSet = new WeakSet();
        }

        const evaluateAndApply = () => {
            if (!darkModeActiveCfg.forceDark) return;
            const htmlEl = document.documentElement;
            if (!htmlEl || htmlEl.classList.contains("uh-reader-active")) return;

            const alreadyDark = isPageAlreadyNativeDark();
            if (alreadyDark) {
                const baseEl = document.getElementById(STYLE_BASE_ID);
                if (baseEl) baseEl.remove();
                const varsEl = document.getElementById(STYLE_VARS_ID);
                if (varsEl) varsEl.remove();
                const rulesEl = document.getElementById(STYLE_RULES_ID);
                if (rulesEl) rulesEl.remove();
                restoreAllInlineDarkOverrides();

                if (darkModeActiveCfg.amoled) {
                    const amoledCss = [
                        "html:not(.uh-reader-active), html:not(.uh-reader-active) body { background-color: #000000 !important; color-scheme: dark !important; }",
                        ":root { --yt-spec-base-background: #000000 !important; --yt-spec-raised-background: #0A0C10 !important; --bgColor-default: #000000 !important; --color-canvas-default: #000000 !important; --color-background: #000000 !important; }",
                        "ytd-app, ytm-app, #app, #root, #__next, main { background-color: #000000 !important; }"
                    ].join("\n");
                    const el = document.getElementById(STYLE_AMOLED_ID) || document.createElement("style");
                    el.id = STYLE_AMOLED_ID;
                    el.textContent = amoledCss;
                    (document.head || htmlEl).appendChild(el);
                } else {
                    const el = document.getElementById(STYLE_AMOLED_ID);
                    if (el) el.remove();
                }
                return;
            }

            const amEl = document.getElementById(STYLE_AMOLED_ID);
            if (amEl) amEl.remove();

            htmlEl.classList.add("__uh-dark-applied");
            if (darkModeActiveCfg.amoled) {
                htmlEl.classList.add("__uh-dark-amoled");
            } else {
                htmlEl.classList.remove("__uh-dark-amoled");
            }

            // Layer 0: Base dark canvas & form controls
            injectBaseDarkSheet(darkModeActiveCfg.amoled);

            // Layer 1 & 2: CSS variables + accessible author stylesheet rules
            scanStylesheetsForVariablesAndRules(darkModeActiveCfg.amoled);

            // Layer 3: IntersectionObserver + full DOM pass
            if (!darkIntersectionObserver && typeof IntersectionObserver === "function") {
                darkIntersectionObserver = new IntersectionObserver((entries) => {
                    for (let i = 0; i < entries.length; i++) {
                        if (entries[i].isIntersecting) {
                            const target = entries[i].target;
                            darkIntersectionObserver.unobserve(target);
                            enqueueElement(target, true);
                        }
                    }
                }, { rootMargin: "650px 0px 650px 0px", threshold: 0 });
            }

            if (document.body) {
                walkAndScheduleTree(document.body, true);
            }
        };

        const scheduleEval = (delayMs) => {
            if (darkEvalTimer) clearTimeout(darkEvalTimer);
            darkEvalTimer = setTimeout(() => {
                darkEvalTimer = null;
                evaluateAndApply();
            }, delayMs);
        };

        evaluateAndApply();
        if (document.readyState === "loading") {
            document.addEventListener("DOMContentLoaded", () => scheduleEval(40), { once: true });
        }
        window.addEventListener("load", () => scheduleEval(140), { once: true });
        setTimeout(evaluateAndApply, 500);
        setTimeout(() => {
            if (darkModeActiveCfg.forceDark) {
                scanStylesheetsForVariablesAndRules(darkModeActiveCfg.amoled);
                if (document.body && document.documentElement.classList.contains("__uh-dark-applied")) {
                    walkAndScheduleTree(document.body, true);
                }
            }
        }, 1600);

        // Live MutationObserver for SPAs, lazy-loaded stylesheets, infinite scroll & modals
        if (darkMutationObserver) {
            try { darkMutationObserver.disconnect(); } catch (_e) {}
            darkMutationObserver = null;
        }
        try {
            darkMutationObserver = new MutationObserver((mutations) => {
                if (!darkModeActiveCfg.forceDark) return;
                const htmlEl = document.documentElement;
                if (!htmlEl || htmlEl.classList.contains("uh-reader-active")) return;

                let newStylesheetAdded = false;
                for (let m = 0; m < mutations.length; m++) {
                    const mut = mutations[m];
                    if (mut.type === "childList") {
                        for (let n = 0; n < mut.addedNodes.length; n++) {
                            const added = mut.addedNodes[n];
                            if (!added || added.nodeType !== 1) continue;
                            if (added.tagName === "STYLE" || (added.tagName === "LINK" && added.rel === "stylesheet")) {
                                if (!added.id || !added.id.startsWith("__uh_")) {
                                    newStylesheetAdded = true;
                                }
                                continue;
                            }
                            if (htmlEl.classList.contains("__uh-dark-applied")) {
                                walkAndScheduleTree(added, true);
                            }
                        }
                    } else if (mut.type === "attributes") {
                        const target = mut.target;
                        if (target === htmlEl) {
                            if (mut.attributeName === "data-theme" || mut.attributeName === "data-color-mode" || mut.attributeName === "dark") {
                                scheduleEval(120);
                            }
                        } else if (target && target.nodeType === 1 && htmlEl.classList.contains("__uh-dark-applied")) {
                            darkQueuedSet.delete(target);
                            enqueueElement(target, true);
                        }
                    }
                }
                if (newStylesheetAdded && htmlEl.classList.contains("__uh-dark-applied")) {
                    if (darkSheetScanTimer) clearTimeout(darkSheetScanTimer);
                    darkSheetScanTimer = setTimeout(() => {
                        darkSheetScanTimer = null;
                        scanStylesheetsForVariablesAndRules(darkModeActiveCfg.amoled);
                    }, 150);
                }
            });

            if (document.documentElement) {
                darkMutationObserver.observe(document.documentElement, {
                    childList: true,
                    subtree: true,
                    attributes: true,
                    attributeFilter: ["class", "style", "data-theme", "data-color-mode", "dark"]
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
