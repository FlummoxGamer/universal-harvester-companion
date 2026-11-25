// -----------------------------------------------------------------------------
// UH Bridge Content Module 4B: Visual "Block Element" Interactive Picker (Shadow DOM)
// -----------------------------------------------------------------------------
(function (C) {
    const api = C.api;
    const isTopFrame = C.isTopFrame;

    let pickerActive = false;
    let pickerHost = null;
    let highlightBox = null;
    let selectorLabelEl = null;
    let countLabelEl = null;
    let previewBtn = null;
    let blockBtn = null;
    let selectedEl = null;
    let childHistory = [];
    let currentSelector = "";
    let previewStyleEl = null;
    let previewOn = false;

    function cssEscapeIdent(str) {
        if (typeof CSS !== "undefined" && typeof CSS.escape === "function") {
            return CSS.escape(str);
        }
        return String(str).replace(/([^\w-])/g, "\\$1");
    }

    function isStableClass(cls) {
        if (!cls || cls.length < 2 || cls.length > 48) return false;
        if (/^(active|open|show|visible|hover|focus|selected|uh-)/i.test(cls)) return false;
        // Skip random hashed Tailwind/CSS-module noise if purely hex/digits
        if (/^[a-f0-9]{8,}$/i.test(cls)) return false;
        return true;
    }

    function computeSelector(el) {
        if (!el || el === document.body || el === document.documentElement) return "";
        if (el.id && el.id.length <= 48 && !/^\d/.test(el.id)) {
            const idSel = "#" + cssEscapeIdent(el.id);
            try {
                if (document.querySelectorAll(idSel).length === 1) return idSel;
            } catch (_e) {}
        }

        const buildPart = (node) => {
            const tag = node.tagName.toLowerCase();
            if (node.id && node.id.length <= 48 && !/^\d/.test(node.id)) {
                return "#" + cssEscapeIdent(node.id);
            }
            const classes = Array.from(node.classList || []).filter(isStableClass).slice(0, 2);
            if (classes.length > 0) {
                return tag + classes.map((c) => "." + cssEscapeIdent(c)).join("");
            }
            const role = node.getAttribute("data-testid") || node.getAttribute("aria-label");
            if (role && role.length <= 32 && !role.includes('"')) {
                const attr = node.hasAttribute("data-testid") ? "data-testid" : "aria-label";
                return tag + "[" + attr + '="' + role + '"]';
            }
            return tag;
        };

        const direct = buildPart(el);
        try {
            const cnt = document.querySelectorAll(direct).length;
            if (cnt >= 1 && cnt <= 8) return direct;
        } catch (_e) {}

        const parts = [direct];
        let cur = el.parentElement;
        let depth = 0;
        while (cur && cur !== document.body && cur !== document.documentElement && depth < 3) {
            parts.unshift(buildPart(cur));
            const candidate = parts.join(" > ");
            try {
                const cnt = document.querySelectorAll(candidate).length;
                if (cnt >= 1 && cnt <= 6) return candidate;
            } catch (_e) {}
            cur = cur.parentElement;
            depth++;
        }
        return parts.join(" > ");
    }

    function updateHighlight() {
        if (!highlightBox) return;
        if (!selectedEl || !selectedEl.isConnected) {
            highlightBox.style.display = "none";
            if (selectorLabelEl) selectorLabelEl.textContent = "Tap any ad, banner, or overlay on the page";
            if (countLabelEl) countLabelEl.textContent = "";
            if (blockBtn) blockBtn.disabled = true;
            return;
        }

        const rect = selectedEl.getBoundingClientRect();
        highlightBox.style.display = "block";
        highlightBox.style.left = Math.max(0, rect.left) + "px";
        highlightBox.style.top = Math.max(0, rect.top) + "px";
        highlightBox.style.width = Math.max(8, rect.width) + "px";
        highlightBox.style.height = Math.max(8, rect.height) + "px";

        currentSelector = computeSelector(selectedEl);
        let matchCount = 0;
        try {
            if (currentSelector) matchCount = document.querySelectorAll(currentSelector).length;
        } catch (_e) {}

        if (selectorLabelEl) {
            selectorLabelEl.textContent = currentSelector || selectedEl.tagName.toLowerCase();
        }
        if (countLabelEl) {
            countLabelEl.textContent = matchCount + (matchCount === 1 ? " match" : " matches");
        }
        if (blockBtn) {
            blockBtn.disabled = !currentSelector;
        }
        if (previewOn) {
            applyPreviewStyle(true);
        }
    }

    function applyPreviewStyle(enable) {
        previewOn = enable;
        if (previewBtn) {
            previewBtn.textContent = enable ? "Unpreview" : "Preview";
        }
        if (!enable || !currentSelector) {
            if (previewStyleEl) {
                previewStyleEl.remove();
                previewStyleEl = null;
            }
            return;
        }
        if (!previewStyleEl) {
            previewStyleEl = document.createElement("style");
            previewStyleEl.id = "__uh_picker_preview";
            (document.head || document.documentElement).appendChild(previewStyleEl);
        }
        previewStyleEl.textContent = currentSelector + " { opacity: 0.12 !important; outline: 2px dashed #ff5252 !important; }";
    }

    function isPickerNode(target) {
        if (!target) return false;
        if (target === pickerHost) return true;
        if (pickerHost && pickerHost.contains(target)) return true;
        return false;
    }

    function onCapturePointer(ev) {
        if (!pickerActive) return;
        const path = typeof ev.composedPath === "function" ? ev.composedPath() : [ev.target];
        for (let i = 0; i < path.length; i++) {
            if (isPickerNode(path[i])) return;
        }
        ev.preventDefault();
        ev.stopPropagation();
        ev.stopImmediatePropagation();

        if (ev.type === "click" || ev.type === "pointerdown") {
            const target = document.elementFromPoint(ev.clientX, ev.clientY);
            if (target && !isPickerNode(target) && target !== document.body && target !== document.documentElement) {
                selectedEl = target;
                childHistory = [];
                updateHighlight();
            }
        }
    }

    function onKeyDown(ev) {
        if (pickerActive && ev.key === "Escape") {
            ev.preventDefault();
            stopElementPicker();
        }
    }

    function stopElementPicker() {
        if (!pickerActive) return;
        pickerActive = false;
        applyPreviewStyle(false);
        window.removeEventListener("click", onCapturePointer, true);
        window.removeEventListener("pointerdown", onCapturePointer, true);
        window.removeEventListener("scroll", updateHighlight, true);
        window.removeEventListener("resize", updateHighlight, true);
        window.removeEventListener("keydown", onKeyDown, true);
        if (pickerHost) {
            pickerHost.remove();
            pickerHost = null;
        }
        highlightBox = null;
        selectorLabelEl = null;
        countLabelEl = null;
        previewBtn = null;
        blockBtn = null;
        selectedEl = null;
        childHistory = [];
        currentSelector = "";
    }

    function startElementPicker() {
        if (!isTopFrame || !document.documentElement) return;
        if (pickerActive) {
            stopElementPicker();
        }
        pickerActive = true;
        selectedEl = null;
        childHistory = [];
        currentSelector = "";

        pickerHost = document.createElement("div");
        pickerHost.id = "__uh_picker_host";
        pickerHost.style.cssText = "position:fixed;inset:0;z-index:2147483647;pointer-events:none;";
        const shadow = pickerHost.attachShadow({ mode: "open" });

        const style = document.createElement("style");
        style.textContent = [
            ".hl { position:fixed; display:none; pointer-events:none; border:2px solid #ef4444; background:rgba(239,68,68,0.18); border-radius:6px; box-sizing:border-box; transition:all 80ms ease-out; }",
            ".hud { position:fixed; left:12px; right:12px; bottom:18px; max-width:520px; margin:0 auto; pointer-events:auto; background:rgba(18,20,26,0.96); color:#f8fafc; border:1px solid rgba(255,255,255,0.14); border-radius:16px; padding:12px 14px; font-family:system-ui,-apple-system,sans-serif; box-shadow:0 12px 32px rgba(0,0,0,0.45); }",
            ".top { display:flex; align-items:center; justify-content:space-between; gap:8px; margin-bottom:8px; }",
            ".title { font-size:13px; font-weight:700; color:#fff; }",
            ".count { font-size:11px; color:#94a3b8; font-weight:600; }",
            ".sel { font-family:monospace; font-size:11.5px; color:#fca5a5; background:rgba(255,255,255,0.06); padding:6px 8px; border-radius:8px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; margin-bottom:10px; }",
            ".row { display:flex; flex-wrap:wrap; gap:6px; }",
            "button { flex:1; min-width:64px; border:none; border-radius:10px; padding:8px 10px; font-size:12px; font-weight:600; cursor:pointer; background:rgba(255,255,255,0.1); color:#f1f5f9; }",
            "button:disabled { opacity:0.4; cursor:default; }",
            "button.primary { background:#ef4444; color:#fff; }",
            "button.cancel { background:rgba(255,255,255,0.06); color:#cbd5e1; }"
        ].join("\n");

        highlightBox = document.createElement("div");
        highlightBox.className = "hl";

        const hud = document.createElement("div");
        hud.className = "hud";

        const top = document.createElement("div");
        top.className = "top";
        const title = document.createElement("span");
        title.className = "title";
        title.textContent = "UH Block Element";
        countLabelEl = document.createElement("span");
        countLabelEl.className = "count";
        top.appendChild(title);
        top.appendChild(countLabelEl);

        selectorLabelEl = document.createElement("div");
        selectorLabelEl.className = "sel";
        selectorLabelEl.textContent = "Tap any ad, banner, or overlay on the page";

        const row = document.createElement("div");
        row.className = "row";

        const parentBtn = document.createElement("button");
        parentBtn.textContent = "Parent";
        parentBtn.addEventListener("click", () => {
            if (selectedEl && selectedEl.parentElement && selectedEl.parentElement !== document.body && selectedEl.parentElement !== document.documentElement) {
                childHistory.push(selectedEl);
                selectedEl = selectedEl.parentElement;
                updateHighlight();
            }
        });

        const childBtn = document.createElement("button");
        childBtn.textContent = "Child";
        childBtn.addEventListener("click", () => {
            if (childHistory.length > 0) {
                selectedEl = childHistory.pop();
                updateHighlight();
            }
        });

        previewBtn = document.createElement("button");
        previewBtn.textContent = "Preview";
        previewBtn.addEventListener("click", () => {
            if (!currentSelector) return;
            applyPreviewStyle(!previewOn);
        });

        blockBtn = document.createElement("button");
        blockBtn.className = "primary";
        blockBtn.textContent = "Block";
        blockBtn.disabled = true;
        blockBtn.addEventListener("click", () => {
            if (!currentSelector) return;
            const host = C.currentNormalizedHost();
            const rule = (host ? host : "") + "##" + currentSelector;
            try {
                const matched = document.querySelectorAll(currentSelector);
                for (let i = 0; i < matched.length; i++) {
                    if (C.collapseElement) {
                        C.collapseElement(matched[i]);
                    } else {
                        matched[i].style.setProperty("display", "none", "important");
                    }
                }
            } catch (_e) {}
            api.runtime.sendMessage({
                type: "UH_ADD_CUSTOM_RULE",
                rule: rule
            }).catch(() => {});
            stopElementPicker();
        });

        const cancelBtn = document.createElement("button");
        cancelBtn.className = "cancel";
        cancelBtn.textContent = "Cancel";
        cancelBtn.addEventListener("click", stopElementPicker);

        row.appendChild(parentBtn);
        row.appendChild(childBtn);
        row.appendChild(previewBtn);
        row.appendChild(blockBtn);
        row.appendChild(cancelBtn);

        hud.appendChild(top);
        hud.appendChild(selectorLabelEl);
        hud.appendChild(row);

        shadow.appendChild(style);
        shadow.appendChild(highlightBox);
        shadow.appendChild(hud);
        document.documentElement.appendChild(pickerHost);

        window.addEventListener("pointerdown", onCapturePointer, true);
        window.addEventListener("click", onCapturePointer, true);
        window.addEventListener("scroll", updateHighlight, true);
        window.addEventListener("resize", updateHighlight, true);
        window.addEventListener("keydown", onKeyDown, true);
    }

    C.startElementPicker = startElementPicker;
    C.stopElementPicker = stopElementPicker;
})(globalThis.__uhCs);
