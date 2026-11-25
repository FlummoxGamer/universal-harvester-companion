// -----------------------------------------------------------------------------
// UH Bridge Content Module 5: Userscripts, DevTools & Runtime Message Router
// -----------------------------------------------------------------------------
(function (C) {
    const api = C.api;
    const isTopFrame = C.isTopFrame;

    const pendingPackets = [];
    let devToolsScriptsLoaded = false;

    function dispatchRunPacket(packet) {
        if (!packet) return;
        const targetRunAt = packet.runAt || "document-start";
        const runNow = () => {
            window.postMessage({ type: "UH_RUN_PACKET", packet: packet }, "*");
        };
        if (targetRunAt === "document-start") {
            runNow();
        } else if (targetRunAt === "document-body") {
            if (document.body) {
                runNow();
            } else {
                const obs = new MutationObserver(() => {
                    if (document.body) {
                        obs.disconnect();
                        runNow();
                    }
                });
                obs.observe(document.documentElement || document, { childList: true, subtree: true });
            }
        } else if (targetRunAt === "document-end") {
            if (document.readyState === "interactive" || document.readyState === "complete") {
                runNow();
            } else {
                document.addEventListener("DOMContentLoaded", runNow, { once: true });
            }
        } else {
            if (document.readyState === "complete") {
                setTimeout(runNow, 0);
            } else {
                window.addEventListener("load", () => setTimeout(runNow, 0), { once: true });
            }
        }
    }

    function loadPageScript(relPath) {
        return new Promise((resolve) => {
            try {
                const s = document.createElement("script");
                s.src = api.runtime.getURL(relPath);
                s.onload = () => { s.remove(); resolve(true); };
                s.onerror = () => { s.remove(); resolve(false); };
                (document.head || document.documentElement).appendChild(s);
            } catch (_e) {
                resolve(false);
            }
        });
    }

    async function ensureAndToggleDevTools(darkTheme) {
        if (!isTopFrame) return;
        if (!devToolsScriptsLoaded) {
            const files = [
                "devtools/eruda.min.js",
                "devtools/uh_devtools_pro.js"
            ];
            for (const f of files) {
                await loadPageScript(f);
            }
            devToolsScriptsLoaded = true;
        }
        window.postMessage({ type: "UH_TOGGLE_DEVTOOLS", darkTheme: darkTheme !== false }, "*");
    }

    window.addEventListener("message", (event) => {
        if (!event.data) return;
        if (event.data.type === "UH_INJECT_READY") {
            api.runtime.sendMessage({
                type: "UH_GET_SCRIPTS",
                url: window.location.href,
                isTop: isTopFrame
            }).then((response) => {
                if (!response) return;
                if (isTopFrame && typeof response.globalZoomPercent === "number" && response.globalZoomPercent !== 100) {
                    window.postMessage({ type: "UH_APPLY_GLOBAL_ZOOM", percent: response.globalZoomPercent }, "*");
                }
                if (response.bootPayload) {
                    window.postMessage({ type: "UH_BOOT_DATA", bootPayload: response.bootPayload }, "*");
                }
                if (Array.isArray(response.packets)) {
                    for (const p of response.packets) {
                        dispatchRunPacket(p);
                    }
                } else if (response.scripts) {
                    window.postMessage({ type: "UH_RUN_SCRIPTS", scripts: response.scripts }, "*");
                }
                while (pendingPackets.length > 0) {
                    dispatchRunPacket(pendingPackets.shift());
                }
            }).catch(() => {});
        } else if (event.data.type === "UH_NATIVE_CALL") {
            api.runtime.sendMessage({
                type: "UH_NATIVE_CALL",
                pageUrl: window.location.href,
                payload: event.data.payload
            }).then((response) => {
                if (event.data.payload && event.data.payload.id) {
                    window.postMessage({
                        type: "UH_NATIVE_RESPONSE",
                        id: event.data.payload.id,
                        response: response
                    }, "*");
                }
            }).catch(() => {
                if (event.data.payload && event.data.payload.id) {
                    window.postMessage({
                        type: "UH_NATIVE_RESPONSE",
                        id: event.data.payload.id,
                        response: null
                    }, "*");
                }
            });
        }
    });

    api.runtime.onMessage.addListener((msg) => {
        if (!msg || !msg.action) return;
        if (msg.action === "COLLAPSE_BLOCKED_URL" && msg.url) {
            C.blockedUrls.add(msg.url);
            C.blockedUrls.add(msg.url.split("#")[0]);
            try {
                const u = new URL(msg.url);
                if (u.pathname && u.pathname.length > 6) {
                    C.blockedPathHints.add(u.pathname + (u.search || ""));
                }
            } catch (_e) {}
            C.scheduleCollapseSweep();
        } else if (msg.action === "SET_PIP_ISOLATION") {
            C.setPipVideoIsolation(msg.enabled === true);
        } else if (msg.action === "MEDIA_CONTROL_ACTION") {
            C.handleMediaControlAction(msg);
        } else if (msg.action === "RUN_MENU_COMMAND" && msg.commandId) {
            window.postMessage({ type: "UH_RUN_MENU_COMMAND", commandId: msg.commandId }, "*");
        } else if (msg.action === "RELOAD_SCRIPTS") {
            api.runtime.sendMessage({
                type: "UH_GET_SCRIPTS",
                url: window.location.href,
                isTop: isTopFrame
            }).then((response) => {
                if (response && Array.isArray(response.packets)) {
                    for (const p of response.packets) dispatchRunPacket(p);
                }
            }).catch(() => {});
        } else if (msg.action === "TOGGLE_DEVTOOLS") {
            ensureAndToggleDevTools(msg.darkTheme !== false);
        } else if (msg.action === "APPLY_GLOBAL_ZOOM" && isTopFrame) {
            window.postMessage({ type: "UH_APPLY_GLOBAL_ZOOM", percent: msg.percent || 100 }, "*");
        } else if (msg.action === "TRANSLATE_IMAGES" && isTopFrame) {
            C.runImageOcrTranslation(msg.fromLang || "auto", msg.toLang || "en");
        } else if (msg.action === "CLEAR_IMAGE_TRANSLATIONS" && isTopFrame) {
            C.clearImageTranslationOverlays();
        } else if (msg.action === "START_ELEMENT_PICKER" && isTopFrame) {
            if (C.startElementPicker) C.startElementPicker();
        } else if (msg.action === "APPLY_LIVE_PAGE_CONFIG" && isTopFrame) {
            const host = C.currentNormalizedHost();
            const paused = Array.isArray(msg.pausedHosts) && msg.pausedHosts.includes(host);
            const darkBypassed = Array.isArray(msg.darkBypassHosts) && msg.darkBypassHosts.includes(host);
            const merged = Object.assign({}, C.pageProtectState || {}, {
                shieldLevel: msg.shieldLevel,
                paused: paused,
                cookieBanners: msg.cookieBanners,
                annoyances: msg.annoyances !== false && !paused,
                blockPopups: msg.blockPopups,
                antiFingerprint: msg.antiFingerprint === true && !paused,
                strictFingerprint: msg.strictFingerprint === true && msg.antiFingerprint === true && !paused,
                dnt: msg.dnt === true,
                fpSessionSalt: msg.fpSessionSalt || (C.pageProtectState && C.pageProtectState.fpSessionSalt) || "",
                backgroundPlay: msg.backgroundPlay,
                webrtcPolicy: msg.webrtcPolicy,
                forceDarkWeb: msg.forceDarkWeb,
                enhanceDark: msg.enhanceDark,
                darkBypassed: darkBypassed
            });
            C.applyPageProtectConfig(merged);
        }
    });

    const injectScript = document.createElement("script");
    injectScript.src = api.runtime.getURL("inject.js");
    (document.head || document.documentElement).appendChild(injectScript);
    injectScript.remove();
})(globalThis.__uhCs);
