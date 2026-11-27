// -----------------------------------------------------------------------------
// UH Bridge Content Script: Kindle-Class Book Reader Mode + Paged Flip + Live TTS
// -----------------------------------------------------------------------------
(function () {
    if (window.top !== window) return;
    if (window.__uhReaderInstalled) return;
    window.__uhReaderInstalled = true;

    const api = (typeof browser !== "undefined" && browser.runtime) ? browser : chrome;
    const ROOT_ID = "uh-reader-overlay-root";
    const STYLE_ID = "uh-reader-overlay-style";
    const HTML_ACTIVE_CLASS = "uh-reader-active";

    let readerActive = false;
    let chromeVisible = true;
    let lastCheckedUrl = "";
    let lastEligible = false;
    let savedHtmlOverflow = "";
    let savedBodyOverflow = "";
    let currentSentences = [];
    let currentTtsIndex = -1;
    let currentTtsPlaying = false;
    let currentPage = 0;
    let totalPages = 1;
    let currentDetectedLang = "en";
    let selectionTimer = null;
    let resizeHandler = null;

    let currentPrefs = {
        theme: "sepia",
        fontFamily: "serif",
        fontSizePx: 19,
        lineHeight: 1.78,
        marginPx: 24,
        showImages: true,
        ttsEnabled: true,
        pageMode: false,
        isFullscreen: false
    };

    const UNLIKELY_RE = /banner|breadcrumbs|combx|comment|community|cover-wrap|disqus|extra|footer|gdpr|header|legends|menu|related|remark|replies|rss|shoutbox|sidebar|skyscraper|social|sponsor|supplemental|ad-break|agegate|pagination|pager|popup|yom-remote|cookie|newsletter|subscribe|promo|widget|nav|toolbar|modal/i;
    const POSITIVE_RE = /article|body|content|entry|hentry|h-entry|main|page|pagination|post|text|blog|story|markdown|prose|wiki|chapter/i;

    const EXCLUDED_HOSTS = new Set([
        "www.youtube.com", "m.youtube.com", "youtube.com",
        "mail.google.com", "calendar.google.com", "drive.google.com", "docs.google.com",
        "maps.google.com", "web.whatsapp.com", "discord.com", "open.spotify.com",
        "www.netflix.com", "music.youtube.com"
    ]);

    function isExcludedUrl(href) {
        try {
            const u = new URL(href);
            if (u.protocol !== "http:" && u.protocol !== "https:") return true;
            const host = u.hostname.toLowerCase();
            if (EXCLUDED_HOSTS.has(host)) return true;
            if ((host.includes("google.") || host === "www.bing.com" || host === "duckduckgo.com") &&
                (u.pathname === "/search" || u.pathname === "/")) {
                return true;
            }
            return false;
        } catch (_e) {
            return true;
        }
    }

    function sendNative(payload) {
        try {
            api.runtime.sendMessage({
                type: "UH_NATIVE_CALL",
                pageUrl: location.href,
                payload: payload
            }).catch(() => {});
        } catch (_e) {}
    }

    function getTextLen(el) {
        return ((el && el.textContent) || "").replace(/\s+/g, " ").trim().length;
    }

    function isNodeVisible(el) {
        if (!el || el.nodeType !== 1) return false;
        if (el.id === ROOT_ID) return false;
        if (el.hidden || el.getAttribute("aria-hidden") === "true") return false;
        const tag = el.tagName;
        if (tag === "SCRIPT" || tag === "STYLE" || tag === "NOSCRIPT" || tag === "TEMPLATE" || tag === "SVG" || tag === "NAV" || tag === "FOOTER" || tag === "ASIDE") {
            return false;
        }
        try {
            const st = window.getComputedStyle(el);
            if (st.display === "none" || st.visibility === "hidden") return false;
        } catch (_e) {}
        return true;
    }

    function scoreCandidate(el) {
        if (!isNodeVisible(el)) return -1;
        const textLen = getTextLen(el);
        if (textLen < 160) return -1;

        const clsId = (el.className || "") + " " + (el.id || "");
        let score = 0;
        const tag = el.tagName;
        if (tag === "ARTICLE") score += 45;
        else if (tag === "MAIN") score += 35;
        else if (tag === "SECTION") score += 12;
        else if (tag === "DIV") score += 5;

        if (POSITIVE_RE.test(clsId)) score += 28;
        if (UNLIKELY_RE.test(clsId)) score -= 32;

        const paragraphs = el.querySelectorAll("p, li, blockquote, pre");
        let paraChars = 0;
        let goodParas = 0;
        for (let i = 0; i < paragraphs.length; i++) {
            const len = getTextLen(paragraphs[i]);
            if (len >= 28) {
                goodParas++;
                paraChars += Math.min(len, 600);
            }
        }
        score += goodParas * 8 + Math.min(paraChars / 42, 95);

        const links = el.querySelectorAll("a");
        let linkChars = 0;
        for (let i = 0; i < links.length; i++) linkChars += getTextLen(links[i]);
        const linkDensity = textLen > 0 ? (linkChars / textLen) : 1;
        if (linkDensity > 0.55) score -= 45;

        return score;
    }

    function findBestRoot() {
        const direct = document.querySelector("article, main, [role='main']");
        let best = null;
        let bestScore = -1;

        const candidates = document.querySelectorAll("article, main, [role='main'], section, div");
        const limit = Math.min(candidates.length, 320);
        for (let i = 0; i < limit; i++) {
            const el = candidates[i];
            if (el.id === ROOT_ID || el.closest("#" + ROOT_ID)) continue;
            const sc = scoreCandidate(el);
            if (sc > bestScore) {
                bestScore = sc;
                best = el;
            }
        }

        if (best && bestScore >= 24) return best;
        if (direct && getTextLen(direct) >= 180) return direct;
        return document.body;
    }

    function checkEligibility() {
        if (!document.body || isExcludedUrl(location.href)) return false;
        const best = findBestRoot();
        if (!best) return false;
        const paras = best.querySelectorAll("p, article, li, blockquote, pre, h2, h3");
        let totalChars = 0;
        let blocks = 0;
        for (let i = 0; i < paras.length; i++) {
            if (!isNodeVisible(paras[i])) continue;
            const len = getTextLen(paras[i]);
            if (len >= 24) {
                totalChars += len;
                blocks++;
            }
            if (totalChars >= 240 && blocks >= 2) return true;
        }
        return getTextLen(best) >= 380;
    }

    function notifyEligibility(force) {
        const href = location.href || "";
        const eligible = checkEligibility();
        if (!force && href === lastCheckedUrl && eligible === lastEligible) return;
        lastCheckedUrl = href;
        lastEligible = eligible;
        let host = "";
        try { host = new URL(href).hostname.toLowerCase(); } catch (_e) {}
        sendNative({
            type: "READER_ELIGIBILITY",
            url: href,
            host: host,
            eligible: eligible,
            active: readerActive
        });
    }

    function escapeHtml(str) {
        return String(str || "")
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;");
    }

    // Multi-language auto-detection (script analysis + document lang tag)
    function detectLanguage(sampleText) {
        const txt = String(sampleText || "").slice(0, 4000);
        const scriptRules = [
            { lang: "hi", re: /[\u0900-\u097F]/g },
            { lang: "bn", re: /[\u0980-\u09FF]/g },
            { lang: "ta", re: /[\u0B80-\u0BFF]/g },
            { lang: "te", re: /[\u0C00-\u0C7F]/g },
            { lang: "ar", re: /[\u0600-\u06FF]/g },
            { lang: "he", re: /[\u0590-\u05FF]/g },
            { lang: "ja", re: /[\u3040-\u30FF]/g },
            { lang: "ko", re: /[\uAC00-\uD7AF]/g },
            { lang: "zh", re: /[\u4E00-\u9FFF]/g },
            { lang: "ru", re: /[\u0400-\u04FF]/g },
            { lang: "el", re: /[\u0370-\u03FF]/g },
            { lang: "th", re: /[\u0E00-\u0E7F]/g }
        ];
        let bestScript = null;
        let bestCount = 0;
        for (let i = 0; i < scriptRules.length; i++) {
            const m = txt.match(scriptRules[i].re);
            const cnt = m ? m.length : 0;
            if (cnt > bestCount) {
                bestCount = cnt;
                bestScript = scriptRules[i].lang;
            }
        }
        if (bestScript && bestCount >= 12) {
            return bestScript;
        }
        const docLang = (document.documentElement.getAttribute("lang") ||
            document.querySelector("meta[property='og:locale']")?.getAttribute("content") ||
            "").trim().toLowerCase().replace("_", "-");
        if (docLang && /^[a-z]{2,3}(-[a-z0-9]{2,4})?$/i.test(docLang)) {
            return docLang;
        }
        return "en";
    }

    function isRtlLang(lang) {
        const base = String(lang || "").split("-")[0].toLowerCase();
        return base === "ar" || base === "he" || base === "fa" || base === "ur";
    }

    // Unicode-safe sentence splitter supporting English, European, Hindi (।), CJK (。！？), Arabic (؟)
    function splitIntoSentences(text) {
        const clean = String(text || "").replace(/\s+/g, " ").trim();
        if (!clean) return [];
        if (clean.length <= 160) return [clean];

        const out = [];
        let buf = "";
        for (let i = 0; i < clean.length; i++) {
            const ch = clean[i];
            buf += ch;
            const isCjkEnd = ch === "。" || ch === "！" || ch === "？" || ch === "।" || ch === "؟";
            const isLatinEnd = (ch === "." || ch === "!" || ch === "?") &&
                (i + 1 === clean.length || clean[i + 1] === " " || clean[i + 1] === "\"" || clean[i + 1] === "”" || clean[i + 1] === ")");

            if ((isCjkEnd || isLatinEnd) && buf.trim().length >= 22) {
                // Avoid splitting common abbreviations like Mr., Dr., U.S., e.g.
                const tail = buf.trim().slice(-5);
                if (ch === "." && /\b(Mr|Ms|Dr|St|vs|No|Jr|Sr)\.$/i.test(tail)) {
                    continue;
                }
                out.push(buf.trim());
                buf = "";
            }
        }
        if (buf.trim().length > 0) {
            if (out.length > 0 && buf.trim().length < 16) {
                out[out.length - 1] += " " + buf.trim();
            } else {
                out.push(buf.trim());
            }
        }
        return out.length ? out : [clean];
    }

    function sanitizeAndBuildArticle() {
        const root = findBestRoot();
        const clone = root ? root.cloneNode(true) : document.body.cloneNode(true);
        const insideOverlay = clone.querySelector("#" + ROOT_ID);
        if (insideOverlay) insideOverlay.remove();

        const stripSel = [
            "script", "style", "noscript", "iframe", "form", "button", "input", "select",
            "textarea", "nav", "footer", "aside", "svg", "canvas", "audio", "video",
            "[role='navigation']", "[role='banner']", "[role='complementary']", "[aria-hidden='true']"
        ].join(",");
        clone.querySelectorAll(stripSel).forEach((n) => n.remove());

        clone.querySelectorAll("div, section, ul, ol").forEach((n) => {
            const clsId = (n.className || "") + " " + (n.id || "");
            if (UNLIKELY_RE.test(clsId) && !POSITIVE_RE.test(clsId) && getTextLen(n) < 500) {
                n.remove();
            }
        });

        clone.querySelectorAll("*").forEach((el) => {
            const tag = el.tagName;
            if (tag === "IMG") {
                const src = el.getAttribute("src") || el.getAttribute("data-src") || el.getAttribute("data-original") || "";
                if (!src || src.startsWith("data:image/gif")) {
                    el.remove();
                    return;
                }
                try {
                    el.setAttribute("src", new URL(src, location.href).href);
                } catch (_e) {}
                el.removeAttribute("width");
                el.removeAttribute("height");
                el.removeAttribute("style");
                el.removeAttribute("srcset");
                el.removeAttribute("class");
                el.setAttribute("loading", "lazy");
            } else if (tag === "A") {
                const href = el.getAttribute("href");
                if (href) {
                    try { el.setAttribute("href", new URL(href, location.href).href); } catch (_e) {}
                }
                el.removeAttribute("style");
                el.removeAttribute("class");
            } else {
                el.removeAttribute("style");
                el.removeAttribute("class");
                el.removeAttribute("id");
                el.removeAttribute("width");
                el.removeAttribute("height");
            }
        });

        const ogTitle = document.querySelector("meta[property='og:title']")?.getAttribute("content") || "";
        const h1El = document.querySelector("h1");
        const h1Text = (h1El && !h1El.closest("#" + ROOT_ID)) ? (h1El.textContent || "").trim() : "";
        const title = (h1Text.length >= 4 && h1Text.length <= 220)
            ? h1Text
            : (ogTitle.trim() || document.title || location.hostname);

        const cloneH1 = clone.querySelector("h1");
        if (cloneH1 && cloneH1.textContent.trim().toLowerCase() === title.trim().toLowerCase()) {
            cloneH1.remove();
        }

        const authorMeta = document.querySelector("meta[name='author'], meta[property='article:author']")?.getAttribute("content") || "";
        const bylineEl = document.querySelector("[rel='author'], .byline, .author, [itemprop='author']");
        const byline = (authorMeta || (bylineEl ? bylineEl.textContent : "") || "").replace(/\s+/g, " ").trim().slice(0, 90);
        const host = location.hostname.replace(/^www\./i, "");

        // Index readable blocks into clean sentence-level spans for accurate TTS & book layout
        const sentences = [];
        const readableNodes = clone.querySelectorAll("p, h2, h3, h4, blockquote, li, pre");
        readableNodes.forEach((node) => {
            if (node.closest("pre") && node.tagName !== "PRE") return;
            if (node.querySelector("p, blockquote, pre")) return;
            const txt = (node.textContent || "").replace(/\s+/g, " ").trim();
            if (txt.length < 4) return;
            if (node.tagName === "PRE") {
                const idx = sentences.length;
                node.setAttribute("data-uh-s", String(idx));
                sentences.push(txt);
                return;
            }
            const parts = splitIntoSentences(txt);
            // Only replace innerHTML with sentence spans when block has no embedded <img>
            if (!node.querySelector("img")) {
                const spans = parts.map((part) => {
                    const idx = sentences.length;
                    sentences.push(part);
                    return `<span class="uh-s" data-uh-s="${idx}">${escapeHtml(part)}</span>`;
                }).join(" ");
                node.innerHTML = spans;
            } else {
                const idx = sentences.length;
                node.setAttribute("data-uh-s", String(idx));
                sentences.push(txt);
            }
        });

        let bodyHtml = clone.innerHTML.trim();
        if (sentences.length === 0) {
            const rawText = (clone.textContent || "").replace(/\n{3,}/g, "\n\n").trim();
            const chunks = rawText.split(/\n+/).map((s) => s.trim()).filter((s) => s.length >= 12);
            bodyHtml = chunks.map((c) => {
                const parts = splitIntoSentences(c);
                const spans = parts.map((p) => {
                    const idx = sentences.length;
                    sentences.push(p);
                    return `<span class="uh-s" data-uh-s="${idx}">${escapeHtml(p)}</span>`;
                }).join(" ");
                return `<p>${spans}</p>`;
            }).join("");
        }

        const joined = sentences.join(" ");
        const detectedLang = detectLanguage(title + " " + joined);
        const wordCount = Math.max(
            joined.split(/\s+/).filter(Boolean).length,
            Math.round(joined.length / 5)
        );
        const readMins = Math.max(1, Math.ceil(wordCount / 215));

        return {
            title,
            byline,
            host,
            bodyHtml,
            sentences,
            wordCount,
            readMins,
            lang: detectedLang
        };
    }

    function themeTokens(theme) {
        switch (theme) {
            case "light":
                return {
                    bg: "#FAF9F6", fg: "#1C1B18", muted: "#68645C", accent: "#2563EB",
                    surface: "#F0EEE9", barBg: "rgba(250,249,246,0.94)", border: "rgba(28,27,24,0.10)",
                    ttsBg: "rgba(37,99,235,0.18)", hlBg: "rgba(250,204,21,0.38)"
                };
            case "sepia":
                return {
                    bg: "#F5ECD8", fg: "#322618", muted: "#75624B", accent: "#9A4A14",
                    surface: "#EADEC5", barBg: "rgba(245,236,216,0.95)", border: "rgba(50,38,24,0.13)",
                    ttsBg: "rgba(154,74,20,0.19)", hlBg: "rgba(217,119,6,0.28)"
                };
            case "oled":
                return {
                    bg: "#000000", fg: "#E4E6EA", muted: "#888E99", accent: "#7DD3FC",
                    surface: "#121418", barBg: "rgba(8,10,13,0.94)", border: "rgba(255,255,255,0.12)",
                    ttsBg: "rgba(125,211,252,0.22)", hlBg: "rgba(250,204,21,0.26)"
                };
            case "dark":
            default:
                return {
                    bg: "#17181C", fg: "#E4E2DC", muted: "#98968E", accent: "#93C5FD",
                    surface: "#22242B", barBg: "rgba(23,24,28,0.94)", border: "rgba(255,255,255,0.10)",
                    ttsBg: "rgba(147,197,253,0.22)", hlBg: "rgba(250,204,21,0.26)"
                };
        }
    }

    function fontStack(family) {
        switch (family) {
            case "editorial":
                return "'Palatino Linotype', 'Book Antiqua', Palatino, 'Noto Serif', Georgia, serif";
            case "sans":
                return "'Inter', 'Roboto', system-ui, -apple-system, sans-serif";
            case "mono":
                return "'JetBrains Mono', 'Roboto Mono', ui-monospace, monospace";
            case "serif":
            default:
                return "'Literata', 'Iowan Old Style', Georgia, 'Noto Serif', 'Times New Roman', serif";
        }
    }

    function ensureStyleElement() {
        const old = document.getElementById(STYLE_ID);
        if (old) old.remove();
        const style = document.createElement("style");
        style.id = STYLE_ID;
        style.textContent = `
            #${ROOT_ID} {
                position: fixed !important;
                inset: 0 !important;
                z-index: 2147483646 !important;
                background: var(--uh-r-bg, #F5ECD8) !important;
                color: var(--uh-r-fg, #322618) !important;
                font-family: var(--uh-r-font, Georgia, serif) !important;
                margin: 0 !important;
                padding: 0 !important;
                box-sizing: border-box !important;
                display: flex !important;
                flex-direction: column !important;
                overflow: hidden !important;
                -webkit-font-smoothing: antialiased !important;
                text-rendering: optimizeLegibility !important;
            }
            #${ROOT_ID} * { box-sizing: border-box !important; }

            /* Top progress line */
            #${ROOT_ID} .uh-r-progress-track {
                position: fixed !important;
                top: 0 !important;
                left: 0 !important;
                right: 0 !important;
                height: 2.5px !important;
                background: transparent !important;
                z-index: 2147483647 !important;
                pointer-events: none !important;
            }
            #${ROOT_ID} .uh-r-progress-fill {
                height: 100% !important;
                width: 0%;
                background: var(--uh-r-accent, #9A4A14) !important;
                transition: width 140ms ease-out !important;
            }

            /* Integrated non-moving compact top & bottom Kindle bars */
            #${ROOT_ID} .uh-r-bar {
                position: fixed !important;
                left: 0 !important;
                right: 0 !important;
                z-index: 2147483647 !important;
                background: var(--uh-r-bar-bg, rgba(245,236,216,0.95)) !important;
                backdrop-filter: blur(14px) !important;
                -webkit-backdrop-filter: blur(14px) !important;
                color: var(--uh-r-fg, #322618) !important;
                display: flex !important;
                align-items: center !important;
                gap: 6px !important;
                transition: opacity 180ms ease, transform 200ms cubic-bezier(0.22, 1, 0.36, 1) !important;
                user-select: none !important;
            }
            #${ROOT_ID} .uh-r-topbar {
                top: 0 !important;
                height: 46px !important;
                padding: 0 10px !important;
                border-bottom: 1px solid var(--uh-r-border) !important;
            }
            #${ROOT_ID} .uh-r-bottombar {
                bottom: 0 !important;
                height: 50px !important;
                padding: 0 12px !important;
                border-top: 1px solid var(--uh-r-border) !important;
                justify-content: space-between !important;
            }
            #${ROOT_ID}.uh-r-chrome-hidden .uh-r-topbar {
                opacity: 0 !important;
                transform: translateY(-100%) !important;
                pointer-events: none !important;
            }
            #${ROOT_ID}.uh-r-chrome-hidden .uh-r-bottombar {
                opacity: 0 !important;
                transform: translateY(100%) !important;
                pointer-events: none !important;
            }
            #${ROOT_ID} .uh-r-bar-title {
                flex: 1 1 auto !important;
                min-width: 0 !important;
                font-family: system-ui, -apple-system, 'Roboto', sans-serif !important;
                font-size: 13px !important;
                font-weight: 600 !important;
                white-space: nowrap !important;
                overflow: hidden !important;
                text-overflow: ellipsis !important;
                color: var(--uh-r-fg) !important;
                padding: 0 4px !important;
            }
            #${ROOT_ID} .uh-r-bar-meta {
                font-family: system-ui, -apple-system, 'Roboto', sans-serif !important;
                font-size: 11.5px !important;
                font-weight: 600 !important;
                color: var(--uh-r-muted) !important;
                white-space: nowrap !important;
                letter-spacing: 0.01em !important;
            }
            #${ROOT_ID} .uh-r-btn {
                appearance: none !important;
                border: none !important;
                background: transparent !important;
                color: var(--uh-r-fg) !important;
                height: 34px !important;
                min-width: 34px !important;
                padding: 0 8px !important;
                border-radius: 10px !important;
                display: inline-flex !important;
                align-items: center !important;
                justify-content: center !important;
                gap: 5px !important;
                font-family: system-ui, -apple-system, 'Roboto', sans-serif !important;
                font-size: 13px !important;
                font-weight: 700 !important;
                cursor: pointer !important;
                flex-shrink: 0 !important;
            }
            #${ROOT_ID} .uh-r-btn:active {
                background: var(--uh-r-surface) !important;
            }
            #${ROOT_ID} .uh-r-btn.uh-active {
                background: var(--uh-r-tts-bg) !important;
                color: var(--uh-r-accent) !important;
            }
            #${ROOT_ID} .uh-r-btn svg {
                width: 18px !important;
                height: 18px !important;
                stroke: currentColor !important;
                fill: none !important;
                stroke-width: 2 !important;
                stroke-linecap: round !important;
                stroke-linejoin: round !important;
            }

            /* Dedicated visible exit-fullscreen pill when Reader Fullscreen is active */
            #${ROOT_ID} .uh-r-exit-fs {
                position: fixed !important;
                top: 12px !important;
                right: 12px !important;
                z-index: 2147483647 !important;
                display: none !important;
                align-items: center !important;
                gap: 5px !important;
                padding: 6px 11px !important;
                border-radius: 999px !important;
                background: var(--uh-r-bar-bg) !important;
                border: 1px solid var(--uh-r-border) !important;
                color: var(--uh-r-fg) !important;
                font-family: system-ui, -apple-system, 'Roboto', sans-serif !important;
                font-size: 11px !important;
                font-weight: 700 !important;
                box-shadow: 0 4px 16px rgba(0,0,0,0.22) !important;
                cursor: pointer !important;
                opacity: 0.85 !important;
            }
            #${ROOT_ID}.uh-r-fullscreen .uh-r-exit-fs {
                display: inline-flex !important;
            }
            #${ROOT_ID}.uh-r-fullscreen:not(.uh-r-chrome-hidden) .uh-r-exit-fs {
                top: 54px !important;
            }

            /* Scroll mode viewport */
            #${ROOT_ID} .uh-r-viewport {
                flex: 1 1 auto !important;
                width: 100% !important;
                height: 100% !important;
                overflow-y: auto !important;
                overflow-x: hidden !important;
                -webkit-overflow-scrolling: touch !important;
            }
            #${ROOT_ID} .uh-r-container {
                max-width: 680px !important;
                margin: 0 auto !important;
                padding: 64px var(--uh-r-pad, 24px) 96px var(--uh-r-pad, 24px) !important;
            }

            /* Kindle Book Pages mode (horizontal multi-column pagination) */
            #${ROOT_ID}.uh-r-paged .uh-r-viewport {
                overflow: hidden !important;
                touch-action: pan-y !important;
                position: relative !important;
            }
            #${ROOT_ID}.uh-r-paged .uh-r-container {
                max-width: none !important;
                margin: 0 !important;
                padding: 54px 0 58px 0 !important;
                height: 100vh !important;
                width: 100vw !important;
                overflow: hidden !important;
            }
            #${ROOT_ID}.uh-r-paged .uh-r-book-track {
                height: calc(100vh - 112px) !important;
                column-width: 100vw !important;
                column-gap: 0px !important;
                column-fill: auto !important;
                will-change: transform !important;
                transition: transform 270ms cubic-bezier(0.22, 1, 0.36, 1) !important;
            }
            #${ROOT_ID}.uh-r-paged .uh-r-book-track.uh-dragging {
                transition: none !important;
            }
            #${ROOT_ID}.uh-r-paged .uh-r-header,
            #${ROOT_ID}.uh-r-paged .uh-r-body > * {
                padding-left: var(--uh-r-pad, 24px) !important;
                padding-right: var(--uh-r-pad, 24px) !important;
            }
            #${ROOT_ID}.uh-r-paged .uh-r-body img {
                max-height: 48vh !important;
                width: auto !important;
                max-width: calc(100vw - (var(--uh-r-pad, 24px) * 2)) !important;
                object-fit: contain !important;
                break-inside: avoid !important;
            }
            #${ROOT_ID}.uh-r-paged .uh-r-body p,
            #${ROOT_ID}.uh-r-paged .uh-r-body blockquote {
                orphans: 2 !important;
                widows: 2 !important;
            }

            /* Kindle Literary Book Typography */
            #${ROOT_ID} .uh-r-header {
                margin-bottom: 18px !important;
            }
            #${ROOT_ID} .uh-r-meta {
                display: flex !important;
                flex-wrap: wrap !important;
                gap: 6px !important;
                align-items: center !important;
                font-family: system-ui, -apple-system, 'Roboto', sans-serif !important;
                font-size: 12px !important;
                color: var(--uh-r-muted) !important;
                margin-bottom: 10px !important;
                letter-spacing: 0.02em !important;
                text-transform: uppercase !important;
                font-weight: 600 !important;
            }
            #${ROOT_ID} .uh-r-title {
                font-size: calc(var(--uh-r-size, 19px) * 1.48) !important;
                line-height: 1.24 !important;
                font-weight: 700 !important;
                letter-spacing: -0.015em !important;
                margin: 0 0 12px 0 !important;
                color: var(--uh-r-fg) !important;
                text-align: start !important;
            }
            #${ROOT_ID} .uh-r-byline {
                font-style: italic !important;
                font-size: calc(var(--uh-r-size, 19px) * 0.82) !important;
                color: var(--uh-r-muted) !important;
                margin: 0 0 14px 0 !important;
            }
            #${ROOT_ID} .uh-r-ornament {
                display: flex !important;
                align-items: center !important;
                justify-content: center !important;
                gap: 10px !important;
                color: var(--uh-r-muted) !important;
                font-size: 12px !important;
                margin: 14px 0 22px 0 !important;
                opacity: 0.65 !important;
            }
            #${ROOT_ID} .uh-r-ornament::before,
            #${ROOT_ID} .uh-r-ornament::after {
                content: "" !important;
                height: 1px !important;
                width: 44px !important;
                background: var(--uh-r-border) !important;
            }
            #${ROOT_ID} .uh-r-body {
                font-size: var(--uh-r-size, 19px) !important;
                line-height: var(--uh-r-lh, 1.78) !important;
                word-wrap: break-word !important;
                overflow-wrap: break-word !important;
                text-align: justify !important;
                hyphens: auto !important;
                -webkit-hyphens: auto !important;
            }
            #${ROOT_ID} .uh-r-body p {
                margin: 0 0 0.72em 0 !important;
                text-indent: 0 !important;
            }
            #${ROOT_ID} .uh-r-body p + p {
                text-indent: 1.45em !important;
            }
            #${ROOT_ID} .uh-r-body h2, #${ROOT_ID} .uh-r-body h3, #${ROOT_ID} .uh-r-body h4 {
                margin: 1.45em 0 0.55em 0 !important;
                line-height: 1.3 !important;
                font-weight: 700 !important;
                text-align: start !important;
                text-indent: 0 !important;
                break-after: avoid !important;
            }
            #${ROOT_ID} .uh-r-body a {
                color: var(--uh-r-accent) !important;
                text-decoration: underline !important;
                text-underline-offset: 3px !important;
            }
            #${ROOT_ID} .uh-r-body blockquote {
                margin: 1.15em 0 !important;
                padding: 10px 16px !important;
                border-left: 3px solid var(--uh-r-accent) !important;
                background: var(--uh-r-surface) !important;
                border-radius: 0 10px 10px 0 !important;
                font-style: italic !important;
                text-align: start !important;
                text-indent: 0 !important;
            }
            #${ROOT_ID} .uh-r-body pre, #${ROOT_ID} .uh-r-body code {
                font-family: 'Roboto Mono', ui-monospace, monospace !important;
                background: var(--uh-r-surface) !important;
                border-radius: 8px !important;
                text-align: start !important;
            }
            #${ROOT_ID} .uh-r-body pre {
                padding: 12px !important;
                overflow-x: auto !important;
                font-size: 0.82em !important;
            }
            #${ROOT_ID} .uh-r-body img {
                display: block !important;
                max-width: 100% !important;
                height: auto !important;
                border-radius: 10px !important;
                margin: 1.1em auto !important;
            }
            #${ROOT_ID}.uh-reader-no-images img,
            #${ROOT_ID}.uh-reader-no-images figure,
            #${ROOT_ID}.uh-reader-no-images picture {
                display: none !important;
            }

            /* Sentence-level TTS highlight & user Kindle highlights */
            #${ROOT_ID} .uh-s {
                border-radius: 4px !important;
                transition: background 160ms ease !important;
            }
            #${ROOT_ID} [data-uh-tts-active="true"] {
                background: var(--uh-r-tts-bg) !important;
                box-shadow: 0 0 0 2px var(--uh-r-tts-bg) !important;
            }
            #${ROOT_ID} mark.uh-r-hl {
                background: var(--uh-r-hl-bg) !important;
                color: inherit !important;
                border-radius: 3px !important;
                padding: 0 2px !important;
            }

            /* Compact Kindle Text Selection Action Pill */
            #${ROOT_ID} .uh-r-sel-bar {
                position: fixed !important;
                z-index: 2147483647 !important;
                display: none !important;
                align-items: center !important;
                gap: 2px !important;
                padding: 4px !important;
                border-radius: 999px !important;
                background: var(--uh-r-fg) !important;
                color: var(--uh-r-bg) !important;
                box-shadow: 0 8px 24px rgba(0,0,0,0.32) !important;
                font-family: system-ui, -apple-system, 'Roboto', sans-serif !important;
                transform: translate(-50%, -100%) !important;
            }
            #${ROOT_ID} .uh-r-sel-bar.uh-show {
                display: inline-flex !important;
            }
            #${ROOT_ID} .uh-r-sel-btn {
                appearance: none !important;
                border: none !important;
                background: transparent !important;
                color: var(--uh-r-bg) !important;
                font-size: 11.5px !important;
                font-weight: 700 !important;
                padding: 6px 10px !important;
                border-radius: 999px !important;
                cursor: pointer !important;
                white-space: nowrap !important;
            }
            #${ROOT_ID} .uh-r-sel-btn:active {
                opacity: 0.72 !important;
            }
        `;
        (document.head || document.documentElement).appendChild(style);
    }

    function updateChromeClasses() {
        const root = document.getElementById(ROOT_ID);
        if (!root) return;
        root.classList.toggle("uh-r-chrome-hidden", !chromeVisible);
        root.classList.toggle("uh-r-fullscreen", currentPrefs.isFullscreen === true);
        root.classList.toggle("uh-r-paged", currentPrefs.pageMode === true);
        root.classList.toggle("uh-reader-no-images", currentPrefs.showImages === false);

        const ttsBtn = root.querySelector("#uh-r-tts-btn");
        if (ttsBtn) {
            ttsBtn.style.display = currentPrefs.ttsEnabled === false ? "none" : "inline-flex";
            ttsBtn.classList.toggle("uh-active", currentTtsPlaying);
        }
        const pageBtn = root.querySelector("#uh-r-pages-btn");
        if (pageBtn) {
            pageBtn.classList.toggle("uh-active", currentPrefs.pageMode === true);
        }
        const fsBtn = root.querySelector("#uh-r-fs-btn");
        if (fsBtn) {
            fsBtn.classList.toggle("uh-active", currentPrefs.isFullscreen === true);
        }
    }

    function recalcPages(preservePage) {
        const root = document.getElementById(ROOT_ID);
        if (!root || !currentPrefs.pageMode) return;
        const track = root.querySelector("#uh-r-book-track");
        if (!track) return;
        const vw = Math.max(1, window.innerWidth);
        const scrollW = track.scrollWidth;
        totalPages = Math.max(1, Math.round(scrollW / vw));
        if (!preservePage) {
            currentPage = 0;
        } else {
            currentPage = Math.max(0, Math.min(currentPage, totalPages - 1));
        }
        goToPage(currentPage, false);
    }

    function goToPage(pageIdx, animated) {
        const root = document.getElementById(ROOT_ID);
        if (!root || !currentPrefs.pageMode) return;
        const track = root.querySelector("#uh-r-book-track");
        if (!track) return;
        currentPage = Math.max(0, Math.min(pageIdx, totalPages - 1));
        if (!animated) track.classList.add("uh-dragging");
        else track.classList.remove("uh-dragging");
        track.style.transform = `translate3d(${-currentPage * window.innerWidth}px, 0, 0)`;
        if (!animated) {
            requestAnimationFrame(() => track.classList.remove("uh-dragging"));
        }
        updateProgressLabel();
    }

    function updateProgressLabel() {
        const root = document.getElementById(ROOT_ID);
        if (!root) return;
        const progEl = root.querySelector("#uh-r-prog");
        const metaEl = root.querySelector("#uh-r-bottom-meta");
        if (currentPrefs.pageMode) {
            const pct = totalPages <= 1 ? 100 : ((currentPage + 1) / totalPages) * 100;
            if (progEl) progEl.style.width = pct.toFixed(1) + "%";
            if (metaEl) metaEl.textContent = `Page ${currentPage + 1} of ${totalPages}`;
        } else {
            const vp = root.querySelector("#uh-r-viewport");
            const maxScroll = vp ? Math.max(1, vp.scrollHeight - vp.clientHeight) : 1;
            const pct = vp ? Math.min(100, Math.max(0, (vp.scrollTop / maxScroll) * 100)) : 0;
            if (progEl) progEl.style.width = pct.toFixed(1) + "%";
            if (metaEl) metaEl.textContent = `${Math.round(pct)}%`;
        }
    }

    function applyReaderStyle(prefs) {
        const prevPageMode = currentPrefs.pageMode;
        currentPrefs = Object.assign({}, currentPrefs, prefs || {});
        const root = document.getElementById(ROOT_ID);
        if (!root) return;
        const t = themeTokens(currentPrefs.theme);
        root.style.setProperty("--uh-r-bg", t.bg);
        root.style.setProperty("--uh-r-fg", t.fg);
        root.style.setProperty("--uh-r-muted", t.muted);
        root.style.setProperty("--uh-r-accent", t.accent);
        root.style.setProperty("--uh-r-surface", t.surface);
        root.style.setProperty("--uh-r-bar-bg", t.barBg);
        root.style.setProperty("--uh-r-border", t.border);
        root.style.setProperty("--uh-r-tts-bg", t.ttsBg);
        root.style.setProperty("--uh-r-hl-bg", t.hlBg);
        root.style.setProperty("--uh-r-font", fontStack(currentPrefs.fontFamily));
        root.style.setProperty("--uh-r-size", (Number(currentPrefs.fontSizePx) || 19) + "px");
        root.style.setProperty("--uh-r-lh", String(Number(currentPrefs.lineHeight) || 1.78));
        root.style.setProperty("--uh-r-pad", (Number(currentPrefs.marginPx) || 24) + "px");
        updateChromeClasses();

        if (currentPrefs.pageMode) {
            requestAnimationFrame(() => recalcPages(prevPageMode === true));
        } else {
            const track = root.querySelector("#uh-r-book-track");
            if (track) track.style.transform = "none";
            updateProgressLabel();
        }
    }

    function hideSelectionBar() {
        const bar = document.getElementById("uh-r-sel-bar");
        if (bar) bar.classList.remove("uh-show");
    }

    function updateSelectionBar() {
        const root = document.getElementById(ROOT_ID);
        const bar = document.getElementById("uh-r-sel-bar");
        if (!root || !bar) return;
        const sel = window.getSelection();
        if (!sel || sel.isCollapsed || !sel.toString().trim()) {
            bar.classList.remove("uh-show");
            return;
        }
        const anchorNode = sel.anchorNode;
        if (!anchorNode || !root.contains(anchorNode)) {
            bar.classList.remove("uh-show");
            return;
        }
        try {
            const rect = sel.getRangeAt(0).getBoundingClientRect();
            if (rect.width === 0 && rect.height === 0) {
                bar.classList.remove("uh-show");
                return;
            }
            const cx = Math.max(96, Math.min(window.innerWidth - 96, rect.left + rect.width / 2));
            const topY = Math.max(60, rect.top - 10);
            bar.style.left = cx + "px";
            bar.style.top = topY + "px";
            bar.classList.add("uh-show");
        } catch (_e) {
            bar.classList.remove("uh-show");
        }
    }

    function bindInteractions(root) {
        const vp = root.querySelector("#uh-r-viewport");
        const track = root.querySelector("#uh-r-book-track");

        // Scroll progress in Scroll mode
        if (vp) {
            vp.addEventListener("scroll", () => {
                if (!currentPrefs.pageMode) updateProgressLabel();
                hideSelectionBar();
            }, { passive: true });
        }

        // Integrated Top Bar buttons
        root.querySelector("#uh-r-exit-btn")?.addEventListener("click", (e) => {
            e.stopPropagation();
            exitReader();
        });
        root.querySelector("#uh-r-translate-btn")?.addEventListener("click", (e) => {
            e.stopPropagation();
            sendNative({ type: "READER_OPEN_TRANSLATE" });
        });
        root.querySelector("#uh-r-fs-btn")?.addEventListener("click", (e) => {
            e.stopPropagation();
            sendNative({ type: "READER_TOGGLE_FULLSCREEN" });
        });
        root.querySelector("#uh-r-exit-fs-btn")?.addEventListener("click", (e) => {
            e.stopPropagation();
            sendNative({ type: "READER_TOGGLE_FULLSCREEN" });
        });

        // Integrated Bottom Bar buttons
        root.querySelector("#uh-r-tts-btn")?.addEventListener("click", (e) => {
            e.stopPropagation();
            sendNative({ type: "READER_TTS_TOGGLE" });
        });
        root.querySelector("#uh-r-pages-btn")?.addEventListener("click", (e) => {
            e.stopPropagation();
            sendNative({ type: "READER_TOGGLE_PAGE_MODE" });
        });
        root.querySelector("#uh-r-controls-btn")?.addEventListener("click", (e) => {
            e.stopPropagation();
            sendNative({ type: "READER_OPEN_CONTROLS" });
        });

        // Selection Action Pill handlers
        root.querySelector("#uh-r-sel-hl")?.addEventListener("click", (e) => {
            e.stopPropagation();
            try {
                const sel = window.getSelection();
                if (sel && !sel.isCollapsed && sel.rangeCount > 0) {
                    const range = sel.getRangeAt(0);
                    const mark = document.createElement("mark");
                    mark.className = "uh-r-hl";
                    try {
                        range.surroundContents(mark);
                    } catch (_e2) {
                        const frag = range.extractContents();
                        mark.appendChild(frag);
                        range.insertNode(mark);
                    }
                    sel.removeAllRanges();
                }
            } catch (_e) {}
            hideSelectionBar();
        });

        root.querySelector("#uh-r-sel-tts")?.addEventListener("click", (e) => {
            e.stopPropagation();
            try {
                const sel = window.getSelection();
                let node = sel && sel.anchorNode ? sel.anchorNode : null;
                if (node && node.nodeType === 3) node = node.parentElement;
                const span = node && node.closest ? node.closest("[data-uh-s]") : null;
                const idx = span ? parseInt(span.getAttribute("data-uh-s") || "0", 10) : 0;
                if (sel) sel.removeAllRanges();
                hideSelectionBar();
                sendNative({ type: "READER_TTS_FROM_INDEX", index: isNaN(idx) ? 0 : idx });
            } catch (_e) {
                hideSelectionBar();
            }
        });

        root.querySelector("#uh-r-sel-copy")?.addEventListener("click", (e) => {
            e.stopPropagation();
            try {
                const txt = window.getSelection()?.toString() || "";
                if (txt) {
                    if (navigator.clipboard && navigator.clipboard.writeText) {
                        navigator.clipboard.writeText(txt).catch(() => {});
                    } else {
                        document.execCommand("copy");
                    }
                }
                window.getSelection()?.removeAllRanges();
            } catch (_e) {}
            hideSelectionBar();
        });

        // Kindle Touch Swipe (Book Pages) + Center Tap to toggle Chrome + Edge Tap to turn pages
        let startX = 0;
        let startY = 0;
        let startTime = 0;
        let swipingHorizontal = false;
        let dragDx = 0;

        if (vp) {
            vp.addEventListener("touchstart", (ev) => {
                if (ev.touches.length !== 1) return;
                startX = ev.touches[0].clientX;
                startY = ev.touches[0].clientY;
                startTime = Date.now();
                swipingHorizontal = false;
                dragDx = 0;
            }, { passive: true });

            vp.addEventListener("touchmove", (ev) => {
                if (!currentPrefs.pageMode || ev.touches.length !== 1 || !track) return;
                const dx = ev.touches[0].clientX - startX;
                const dy = ev.touches[0].clientY - startY;
                if (!swipingHorizontal && Math.abs(dx) > 12 && Math.abs(dx) > Math.abs(dy) * 1.2) {
                    swipingHorizontal = true;
                    track.classList.add("uh-dragging");
                    hideSelectionBar();
                }
                if (swipingHorizontal) {
                    dragDx = dx;
                    const base = -currentPage * window.innerWidth;
                    const resist = ((currentPage === 0 && dx > 0) || (currentPage === totalPages - 1 && dx < 0)) ? 0.25 : 1;
                    track.style.transform = `translate3d(${base + dx * resist}px, 0, 0)`;
                }
            }, { passive: true });

            vp.addEventListener("touchend", (ev) => {
                const elapsed = Date.now() - startTime;
                if (currentPrefs.pageMode && swipingHorizontal && track) {
                    swipingHorizontal = false;
                    track.classList.remove("uh-dragging");
                    const threshold = Math.min(72, window.innerWidth * 0.16);
                    if (dragDx < -threshold && currentPage < totalPages - 1) {
                        goToPage(currentPage + 1, true);
                        chromeVisible = false;
                        updateChromeClasses();
                    } else if (dragDx > threshold && currentPage > 0) {
                        goToPage(currentPage - 1, true);
                        chromeVisible = false;
                        updateChromeClasses();
                    } else {
                        goToPage(currentPage, true);
                    }
                    dragDx = 0;
                    return;
                }

                // Quick clean tap (not dragging, not selecting text, not tapping a link)
                const selTxt = (window.getSelection()?.toString() || "").trim();
                if (selTxt) {
                    setTimeout(updateSelectionBar, 30);
                    return;
                }
                const targetEl = ev.target;
                if (targetEl && targetEl.closest && targetEl.closest("a, button, .uh-r-bar, .uh-r-sel-bar, .uh-r-exit-fs")) {
                    return;
                }
                const endX = ev.changedTouches && ev.changedTouches[0] ? ev.changedTouches[0].clientX : startX;
                const endY = ev.changedTouches && ev.changedTouches[0] ? ev.changedTouches[0].clientY : startY;
                const moved = Math.hypot(endX - startX, endY - startY);
                if (elapsed < 340 && moved < 14) {
                    const vw = window.innerWidth || 360;
                    if (currentPrefs.pageMode) {
                        if (endX < vw * 0.22) {
                            if (currentPage > 0) goToPage(currentPage - 1, true);
                            return;
                        }
                        if (endX > vw * 0.78) {
                            if (currentPage < totalPages - 1) goToPage(currentPage + 1, true);
                            return;
                        }
                    }
                    chromeVisible = !chromeVisible;
                    updateChromeClasses();
                }
            }, { passive: true });
        }

        document.addEventListener("selectionchange", () => {
            if (!readerActive) return;
            if (selectionTimer) clearTimeout(selectionTimer);
            selectionTimer = setTimeout(updateSelectionBar, 120);
        });
    }

    function renderArticleIntoDom(root, article) {
        const rtl = isRtlLang(article.lang);
        root.setAttribute("dir", rtl ? "rtl" : "ltr");
        root.setAttribute("lang", article.lang || "en");

        const titleBar = root.querySelector("#uh-r-bar-title");
        if (titleBar) titleBar.textContent = article.title;

        const metaBits = [];
        metaBits.push(`<span>${escapeHtml(article.readMins)} min read</span>`);
        if (article.host) metaBits.push(`<span>· ${escapeHtml(article.host)}</span>`);
        if (article.lang && article.lang !== "en") {
            metaBits.push(`<span>· ${escapeHtml(article.lang.toUpperCase())}</span>`);
        }

        const headerEl = root.querySelector("#uh-r-header");
        if (headerEl) {
            headerEl.innerHTML = `
                <div class="uh-r-meta">${metaBits.join("")}</div>
                <h1 class="uh-r-title">${escapeHtml(article.title)}</h1>
                ${article.byline ? `<div class="uh-r-byline">By ${escapeHtml(article.byline)}</div>` : ""}
                <div class="uh-r-ornament">✦</div>
            `;
        }
        const bodyEl = root.querySelector("#uh-r-body");
        if (bodyEl) {
            bodyEl.innerHTML = article.bodyHtml;
        }
        currentSentences = article.sentences;
        currentDetectedLang = article.lang || "en";
    }

    function enterReader(prefs, isRefresh) {
        if (readerActive && !isRefresh) {
            applyReaderStyle(prefs);
            return;
        }
        ensureStyleElement();
        const article = sanitizeAndBuildArticle();

        if (isRefresh && readerActive) {
            const existingRoot = document.getElementById(ROOT_ID);
            if (existingRoot) {
                renderArticleIntoDom(existingRoot, article);
                if (currentPrefs.pageMode) {
                    requestAnimationFrame(() => recalcPages(true));
                } else {
                    updateProgressLabel();
                }
                let host = "";
                try { host = new URL(location.href).hostname.toLowerCase(); } catch (_e) {}
                sendNative({
                    type: "READER_CONTENT_REFRESHED",
                    url: location.href,
                    host: host,
                    title: article.title,
                    readMins: article.readMins,
                    wordCount: article.wordCount,
                    lang: article.lang,
                    sentences: article.sentences.slice(0, 1200)
                });
                return;
            }
        }

        currentTtsIndex = -1;
        currentTtsPlaying = false;
        chromeVisible = true;
        currentPage = 0;

        savedHtmlOverflow = document.documentElement.style.overflow;
        savedBodyOverflow = document.body ? document.body.style.overflow : "";
        document.documentElement.classList.add(HTML_ACTIVE_CLASS);
        document.documentElement.style.overflow = "hidden";
        if (document.body) document.body.style.overflow = "hidden";

        const old = document.getElementById(ROOT_ID);
        if (old) old.remove();

        const root = document.createElement("div");
        root.id = ROOT_ID;
        root.innerHTML = `
            <div class="uh-r-progress-track"><div class="uh-r-progress-fill" id="uh-r-prog"></div></div>
            <div class="uh-r-bar uh-r-topbar" id="uh-r-topbar">
                <button type="button" class="uh-r-btn" id="uh-r-exit-btn" aria-label="Exit Reader">
                    <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6"/></svg>
                </button>
                <div class="uh-r-bar-title" id="uh-r-bar-title"></div>
                <button type="button" class="uh-r-btn" id="uh-r-translate-btn" aria-label="Translate">
                    <svg viewBox="0 0 24 24"><path d="M5 8l6 6"/><path d="M4 14l6-6 2-3"/><path d="M2 5h12"/><path d="M7 2h1"/><path d="M22 22l-5-10-5 10"/><path d="M14 18h6"/></svg>
                </button>
                <button type="button" class="uh-r-btn" id="uh-r-fs-btn" aria-label="Fullscreen">
                    <svg viewBox="0 0 24 24"><path d="M8 3H5a2 2 0 0 0-2 2v3"/><path d="M21 8V5a2 2 0 0 0-2-2h-3"/><path d="M3 16v3a2 2 0 0 0 2 2h3"/><path d="M16 21h3a2 2 0 0 0 2-2v-3"/></svg>
                </button>
            </div>
            <button type="button" class="uh-r-exit-fs" id="uh-r-exit-fs-btn">Exit Fullscreen</button>
            <div class="uh-r-viewport" id="uh-r-viewport">
                <div class="uh-r-container">
                    <div class="uh-r-book-track" id="uh-r-book-track">
                        <div class="uh-r-header" id="uh-r-header"></div>
                        <div class="uh-r-body" id="uh-r-body"></div>
                    </div>
                </div>
            </div>
            <div class="uh-r-bar uh-r-bottombar" id="uh-r-bottombar">
                <button type="button" class="uh-r-btn" id="uh-r-tts-btn" aria-label="Read Aloud">
                    <svg viewBox="0 0 24 24"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><path d="M15.54 8.46a5 5 0 0 1 0 7.07"/><path d="M19.07 4.93a10 10 0 0 1 0 14.14"/></svg>
                </button>
                <div class="uh-r-bar-meta" id="uh-r-bottom-meta">0%</div>
                <div style="display:flex;align-items:center;gap:4px;">
                    <button type="button" class="uh-r-btn" id="uh-r-pages-btn" aria-label="Book Pages">
                        <svg viewBox="0 0 24 24"><path d="M2 4h7a4 4 0 0 1 4 4v12a3 3 0 0 0-3-3H2z"/><path d="M22 4h-7a4 4 0 0 0-4 4v12a3 3 0 0 1 3-3h8z"/></svg>
                    </button>
                    <button type="button" class="uh-r-btn" id="uh-r-controls-btn" aria-label="Reader Settings">Aa</button>
                </div>
            </div>
            <div class="uh-r-sel-bar" id="uh-r-sel-bar">
                <button type="button" class="uh-r-sel-btn" id="uh-r-sel-hl">Highlight</button>
                <button type="button" class="uh-r-sel-btn" id="uh-r-sel-tts">Read here</button>
                <button type="button" class="uh-r-sel-btn" id="uh-r-sel-copy">Copy</button>
            </div>
        `;

        document.documentElement.appendChild(root);
        renderArticleIntoDom(root, article);
        bindInteractions(root);
        applyReaderStyle(prefs);

        if (resizeHandler) window.removeEventListener("resize", resizeHandler);
        resizeHandler = () => {
            if (readerActive && currentPrefs.pageMode) {
                recalcPages(true);
            }
        };
        window.addEventListener("resize", resizeHandler);

        readerActive = true;
        let host = "";
        try { host = new URL(location.href).hostname.toLowerCase(); } catch (_e) {}
        sendNative({
            type: "READER_ENTERED",
            url: location.href,
            host: host,
            title: article.title,
            readMins: article.readMins,
            wordCount: article.wordCount,
            lang: article.lang,
            sentences: article.sentences.slice(0, 1200)
        });
    }

    function exitReader() {
        const root = document.getElementById(ROOT_ID);
        if (root) root.remove();
        if (resizeHandler) {
            window.removeEventListener("resize", resizeHandler);
            resizeHandler = null;
        }
        document.documentElement.classList.remove(HTML_ACTIVE_CLASS);
        document.documentElement.style.overflow = savedHtmlOverflow || "";
        if (document.body) document.body.style.overflow = savedBodyOverflow || "";
        readerActive = false;
        currentTtsIndex = -1;
        currentTtsPlaying = false;
        sendNative({
            type: "READER_EXITED",
            url: location.href
        });
    }

    function highlightTtsSentence(index, playing) {
        const root = document.getElementById(ROOT_ID);
        if (!root) return;
        currentTtsPlaying = playing === true;
        updateChromeClasses();
        root.querySelectorAll("[data-uh-tts-active='true']").forEach((el) => {
            el.removeAttribute("data-uh-tts-active");
        });
        currentTtsIndex = index;
        if (index >= 0) {
            const target = root.querySelector(`[data-uh-s="${index}"]`);
            if (target) {
                target.setAttribute("data-uh-tts-active", "true");
                if (currentPrefs.pageMode) {
                    // Auto-flip book page to the exact page containing the spoken sentence
                    try {
                        const rect = target.getBoundingClientRect();
                        const vw = Math.max(1, window.innerWidth);
                        const pageDelta = Math.floor(rect.left / vw);
                        if (pageDelta !== 0) {
                            goToPage(currentPage + pageDelta, true);
                        }
                    } catch (_e) {}
                } else {
                    try {
                        target.scrollIntoView({ behavior: "smooth", block: "center" });
                    } catch (_e) {}
                }
            }
        }
    }

    api.runtime.onMessage.addListener((msg) => {
        if (!msg || !msg.action) return undefined;
        if (msg.action === "READER_TOGGLE") {
            if (readerActive) exitReader();
            else enterReader(msg.prefs || currentPrefs, false);
            return Promise.resolve({ active: readerActive });
        }
        if (msg.action === "READER_ENTER") {
            enterReader(msg.prefs || currentPrefs, false);
            return Promise.resolve({ active: true });
        }
        if (msg.action === "READER_EXIT") {
            if (readerActive) exitReader();
            return Promise.resolve({ active: false });
        }
        if (msg.action === "READER_REFRESH_CONTENT") {
            if (readerActive) enterReader(currentPrefs, true);
            return Promise.resolve(true);
        }
        if (msg.action === "READER_UPDATE_STYLE") {
            applyReaderStyle(msg.prefs || {});
            return Promise.resolve(true);
        }
        if (msg.action === "READER_TTS_HIGHLIGHT") {
            highlightTtsSentence(
                typeof msg.index === "number" ? msg.index : -1,
                msg.playing === true
            );
            return Promise.resolve(true);
        }
        if (msg.action === "READER_CHECK") {
            notifyEligibility(true);
            return Promise.resolve(true);
        }
        return undefined;
    });

    document.addEventListener("DOMContentLoaded", () => {
        setTimeout(() => notifyEligibility(false), 180);
    }, { once: true });

    window.addEventListener("load", () => {
        setTimeout(() => notifyEligibility(false), 350);
    }, { once: true });

    setTimeout(() => notifyEligibility(false), 1200);

    let prevHref = location.href;
    setInterval(() => {
        if (location.href !== prevHref) {
            prevHref = location.href;
            if (readerActive) exitReader();
            setTimeout(() => notifyEligibility(true), 550);
        }
    }, 1000);
})();
