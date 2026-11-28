// -----------------------------------------------------------------------------
// UH Bridge Background Module 4: Native Messaging Port & Runtime Message Router
// -----------------------------------------------------------------------------
(function (B) {
    const api = B.api;
    let nativePort = null;

    function reportDeliveryFailure(msg) {
        try {
            if (msg.action === "ONLINE_TRANSLATE_PAGE") {
                api.runtime.sendNativeMessage("uhApp", {
                    type: "ONLINE_TRANSLATE_DONE",
                    ok: false,
                    restored: false,
                    error: "Cannot translate this page"
                }).catch(() => {});
            }
        } catch (_e) {}
    }

    function dispatchToTabs(tabs, msg) {
        if (!tabs || !tabs.length) return Promise.resolve(false);
        const sendPromises = tabs.map((t) =>
            api.tabs.sendMessage(t.id, msg).then(() => true).catch(() => false)
        );
        return Promise.all(sendPromises).then((results) => results.some(Boolean));
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
                if (msg.action === "ADD_CNAME_BLOCK" && msg.subdomain) {
                    B.cnameBlockedHosts.set(String(msg.subdomain).toLowerCase(), {
                        target: msg.target || "",
                        isTracker: msg.isTracker !== false
                    });
                    return;
                }
                const broadcastAll = (msg.action === "APPLY_GLOBAL_ZOOM" || msg.action === "SET_PIP_ISOLATION");
                const query = broadcastAll ? {} : { active: true };
                api.tabs.query(query).then((tabs) => {
                    return dispatchToTabs(tabs, msg).then((delivered) => {
                        if (delivered || broadcastAll) {
                            if (!delivered) reportDeliveryFailure(msg);
                            return;
                        }
                        // Fallback when GeckoView doesn't flag the tab as active: query all tabs and target most recent HTTP(S) tab
                        return api.tabs.query({}).then((allTabs) => {
                            const sorted = (allTabs || []).slice().sort((a, b) => (b.lastAccessed || 0) - (a.lastAccessed || 0));
                            const target = sorted.find((t) => t.active) || sorted[0];
                            if (!target) {
                                reportDeliveryFailure(msg);
                                return;
                            }
                            return dispatchToTabs([target], msg).then((ok) => {
                                if (!ok) reportDeliveryFailure(msg);
                            });
                        });
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
                B.queueBlockedStat(message.openerHost || "", false, B.normalizeHost(message.url || ""), "popup_check");
                B.notifyBlockedRedirect(message.openerHost || "", message.url || "", true);
            }
            return Promise.resolve({ blocked });
        }
        if (message.type === "UH_BLOCKED_REDIRECT") {
            const targetUrl = message.targetUrl || "";
            const sourceHost = message.sourceHost || B.activeMainHost || "";
            const targetHost = B.normalizeHost(targetUrl) || "ad-redirect";
            B.queueBlockedStat(sourceHost, false, targetHost, "scriptlet_redirect");
            if (targetUrl) {
                B.notifyBlockedRedirect(sourceHost, targetUrl, message.isPopupTab === true);
            }
            return Promise.resolve(true);
        }
        if (message.type === "UH_CONTENT_BLOCKED_AD") {
            B.queueBlockedStat(message.host || B.activeMainHost, false, message.host || "cosmetic", "cosmetic");
            return Promise.resolve(true);
        }
        if (message.type === "UH_ADD_CUSTOM_RULE" && message.rule) {
            return api.runtime.sendNativeMessage("uhApp", {
                type: "ADD_CUSTOM_RULE",
                rule: String(message.rule)
            }).catch(() => false);
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
