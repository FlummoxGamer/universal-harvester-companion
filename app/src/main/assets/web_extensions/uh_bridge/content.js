const api = typeof browser !== "undefined" ? browser : chrome;

const isTopFrame = (function () {
    try { return window.self === window.top; } catch (_e) { return true; }
})();

const pendingPackets = [];
let injectReady = false;

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

window.addEventListener("message", (event) => {
    if (!event.data) return;
    if (event.data.type === "UH_INJECT_READY") {
        injectReady = true;
        api.runtime.sendMessage({
            type: "UH_GET_SCRIPTS",
            url: window.location.href,
            isTop: isTopFrame
        }).then((response) => {
            if (!response) return;
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
        }).catch(() => {});
    }
});

api.runtime.onMessage.addListener((msg) => {
    if (!msg || !msg.action) return;
    if (msg.action === "RUN_MENU_COMMAND" && msg.commandId) {
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
    } else if (msg.action === "TRANSLATE_PAGE") {
        window.postMessage({ type: "UH_TRANSLATE_PAGE", targetLang: msg.targetLang || "en" }, "*");
    }
});

const injectScript = document.createElement("script");
injectScript.src = api.runtime.getURL("inject.js");
(document.head || document.documentElement).appendChild(injectScript);
injectScript.remove();
