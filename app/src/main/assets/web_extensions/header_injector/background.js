const api = typeof browser !== "undefined" ? browser : chrome;

// Header capture is dormant by default so we NEVER block network requests or
// alter genuine GeckoView TLS/HTTP fingerprints (e.g. Cloudflare WAF).
let captureActive = false;
let nativePort = null;

function ensurePort() {
    if (nativePort) return;
    try {
        nativePort = api.runtime.connectNative("uhApp");
        nativePort.onMessage.addListener((msg) => {
            if (msg && msg.action === "SET_HEADER_CAPTURE") {
                captureActive = msg.enabled === true;
            }
        });
        nativePort.onDisconnect.addListener(() => {
            nativePort = null;
            captureActive = false;
        });
    } catch (_e) {
        nativePort = null;
    }
}

ensurePort();

// Passive, non-blocking observer — only forwards headers when SessionRecorder is explicitly active
if (api.webRequest && api.webRequest.onSendHeaders) {
    api.webRequest.onSendHeaders.addListener(
        function (details) {
            if (!captureActive) return;
            if (details.type !== "main_frame" && details.type !== "xmlhttprequest" && details.type !== "sub_frame") {
                return;
            }
            const headers = details.requestHeaders || [];
            try {
                const headerMap = {};
                for (let i = 0; i < headers.length; i++) {
                    const h = headers[i];
                    if (h && h.name) headerMap[h.name] = h.value || "";
                }
                api.runtime.sendNativeMessage("uhApp", {
                    type: "HEADER_EVENT",
                    url: details.url,
                    method: details.method || "GET",
                    reqType: details.type,
                    headers: headerMap
                }).catch(() => {});
            } catch (_e) {}
        },
        { urls: ["http://*/*", "https://*/*"] },
        ["requestHeaders"]
    );
}
