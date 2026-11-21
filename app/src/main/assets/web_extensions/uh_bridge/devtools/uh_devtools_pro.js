(function () {
    if (window.__uhDevToolsProInstalled) return;
    window.__uhDevToolsProInstalled = true;

    let devToolsVisible = false;
    let initialized = false;
    let isDarkTheme = true;

    const earlyNetLog = (window.__uhEarlyNetLog = window.__uhEarlyNetLog || []);
    const netListeners = (window.__uhNetListeners = window.__uhNetListeners || []);

    function notifyNativeState(visible) {
        try {
            window.postMessage({
                type: "UH_NATIVE_CALL",
                payload: { type: "DEVTOOLS_STATE", visible: !!visible }
            }, "*");
        } catch (_e) {}
    }

    function escapeHtml(str) {
        return String(str || "")
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;");
    }

    function formatBytes(bytes) {
        const b = Number(bytes) || 0;
        if (b <= 0) return "—";
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return (b / 1024).toFixed(1) + " KB";
        return (b / (1024 * 1024)).toFixed(2) + " MB";
    }

    function buildCssSelector(el) {
        if (!el || el.nodeType !== 1) return "";
        if (el.id) return "#" + CSS.escape(el.id);
        const parts = [];
        let cur = el;
        while (cur && cur.nodeType === 1 && parts.length < 5) {
            let sel = cur.tagName.toLowerCase();
            if (cur.id) {
                parts.unshift("#" + CSS.escape(cur.id));
                break;
            }
            if (cur.classList && cur.classList.length > 0) {
                sel += "." + Array.from(cur.classList).slice(0, 2).map((c) => CSS.escape(c)).join(".");
            }
            parts.unshift(sel);
            cur = cur.parentElement;
        }
        return parts.join(" > ");
    }

    function buildCurl(entry) {
        const parts = ["curl", "-X", entry.method || "GET", "'" + String(entry.url || "").replace(/'/g, "'\\''") + "'"];
        const hdrs = entry.reqHeaders || {};
        for (const k of Object.keys(hdrs)) {
            parts.push("-H", "'" + k + ": " + String(hdrs[k]).replace(/'/g, "'\\''") + "'");
        }
        if (entry.reqBody) {
            parts.push("--data-raw", "'" + String(entry.reqBody).replace(/'/g, "'\\''") + "'");
        }
        return parts.join(" ");
    }

    function copyText(text) {
        try {
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(text);
                return;
            }
        } catch (_e) {}
        try {
            const ta = document.createElement("textarea");
            ta.value = text;
            ta.style.position = "fixed";
            ta.style.opacity = "0";
            document.body.appendChild(ta);
            ta.select();
            document.execCommand("copy");
            ta.remove();
        } catch (_e) {}
    }

    function getShadowOrRoot() {
        try {
            if (window.eruda && window.eruda._shadowRoot) return window.eruda._shadowRoot;
        } catch (_e) {}
        const host = document.getElementById("eruda");
        if (!host) return null;
        return host.shadowRoot || host;
    }

    // -------------------------------------------------------------------------
    // Box-Model Touch Element Picker (Integrated into UH Header + Elements)
    // -------------------------------------------------------------------------
    let boxOverlay = null;
    let pickingActive = false;

    function removeBoxOverlay() {
        if (boxOverlay) {
            boxOverlay.remove();
            boxOverlay = null;
        }
    }

    function drawBoxHighlight(el) {
        removeBoxOverlay();
        if (!el || !el.getBoundingClientRect) return;
        const rect = el.getBoundingClientRect();
        const cs = getComputedStyle(el);
        const mt = parseFloat(cs.marginTop) || 0;
        const mr = parseFloat(cs.marginRight) || 0;
        const mb = parseFloat(cs.marginBottom) || 0;
        const ml = parseFloat(cs.marginLeft) || 0;
        const pt = parseFloat(cs.paddingTop) || 0;
        const pr = parseFloat(cs.paddingRight) || 0;
        const pb = parseFloat(cs.paddingBottom) || 0;
        const pl = parseFloat(cs.paddingLeft) || 0;

        boxOverlay = document.createElement("div");
        boxOverlay.id = "__uh_inspector_overlay";
        boxOverlay.style.cssText =
            "position:fixed;pointer-events:none;z-index:2147483645;" +
            `left:${rect.left - ml}px;top:${rect.top - mt}px;` +
            `width:${rect.width + ml + mr}px;height:${rect.height + mt + mb}px;` +
            "background:rgba(246,178,107,0.34);box-sizing:border-box;";

        const borderBox = document.createElement("div");
        borderBox.style.cssText =
            `position:absolute;left:${ml}px;top:${mt}px;width:${rect.width}px;height:${rect.height}px;` +
            "background:rgba(147,196,125,0.36);box-sizing:border-box;outline:1.5px solid #457B9D;";

        const contentBox = document.createElement("div");
        contentBox.style.cssText =
            `position:absolute;left:${pl}px;top:${pt}px;` +
            `width:${Math.max(0, rect.width - pl - pr)}px;height:${Math.max(0, rect.height - pt - pb)}px;` +
            "background:rgba(69,123,157,0.42);";

        const tip = document.createElement("div");
        tip.textContent = `${el.tagName.toLowerCase()}${el.id ? "#" + el.id : ""} · ${Math.round(rect.width)}×${Math.round(rect.height)}`;
        tip.style.cssText =
            "position:absolute;left:0;top:-24px;background:#0A0F1A;color:#E6E9EE;" +
            "font:600 11px/1.2 monospace;padding:3px 7px;border-radius:6px;white-space:nowrap;" +
            "border:1px solid #457B9D;box-shadow:0 2px 8px rgba(0,0,0,0.45);";

        borderBox.appendChild(contentBox);
        borderBox.appendChild(tip);
        boxOverlay.appendChild(borderBox);
        document.documentElement.appendChild(boxOverlay);
    }

    function updatePickedStrip(el) {
        const root = getShadowOrRoot();
        if (!root) return;
        let strip = root.querySelector("#__uh_pick_strip");
        if (!el) {
            if (strip) strip.remove();
            return;
        }
        const elementsPanel = root.querySelector(".eruda-elements");
        if (!elementsPanel) return;
        if (!strip) {
            strip = document.createElement("div");
            strip.id = "__uh_pick_strip";
            elementsPanel.insertBefore(strip, elementsPanel.firstChild);
        }
        const rect = el.getBoundingClientRect();
        const sel = buildCssSelector(el);
        const bgCard = isDarkTheme ? "#171B21" : "#FFFFFF";
        const borderCol = isDarkTheme ? "#2A3138" : "#CBD5E1";
        const textMain = isDarkTheme ? "#E6E9EE" : "#0F172A";
        const bgBtn = isDarkTheme ? "#0A0F1A" : "#F1F5F9";
        strip.style.cssText =
            `position:relative;z-index:10;display:flex;align-items:center;gap:6px;padding:6px 10px;background:${bgCard};` +
            `border-bottom:1px solid ${borderCol};font-family:monospace;font-size:11px;color:${textMain};flex-wrap:wrap;`;
        strip.innerHTML =
            `<span style="color:#457B9D;font-weight:700;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">${escapeHtml(sel)} (${Math.round(rect.width)}×${Math.round(rect.height)})</span>` +
            `<button id="__uh_pk_sel" style="padding:3px 7px;border:1px solid ${borderCol};border-radius:5px;background:${bgBtn};color:${textMain};font-size:10px;cursor:pointer;">Copy Selector</button>` +
            `<button id="__uh_pk_html" style="padding:3px 7px;border:1px solid ${borderCol};border-radius:5px;background:${bgBtn};color:${textMain};font-size:10px;cursor:pointer;">Copy HTML</button>` +
            `<button id="__uh_pk_clr" style="padding:3px 7px;border:none;border-radius:5px;background:transparent;color:#8A96A8;font-size:10px;cursor:pointer;">Clear</button>`;

        const bSel = strip.querySelector("#__uh_pk_sel");
        if (bSel) bSel.onclick = () => copyText(buildCssSelector(el));
        const bHtml = strip.querySelector("#__uh_pk_html");
        if (bHtml) bHtml.onclick = () => copyText(el.outerHTML);
        const bClr = strip.querySelector("#__uh_pk_clr");
        if (bClr) bClr.onclick = () => {
            removeBoxOverlay();
            strip.remove();
        };
    }

    function onPickElementTap(e) {
        if (!pickingActive) return;
        const target = e.target;
        if (!target || (target.closest && target.closest("#eruda"))) return;
        e.preventDefault();
        e.stopPropagation();
        pickingActive = false;
        document.removeEventListener("click", onPickElementTap, true);
        const banner = document.getElementById("__uh_pick_banner");
        if (banner) banner.remove();

        drawBoxHighlight(target);
        if (window.eruda) {
            window.eruda.show();
            devToolsVisible = true;
            notifyNativeState(true);
            try {
                window.eruda.show("elements");
                const elTool = window.eruda.get("elements");
                if (elTool && typeof elTool.select === "function") {
                    elTool.select(target);
                }
            } catch (_e) {}
            setTimeout(() => updatePickedStrip(target), 60);
        }
    }

    function startElementPicking() {
        pickingActive = true;
        if (window.eruda) {
            window.eruda.hide();
        }
        let banner = document.getElementById("__uh_pick_banner");
        if (!banner) {
            banner = document.createElement("div");
            banner.id = "__uh_pick_banner";
            banner.textContent = "UH DevTools · Tap any element on the page to inspect";
            banner.style.cssText =
                "position:fixed;top:12px;left:50%;transform:translateX(-50%);z-index:2147483646;" +
                "background:#0A0F1A;color:#E6E9EE;border:1px solid #457B9D;border-radius:999px;" +
                "padding:7px 14px;font:600 12px/1.2 system-ui,sans-serif;box-shadow:0 4px 16px rgba(0,0,0,0.45);pointer-events:none;";
            document.documentElement.appendChild(banner);
        }
        document.addEventListener("click", onPickElementTap, true);
    }

    function closeDevToolsPanel() {
        pickingActive = false;
        document.removeEventListener("click", onPickElementTap, true);
        const banner = document.getElementById("__uh_pick_banner");
        if (banner) banner.remove();
        removeBoxOverlay();
        if (window.eruda) {
            try { window.eruda.hide(); } catch (_e) {}
        }
        devToolsVisible = false;
        notifyNativeState(false);
    }

    // -------------------------------------------------------------------------
    // Tab: Network (Proper Eruda v3 Tool with visible $el.show() / $el.hide())
    // -------------------------------------------------------------------------
    function createNetworkTool() {
        let $container = null;
        let rootEl = null;
        let filterText = "";
        let filterKind = "all";
        let selectedId = null;
        let detailSubTab = "response";
        let clearedAtTs = 0;
        let isShown = false;

        function getCombinedEntries() {
            const live = earlyNetLog.slice();
            const liveUrls = new Set(live.map((e) => e.url));
            const assetEntries = [];
            try {
                const perf = performance.getEntriesByType("resource") || [];
                for (let i = perf.length - 1; i >= 0; i--) {
                    const p = perf[i];
                    if (!p || !p.name || p.startTime < clearedAtTs) continue;
                    if ((p.initiatorType === "fetch" || p.initiatorType === "xmlhttprequest") && liveUrls.has(p.name)) {
                        continue;
                    }
                    const status = typeof p.responseStatus === "number" && p.responseStatus > 0 ? p.responseStatus : 200;
                    const size = p.transferSize || p.encodedBodySize || p.decodedBodySize || 0;
                    assetEntries.push({
                        id: "perf_" + i + "_" + Math.round(p.startTime),
                        kind: "asset",
                        initiator: p.initiatorType || "other",
                        method: "GET",
                        url: p.name,
                        status: status,
                        statusText: status === 200 ? "OK" : String(status),
                        durationMs: Math.max(1, Math.round(p.duration || 0)),
                        sizeBytes: size,
                        contentType: p.initiatorType || "resource",
                        reqHeaders: {},
                        reqBody: "",
                        resHeaders: p.nextHopProtocol ? { protocol: p.nextHopProtocol } : {},
                        resBody: "",
                        startedAt: Math.round(p.startTime) + "ms",
                        error: null
                    });
                }
            } catch (_e) {}
            return live.concat(assetEntries);
        }

        function render() {
            if (!rootEl || !isShown) return;
            const bgMain = isDarkTheme ? "#0A0F1A" : "#F8FAFC";
            const bgCard = isDarkTheme ? "#171B21" : "#FFFFFF";
            const borderCol = isDarkTheme ? "#2A3138" : "#E2E8F0";
            const textMain = isDarkTheme ? "#E6E9EE" : "#0F172A";
            const textSub = isDarkTheme ? "#8A96A8" : "#64748B";

            const allEntries = getCombinedEntries();
            const filtered = allEntries.filter((e) => {
                if (filterKind === "api" && e.kind !== "fetch" && e.kind !== "xhr") return false;
                if (filterKind === "assets" && e.kind !== "asset") return false;
                if (filterKind === "err" && e.status >= 200 && e.status < 400) return false;
                if (filterText && !String(e.url || "").toLowerCase().includes(filterText.toLowerCase())) return false;
                return true;
            });

            const sel = filtered.find((x) => x.id === selectedId) || null;
            const rows = filtered.slice(0, 140).map((e) => {
                const statusColor = e.status >= 200 && e.status < 300
                    ? "#4ADE80"
                    : (e.status >= 400 || e.status < 0 ? "#F87171" : "#FBBF24");
                const shortUrl = String(e.url || "").replace(/^https?:\/\/[^/]+/, "") || e.url;
                const barW = Math.min(100, Math.max(4, Math.round((e.durationMs / 800) * 100)));
                const tag = e.kind === "asset" ? (e.initiator || "asset").toUpperCase() : e.method;
                const isSelected = e.id === selectedId;
                return `
                    <div data-id="${escapeHtml(e.id)}" class="uh-net-row" style="padding:8px 10px;border-bottom:1px solid ${borderCol};cursor:pointer;background:${isSelected ? "rgba(69,123,157,0.22)" : "transparent"};">
                        <div style="display:flex;align-items:center;gap:6px;font-family:monospace;font-size:11px;">
                            <span style="color:${statusColor};font-weight:700;min-width:28px;">${e.status || "..."}</span>
                            <span style="color:#457B9D;font-weight:700;font-size:10px;min-width:36px;">${escapeHtml(tag)}</span>
                            <span style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:${textMain};">${escapeHtml(shortUrl)}</span>
                            <span style="color:${textSub};font-size:10px;">${formatBytes(e.sizeBytes)}</span>
                            <span style="color:${textSub};font-size:10px;min-width:40px;text-align:right;">${e.durationMs}ms</span>
                        </div>
                        <div style="margin-top:4px;height:2.5px;background:${bgCard};border-radius:2px;overflow:hidden;">
                            <div style="width:${barW}%;height:100%;background:${statusColor};"></div>
                        </div>
                    </div>`;
            }).join("") || `<div style="padding:28px 16px;text-align:center;color:${textSub};font-size:12px;">No matching requests captured yet. Interact with or reload the page to inspect traffic.</div>`;

            let detailHtml = "";
            if (sel) {
                let bodySection = "";
                if (detailSubTab === "headers") {
                    const reqH = Object.entries(sel.reqHeaders || {}).map(([k, v]) => `<div><span style="color:#7DD3FC;">${escapeHtml(k)}</span>: ${escapeHtml(v)}</div>`).join("") || `<span style="color:${textSub};">None captured</span>`;
                    const resH = Object.entries(sel.resHeaders || {}).map(([k, v]) => `<div><span style="color:#4ADE80;">${escapeHtml(k)}</span>: ${escapeHtml(v)}</div>`).join("") || `<span style="color:${textSub};">None captured</span>`;
                    bodySection = `
                        <div style="max-height:190px;overflow:auto;background:${bgMain};padding:8px;border-radius:8px;font-family:monospace;font-size:10.5px;line-height:1.45;">
                            <div style="font-weight:700;color:${textSub};margin-bottom:4px;">REQUEST HEADERS</div>
                            ${reqH}
                            <div style="font-weight:700;color:${textSub};margin:8px 0 4px;">RESPONSE HEADERS</div>
                            ${resH}
                        </div>`;
                } else {
                    let prettyBody = sel.resBody || (sel.kind === "asset" ? "(Static page resource — tap Replay to inspect response text)" : "(empty response body)");
                    try {
                        if (sel.resBody && (String(sel.contentType).includes("json") || sel.resBody.trim().startsWith("{") || sel.resBody.trim().startsWith("["))) {
                            prettyBody = JSON.stringify(JSON.parse(sel.resBody), null, 2);
                        }
                    } catch (_e) {}
                    bodySection = `<pre style="margin:0;max-height:190px;overflow:auto;background:${bgMain};padding:8px;border-radius:8px;color:${textMain};font-size:10.5px;white-space:pre-wrap;word-break:break-word;">${escapeHtml(prettyBody.slice(0, 8000))}</pre>`;
                }

                detailHtml = `
                    <div style="padding:10px;background:${bgCard};border-top:2px solid #457B9D;font-size:11px;">
                        <div style="display:flex;gap:6px;margin-bottom:8px;align-items:center;flex-wrap:wrap;">
                            <button id="uh-net-sub-res" style="padding:5px 9px;border:none;border-radius:6px;background:${detailSubTab === "response" ? "#457B9D" : bgMain};color:${detailSubTab === "response" ? "#fff" : textMain};font-weight:600;cursor:pointer;font-size:11px;">Response</button>
                            <button id="uh-net-sub-hdr" style="padding:5px 9px;border:none;border-radius:6px;background:${detailSubTab === "headers" ? "#457B9D" : bgMain};color:${detailSubTab === "headers" ? "#fff" : textMain};font-weight:600;cursor:pointer;font-size:11px;">Headers</button>
                            <button id="uh-net-curl" style="padding:5px 9px;border:1px solid ${borderCol};border-radius:6px;background:${bgMain};color:${textMain};font-weight:600;cursor:pointer;font-size:11px;">Copy cURL</button>
                            <button id="uh-net-replay" style="padding:5px 9px;border:1px solid ${borderCol};border-radius:6px;background:${bgMain};color:${textMain};cursor:pointer;font-size:11px;">Replay</button>
                            <button id="uh-net-close-det" style="margin-left:auto;padding:5px 8px;border:none;background:transparent;color:${textSub};cursor:pointer;font-weight:700;">✕</button>
                        </div>
                        <div style="font-family:monospace;color:#457B9D;word-break:break-all;margin-bottom:5px;font-size:10.5px;">${escapeHtml(sel.url)}</div>
                        <div style="color:${textSub};margin-bottom:6px;font-size:10.5px;">${escapeHtml(sel.method)} · ${sel.status || "..."} · ${escapeHtml(sel.contentType || "resource")} · ${formatBytes(sel.sizeBytes)} · ${sel.durationMs}ms</div>
                        ${bodySection}
                    </div>`;
            }

            const chip = (id, label) => {
                const active = filterKind === id;
                return `<button data-kind="${id}" class="uh-net-chip" style="padding:4px 9px;border-radius:6px;border:1px solid ${active ? "#457B9D" : borderCol};background:${active ? "#457B9D" : bgMain};color:${active ? "#fff" : textSub};font-size:10.5px;font-weight:600;cursor:pointer;">${label}</button>`;
            };

            rootEl.innerHTML = `
                <div style="font-family:system-ui,-apple-system,sans-serif;color:${textMain};background:${bgMain};min-height:100%;box-sizing:border-box;padding-bottom:16px;">
                    <div style="position:sticky;top:0;z-index:5;background:${bgCard};">
                        <div style="display:flex;gap:6px;padding:8px 10px;border-bottom:1px solid ${borderCol};align-items:center;">
                            <input id="uh-net-filter" placeholder="Filter URL…" value="${escapeHtml(filterText)}" style="flex:1;min-width:110px;padding:6px 8px;border-radius:6px;border:1px solid ${borderCol};background:${bgMain};color:${textMain};font-size:12px;" />
                            <span style="font-size:10.5px;color:${textSub};">${filtered.length} req</span>
                            <button id="uh-net-clear" style="padding:5px 8px;border:1px solid ${borderCol};border-radius:6px;background:${bgMain};color:#F87171;font-size:11px;font-weight:600;cursor:pointer;">Clear</button>
                        </div>
                        <div style="display:flex;gap:5px;padding:6px 10px;border-bottom:1px solid ${borderCol};overflow-x:auto;">
                            ${chip("all", "All")}
                            ${chip("api", "Fetch / XHR")}
                            ${chip("assets", "Assets")}
                            ${chip("err", "Errors")}
                        </div>
                    </div>
                    <div>${rows}</div>
                    ${detailHtml}
                </div>`;

            const fIn = rootEl.querySelector("#uh-net-filter");
            if (fIn) {
                fIn.oninput = (ev) => {
                    filterText = ev.target.value;
                    render();
                    const nextInput = rootEl.querySelector("#uh-net-filter");
                    if (nextInput) {
                        nextInput.focus();
                        nextInput.setSelectionRange(filterText.length, filterText.length);
                    }
                };
            }
            rootEl.querySelectorAll(".uh-net-chip").forEach((btn) => {
                btn.onclick = () => { filterKind = btn.getAttribute("data-kind") || "all"; render(); };
            });
            const clr = rootEl.querySelector("#uh-net-clear");
            if (clr) clr.onclick = () => {
                earlyNetLog.length = 0;
                clearedAtTs = performance.now();
                selectedId = null;
                render();
            };
            rootEl.querySelectorAll(".uh-net-row").forEach((row) => {
                row.onclick = () => { selectedId = row.getAttribute("data-id"); render(); };
            });
            const subRes = rootEl.querySelector("#uh-net-sub-res");
            if (subRes) subRes.onclick = () => { detailSubTab = "response"; render(); };
            const subHdr = rootEl.querySelector("#uh-net-sub-hdr");
            if (subHdr) subHdr.onclick = () => { detailSubTab = "headers"; render(); };
            const curlBtn = rootEl.querySelector("#uh-net-curl");
            if (curlBtn && sel) curlBtn.onclick = () => copyText(buildCurl(sel));
            const repBtn = rootEl.querySelector("#uh-net-replay");
            if (repBtn && sel) {
                repBtn.onclick = () => {
                    fetch(sel.url, {
                        method: sel.method || "GET",
                        headers: sel.reqHeaders || {},
                        body: sel.method === "GET" || sel.method === "HEAD" ? undefined : sel.reqBody
                    }).catch(() => {});
                };
            }
            const closeDet = rootEl.querySelector("#uh-net-close-det");
            if (closeDet) closeDet.onclick = () => { selectedId = null; render(); };
        }

        const ToolBase = window.eruda && window.eruda.Tool;
        if (ToolBase) {
            return new (ToolBase.extend({
                name: "Network",
                init($el) {
                    this.callSuper(ToolBase, "init", arguments);
                    $container = $el;
                    rootEl = $el[0];
                    if (rootEl) {
                        rootEl.style.overflowY = "auto";
                        rootEl.style.webkitOverflowScrolling = "touch";
                    }
                    netListeners.push(render);
                },
                show() {
                    this.callSuper(ToolBase, "show", arguments);
                    isShown = true;
                    if (rootEl) rootEl.style.display = "block";
                    render();
                    return this;
                },
                hide() {
                    isShown = false;
                    this.callSuper(ToolBase, "hide", arguments);
                    if (rootEl) rootEl.style.display = "none";
                    return this;
                },
                destroy() {
                    isShown = false;
                    const idx = netListeners.indexOf(render);
                    if (idx >= 0) netListeners.splice(idx, 1);
                    this.callSuper(ToolBase, "destroy", arguments);
                }
            }))();
        }

        return {
            name: "Network",
            init($el) {
                $container = $el;
                rootEl = $el[0];
                if (rootEl) {
                    rootEl.style.overflowY = "auto";
                    rootEl.style.webkitOverflowScrolling = "touch";
                }
                netListeners.push(render);
            },
            show() {
                isShown = true;
                if ($container && $container.show) $container.show();
                if (rootEl) rootEl.style.display = "block";
                render();
                return this;
            },
            hide() {
                isShown = false;
                if ($container && $container.hide) $container.hide();
                if (rootEl) rootEl.style.display = "none";
                return this;
            },
            destroy() {
                isShown = false;
                const idx = netListeners.indexOf(render);
                if (idx >= 0) netListeners.splice(idx, 1);
                if ($container && $container.remove) $container.remove();
            }
        };
    }

    // -------------------------------------------------------------------------
    // Tab: Performance & Page Info (Proper Eruda v3 Tool)
    // -------------------------------------------------------------------------
    function createPerformanceTool() {
        let $container = null;
        let rootEl = null;
        let fps = 60;
        let frames = 0;
        let lastTs = performance.now();
        let rafId = 0;
        let outlinesOn = false;
        let showSource = false;
        let isShown = false;

        function tick(now) {
            frames++;
            if (now - lastTs >= 1000) {
                fps = Math.round((frames * 1000) / (now - lastTs));
                frames = 0;
                lastTs = now;
                const el = rootEl && rootEl.querySelector("#uh-fps-val");
                if (el) el.textContent = fps + " FPS";
            }
            if (isShown) {
                rafId = requestAnimationFrame(tick);
            } else {
                rafId = 0;
            }
        }

        function toggleOutlines() {
            outlinesOn = !outlinesOn;
            let st = document.getElementById("__uh_outline_css");
            if (outlinesOn) {
                if (!st) {
                    st = document.createElement("style");
                    st.id = "__uh_outline_css";
                    st.textContent = "* { outline: 1px solid rgba(69, 123, 157, 0.55) !important; }";
                    (document.head || document.documentElement).appendChild(st);
                }
            } else if (st) {
                st.remove();
            }
            render();
        }

        function render() {
            if (!rootEl || !isShown) return;
            const bgMain = isDarkTheme ? "#0A0F1A" : "#F8FAFC";
            const bgCard = isDarkTheme ? "#171B21" : "#FFFFFF";
            const borderCol = isDarkTheme ? "#2A3138" : "#E2E8F0";
            const textMain = isDarkTheme ? "#E6E9EE" : "#0F172A";
            const textSub = isDarkTheme ? "#8A96A8" : "#64748B";

            const nav = performance.getEntriesByType("navigation")[0];
            const ttfb = nav ? Math.max(0, Math.round(nav.responseStart - nav.requestStart)) : 0;
            const domReady = nav ? Math.max(0, Math.round(nav.domContentLoadedEventEnd)) : 0;
            const loadDone = nav ? Math.max(0, Math.round(nav.loadEventEnd)) : 0;
            const paints = performance.getEntriesByType("paint");
            const fcpObj = paints.find((p) => p.name === "first-contentful-paint");
            const fcp = fcpObj ? Math.round(fcpObj.startTime) : 0;
            const nodeCount = document.getElementsByTagName("*").length;
            const scriptCount = document.scripts ? document.scripts.length : 0;
            const imgCount = document.images ? document.images.length : 0;
            const resCount = (performance.getEntriesByType("resource") || []).length;
            const srcHtml = showSource
                ? `<pre style="margin-top:10px;max-height:240px;overflow:auto;background:${bgCard};border:1px solid ${borderCol};padding:10px;border-radius:10px;color:${textMain};font-size:10px;white-space:pre-wrap;word-break:break-word;">${escapeHtml(document.documentElement.outerHTML.slice(0, 28000))}</pre>`
                : "";

            rootEl.innerHTML = `
                <div style="padding:12px;font-family:system-ui,-apple-system,sans-serif;color:${textMain};background:${bgMain};min-height:100%;box-sizing:border-box;font-size:12px;">
                    <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:10px;">
                        <div style="background:${bgCard};border:1px solid ${borderCol};padding:10px;border-radius:10px;">
                            <div style="color:${textSub};font-size:10px;">Live Frame Rate</div>
                            <div id="uh-fps-val" style="font-size:18px;font-weight:700;color:#4ADE80;">${fps} FPS</div>
                        </div>
                        <div style="background:${bgCard};border:1px solid ${borderCol};padding:10px;border-radius:10px;">
                            <div style="color:${textSub};font-size:10px;">DOM Nodes</div>
                            <div style="font-size:18px;font-weight:700;color:#457B9D;">${nodeCount}</div>
                        </div>
                        <div style="background:${bgCard};border:1px solid ${borderCol};padding:10px;border-radius:10px;">
                            <div style="color:${textSub};font-size:10px;">TTFB / FCP</div>
                            <div style="font-size:14px;font-weight:700;">${ttfb}ms / ${fcp}ms</div>
                        </div>
                        <div style="background:${bgCard};border:1px solid ${borderCol};padding:10px;border-radius:10px;">
                            <div style="color:${textSub};font-size:10px;">DOM / Load</div>
                            <div style="font-size:14px;font-weight:700;">${domReady}ms / ${loadDone}ms</div>
                        </div>
                    </div>
                    <div style="background:${bgCard};border:1px solid ${borderCol};padding:10px;border-radius:10px;margin-bottom:10px;color:${textSub};font-size:11px;line-height:1.55;">
                        <div>URL: <b style="color:${textMain};word-break:break-all;">${escapeHtml(window.location.href)}</b></div>
                        <div>Viewport: <b style="color:${textMain};">${window.innerWidth}×${window.innerHeight}</b> (DPR ${window.devicePixelRatio}) · Resources: <b style="color:${textMain};">${resCount}</b> · Scripts: <b style="color:${textMain};">${scriptCount}</b> · Images: <b style="color:${textMain};">${imgCount}</b></div>
                    </div>
                    <div style="display:flex;gap:8px;flex-wrap:wrap;">
                        <button id="uh-toggle-outlines" style="padding:8px 12px;border:1px solid ${outlinesOn ? "#457B9D" : borderCol};border-radius:8px;background:${outlinesOn ? "#457B9D" : bgCard};color:${outlinesOn ? "#fff" : textMain};font-weight:600;cursor:pointer;">CSS Outlines: ${outlinesOn ? "ON" : "OFF"}</button>
                        <button id="uh-toggle-source" style="padding:8px 12px;border:1px solid ${showSource ? "#457B9D" : borderCol};border-radius:8px;background:${showSource ? "#457B9D" : bgCard};color:${showSource ? "#fff" : textMain};font-weight:600;cursor:pointer;">${showSource ? "Hide Source" : "Page Source"}</button>
                        <button id="uh-copy-source" style="padding:8px 12px;border:1px solid ${borderCol};border-radius:8px;background:${bgCard};color:${textMain};cursor:pointer;">Copy HTML</button>
                    </div>
                    ${srcHtml}
                </div>`;

            const oBtn = rootEl.querySelector("#uh-toggle-outlines");
            if (oBtn) oBtn.onclick = toggleOutlines;
            const sBtn = rootEl.querySelector("#uh-toggle-source");
            if (sBtn) sBtn.onclick = () => { showSource = !showSource; render(); };
            const cBtn = rootEl.querySelector("#uh-copy-source");
            if (cBtn) cBtn.onclick = () => copyText(document.documentElement.outerHTML);
        }

        const ToolBase = window.eruda && window.eruda.Tool;
        if (ToolBase) {
            return new (ToolBase.extend({
                name: "Performance",
                init($el) {
                    this.callSuper(ToolBase, "init", arguments);
                    $container = $el;
                    rootEl = $el[0];
                    if (rootEl) {
                        rootEl.style.overflowY = "auto";
                        rootEl.style.webkitOverflowScrolling = "touch";
                    }
                },
                show() {
                    this.callSuper(ToolBase, "show", arguments);
                    isShown = true;
                    if (rootEl) rootEl.style.display = "block";
                    render();
                    if (!rafId) {
                        lastTs = performance.now();
                        frames = 0;
                        rafId = requestAnimationFrame(tick);
                    }
                    return this;
                },
                hide() {
                    isShown = false;
                    if (rafId) {
                        cancelAnimationFrame(rafId);
                        rafId = 0;
                    }
                    this.callSuper(ToolBase, "hide", arguments);
                    if (rootEl) rootEl.style.display = "none";
                    return this;
                },
                destroy() {
                    isShown = false;
                    if (rafId) {
                        cancelAnimationFrame(rafId);
                        rafId = 0;
                    }
                    const st = document.getElementById("__uh_outline_css");
                    if (st) st.remove();
                    this.callSuper(ToolBase, "destroy", arguments);
                }
            }))();
        }

        return {
            name: "Performance",
            init($el) {
                $container = $el;
                rootEl = $el[0];
                if (rootEl) {
                    rootEl.style.overflowY = "auto";
                    rootEl.style.webkitOverflowScrolling = "touch";
                }
            },
            show() {
                isShown = true;
                if ($container && $container.show) $container.show();
                if (rootEl) rootEl.style.display = "block";
                render();
                if (!rafId) {
                    lastTs = performance.now();
                    frames = 0;
                    rafId = requestAnimationFrame(tick);
                }
                return this;
            },
            hide() {
                isShown = false;
                if (rafId) {
                    cancelAnimationFrame(rafId);
                    rafId = 0;
                }
                if ($container && $container.hide) $container.hide();
                if (rootEl) rootEl.style.display = "none";
                return this;
            },
            destroy() {
                isShown = false;
                if (rafId) cancelAnimationFrame(rafId);
                const st = document.getElementById("__uh_outline_css");
                if (st) st.remove();
                if ($container && $container.remove) $container.remove();
            }
        };
    }

    // -------------------------------------------------------------------------
    // UH Top Header Bar (Title + Pick Element + Prominent Close Button)
    // -------------------------------------------------------------------------
    function applyUhBrandingAndHeader() {
        try {
            if (window.eruda && window.eruda._entryBtn) {
                window.eruda._entryBtn.hide();
                if (window.eruda._entryBtn._$el && window.eruda._entryBtn._$el[0]) {
                    window.eruda._entryBtn._$el[0].style.display = "none";
                }
            }
        } catch (_e) {}

        const root = getShadowOrRoot();
        if (!root) return;

        const hdrBg = isDarkTheme ? "#0F141C" : "#F1F5F9";
        const hdrBorder = isDarkTheme ? "#2A3138" : "#CBD5E1";
        const hdrText = isDarkTheme ? "#E6E9EE" : "#0F172A";
        const btnBg = isDarkTheme ? "#1C232E" : "#FFFFFF";

        let st = root.querySelector("#__uh_dt_brand_style");
        if (!st) {
            st = document.createElement("style");
            st.id = "__uh_dt_brand_style";
            root.appendChild(st);
        }
        st.textContent = `
            .eruda-entry-btn { display: none !important; visibility: hidden !important; pointer-events: none !important; }
            .eruda-dev-tools {
                padding-top: 78px !important;
                border-top: 1.5px solid #457B9D !important;
            }
            .eruda-dev-tools .eruda-nav-bar,
            .eruda-dev-tools .luna-tab {
                top: 38px !important;
            }
            .eruda-dev-tools .eruda-resizer {
                top: 0 !important;
                height: 6px !important;
                z-index: 200 !important;
            }
            .luna-tab-slider { background: #457B9D !important; }
            .luna-tab-item.luna-tab-selected { color: #457B9D !important; font-weight: 700 !important; }
            #__uh_dt_topbar {
                position: absolute;
                top: 0;
                left: 0;
                right: 0;
                height: 38px;
                z-index: 160;
                display: flex;
                align-items: center;
                justify-content: space-between;
                padding: 0 10px 0 12px;
                background: ${hdrBg};
                border-bottom: 1px solid ${hdrBorder};
                box-sizing: border-box;
                font-family: system-ui, -apple-system, sans-serif;
            }
            #__uh_dt_topbar .uh-dt-title {
                display: flex;
                align-items: center;
                gap: 7px;
                color: ${hdrText};
                font-size: 12.5px;
                font-weight: 700;
                letter-spacing: 0.2px;
            }
            #__uh_dt_topbar .uh-dt-dot {
                width: 8px;
                height: 8px;
                border-radius: 50%;
                background: #457B9D;
                display: inline-block;
            }
            #__uh_dt_topbar .uh-dt-controls {
                display: flex;
                align-items: center;
                gap: 6px;
            }
            #__uh_dt_topbar .uh-dt-btn {
                border: 1px solid ${hdrBorder};
                background: ${btnBg};
                color: ${hdrText};
                font: 600 11px/1 system-ui, -apple-system, sans-serif;
                padding: 5px 10px;
                border-radius: 7px;
                cursor: pointer;
                display: inline-flex;
                align-items: center;
                gap: 4px;
            }
            #__uh_dt_topbar .uh-dt-close {
                border: 1px solid ${hdrBorder};
                background: ${btnBg};
                color: ${hdrText};
                font: 700 13px/1 system-ui, -apple-system, sans-serif;
                width: 28px;
                height: 26px;
                border-radius: 7px;
                cursor: pointer;
                display: inline-flex;
                align-items: center;
                justify-content: center;
            }
        `;

        // Rename "Resources" -> "Storage" and capitalize clean UH labels in luna-tab
        const tabItems = root.querySelectorAll(".luna-tab-item");
        tabItems.forEach((item) => {
            const raw = (item.textContent || "").trim().toLowerCase();
            if (raw === "resources") item.textContent = "Storage";
            else if (raw === "console") item.textContent = "Console";
            else if (raw === "elements") item.textContent = "Elements";
            else if (raw === "network") item.textContent = "Network";
            else if (raw === "performance") item.textContent = "Performance";
        });

        // Mount dedicated UH DevTools header bar inside .eruda-dev-tools
        const devToolsEl = root.querySelector(".eruda-dev-tools");
        if (devToolsEl && !devToolsEl.querySelector("#__uh_dt_topbar")) {
            const bar = document.createElement("div");
            bar.id = "__uh_dt_topbar";
            bar.innerHTML = `
                <div class="uh-dt-title">
                    <span class="uh-dt-dot"></span>
                    <span>UH DevTools</span>
                </div>
                <div class="uh-dt-controls">
                    <button id="__uh_dt_pick" class="uh-dt-btn" title="Pick Element">◎ Pick</button>
                    <button id="__uh_dt_close" class="uh-dt-close" title="Close UH DevTools">✕</button>
                </div>`;
            devToolsEl.insertBefore(bar, devToolsEl.firstChild);

            const pickBtn = bar.querySelector("#__uh_dt_pick");
            if (pickBtn) pickBtn.onclick = () => startElementPicking();
            const closeBtn = bar.querySelector("#__uh_dt_close");
            if (closeBtn) closeBtn.onclick = () => closeDevToolsPanel();
        }
    }

    function bootUhDevTools(darkTheme) {
        isDarkTheme = darkTheme !== false;
        if (initialized && window.eruda) {
            applyUhBrandingAndHeader();
            return;
        }
        if (!window.eruda) return;

        window.eruda.init({
            useShadowDom: true,
            autoScale: true,
            tool: ["console", "elements", "resources"],
            defaults: {
                displaySize: 58,
                transparency: 0.99,
                theme: isDarkTheme ? "Dark" : "Light"
            }
        });

        // Add our properly subclassed Network & Performance tools
        try { window.eruda.add(createNetworkTool()); } catch (_e) {}
        try { window.eruda.add(createPerformanceTool()); } catch (_e) {}

        // Completely remove the Eruda Settings tab so zero blank tabs exist
        try { window.eruda.remove("settings"); } catch (_e) {}

        applyUhBrandingAndHeader();
        setTimeout(applyUhBrandingAndHeader, 80);

        initialized = true;
    }

    window.__uhToggleDevTools = function (darkTheme) {
        bootUhDevTools(darkTheme !== false);
        if (!window.eruda) return false;
        if (devToolsVisible) {
            closeDevToolsPanel();
        } else {
            window.eruda.show();
            devToolsVisible = true;
            applyUhBrandingAndHeader();
            notifyNativeState(true);
        }
        return devToolsVisible;
    };
})();
