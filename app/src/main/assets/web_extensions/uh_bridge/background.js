const api = typeof browser !== "undefined" ? browser : chrome;

let nativePort = null;

function ensureNativePort() {
    if (nativePort) return nativePort;
    try {
        nativePort = api.runtime.connectNative("uhApp");
        nativePort.onMessage.addListener((msg) => {
            if (!msg || !msg.action) return;
            api.tabs.query({ active: true }).then((tabs) => {
                if (!tabs || !tabs.length) return;
                for (const t of tabs) {
                    api.tabs.sendMessage(t.id, msg).catch(() => {});
                }
            }).catch(() => {});
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
