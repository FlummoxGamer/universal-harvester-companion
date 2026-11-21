const api = typeof browser !== "undefined" ? browser : chrome;

let nativePort = null;

function reportDeliveryFailure(msg) {
    try {
        if (msg.action === "FAST_TRANSLATE_PAGE") {
            api.runtime.sendNativeMessage("uhApp", {
                type: "FAST_TRANSLATE_DONE",
                success: false,
                count: 0,
                durationMs: 0,
                fromLang: msg.fromLang || "auto",
                toLang: msg.toLang || "en",
                pageUrl: "",
                error: "This page cannot be translated."
            }).catch(() => {});
        } else if (msg.action === "TRANSLATE_IMAGES") {
            api.runtime.sendNativeMessage("uhApp", {
                type: "IMAGE_TRANSLATE_DONE",
                imageCount: 0,
                overlayCount: 0,
                pageUrl: ""
            }).catch(() => {});
        }
    } catch (_e) {}
}

function ensureNativePort() {
    if (nativePort) return nativePort;
    try {
        nativePort = api.runtime.connectNative("uhApp");
        nativePort.onMessage.addListener((msg) => {
            if (!msg || !msg.action) return;
            const query = msg.action === "APPLY_GLOBAL_ZOOM" ? {} : { active: true };
            api.tabs.query(query).then((tabs) => {
                if (!tabs || !tabs.length) {
                    reportDeliveryFailure(msg);
                    return;
                }
                const sendPromises = tabs.map((t) =>
                    api.tabs.sendMessage(t.id, msg).then(() => true).catch(() => false)
                );
                Promise.all(sendPromises).then((results) => {
                    if (!results.some(Boolean)) {
                        reportDeliveryFailure(msg);
                    }
                });
            }).catch(() => {
                reportDeliveryFailure(msg);
            });
        });
        nativePort.onDisconnect.addListener(() => {
            nativePort = null;
        });
    } catch (_e) {
        nativePort = null;
    }
    return nativePort;
}

ensureNativePort();

api.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (!message) return false;
    ensureNativePort();
    if (message.type === "UH_GET_SCRIPTS") {
        api.runtime.sendNativeMessage("uhApp", {
            type: "GET_SCRIPTS",
            url: message.url || "",
            isTop: message.isTop !== false
        }).then((response) => sendResponse(response))
          .catch(() => sendResponse(null));
        return true;
    }
    if (message.type === "UH_NATIVE_CALL") {
        const payload = Object.assign({}, message.payload || {}, {
            pageUrl: message.pageUrl || ""
        });
        api.runtime.sendNativeMessage("uhApp", payload)
            .then((response) => sendResponse(response))
            .catch(() => sendResponse(null));
        return true;
    }
    return false;
});
