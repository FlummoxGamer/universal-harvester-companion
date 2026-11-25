// -----------------------------------------------------------------------------
// UH Bridge Content Module 4: On-Device Image OCR Translation Overlays
// -----------------------------------------------------------------------------
(function (C) {
    let activeImageOverlays = [];
    let imageSyncAttached = false;
    let imageResizeObserver = null;

    function clearImageTranslationOverlays() {
        activeImageOverlays = [];
        if (imageResizeObserver) {
            try { imageResizeObserver.disconnect(); } catch (_e) {}
            imageResizeObserver = null;
        }
        const layer = document.getElementById("__uh_img_tr_layer");
        if (layer) layer.remove();
    }

    function syncImageOverlaysPosition() {
        if (!activeImageOverlays.length) return;
        const vh = window.innerHeight || 800;
        for (let i = 0; i < activeImageOverlays.length; i++) {
            const item = activeImageOverlays[i];
            if (!item.img || !document.contains(item.img)) {
                item.container.style.display = "none";
                continue;
            }
            const rect = item.img.getBoundingClientRect();
            if (rect.width < 20 || rect.height < 20 || rect.bottom < -60 || rect.top > vh + 60) {
                item.container.style.display = "none";
                continue;
            }
            item.container.style.display = "block";
            item.container.style.left = rect.left + "px";
            item.container.style.top = rect.top + "px";
            item.container.style.width = rect.width + "px";
            item.container.style.height = rect.height + "px";
        }
    }

    function ensureImageOverlaySync() {
        if (imageSyncAttached) return;
        imageSyncAttached = true;
        const onMove = () => {
            if (activeImageOverlays.length) requestAnimationFrame(syncImageOverlaysPosition);
        };
        window.addEventListener("scroll", onMove, { passive: true, capture: true });
        window.addEventListener("resize", onMove, { passive: true });
        if (window.visualViewport) {
            window.visualViewport.addEventListener("resize", onMove, { passive: true });
            window.visualViewport.addEventListener("scroll", onMove, { passive: true });
        }
    }

    function tryExtractCanvasDataUrl(img) {
        try {
            const natW = img.naturalWidth || img.width || 0;
            const natH = img.naturalHeight || img.height || 0;
            if (natW < 40 || natH < 24) return "";
            const maxSide = 1600;
            const scale = Math.min(1, maxSide / Math.max(natW, natH));
            const w = Math.max(1, Math.round(natW * scale));
            const h = Math.max(1, Math.round(natH * scale));
            const canvas = document.createElement("canvas");
            canvas.width = w;
            canvas.height = h;
            const ctx = canvas.getContext("2d");
            if (!ctx) return "";
            ctx.drawImage(img, 0, 0, w, h);
            return canvas.toDataURL("image/jpeg", 0.86);
        } catch (_e) {
            return "";
        }
    }

    function blobToDataUrl(blob) {
        return new Promise((resolve) => {
            try {
                const reader = new FileReader();
                reader.onloadend = () => resolve(typeof reader.result === "string" ? reader.result : "");
                reader.onerror = () => resolve("");
                reader.readAsDataURL(blob);
            } catch (_e) {
                resolve("");
            }
        });
    }

    async function tryFetchImageWithCredentials(src) {
        if (!src || (!src.startsWith("http://") && !src.startsWith("https://") && !src.startsWith("blob:"))) {
            return "";
        }
        try {
            const controller = typeof AbortController !== "undefined" ? new AbortController() : null;
            const timer = controller ? setTimeout(() => controller.abort(), 5000) : null;
            const resp = await fetch(src, {
                credentials: "include",
                mode: "cors",
                signal: controller ? controller.signal : undefined
            });
            if (timer) clearTimeout(timer);
            if (!resp || !resp.ok) return "";
            const blob = await resp.blob();
            if (!blob || blob.size < 64 || blob.size > 6 * 1024 * 1024) return "";
            if (blob.type && !blob.type.startsWith("image/")) return "";
            return await blobToDataUrl(blob);
        } catch (_e) {
            return "";
        }
    }

    async function captureImagePayload(img) {
        const src = img.currentSrc || img.src || "";
        if (src.startsWith("data:image/")) {
            return { imageUrl: "", dataUrl: src };
        }
        const canvasData = tryExtractCanvasDataUrl(img);
        if (canvasData) {
            return { imageUrl: src.startsWith("http") ? src : "", dataUrl: canvasData };
        }
        const fetchedData = await tryFetchImageWithCredentials(src);
        if (fetchedData) {
            return { imageUrl: src.startsWith("http") ? src : "", dataUrl: fetchedData };
        }
        if (src.startsWith("http://") || src.startsWith("https://")) {
            return { imageUrl: src, dataUrl: "" };
        }
        return null;
    }

    async function runImageOcrTranslation(fromLang, toLang) {
        clearImageTranslationOverlays();
        const imgs = Array.from(document.querySelectorAll("img"));
        const vh = window.innerHeight || 800;
        const rawTargets = [];

        for (let i = 0; i < imgs.length && rawTargets.length < 12; i++) {
            const img = imgs[i];
            if (!img.complete || img.naturalWidth < 60 || img.naturalHeight < 32) continue;
            const rect = img.getBoundingClientRect();
            if (rect.width < 72 || rect.height < 36) continue;
            if (rect.bottom < -220 || rect.top > vh * 2.2) continue;
            rawTargets.push(img);
        }

        if (!rawTargets.length) {
            C.sendNativeCall({ type: "IMAGE_TRANSLATE_DONE", imageCount: 0, overlayCount: 0 });
            return;
        }

        const candidates = [];
        const imgById = new Map();
        const capturedList = await Promise.all(rawTargets.map((img) => captureImagePayload(img)));
        for (let i = 0; i < rawTargets.length; i++) {
            const cap = capturedList[i];
            if (!cap) continue;
            const id = "img_" + candidates.length;
            imgById.set(id, rawTargets[i]);
            candidates.push({ id: id, imageUrl: cap.imageUrl, dataUrl: cap.dataUrl });
        }

        if (!candidates.length) {
            C.sendNativeCall({ type: "IMAGE_TRANSLATE_DONE", imageCount: 0, overlayCount: 0 });
            return;
        }

        const resp = await C.sendNativeCall({
            type: "TRANSLATE_IMAGES_OCR",
            fromLang: fromLang || "auto",
            toLang: toLang || "en",
            images: candidates
        });

        if (!resp || !Array.isArray(resp.results)) {
            C.sendNativeCall({ type: "IMAGE_TRANSLATE_DONE", imageCount: candidates.length, overlayCount: 0 });
            return;
        }

        const layer = document.createElement("div");
        layer.id = "__uh_img_tr_layer";
        layer.style.cssText = "position:fixed;inset:0;pointer-events:none;z-index:2147483640;overflow:hidden;";

        if (typeof ResizeObserver !== "undefined") {
            imageResizeObserver = new ResizeObserver(() => {
                if (activeImageOverlays.length) requestAnimationFrame(syncImageOverlaysPosition);
            });
        }

        let overlayCount = 0;
        for (let i = 0; i < resp.results.length; i++) {
            const res = resp.results[i];
            const img = imgById.get(res.id);
            if (!img || !Array.isArray(res.blocks) || !res.blocks.length) continue;

            const boxContainer = document.createElement("div");
            boxContainer.style.cssText = "position:fixed;pointer-events:none;overflow:hidden;";

            for (let b = 0; b < res.blocks.length; b++) {
                const blk = res.blocks[b];
                if (!blk.translatedText) continue;

                const bg = blk.bgColor || "rgba(15,23,42,0.92)";
                const fg = blk.textColor || "#F8FAFC";

                const chip = document.createElement("div");
                chip.textContent = blk.translatedText;
                chip.title = "Tap to toggle original text";
                chip.dataset.translated = blk.translatedText;
                chip.dataset.original = blk.originalText || blk.translatedText;
                chip.dataset.showingOriginal = "0";
                chip.style.cssText =
                    "position:absolute;box-sizing:border-box;pointer-events:auto;cursor:pointer;" +
                    "display:flex;align-items:center;justify-content:center;text-align:center;" +
                    "left:" + blk.leftPct + "%;top:" + blk.topPct + "%;" +
                    "min-width:" + blk.widthPct + "%;max-width:96%;" +
                    "min-height:" + blk.heightPct + "%;" +
                    "background:" + bg + ";color:" + fg + ";" +
                    "border:1px solid rgba(148,163,184,0.38);border-radius:6px;" +
                    "padding:3px 6px;font:600 11.5px/1.25 system-ui,-apple-system,sans-serif;" +
                    "backdrop-filter:blur(3px);word-break:break-word;" +
                    "box-shadow:0 2px 8px rgba(0,0,0,0.28);transition:opacity 0.15s ease;";

                chip.addEventListener("click", (ev) => {
                    ev.preventDefault();
                    ev.stopPropagation();
                    const isOrig = chip.dataset.showingOriginal === "1";
                    if (isOrig) {
                        chip.dataset.showingOriginal = "0";
                        chip.textContent = chip.dataset.translated;
                        chip.style.opacity = "1";
                    } else {
                        chip.dataset.showingOriginal = "1";
                        chip.textContent = chip.dataset.original;
                        chip.style.opacity = "0.78";
                    }
                });

                boxContainer.appendChild(chip);
                overlayCount++;
            }

            layer.appendChild(boxContainer);
            activeImageOverlays.push({ img: img, container: boxContainer });
            if (imageResizeObserver) {
                try { imageResizeObserver.observe(img); } catch (_e) {}
            }
        }

        if (overlayCount > 0) {
            (document.body || document.documentElement).appendChild(layer);
            ensureImageOverlaySync();
            syncImageOverlaysPosition();
        }

        C.sendNativeCall({
            type: "IMAGE_TRANSLATE_DONE",
            imageCount: candidates.length,
            overlayCount: overlayCount
        });
    }

    C.clearImageTranslationOverlays = clearImageTranslationOverlays;
    C.runImageOcrTranslation = runImageOcrTranslation;
})(globalThis.__uhCs);
