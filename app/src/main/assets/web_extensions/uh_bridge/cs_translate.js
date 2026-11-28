// -----------------------------------------------------------------------------
// UH Bridge Content Module 6: UH Translate Full-Page Parallel Background Engine
// -----------------------------------------------------------------------------
(function (C) {
    if (!C || !C.isTopFrame) return;

    const SKIP_TAGS = new Set([
        "SCRIPT", "STYLE", "NOSCRIPT", "CODE", "PRE", "TEXTAREA", "INPUT",
        "SELECT", "OPTION", "SVG", "MATH", "CANVAS", "IFRAME", "AUDIO", "VIDEO"
    ]);

    const BATCH_SIZE = 24;
    const PARALLEL_WORKERS = 3;
    const MAX_PAGE_NODES = 5500;

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

    function isNodeTranslatable(textNode, allowInsideReader) {
        if (!textNode || textNode.nodeType !== 3) return false;
        const parent = textNode.parentElement;
        if (!parent || !parent.isConnected) return false;
        if (SKIP_TAGS.has(parent.tagName)) return false;
        if (parent.closest("[translate='no'], .notranslate, code, pre, script, style")) {
            return false;
        }
        // Skip UH Reader chrome buttons/labels, but allow Reader article content when allowInsideReader=true
        if (parent.closest("#uh-r-topbar, #uh-r-bottombar, #uh-r-sel-bar, #uh-r-exit-fs-btn")) {
            return false;
        }
        if (!allowInsideReader && parent.closest("#uh-reader-overlay-root")) {
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

    function collectTextNodes(rootEl, allowInsideReader) {
        const viewportNodes = [];
        const offscreenNodes = [];
        const vh = window.innerHeight || 800;

        try {
            const walker = document.createTreeWalker(rootEl, NodeFilter.SHOW_TEXT, null);
            let node = walker.nextNode();
            while (node) {
                if (isNodeTranslatable(node, allowInsideReader)) {
                    let inViewport = false;
                    try {
                        const rect = node.parentElement.getBoundingClientRect();
                        inViewport = rect.bottom >= -150 && rect.top <= vh + 300 && rect.width > 0 && rect.height > 0;
                    } catch (_e) {}
                    if (inViewport) viewportNodes.push(node);
                    else offscreenNodes.push(node);
                }
                node = walker.nextNode();
            }
        } catch (_e) {}

        // Viewport-first order so the visible screen translates almost instantaneously,
        // while the rest of the entire webpage finishes automatically in background!
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

    async function translateNodesParallel(nodes, fromLang, toLang, sessionId, onProgress) {
        if (!nodes.length || sessionId !== activeSessionId) {
            return { ok: true, count: 0, detectedLang: null };
        }

        // Partition into batches
        const batches = [];
        for (let i = 0; i < nodes.length; i += BATCH_SIZE) {
            batches.push(nodes.slice(i, i + BATCH_SIZE));
        }

        let nextBatchIdx = 0;
        let completedBatches = 0;
        let translatedCount = 0;
        let detectedLang = null;
        let anyOk = false;
        let firstError = null;
        let aborted = false;

        async function runWorker() {
            while (!aborted && sessionId === activeSessionId) {
                const idx = nextBatchIdx++;
                if (idx >= batches.length) break;

                const slice = batches[idx].filter((n) => n && n.isConnected);
                if (!slice.length) {
                    completedBatches++;
                    continue;
                }

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
                    if (!firstError) {
                        firstError = (resp && resp.error) ? resp.error : "Translation failed";
                    }
                    // Abort quickly if the very first wave cannot reach the network
                    if (!anyOk && idx < PARALLEL_WORKERS) {
                        aborted = true;
                        break;
                    }
                }

                completedBatches++;
                if (onProgress && batches.length > 1) {
                    onProgress(Math.min(0.98, completedBatches / batches.length));
                }
            }
        }

        const workerCount = Math.min(PARALLEL_WORKERS, batches.length);
        const workers = [];
        for (let w = 0; w < workerCount; w++) {
            workers.push(runWorker());
        }
        await Promise.all(workers);

        return {
            ok: anyOk || nodes.length === 0,
            count: translatedCount,
            detectedLang,
            error: firstError
        };
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
                            if (isNodeTranslatable(added, false) && !originalTextMap.has(added)) {
                                pendingMutationNodes.add(added);
                            }
                        } else if (added.nodeType === 1 && !SKIP_TAGS.has(added.tagName)) {
                            if (added.id === "uh-reader-overlay-root" || (added.closest && added.closest("#uh-reader-overlay-root"))) {
                                continue;
                            }
                            const sub = collectTextNodes(added, false);
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
                    const batch = Array.from(pendingMutationNodes).slice(0, 120);
                    pendingMutationNodes.clear();
                    await translateNodesParallel(batch, activeFromLang, activeToLang, sessionId, null);
                }, 380);
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

        // If UH Reader is open, immediately sync restored sentences back to Read Aloud
        if (C.onReaderLiveTranslationUpdated) {
            try { C.onReaderLiveTranslationUpdated(null); } catch (_e) {}
        }

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

        if (document.title && document.title.trim().length > 1) {
            originalTitle = document.title;
        }

        const readerRoot = document.getElementById("uh-reader-overlay-root");
        const readerArticle = document.getElementById("uh-r-book-track");
        const isReaderOpen = !!(readerRoot && readerArticle);

        let lastProgressSent = 0;
        const emitProgress = (frac) => {
            const now = Date.now();
            if (now - lastProgressSent < 180) return;
            lastProgressSent = now;
            C.sendNativeCall({
                type: "ONLINE_TRANSLATE_PROGRESS",
                progress: frac
            });
        };

        if (isReaderOpen) {
            // 1) Translate the ENTIRE UH Reader article live in place first for instant seamless reading
            const readerNodes = collectTextNodes(readerArticle, true).slice(0, MAX_PAGE_NODES);
            const readerRes = await translateNodesParallel(
                readerNodes,
                activeFromLang,
                activeToLang,
                sessionId,
                (p) => emitProgress(p * 0.85)
            );
            if (sessionId !== activeSessionId) return;

            if (!readerRes.ok) {
                restoreOnlinePageTranslation(true);
                C.sendNativeCall({
                    type: "ONLINE_TRANSLATE_DONE",
                    ok: false,
                    error: readerRes.error || "Translation failed"
                });
                return;
            }

            isPageTranslated = true;

            // Live-refresh UH Reader Read Aloud sentences, page layout & detected language in place!
            if (C.onReaderLiveTranslationUpdated) {
                try { C.onReaderLiveTranslationUpdated(activeToLang); } catch (_e) {}
            }

            C.sendNativeCall({
                type: "ONLINE_TRANSLATE_DONE",
                ok: true,
                restored: false,
                translatedCount: readerRes.count,
                detectedLang: readerRes.detectedLang || "",
                toLang: activeToLang
            });

            // 2) Silently sync the underlying original webpage behind UH Reader in the background
            const pageNodes = collectTextNodes(rootEl, false).slice(0, MAX_PAGE_NODES);
            if (pageNodes.length > 0 && sessionId === activeSessionId) {
                translateNodesParallel(pageNodes, activeFromLang, activeToLang, sessionId, null).then(() => {
                    if (sessionId === activeSessionId) startMutationObserver(sessionId);
                }).catch(() => {});
            } else {
                startMutationObserver(sessionId);
            }
            return;
        }

        // Standard webpage mode: collect ENTIRE page (viewport first, then full document) with no 650-node cutoff
        const pageNodes = collectTextNodes(rootEl, false).slice(0, MAX_PAGE_NODES);
        if (!pageNodes.length) {
            C.sendNativeCall({
                type: "ONLINE_TRANSLATE_DONE",
                ok: false,
                error: "No translatable text found on this page"
            });
            return;
        }

        const result = await translateNodesParallel(
            pageNodes,
            activeFromLang,
            activeToLang,
            sessionId,
            emitProgress
        );
        if (sessionId !== activeSessionId) return;

        if (!result.ok) {
            restoreOnlinePageTranslation(true);
            C.sendNativeCall({
                type: "ONLINE_TRANSLATE_DONE",
                ok: false,
                error: result.error || "Translation failed"
            });
            return;
        }

        // Also translate document.title cleanly
        if (originalTitle && originalTitle.trim().length > 1) {
            C.sendNativeCall({
                type: "ONLINE_TRANSLATE_BATCH",
                fromLang: activeFromLang,
                toLang: activeToLang,
                texts: [originalTitle.trim()]
            }).then((r) => {
                if (sessionId === activeSessionId && r && r.ok && Array.isArray(r.translations) && r.translations[0]) {
                    try { document.title = r.translations[0]; } catch (_e) {}
                }
            }).catch(() => {});
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
    C.isOnlinePageTranslated = () => isPageTranslated;
})(globalThis.__uhCs);
