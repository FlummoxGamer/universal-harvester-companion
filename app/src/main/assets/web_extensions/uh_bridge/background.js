// -----------------------------------------------------------------------------
// UH Bridge Background Module 4: Native Messaging Port & Runtime Message Router
// -----------------------------------------------------------------------------
(function (B) {
    const api = B.api;
    let nativePort = null;

    function reportDeliveryFailure(msg) {
        try {
            if (msg.action === "TRANSLATE_IMAGES") {
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
                if (B.handleChunkedConfigMessage(msg)) {
                    return;
                }
                if (msg.action === "APPLY_PRIVACY_CONFIG" && msg.config) {
                    B.applySettingsObject(msg.config);
                    B.broadcastLiveSettingsToTabs();
                    return;
                }
                const query = (msg.action === "APPLY_GLOBAL_ZOOM" || msg.action === "SET_PIP_ISOLATION") ? {} : { active: true };
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

    api.runtime.sendNativeMessage("uhApp", { type: "GET_PRIVACY_RUNTIME" })
        .then((cfg) => { if (cfg) B.applySettingsObject(cfg); })
        .catch(() => {});

    api.runtime.onMessage.addListener((message) => {
        if (!message) return undefined;
        ensureNativePort();
        if (message.type === "UH_GET_SCRIPTS") {
            return api.runtime.sendNativeMessage("uhApp", {
                type: "GET_SCRIPTS",
                url: message.url || "",
                isTop: message.isTop !== false
            }).catch(() => null);
        }
        if (message.type === "UH_GET_PAGE_PROTECT") {
            return api.runtime.sendNativeMessage("uhApp", {
                type: "GET_PAGE_PROTECT",
                url: message.url || ""
            }).catch(() => null);
        }
        if (message.type === "UH_CHECK_POPUP_URL") {
            const blocked = B.isBlockedPopunderUrl(message.url || "", message.openerHost || "");
            if (blocked) {
                B.queueBlockedStat(message.openerHost || "", false, B.normalizeHost(message.url || ""));
            }
            return Promise.resolve({ blocked });
        }
        if (message.type === "UH_CONTENT_BLOCKED_AD") {
            B.queueBlockedStat(message.host || B.activeMainHost, false, message.host || "");
            return Promise.resolve(true);
        }
        if (message.type === "UH_MEDIA_STATE") {
            api.runtime.sendNativeMessage("uhApp", {
                type: "MEDIA_STATE_UPDATE",
                title: message.title || "",
                artist: message.artist || "",
                artworkUrl: message.artworkUrl || "",
                pageUrl: message.pageUrl || "",
                playing: message.playing === true,
                hasVideo: message.hasVideo === true,
                durationSec: Number(message.durationSec) || 0,
                positionSec: Number(message.positionSec) || 0,
                videoWidth: Number(message.videoWidth) || 0,
                videoHeight: Number(message.videoHeight) || 0
            }).catch(() => {});
            return Promise.resolve(true);
        }
        if (message.type === "UH_NATIVE_CALL") {
            const payload = Object.assign({}, message.payload || {}, {
                pageUrl: message.pageUrl || ""
            });
            return api.runtime.sendNativeMessage("uhApp", payload).catch(() => null);
        }
        return undefined;
    });
})(globalThis.__uhBg);
