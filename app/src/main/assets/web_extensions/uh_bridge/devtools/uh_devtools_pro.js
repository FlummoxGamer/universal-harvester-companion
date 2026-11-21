(function () {
    if (window.__uhDevToolsProInstalled) return;
    window.__uhDevToolsProInstalled = true;

    const MAX_NET_ENTRIES = 200;
    const netLog = [];
    let netListeners = [];
    let devToolsVisible = false;
    let initialized = false;

    function notifyNet() {
        for (const fn of netListeners) {
            try { fn(); } catch (_e) {}
        }
    }

    // Intercept fetch & XHR early once DevTools boots for NetPro Waterfall + cURL + Replay
    function installNetworkHooks() {
        if (window.__uhNetProHooked) return;
        window.__uhNetProHooked = true;

        const origFetch = window.fetch;
        if (typeof origFetch === "function") {
            window.fetch = function (input, init) {
                const start = performance.now();
                const reqUrl = typeof input === "string" ? input : (input && input.url ? input.url : String(input));
                const method = ((init && init.method) || (input && input.method) || "GET").toUpperCase();
                const reqHeaders = {};
                try {
                    const hdrs = (init && init.headers) || (input && input.headers);
                    if (hdrs instanceof Headers) {
                        hdrs.forEach((v, k) => { reqHeaders[k] = v; });
                    } else if (hdrs && typeof hdrs === "object") {
                        Object.assign(reqHeaders, hdrs);
                    }
                } catch (_e) {}
                const reqBody = init && typeof init.body === "string" ? init.body.slice(0, 4000) : "";

                const entry = {
                    id: Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
                    kind: "fetch",
                    method,
                    url: reqUrl,
                    status: 0,
                    statusText: "Pending",
                    durationMs: 0,
                    sizeBytes: 0,
                    contentType: "",
                    reqHeaders,
                    reqBody,
                    resHeaders: {},
                    resBody: "",
                    startedAt: new Date().toLocaleTimeString(),
                    error: null
                };
                netLog.unshift(entry);
                if (netLog.length > MAX_NET_ENTRIES) netLog.pop();
                notifyNet();

                return origFetch.apply(this, arguments).then((resp) => {
                    entry.status = resp.status;
                    entry.statusText = resp.statusText || String(resp.status);
                    entry.durationMs = Math.round(performance.now() - start);
                    entry.contentType = resp.headers.get("content-type") || "";
                    resp.headers.forEach((v, k) => { entry.resHeaders[k] = v; });
                    try {
                        resp.clone().text().then((txt) => {
                            entry.sizeBytes = txt.length;
                            entry.resBody = txt.slice(0, 12000);
                            notifyNet();
                        }).catch(() => {});
                    } catch (_e) {}
                    notifyNet();
                    return resp;
                }).catch((err) => {
                    entry.status = -1;
                    entry.statusText = "ERR";
                    entry.durationMs = Math.round(performance.now() - start);
                    entry.error = String(err && err.message ? err.message : err);
                    notifyNet();
                    throw err;
                });
            };
        }

        const origOpen = XMLHttpRequest.prototype.open;
        const origSend = XMLHttpRequest.prototype.send;
        const origSetHeader = XMLHttpRequest.prototype.setRequestHeader;
        XMLHttpRequest.prototype.open = function (method, url) {
            this.__uhMeta = {
                method: String(method || "GET").toUpperCase(),
                url: String(url || ""),
                reqHeaders: {}
            };
            return origOpen.apply(this, arguments);
        };
        XMLHttpRequest.prototype.setRequestHeader = function (k, v) {
            if (this.__uhMeta) this.__uhMeta.reqHeaders[k] = v;
            return origSetHeader.apply(this, arguments);
        };
        XMLHttpRequest.prototype.send = function (body) {
            const meta = this.__uhMeta || { method: "GET", url: "", reqHeaders: {} };
            const start = performance.now();
            const entry = {
                id: Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
                kind: "xhr",
                method: meta.method,
                url: meta.url,
                status: 0,
                statusText: "Pending",
                durationMs: 0,
                sizeBytes: 0,
                contentType: "",
                reqHeaders: meta.reqHeaders,
                reqBody: typeof body === "string" ? body.slice(0, 4000) : "",
                resHeaders: {},
                resBody: "",
                startedAt: new Date().toLocaleTimeString(),
                error: null
            };
            netLog.unshift(entry);
            if (netLog.length > MAX_NET_ENTRIES) netLog.pop();
            notifyNet();

            this.addEventListener("loadend", () => {
                entry.status = this.status;
                entry.statusText = this.statusText || String(this.status);
                entry.durationMs = Math.round(performance.now() - start);
                try {
                    const rawHdrs = this.getAllResponseHeaders() || "";
                    rawHdrs.trim().split(/[\r\n]+/).forEach((line) => {
                        const idx = line.indexOf(":");
                        if (idx > 0) entry.resHeaders[line.slice(0, idx).trim()] = line.slice(idx + 1).trim();
                    });
                    entry.contentType = entry.resHeaders["content-type"] || "";
                    if (this.responseType === "" || this.responseType === "text") {
                        const txt = String(this.responseText || "");
                        entry.sizeBytes = txt.length;
                        entry.resBody = txt.slice(0, 12000);
                    }
                } catch (_e) {}
                notifyNet();
            });
            return origSend.apply(this, arguments);
        };
    }

    function escapeHtml(str) {
        return String(str || "")
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;");
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
        const parts = ["curl", "-X", entry.method, "'" + entry.url.replace(/'/g, "'\\''") + "'"];
        for (const [k, v] of Object.entries(entry.reqHeaders || {})) {
            parts.push("-H", "'" + k + ": " + String(v).replace(/'/g, "'\\''") + "'");
        }
        if (entry.reqBody) {
            parts.push("--data-raw", "'" + entry.reqBody.replace(/'/g, "'\\''") + "'");
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

    // 1. Live Box-Model Element Inspector Plugin (Chrome/Firefox DevTools style)
    function createInspectorTool(eruda) {
        let overlay = null;
        let picking = false;
        let selectedEl = null;
        let rootEl = null;

        function removeOverlay() {
            if (overlay) {
                overlay.remove();
                overlay = null;
            }
        }

        function drawHighlight(el) {
            removeOverlay();
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

            overlay = document.createElement("div");
            overlay.id = "__uh_inspector_overlay";
            overlay.style.cssText =
                "position:fixed;pointer-events:none;z-index:2147483645;" +
                `left:${rect.left - ml}px;top:${rect.top - mt}px;` +
                `width:${rect.width + ml + mr}px;height:${rect.height + mt + mb}px;` +
                "background:rgba(246,178,107,0.35);box-sizing:border-box;";

            const borderBox = document.createElement("div");
            borderBox.style.cssText =
                `position:absolute;left:${ml}px;top:${mt}px;width:${rect.width}px;height:${rect.height}px;` +
                "background:rgba(147,196,125,0.38);box-sizing:border-box;outline:1.5px solid #457B9D;";

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
                "border:1px solid #457B9D;box-shadow:0 2px 8px rgba(0,0,0,0.4);";

            borderBox.appendChild(contentBox);
            borderBox.appendChild(tip);
            overlay.appendChild(borderBox);
            document.documentElement.appendChild(overlay);
        }

        function onPickTap(e) {
            if (!picking) return;
            const target = e.target;
            if (!target || (target.closest && target.closest("#eruda"))) return;
            e.preventDefault();
            e.stopPropagation();
            picking = false;
            document.removeEventListener("click", onPickTap, true);
            selectedEl = target;
            drawHighlight(target);
            eruda.show();
            render();
        }

        function startPicking() {
            picking = true;
            eruda.hide();
            document.addEventListener("click", onPickTap, true);
        }

        function render() {
            if (!rootEl) return;
            const el = selectedEl;
            if (!el) {
                rootEl.innerHTML = `
                    <div style="padding:14px;font-family:system-ui,sans-serif;color:#E6E9EE;">
                        <button id="uh-pick-btn" style="width:100%;padding:12px;border:none;border-radius:10px;background:#457B9D;color:#fff;font-weight:700;font-size:13px;cursor:pointer;">
                            Tap to Pick Any Element on Page
                        </button>
                        <p style="margin-top:10px;font-size:12px;color:#8A96A8;line-height:1.5;">
                            Visual Box-Model Inspector overlays Margin (amber), Padding (green), and Content (blue) dimensions, lets you live-edit inline CSS, copy selectors, or inspect attributes.
                        </p>
                    </div>`;
            } else {
                const rect = el.getBoundingClientRect();
                const cs = getComputedStyle(el);
                const sel = buildCssSelector(el);
                const attrs = Array.from(el.attributes || [])
                    .map((a) => `<div style="font-family:monospace;font-size:11px;margin:2px 0;"><span style="color:#7DD3FC;">${escapeHtml(a.name)}</span>=<span style="color:#FDE68A;">"${escapeHtml(a.value)}"</span></div>`)
                    .join("") || "<span style='color:#8A96A8;font-size:11px;'>None</span>";

                rootEl.innerHTML = `
                    <div style="padding:12px;font-family:system-ui,sans-serif;color:#E6E9EE;font-size:12px;">
                        <div style="display:flex;gap:8px;margin-bottom:10px;flex-wrap:wrap;">
                            <button id="uh-pick-btn" style="flex:1;padding:9px;border:none;border-radius:8px;background:#457B9D;color:#fff;font-weight:700;cursor:pointer;">Pick Another</button>
                            <button id="uh-copy-sel" style="padding:9px 11px;border:1px solid #2A3138;border-radius:8px;background:#171B21;color:#E6E9EE;cursor:pointer;">Copy Selector</button>
                            <button id="uh-copy-html" style="padding:9px 11px;border:1px solid #2A3138;border-radius:8px;background:#171B21;color:#E6E9EE;cursor:pointer;">Copy HTML</button>
                            <button id="uh-hide-el" style="padding:9px 11px;border:1px solid #2A3138;border-radius:8px;background:#171B21;color:#F87171;cursor:pointer;">Hide</button>
                            <button id="uh-clear-ov" style="padding:9px 11px;border:1px solid #2A3138;border-radius:8px;background:#171B21;color:#8A96A8;cursor:pointer;">Clear Box</button>
                        </div>
                        <div style="background:#171B21;border-radius:10px;padding:10px;margin-bottom:10px;">
                            <div style="font-family:monospace;color:#7DD3FC;word-break:break-all;margin-bottom:4px;">${escapeHtml(sel)}</div>
                            <div style="color:#8A96A8;font-size:11px;">
                                Size: <b style="color:#E6E9EE;">${Math.round(rect.width)}×${Math.round(rect.height)}px</b> ·
                                Display: <b style="color:#E6E9EE;">${escapeHtml(cs.display)}</b> ·
                                Position: <b style="color:#E6E9EE;">${escapeHtml(cs.position)}</b> ·
                                Font: <b style="color:#E6E9EE;">${escapeHtml(cs.fontSize)}</b>
                            </div>
                        </div>
                        <div style="background:#171B21;border-radius:10px;padding:10px;margin-bottom:10px;">
                            <div style="font-weight:600;margin-bottom:6px;">Live Inline CSS</div>
                            <textarea id="uh-style-edit" style="width:100%;min-height:54px;background:#0A0F1A;color:#E6E9EE;border:1px solid #2A3138;border-radius:8px;padding:8px;font-family:monospace;font-size:11px;box-sizing:border-box;">${escapeHtml(el.getAttribute("style") || "")}</textarea>
                            <button id="uh-apply-css" style="margin-top:6px;padding:6px 12px;border:none;border-radius:6px;background:#457B9D;color:#fff;font-weight:600;cursor:pointer;">Apply Style</button>
                        </div>
                        <div style="background:#171B21;border-radius:10px;padding:10px;">
                            <div style="font-weight:600;margin-bottom:4px;">Attributes</div>
                            ${attrs}
                        </div>
                    </div>`;
            }

            const pickBtn = rootEl.querySelector("#uh-pick-btn");
            if (pickBtn) pickBtn.onclick = startPicking;
            const copySelBtn = rootEl.querySelector("#uh-copy-sel");
            if (copySelBtn && el) copySelBtn.onclick = () => copyText(buildCssSelector(el));
            const copyHtmlBtn = rootEl.querySelector("#uh-copy-html");
            if (copyHtmlBtn && el) copyHtmlBtn.onclick = () => copyText(el.outerHTML);
            const hideBtn = rootEl.querySelector("#uh-hide-el");
            if (hideBtn && el) hideBtn.onclick = () => { el.style.display = "none"; removeOverlay(); };
            const clearBtn = rootEl.querySelector("#uh-clear-ov");
            if (clearBtn) clearBtn.onclick = removeOverlay;
            const applyBtn = rootEl.querySelector("#uh-apply-css");
            if (applyBtn && el) {
                applyBtn.onclick = () => {
                    const ta = rootEl.querySelector("#uh-style-edit");
                    if (ta) {
                        el.setAttribute("style", ta.value);
                        drawHighlight(el);
                    }
                };
            }
        }

        return {
            name: "Inspector",
            init($el) {
                rootEl = $el[0];
                render();
            },
            show() {
                render();
            },
            hide() {},
            destroy() {
                removeOverlay();
                document.removeEventListener("click", onPickTap, true);
            }
        };
    }

    // 2. Enhanced Network Pro Waterfall + Copy as cURL + Replay
    function createNetProTool() {
        let rootEl = null;
        let filterText = "";
        let filterKind = "all";
        let selectedId = null;

        function render() {
            if (!rootEl) return;
            const filtered = netLog.filter((e) => {
                if (filterKind === "err" && e.status >= 200 && e.status < 400) return false;
                if (filterKind === "fetch" && e.kind !== "fetch") return false;
                if (filterKind === "xhr" && e.kind !== "xhr") return false;
                if (filterText && !e.url.toLowerCase().includes(filterText.toLowerCase())) return false;
                return true;
            });
            const sel = filtered.find((x) => x.id === selectedId) || null;
            const rows = filtered.slice(0, 60).map((e) => {
                const statusColor = e.status >= 200 && e.status < 300 ? "#4ADE80" : (e.status >= 400 || e.status < 0 ? "#F87171" : "#FBBF24");
                const shortUrl = e.url.replace(/^https?:\/\/[^/]+/, "") || e.url;
                const barW = Math.min(100, Math.max(6, Math.round((e.durationMs / 1000) * 100)));
                return `
                    <div data-id="${e.id}" class="uh-net-row" style="padding:8px 10px;border-bottom:1px solid #2A3138;cursor:pointer;background:${e.id === selectedId ? "#1E2329" : "transparent"};">
                        <div style="display:flex;align-items:center;gap:6px;font-family:monospace;font-size:11px;">
                            <span style="color:${statusColor};font-weight:700;min-width:28px;">${e.status || "..."}</span>
                            <span style="color:#7DD3FC;font-weight:600;">${escapeHtml(e.method)}</span>
                            <span style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#E6E9EE;">${escapeHtml(shortUrl)}</span>
                            <span style="color:#8A96A8;">${e.durationMs}ms</span>
                        </div>
                        <div style="margin-top:4px;height:3px;background:#171B21;border-radius:2px;overflow:hidden;">
                            <div style="width:${barW}%;height:100%;background:${statusColor};"></div>
                        </div>
                    </div>`;
            }).join("") || "<div style='padding:20px;text-align:center;color:#8A96A8;font-size:12px;'>No captured requests yet. Interact with the page to capture live fetch/XHR traffic.</div>";

            let detailHtml = "";
            if (sel) {
                let prettyBody = sel.resBody || "(empty)";
                try {
                    if (sel.resBody && (sel.contentType.includes("json") || sel.resBody.trim().startsWith("{") || sel.resBody.trim().startsWith("["))) {
                        prettyBody = JSON.stringify(JSON.parse(sel.resBody), null, 2);
                    }
                } catch (_e) {}
                detailHtml = `
                    <div style="padding:10px;background:#171B21;border-top:2px solid #457B9D;font-size:11px;">
                        <div style="display:flex;gap:8px;margin-bottom:8px;">
                            <button id="uh-net-curl" style="padding:6px 10px;border:none;border-radius:6px;background:#457B9D;color:#fff;font-weight:600;cursor:pointer;">Copy cURL</button>
                            <button id="uh-net-replay" style="padding:6px 10px;border:1px solid #2A3138;border-radius:6px;background:#0A0F1A;color:#E6E9EE;cursor:pointer;">Replay</button>
                            <button id="uh-net-close-det" style="margin-left:auto;padding:6px 10px;border:none;background:transparent;color:#8A96A8;cursor:pointer;">Close</button>
                        </div>
                        <div style="font-family:monospace;color:#7DD3FC;word-break:break-all;margin-bottom:6px;">${escapeHtml(sel.url)}</div>
                        <div style="color:#8A96A8;margin-bottom:6px;">Type: ${escapeHtml(sel.contentType || "unknown")} · Size: ${sel.sizeBytes} B · ${sel.durationMs}ms</div>
                        <pre style="max-height:180px;overflow:auto;background:#0A0F1A;padding:8px;border-radius:8px;color:#E6E9EE;font-size:10px;white-space:pre-wrap;word-break:break-word;">${escapeHtml(prettyBody.slice(0, 6000))}</pre>
                    </div>`;
            }

            rootEl.innerHTML = `
                <div style="font-family:system-ui,sans-serif;color:#E6E9EE;">
                    <div style="display:flex;gap:6px;padding:8px 10px;background:#171B21;border-bottom:1px solid #2A3138;align-items:center;">
                        <input id="uh-net-filter" placeholder="Filter URL…" value="${escapeHtml(filterText)}" style="flex:1;padding:6px 8px;border-radius:6px;border:1px solid #2A3138;background:#0A0F1A;color:#E6E9EE;font-size:12px;" />
                        <select id="uh-net-kind" style="padding:6px;border-radius:6px;border:1px solid #2A3138;background:#0A0F1A;color:#E6E9EE;font-size:11px;">
                            <option value="all" ${filterKind === "all" ? "selected" : ""}>All</option>
                            <option value="fetch" ${filterKind === "fetch" ? "selected" : ""}>Fetch</option>
                            <option value="xhr" ${filterKind === "xhr" ? "selected" : ""}>XHR</option>
                            <option value="err" ${filterKind === "err" ? "selected" : ""}>Errors</option>
                        </select>
                        <button id="uh-net-clear" style="padding:6px 8px;border:1px solid #2A3138;border-radius:6px;background:#0A0F1A;color:#F87171;font-size:11px;cursor:pointer;">Clear</button>
                    </div>
                    <div>${rows}</div>
                    ${detailHtml}
                </div>`;

            const fIn = rootEl.querySelector("#uh-net-filter");
            if (fIn) fIn.oninput = (ev) => { filterText = ev.target.value; render(); };
            const kSel = rootEl.querySelector("#uh-net-kind");
            if (kSel) kSel.onchange = (ev) => { filterKind = ev.target.value; render(); };
            const clr = rootEl.querySelector("#uh-net-clear");
            if (clr) clr.onclick = () => { netLog.length = 0; selectedId = null; render(); };
            rootEl.querySelectorAll(".uh-net-row").forEach((row) => {
                row.onclick = () => { selectedId = row.getAttribute("data-id"); render(); };
            });
            const curlBtn = rootEl.querySelector("#uh-net-curl");
            if (curlBtn && sel) curlBtn.onclick = () => copyText(buildCurl(sel));
            const repBtn = rootEl.querySelector("#uh-net-replay");
            if (repBtn && sel) {
                repBtn.onclick = () => {
                    fetch(sel.url, {
                        method: sel.method,
                        headers: sel.reqHeaders,
                        body: sel.method === "GET" || sel.method === "HEAD" ? undefined : sel.reqBody
                    }).catch(() => {});
                };
            }
            const closeDet = rootEl.querySelector("#uh-net-close-det");
            if (closeDet) closeDet.onclick = () => { selectedId = null; render(); };
        }

        return {
            name: "NetPro",
            init($el) {
                rootEl = $el[0];
                netListeners.push(render);
                render();
            },
            show() { render(); },
            hide() {},
            destroy() {
                netListeners = netListeners.filter((x) => x !== render);
            }
        };
    }

    // 3. Web Vitals, FPS, CSS Outlines, Page Source Inspector
    function createVitalsTool() {
        let rootEl = null;
        let fps = 60;
        let frames = 0;
        let lastTs = performance.now();
        let rafId = 0;
        let outlinesOn = false;
        let showSource = false;

        function tick(now) {
            frames++;
            if (now - lastTs >= 1000) {
                fps = Math.round((frames * 1000) / (now - lastTs));
                frames = 0;
                lastTs = now;
                const el = rootEl && rootEl.querySelector("#uh-fps-val");
                if (el) el.textContent = fps + " FPS";
            }
            rafId = requestAnimationFrame(tick);
        }

        function toggleOutlines() {
            outlinesOn = !outlinesOn;
            let st = document.getElementById("__uh_outline_css");
            if (outlinesOn) {
                if (!st) {
                    st = document.createElement("style");
                    st.id = "__uh_outline_css";
                    st.textContent = "* { outline: 1px solid rgba(69, 123, 157, 0.55) !important; }";
                    document.head.appendChild(st);
                }
            } else if (st) {
                st.remove();
            }
            render();
        }

        function render() {
            if (!rootEl) return;
            const nav = performance.getEntriesByType("navigation")[0];
            const ttfb = nav ? Math.round(nav.responseStart - nav.requestStart) : 0;
            const domReady = nav ? Math.round(nav.domContentLoadedEventEnd) : 0;
            const loadDone = nav ? Math.round(nav.loadEventEnd) : 0;
            const paints = performance.getEntriesByType("paint");
            const fcpObj = paints.find((p) => p.name === "first-contentful-paint");
            const fcp = fcpObj ? Math.round(fcpObj.startTime) : 0;
            const nodeCount = document.getElementsByTagName("*").length;
            const scriptCount = document.scripts ? document.scripts.length : 0;
            const imgCount = document.images ? document.images.length : 0;
            const srcHtml = showSource
                ? `<pre style="margin-top:10px;max-height:240px;overflow:auto;background:#0A0F1A;padding:10px;border-radius:10px;color:#E6E9EE;font-size:10px;white-space:pre-wrap;word-break:break-word;">${escapeHtml(document.documentElement.outerHTML.slice(0, 25000))}</pre>`
                : "";

            rootEl.innerHTML = `
                <div style="padding:12px;font-family:system-ui,sans-serif;color:#E6E9EE;font-size:12px;">
                    <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:12px;">
                        <div style="background:#171B21;padding:10px;border-radius:10px;"><div style="color:#8A96A8;font-size:10px;">Live Frame Rate</div><div id="uh-fps-val" style="font-size:18px;font-weight:700;color:#4ADE80;">${fps} FPS</div></div>
                        <div style="background:#171B21;padding:10px;border-radius:10px;"><div style="color:#8A96A8;font-size:10px;">DOM Nodes</div><div style="font-size:18px;font-weight:700;color:#7DD3FC;">${nodeCount}</div></div>
                        <div style="background:#171B21;padding:10px;border-radius:10px;"><div style="color:#8A96A8;font-size:10px;">TTFB / FCP</div><div style="font-size:14px;font-weight:700;">${ttfb}ms / ${fcp}ms</div></div>
                        <div style="background:#171B21;padding:10px;border-radius:10px;"><div style="color:#8A96A8;font-size:10px;">DOM / Load</div><div style="font-size:14px;font-weight:700;">${domReady}ms / ${loadDone}ms</div></div>
                    </div>
                    <div style="background:#171B21;padding:10px;border-radius:10px;margin-bottom:10px;color:#8A96A8;font-size:11px;">
                        Viewport: <b style="color:#E6E9EE;">${window.innerWidth}×${window.innerHeight}</b> (DPR ${window.devicePixelRatio}) ·
                        Scripts: <b style="color:#E6E9EE;">${scriptCount}</b> ·
                        Images: <b style="color:#E6E9EE;">${imgCount}</b>
                    </div>
                    <div style="display:flex;gap:8px;flex-wrap:wrap;">
                        <button id="uh-toggle-outlines" style="padding:8px 12px;border:none;border-radius:8px;background:${outlinesOn ? "#457B9D" : "#171B21"};color:#E6E9EE;font-weight:600;cursor:pointer;">CSS Outlines: ${outlinesOn ? "ON" : "OFF"}</button>
                        <button id="uh-toggle-source" style="padding:8px 12px;border:none;border-radius:8px;background:${showSource ? "#457B9D" : "#171B21"};color:#E6E9EE;font-weight:600;cursor:pointer;">${showSource ? "Hide Source" : "Page Source"}</button>
                        <button id="uh-copy-source" style="padding:8px 12px;border:1px solid #2A3138;border-radius:8px;background:#0A0F1A;color:#E6E9EE;cursor:pointer;">Copy HTML</button>
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

        return {
            name: "Vitals",
            init($el) {
                rootEl = $el[0];
                render();
                rafId = requestAnimationFrame(tick);
            },
            show() { render(); },
            hide() {},
            destroy() {
                if (rafId) cancelAnimationFrame(rafId);
                const st = document.getElementById("__uh_outline_css");
                if (st) st.remove();
            }
        };
    }

    function bootEruda(darkTheme) {
        if (initialized && window.eruda) return;
        if (!window.eruda) return;
        installNetworkHooks();
        window.eruda.init({
            useShadowDom: true,
            autoScale: true,
            defaults: {
                displaySize: 55,
                transparency: 0.98,
                theme: darkTheme ? "Dark" : "Light"
            }
        });
        try { if (window.erudaDom) window.eruda.add(window.erudaDom); } catch (_e) {}
        try { if (window.erudaTiming) window.eruda.add(window.erudaTiming); } catch (_e) {}
        try { if (window.erudaCode) window.eruda.add(window.erudaCode); } catch (_e) {}
        try { if (window.erudaFeatures) window.eruda.add(window.erudaFeatures); } catch (_e) {}
        try { window.eruda.add(createInspectorTool(window.eruda)); } catch (_e) {}
        try { window.eruda.add(createNetProTool()); } catch (_e) {}
        try { window.eruda.add(createVitalsTool()); } catch (_e) {}
        initialized = true;
    }

    window.__uhToggleDevTools = function (darkTheme) {
        bootEruda(darkTheme !== false);
        if (!window.eruda) return false;
        if (devToolsVisible) {
            window.eruda.hide();
            devToolsVisible = false;
        } else {
            window.eruda.show();
            devToolsVisible = true;
        }
        return devToolsVisible;
    };
})();
