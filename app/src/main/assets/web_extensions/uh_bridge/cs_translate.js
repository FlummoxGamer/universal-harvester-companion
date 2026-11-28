// -----------------------------------------------------------------------------
// UH Bridge Content Module 6: Fast Online Page Translation (Viewport-First DOM Walker)
// -----------------------------------------------------------------------------
(function (C) {
    if (!C || !C.isTopFrame) return;

    const SKIP_TAGS = new Set([
        "SCRIPT", "STYLE", "NOSCRIPT", "CODE", "PRE", "TEXTAREA", "INPUT",
        "SELECT", "OPTION", "SVG", "MATH", "CANVAS", "IFRAME", "AUDIO", "VIDEO"
    ]);

    let activeSessionId = 0;
    let isPageTranslated = false;
    let activeFromLang = "auto";
    let activeToLang = "en";
    let mutationObserver = null;
    let mutationTimer = null;
    const pendingMutationNodes = new Set();

    // Map<TextNode, originalString> for instant zero-lag Restore Original
    const originalTextMap = new Map();
    let originalTitle = null;

    function isNodeTranslatable(textNode) {
        if (!textNode || textNode.nodeType !== 3) return false;
        const parent = textNode.parentElement;
        if (!parent || !parent.isConnected) return false;
        if (SKIP_TAGS.has(parent.tagName)) return false;
        if (parent.closest("[translate='no'], .notranslate, code, pre, script, style, #__uh_img_tr_layer")) {
            return false;
        }
        const val = textNode.nodeValue || "";
        const trimmed = val.trim();
        if (trimmed.length < 2) return false;
        // Skip pure numbers, symbols, timestamps, URLs
        if (!/[\p{L}]/u.test(trimmed)) return false;
        if (/^https?:\/\/\S+$/i.test(trimmed)) return false;
        return true;
    }

    function collectTextNodes(rootEl) {
        const viewportNodes = [];
        const offscreenNodes = [];
        const vh = window.innerHeight || 800;

        try {
            const walker = document.createTreeWalker(rootEl, NodeFilter.SHOW_TEXT, null);
            let node = walker.nextNode();
            while (node) {
                if (isNodeTranslatable(node)) {
                    let inViewport = false;
                    try {
                        const rect = node.parentElement.getBoundingClientRect();
                        inViewport = rect.bottom >= -120 && rect.top <= vh + 240 && rect.width > 0 && rect.height > 0;
                    } catch (_e) {}
                    if (inViewport) viewportNodes.push(node);
                    else offscreenNodes.push(node);
                }
                node = walker.nextNode();
            }
        } catch (_e) {}

        // Viewport-first order so visible text translates almost instantly!
        return viewportNodes.concat(offscreenNodes);
    }

    function preserveWhitespace(original, translated) {
        if (!translated) return original;
        const lead = (original.match(/^\s*/) || [""])[0];
        const trail = (original.match(/\s*$/) || [""])[0];
        return lead + translated.trim() + trail;
    }

    function stopMutationObserver() {
        if (mutationTimer) {
            clearTimeout(mutationTimer);
            mutationTimer = null;
        }
        pendingMutationNodes.clear();
        if (mutationObserver) {
            try { mutationObserver.disconnect(); } catch (_e) {}
            mutationObserver = null;
        }
    }

    async function translateNodeBatch(nodes, fromLang, toLang, sessionId) {
        if (!nodes.length || sessionId !== activeSessionId) {
            return { ok: true, count: 0, detectedLang: null };
        }

        const BATCH_SIZE = 26;
        let translatedCount = 0;
        let detectedLang = null;
        let anyOk = false;
        let lastErr = null;

        for (let i = 0; i < nodes.length; i += BATCH_SIZE) {
            if (sessionId !== activeSessionId) break;
            const slice = nodes.slice(i, i + BATCH_SIZE).filter((n) => n && n.isConnected);
            if (!slice.length) continue;

            const texts = slice.map((n) => {
                if (!originalTextMap.has(n)) {
                    originalTextMap.set(n, n.nodeValue || "");
                }
                return (originalTextMap.get(n) || "").trim();
            });

            const resp = await C.sendNativeCall({
                type: "ONLINE_TRANSLATE_BATCH",
                fromLang: fromLang || "auto",
                toLang: toLang || "en",
                texts: texts
            });

            if (sessionId !== activeSessionId) break;

            if (resp && resp.ok && Array.isArray(resp.translations)) {
                anyOk = true;
                if (!detectedLang && resp.detectedLang) {
                    detectedLang = resp.detectedLang;
                }
                for (let j = 0; j < slice.length && j < resp.translations.length; j++) {
                    const node = slice[j];
                    const orig = originalTextMap.get(node);
                    const tr = resp.translations[j];
                    if (node.isConnected && typeof tr === "string" && tr.trim()) {
                        node.nodeValue = preserveWhitespace(orig || "", tr);
                        translatedCount++;
                    }
                }
            } else {
                lastErr = (resp && resp.error) ? resp.error : "Translation failed";
                // Abort quickly on first network failure instead of looping all batches
                if (!anyOk) break;
            }
        }

        return { ok: anyOk || nodes.length === 0, count: translatedCount, detectedLang, error: lastErr };
    }

    function startMutationObserver(sessionId) {
        stopMutationObserver();
        if (!document.body || typeof MutationObserver === "undefined") return;

        mutationObserver = new MutationObserver((mutations) => {
            if (!isPageTranslated || sessionId !== activeSessionId) return;
            for (let i = 0; i < mutations.length; i++) {
                const m = mutations[i];
                if (m.type === "childList" && m.addedNodes) {
                    for (let j = 0; j < m.addedNodes.length; j++) {
                        const added = m.addedNodes[j];
                        if (added.nodeType === 3) {
                            if (isNodeTranslatable(added) && !originalTextMap.has(added)) {
                                pendingMutationNodes.add(added);
                            }
                        } else if (added.nodeType === 1 && !SKIP_TAGS.has(added.tagName)) {
                            const sub = collectTextNodes(added);
                            for (let k = 0; k < sub.length; k++) {
                                if (!originalTextMap.has(sub[k])) pendingMutationNodes.add(sub[k]);
                            }
                        }
                    }
                }
            }

            if (pendingMutationNodes.size > 0 && !mutationTimer) {
                mutationTimer = setTimeout(async () => {
                    mutationTimer = null;
                    if (!isPageTranslated || sessionId !== activeSessionId) {
                        pendingMutationNodes.clear();
                        return;
                    }
                    const batch = Array.from(pendingMutationNodes).slice(0, 60);
                    pendingMutationNodes.clear();
                    await translateNodeBatch(batch, activeFromLang, activeToLang, sessionId);
                }, 420);
            }
        });

        try {
            mutationObserver.observe(document.body, { childList: true, subtree: true });
        } catch (_e) {}
    }

    function restoreOnlinePageTranslation(silent) {
        activeSessionId++;
        stopMutationObserver();
        originalTextMap.forEach((origVal, node) => {
            try {
                if (node && node.isConnected) {
                    node.nodeValue = origVal;
                }
            } catch (_e) {}
        });
        originalTextMap.clear();
        if (originalTitle !== null) {
            try { document.title = originalTitle; } catch (_e) {}
            originalTitle = null;
        }
        isPageTranslated = false;
        if (!silent) {
            C.sendNativeCall({
                type: "ONLINE_TRANSLATE_DONE",
                ok: true,
                restored: true,
                translatedCount: 0,
                toLang: ""
            });
        }
    }

    async function runOnlinePageTranslation(fromLang, toLang) {
        if (isPageTranslated) {
            restoreOnlinePageTranslation(true);
        }
        const sessionId = ++activeSessionId;
        activeFromLang = fromLang || "auto";
        activeToLang = toLang || "en";

        const rootEl = document.body || document.documentElement;
        if (!rootEl) {
            C.sendNativeCall({
                type: "ONLINE_TRANSLATE_DONE",
                ok: false,
                error: "Page has no readable content"
            });
            return;
        }

        // Translate document title alongside first batch if present
        if (document.title && document.title.trim().length > 1) {
            originalTitle = document.title;
        }

        const nodes = collectTextNodes(rootEl).slice(0, 650);
        if (!nodes.length) {
            C.sendNativeCall({
                type: "ONLINE_TRANSLATE_DONE",
                ok: false,
                error: "No translatable text found on this page"
            });
            return;
        }

        const result = await translateNodeBatch(nodes, activeFromLang, activeToLang, sessionId);
        if (sessionId !== activeSessionId) return;

        if (!result.ok) {
            restoreOnlinePageTranslation(true);
            C.sendNativeCall({
                type: "ONLINE_TRANSLATE_DONE",
                ok: false,
                error: result.error || "Online translation failed"
            });
            return;
        }

        isPageTranslated = true;
        startMutationObserver(sessionId);

        C.sendNativeCall({
            type: "ONLINE_TRANSLATE_DONE",
            ok: true,
            restored: false,
            translatedCount: result.count,
            detectedLang: result.detectedLang || "",
            toLang: activeToLang
        });
    }

    C.runOnlinePageTranslation = runOnlinePageTranslation;
    C.restoreOnlinePageTranslation = restoreOnlinePageTranslation;
})(globalThis.__uhCs);
