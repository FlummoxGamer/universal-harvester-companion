// -----------------------------------------------------------------------------
// UH Bridge Content Script: Enterprise In-Page Reader Mode + Read Aloud Sync
// -----------------------------------------------------------------------------
(function () {
    if (window.top !== window) return;
    if (window.__uhReaderInstalled) return;
    window.__uhReaderInstalled = true;

    const api = (typeof browser !== "undefined" && browser.runtime) ? browser : chrome;
    const ROOT_ID = "uh-reader-overlay-root";
    const STYLE_ID = "uh-reader-overlay-style";

    let readerActive = false;
    let lastCheckedUrl = "";
    let lastEligible = false;
    let savedHtmlOverflow = "";
    let savedBodyOverflow = "";
    let currentParagraphs = [];
    let currentTtsIndex = -1;
    let currentPrefs = {
        theme: "dark",
        fontFamily: "sans",
        fontSizePx: 18,
        lineHeight: 1.72,
        marginPx: 22,
        showImages: true
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
            // Pure search result pages (Google/Bing/DDG search SERPs)
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
        if (textLen < 180) return -1;

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
            if (len >= 35) {
                goodParas++;
                paraChars += Math.min(len, 600);
            }
        }
        score += goodParas * 8 + Math.min(paraChars / 45, 90);

        // Penalize link-heavy nav directories
        const links = el.querySelectorAll("a");
        let linkChars = 0;
        for (let i = 0; i < links.length; i++) linkChars += getTextLen(links[i]);
        const linkDensity = textLen > 0 ? (linkChars / textLen) : 1;
        if (linkDensity > 0.52) score -= 45;

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
            if (el.id === ROOT_ID) continue;
            const sc = scoreCandidate(el);
            if (sc > bestScore) {
                bestScore = sc;
                best = el;
            }
        }

        if (best && bestScore >= 25) return best;
        if (direct && getTextLen(direct) >= 200) return direct;
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
            if (len >= 30) {
                totalChars += len;
                blocks++;
            }
            if (totalChars >= 280 && blocks >= 2) return true;
        }
        // Fallback for prose pages using divs/spans without <p> tags (forums, novels, docs)
        return getTextLen(best) >= 420;
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

    function sanitizeAndBuildArticle() {
        const root = findBestRoot();
        const clone = root ? root.cloneNode(true) : document.body.cloneNode(true);

        // Strip junk & interactive clutter
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

        // Clean attributes & resolve relative URLs cleanly
        clone.querySelectorAll("*").forEach((el) => {
            const tag = el.tagName;
            if (tag === "IMG") {
                const src = el.getAttribute("src") || el.getAttribute("data-src") || el.getAttribute("data-original") || "";
                if (!src || src.startsWith("data:image/gif")) {
                    el.remove();
                    return;
                }
                try {
                    const abs = new URL(src, location.href).href;
                    el.setAttribute("src", abs);
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

        // Extract title
        const ogTitle = document.querySelector("meta[property='og:title']")?.getAttribute("content") || "";
        const h1Text = document.querySelector("h1")?.textContent?.trim() || "";
        const title = (h1Text.length >= 8 && h1Text.length <= 220)
            ? h1Text
            : (ogTitle.trim() || document.title || location.hostname);

        // Remove duplicate first H1 inside cloned body
        const cloneH1 = clone.querySelector("h1");
        if (cloneH1 && cloneH1.textContent.trim().toLowerCase() === title.trim().toLowerCase()) {
            cloneH1.remove();
        }

        // Extract byline & domain
        const authorMeta = document.querySelector("meta[name='author'], meta[property='article:author']")?.getAttribute("content") || "";
        const bylineEl = document.querySelector("[rel='author'], .byline, .author, [itemprop='author']");
        const byline = (authorMeta || (bylineEl ? bylineEl.textContent : "") || "").replace(/\s+/g, " ").trim().slice(0, 90);
        const host = location.hostname.replace(/^www\./i, "");

        // Tag readable paragraphs for Read Aloud (TTS) highlighting & collect text
        const ttsBlocks = [];
        const readableNodes = clone.querySelectorAll("p, h2, h3, h4, blockquote, li, pre");
        readableNodes.forEach((node) => {
            if (node.closest("pre") && node.tagName !== "PRE") return;
            const txt = (node.textContent || "").replace(/\s+/g, " ").trim();
            if (txt.length >= 18) {
                const idx = ttsBlocks.length;
                node.setAttribute("data-uh-tts-idx", String(idx));
                ttsBlocks.push(txt);
            }
        });

        // If page didn't use <p> tags, wrap body text cleanly
        let bodyHtml = clone.innerHTML.trim();
        if (ttsBlocks.length === 0) {
            const rawText = (clone.textContent || "").replace(/\n{3,}/g, "\n\n").trim();
            const chunks = rawText.split(/\n+/).map((s) => s.trim()).filter((s) => s.length >= 15);
            bodyHtml = chunks.map((c, idx) => {
                ttsBlocks.push(c);
                return `<p data-uh-tts-idx="${idx}">${escapeHtml(c)}</p>`;
            }).join("");
        }

        const totalWords = ttsBlocks.join(" ").split(/\s+/).filter(Boolean).length;
        const readMins = Math.max(1, Math.ceil(totalWords / 220));

        return {
            title,
            byline,
            host,
            bodyHtml,
            paragraphs: ttsBlocks,
            wordCount: totalWords,
            readMins
        };
    }

    function themeTokens(theme) {
        switch (theme) {
            case "light":
                return { bg: "#FFFFFF", fg: "#1A1C1E", muted: "#5F6670", accent: "#2563EB", surface: "#F2F4F8", border: "rgba(0,0,0,0.09)", ttsBg: "rgba(37,99,235,0.14)" };
            case "sepia":
                return { bg: "#F6EFE2", fg: "#3B2E1E", muted: "#7A6852", accent: "#A8551A", surface: "#ECE2CF", border: "rgba(59,46,30,0.12)", ttsBg: "rgba(168,85,26,0.15)" };
            case "oled":
                return { bg: "#000000", fg: "#E8EAED", muted: "#8E95A1", accent: "#8AB4F8", surface: "#111317", border: "rgba(255,255,255,0.12)", ttsBg: "rgba(138,180,248,0.18)" };
            case "dark":
            default:
                return { bg: "#181A1F", fg: "#E6E8EC", muted: "#9AA2B1", accent: "#7DD3FC", surface: "#22252C", border: "rgba(255,255,255,0.10)", ttsBg: "rgba(125,211,252,0.16)" };
        }
    }

    function fontStack(family) {
        switch (family) {
            case "serif": return "Georgia, 'Noto Serif', 'Times New Roman', serif";
            case "mono": return "'JetBrains Mono', 'Roboto Mono', ui-monospace, monospace";
            case "sans":
            default: return "system-ui, -apple-system, 'Roboto', 'Segoe UI', sans-serif";
        }
    }

    function applyReaderStyle(prefs) {
        currentPrefs = Object.assign({}, currentPrefs, prefs || {});
        const root = document.getElementById(ROOT_ID);
        if (!root) return;
        const t = themeTokens(currentPrefs.theme);
        root.style.setProperty("--uh-r-bg", t.bg);
        root.style.setProperty("--uh-r-fg", t.fg);
        root.style.setProperty("--uh-r-muted", t.muted);
        root.style.setProperty("--uh-r-accent", t.accent);
        root.style.setProperty("--uh-r-surface", t.surface);
        root.style.setProperty("--uh-r-border", t.border);
        root.style.setProperty("--uh-r-tts-bg", t.ttsBg);
        root.style.setProperty("--uh-r-font", fontStack(currentPrefs.fontFamily));
        root.style.setProperty("--uh-r-size", (Number(currentPrefs.fontSizePx) || 18) + "px");
        root.style.setProperty("--uh-r-lh", String(Number(currentPrefs.lineHeight) || 1.72));
        root.style.setProperty("--uh-r-pad", (Number(currentPrefs.marginPx) || 22) + "px");
        root.classList.toggle("uh-reader-no-images", currentPrefs.showImages === false);
    }

    function ensureStyleElement() {
        if (document.getElementById(STYLE_ID)) return;
        const style = document.createElement("style");
        style.id = STYLE_ID;
        style.textContent = `
            #${ROOT_ID} {
                position: fixed !important;
                inset: 0 !important;
                z-index: 2147483646 !important;
                background: var(--uh-r-bg, #181A1F) !important;
                color: var(--uh-r-fg, #E6E8EC) !important;
                font-family: var(--uh-r-font, system-ui, sans-serif) !important;
                overflow-y: auto !important;
                overflow-x: hidden !important;
                -webkit-overflow-scrolling: touch !important;
                margin: 0 !important;
                padding: 0 !important;
                box-sizing: border-box !important;
            }
            #${ROOT_ID} * { box-sizing: border-box !important; max-width: 100% !important; }
            #${ROOT_ID} .uh-r-progress-track {
                position: fixed !important;
                top: 0 !important;
                left: 0 !important;
                right: 0 !important;
                height: 3px !important;
                background: transparent !important;
                z-index: 2147483647 !important;
                pointer-events: none !important;
            }
            #${ROOT_ID} .uh-r-progress-fill {
                height: 100% !important;
                width: 0%;
                background: var(--uh-r-accent, #7DD3FC) !important;
                transition: width 80ms linear !important;
            }
            #${ROOT_ID} .uh-r-container {
                max-width: 720px !important;
                margin: 0 auto !important;
                padding: 28px var(--uh-r-pad, 22px) 120px var(--uh-r-pad, 22px) !important;
            }
            #${ROOT_ID} .uh-r-meta {
                display: flex !important;
                flex-wrap: wrap !important;
                gap: 8px !important;
                align-items: center !important;
                font-size: 13px !important;
                color: var(--uh-r-muted, #9AA2B1) !important;
                margin-bottom: 12px !important;
                font-weight: 500 !important;
            }
            #${ROOT_ID} .uh-r-chip {
                background: var(--uh-r-surface, #22252C) !important;
                border: 1px solid var(--uh-r-border, rgba(255,255,255,0.1)) !important;
                padding: 3px 10px !important;
                border-radius: 999px !important;
                font-size: 12px !important;
            }
            #${ROOT_ID} .uh-r-title {
                font-size: calc(var(--uh-r-size, 18px) * 1.55) !important;
                line-height: 1.24 !important;
                font-weight: 800 !important;
                letter-spacing: -0.02em !important;
                margin: 0 0 14px 0 !important;
                color: var(--uh-r-fg, #E6E8EC) !important;
            }
            #${ROOT_ID} .uh-r-divider {
                height: 1px !important;
                background: var(--uh-r-border, rgba(255,255,255,0.1)) !important;
                margin: 16px 0 24px 0 !important;
            }
            #${ROOT_ID} .uh-r-body {
                font-size: var(--uh-r-size, 18px) !important;
                line-height: var(--uh-r-lh, 1.72) !important;
                word-wrap: break-word !important;
            }
            #${ROOT_ID} .uh-r-body p {
                margin: 0 0 1.15em 0 !important;
            }
            #${ROOT_ID} .uh-r-body h2, #${ROOT_ID} .uh-r-body h3, #${ROOT_ID} .uh-r-body h4 {
                margin: 1.5em 0 0.6em 0 !important;
                line-height: 1.3 !important;
                font-weight: 700 !important;
            }
            #${ROOT_ID} .uh-r-body a {
                color: var(--uh-r-accent, #7DD3FC) !important;
                text-decoration: underline !important;
                text-underline-offset: 3px !important;
            }
            #${ROOT_ID} .uh-r-body blockquote {
                margin: 1.2em 0 !important;
                padding: 10px 16px !important;
                border-left: 3px solid var(--uh-r-accent, #7DD3FC) !important;
                background: var(--uh-r-surface, #22252C) !important;
                border-radius: 0 12px 12px 0 !important;
            }
            #${ROOT_ID} .uh-r-body pre, #${ROOT_ID} .uh-r-body code {
                font-family: 'Roboto Mono', ui-monospace, monospace !important;
                background: var(--uh-r-surface, #22252C) !important;
                border-radius: 8px !important;
            }
            #${ROOT_ID} .uh-r-body pre {
                padding: 14px !important;
                overflow-x: auto !important;
                font-size: 0.85em !important;
            }
            #${ROOT_ID} .uh-r-body img {
                display: block !important;
                width: 100% !important;
                height: auto !important;
                border-radius: 14px !important;
                margin: 1.2em auto !important;
            }
            #${ROOT_ID}.uh-reader-no-images img,
            #${ROOT_ID}.uh-reader-no-images figure,
            #${ROOT_ID}.uh-reader-no-images picture {
                display: none !important;
            }
            #${ROOT_ID} [data-uh-tts-active="true"] {
                background: var(--uh-r-tts-bg, rgba(125,211,252,0.16)) !important;
                border-radius: 8px !important;
                padding: 4px 6px !important;
                margin-left: -6px !important;
                margin-right: -6px !important;
                transition: background 180ms ease !important;
            }
            #${ROOT_ID} .uh-r-fab {
                position: fixed !important;
                right: 18px !important;
                bottom: 24px !important;
                z-index: 2147483647 !important;
                display: flex !important;
                align-items: center !important;
                gap: 6px !important;
                background: var(--uh-r-surface, #22252C) !important;
                color: var(--uh-r-fg, #E6E8EC) !important;
                border: 1px solid var(--uh-r-border, rgba(255,255,255,0.14)) !important;
                border-radius: 999px !important;
                padding: 10px 16px !important;
                font-size: 14px !important;
                font-weight: 700 !important;
                box-shadow: 0 8px 24px rgba(0,0,0,0.32) !important;
                cursor: pointer !important;
                user-select: none !important;
            }
        `;
        (document.head || document.documentElement).appendChild(style);
    }

    function enterReader(prefs) {
        if (readerActive) {
            applyReaderStyle(prefs);
            return;
        }
        ensureStyleElement();
        const article = sanitizeAndBuildArticle();
        currentParagraphs = article.paragraphs;
        currentTtsIndex = -1;

        savedHtmlOverflow = document.documentElement.style.overflow;
        savedBodyOverflow = document.body ? document.body.style.overflow : "";
        document.documentElement.style.overflow = "hidden";
        if (document.body) document.body.style.overflow = "hidden";

        const old = document.getElementById(ROOT_ID);
        if (old) old.remove();

        const root = document.createElement("div");
        root.id = ROOT_ID;

        const metaBits = [];
        metaBits.push(`<span class="uh-r-chip">${escapeHtml(article.readMins)} min read</span>`);
        if (article.host) metaBits.push(`<span>${escapeHtml(article.host)}</span>`);
        if (article.byline) metaBits.push(`<span>• ${escapeHtml(article.byline)}</span>`);

        root.innerHTML = `
            <div class="uh-r-progress-track"><div class="uh-r-progress-fill" id="uh-r-prog"></div></div>
            <div class="uh-r-container">
                <div class="uh-r-meta">${metaBits.join("")}</div>
                <h1 class="uh-r-title">${escapeHtml(article.title)}</h1>
                <div class="uh-r-divider"></div>
                <div class="uh-r-body">${article.bodyHtml}</div>
            </div>
            <button type="button" class="uh-r-fab" id="uh-r-controls-btn">Aa</button>
        `;

        document.documentElement.appendChild(root);
        applyReaderStyle(prefs);

        const progEl = root.querySelector("#uh-r-prog");
        root.addEventListener("scroll", () => {
            const maxScroll = Math.max(1, root.scrollHeight - root.clientHeight);
            const pct = Math.min(100, Math.max(0, (root.scrollTop / maxScroll) * 100));
            if (progEl) progEl.style.width = pct.toFixed(1) + "%";
        }, { passive: true });

        const controlsBtn = root.querySelector("#uh-r-controls-btn");
        if (controlsBtn) {
            controlsBtn.addEventListener("click", (ev) => {
                ev.stopPropagation();
                sendNative({ type: "READER_OPEN_CONTROLS" });
            });
        }

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
            paragraphs: article.paragraphs.slice(0, 400)
        });
    }

    function exitReader() {
        const root = document.getElementById(ROOT_ID);
        if (root) root.remove();
        document.documentElement.style.overflow = savedHtmlOverflow || "";
        if (document.body) document.body.style.overflow = savedBodyOverflow || "";
        readerActive = false;
        currentTtsIndex = -1;
        sendNative({
            type: "READER_EXITED",
            url: location.href
        });
    }

    function highlightTtsParagraph(index) {
        const root = document.getElementById(ROOT_ID);
        if (!root) return;
        root.querySelectorAll("[data-uh-tts-active='true']").forEach((el) => {
            el.removeAttribute("data-uh-tts-active");
        });
        currentTtsIndex = index;
        if (index >= 0) {
            const target = root.querySelector(`[data-uh-tts-idx="${index}"]`);
            if (target) {
                target.setAttribute("data-uh-tts-active", "true");
                try {
                    target.scrollIntoView({ behavior: "smooth", block: "center" });
                } catch (_e) {}
            }
        }
    }

    api.runtime.onMessage.addListener((msg) => {
        if (!msg || !msg.action) return undefined;
        if (msg.action === "READER_TOGGLE") {
            if (readerActive) exitReader();
            else enterReader(msg.prefs || currentPrefs);
            return Promise.resolve({ active: readerActive });
        }
        if (msg.action === "READER_ENTER") {
            enterReader(msg.prefs || currentPrefs);
            return Promise.resolve({ active: true });
        }
        if (msg.action === "READER_EXIT") {
            if (readerActive) exitReader();
            return Promise.resolve({ active: false });
        }
        if (msg.action === "READER_UPDATE_STYLE") {
            applyReaderStyle(msg.prefs || {});
            return Promise.resolve(true);
        }
        if (msg.action === "READER_TTS_HIGHLIGHT") {
            highlightTtsParagraph(typeof msg.index === "number" ? msg.index : -1);
            return Promise.resolve(true);
        }
        if (msg.action === "READER_CHECK") {
            notifyEligibility(true);
            return Promise.resolve(true);
        }
        return undefined;
    });

    // Eligibility lifecycle hooks (covers static pages + SPA navigation)
    document.addEventListener("DOMContentLoaded", () => {
        setTimeout(() => notifyEligibility(false), 180);
    }, { once: true });

    window.addEventListener("load", () => {
        setTimeout(() => notifyEligibility(false), 350);
    }, { once: true });

    setTimeout(() => notifyEligibility(false), 1200);

    // Lightweight SPA URL watcher
    let prevHref = location.href;
    setInterval(() => {
        if (location.href !== prevHref) {
            prevHref = location.href;
            if (readerActive) exitReader();
            setTimeout(() => notifyEligibility(true), 550);
        }
    }, 1000);
})();
