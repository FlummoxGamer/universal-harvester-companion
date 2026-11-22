const api = typeof browser !== "undefined" ? browser : chrome;

let nativePort = null;

// -----------------------------------------------------------------------------
// UH Shield & Privacy Runtime State (O(1) domain hash sets, zero latency)
// -----------------------------------------------------------------------------
const shieldState = {
    shieldLevel: 2, // 0=Off, 1=Standard, 2=Aggressive
    adDomains: new Set(),
    trackerDomains: new Set(),
    exceptionDomains: new Set(),
    urlPatterns: [],
    pausedHosts: new Set(),
    cookieBanners: true,
    clearUrls: true,
    deAmp: true,
    blockPopups: true,
    dnt: false,
    gpc: true,
    webrtcPolicy: 1,
    backgroundPlay: true,
    forceDarkWeb: false,
    enhanceDark: false,
    darkBypassHosts: new Set()
};

const tabUrlMap = new Map();
const pendingBlockedByHost = new Map();
let flushTimer = null;

const TRACKING_QUERY_PARAMS = new Set([
    "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "twclid",
    "yclid", "igshid", "igsh", "si", "ref_src", "ref_url",
    "mc_cid", "mc_eid", "oly_anon_id", "oly_enc_id", "vero_id", "wickedid",
    "_hsenc", "_hsmi", "hsctatracking", "mkt_tok", "trk", "trkid",
    "spm", "scm", "aff_id", "aff_sub", "click_id", "clickid", "irclickid",
    "s_cid", "s_kwcid", "icid", "ncid", "sr_share", "share_id",
    "__cf_chl_tk", "guccounter", "guce_referrer", "guce_referrer_sig"
]);

function normalizeHost(urlStr) {
    if (!urlStr) return "";
    try {
        return new URL(urlStr).hostname.toLowerCase().replace(/^www\./, "");
    } catch (_e) {
        return "";
    }
}

function getRegistrableBase(host) {
    const parts = host.split(".");
    if (parts.length <= 2) return host;
    const last2 = parts.slice(-2).join(".");
    if (last2.length <= 6 && (last2.startsWith("co.") || last2.startsWith("com.") || last2.startsWith("org.") || last2.startsWith("net."))) {
        return parts.slice(-3).join(".");
    }
    return last2;
}

function matchesDomainSet(host, set) {
    if (!host || !set || set.size === 0) return false;
    let cur = host;
    while (cur) {
        if (set.has(cur)) return true;
        const dot = cur.indexOf(".");
        if (dot < 0) break;
        cur = cur.slice(dot + 1);
    }
    return false;
}

function queueBlockedStat(pageHost, isTracker) {
    const key = pageHost || "unknown";
    let entry = pendingBlockedByHost.get(key);
    if (!entry) {
        entry = { ads: 0, trackers: 0 };
        pendingBlockedByHost.set(key, entry);
    }
    if (isTracker) entry.trackers += 1;
    else entry.ads += 1;

    if (!flushTimer) {
        flushTimer = setTimeout(flushBlockedStats, 700);
    }
}

function flushBlockedStats() {
    flushTimer = null;
    if (pendingBlockedByHost.size === 0) return;
    const entries = [];
    for (const [host, counts] of pendingBlockedByHost.entries()) {
        entries.push({ host, ads: counts.ads, trackers: counts.trackers });
    }
    pendingBlockedByHost.clear();
    api.runtime.sendNativeMessage("uhApp", {
        type: "SHIELD_BLOCKED_BATCH",
        entries: entries
    }).catch(() => {});
}

function applyWebRtcPrivacySetting(policyCode) {
    try {
        if (!api.privacy || !api.privacy.network || !api.privacy.network.webRTCIPHandlingPolicy) return;
        let val = "default";
        if (policyCode === 1) val = "default_public_interface_only";
        else if (policyCode === 2 || policyCode === 3) val = "disable_non_proxied_udp";
        api.privacy.network.webRTCIPHandlingPolicy.set({ value: val }).catch(() => {});
    } catch (_e) {}
}

function applyRuntimeConfig(cfg) {
    if (!cfg || typeof cfg !== "object") return;
    if (typeof cfg.shieldLevel === "number") shieldState.shieldLevel = cfg.shieldLevel;
    if (Array.isArray(cfg.adDomains)) shieldState.adDomains = new Set(cfg.adDomains);
    if (Array.isArray(cfg.trackerDomains)) shieldState.trackerDomains = new Set(cfg.trackerDomains);
    if (Array.isArray(cfg.exceptionDomains)) shieldState.exceptionDomains = new Set(cfg.exceptionDomains);
    if (Array.isArray(cfg.urlPatterns)) shieldState.urlPatterns = cfg.urlPatterns;
    if (Array.isArray(cfg.pausedHosts)) shieldState.pausedHosts = new Set(cfg.pausedHosts);
    if (typeof cfg.cookieBanners === "boolean") shieldState.cookieBanners = cfg.cookieBanners;
    if (typeof cfg.clearUrls === "boolean") shieldState.clearUrls = cfg.clearUrls;
    if (typeof cfg.deAmp === "boolean") shieldState.deAmp = cfg.deAmp;
    if (typeof cfg.blockPopups === "boolean") shieldState.blockPopups = cfg.blockPopups;
    if (typeof cfg.dnt === "boolean") shieldState.dnt = cfg.dnt;
    if (typeof cfg.gpc === "boolean") shieldState.gpc = cfg.gpc;
    if (typeof cfg.webrtcPolicy === "number") {
        shieldState.webrtcPolicy = cfg.webrtcPolicy;
        applyWebRtcPrivacySetting(cfg.webrtcPolicy);
    }
    if (typeof cfg.backgroundPlay === "boolean") shieldState.backgroundPlay = cfg.backgroundPlay;
    if (typeof cfg.forceDarkWeb === "boolean") shieldState.forceDarkWeb = cfg.forceDarkWeb;
    if (typeof cfg.enhanceDark === "boolean") shieldState.enhanceDark = cfg.enhanceDark;
    if (Array.isArray(cfg.darkBypassHosts)) shieldState.darkBypassHosts = new Set(cfg.darkBypassHosts);

    // Push lightweight live update to all open tabs so Dark Mode / Shield / BG Play react immediately
    const tabMsg = {
        action: "APPLY_LIVE_PAGE_CONFIG",
        shieldLevel: shieldState.shieldLevel,
        pausedHosts: Array.from(shieldState.pausedHosts),
        cookieBanners: shieldState.cookieBanners,
        blockPopups: shieldState.blockPopups,
        backgroundPlay: shieldState.backgroundPlay,
        webrtcPolicy: shieldState.webrtcPolicy,
        forceDarkWeb: shieldState.forceDarkWeb,
        enhanceDark: shieldState.enhanceDark,
        darkBypassHosts: Array.from(shieldState.darkBypassHosts)
    };
    api.tabs.query({}).then((tabs) => {
        if (!tabs) return;
        for (const t of tabs) {
            api.tabs.sendMessage(t.id, tabMsg).catch(() => {});
        }
    }).catch(() => {});
}

function cleanMainFrameUrl(urlStr) {
    if (!urlStr || (!urlStr.startsWith("http://") && !urlStr.startsWith("https://"))) return null;
    let next = urlStr;
    let changed = false;

    if (shieldState.deAmp) {
        const lower = next.toLowerCase();
        const ampSIdx = lower.indexOf("/amp/s/");
        if (ampSIdx > 0 && (lower.includes(".google.") || lower.includes("ampproject.org"))) {
            const target = next.slice(ampSIdx + 7);
            if (target.includes(".")) {
                try {
                    next = "https://" + decodeURIComponent(target).replace(/^https?:\/\//i, "");
                    changed = true;
                } catch (_e) {}
            }
        }
    }

    if (shieldState.clearUrls && next.includes("?")) {
        try {
            const parsed = new URL(next);
            const toDelete = [];
            for (const key of parsed.searchParams.keys()) {
                const k = key.toLowerCase();
                if (k.startsWith("utm_") || k.startsWith("hsa_") || k.startsWith("mtm_") || k.startsWith("pk_") || TRACKING_QUERY_PARAMS.has(k)) {
                    toDelete.push(key);
                }
            }
            if (toDelete.length > 0) {
                for (const k of toDelete) parsed.searchParams.delete(k);
                next = parsed.toString();
                changed = true;
            }
        } catch (_e) {}
    }

    return changed && next !== urlStr ? next : null;
}

// -----------------------------------------------------------------------------
// Layer 1 Network Request Blocker + ClearURLs / De-AMP
// -----------------------------------------------------------------------------
if (api.webRequest && api.webRequest.onBeforeRequest) {
    api.webRequest.onBeforeRequest.addListener(
        (details) => {
            try {
                if (details.type === "main_frame") {
                    if (typeof details.tabId === "number" && details.tabId >= 0) {
                        tabUrlMap.set(details.tabId, details.url);
                    }
                    const cleaned = cleanMainFrameUrl(details.url);
                    if (cleaned) {
                        return { redirectUrl: cleaned };
                    }
                    return {};
                }

                if (shieldState.shieldLevel === 0) return {};

                const pageUrl = details.documentUrl || details.originUrl || tabUrlMap.get(details.tabId) || "";
                const pageHost = normalizeHost(pageUrl);
                if (pageHost && shieldState.pausedHosts.has(pageHost)) return {};

                const reqHost = normalizeHost(details.url);
                if (!reqHost) return {};

                const isThirdParty = !pageHost || getRegistrableBase(reqHost) !== getRegistrableBase(pageHost);
                if (shieldState.shieldLevel === 1 && !isThirdParty) return {};

                if (matchesDomainSet(reqHost, shieldState.exceptionDomains)) return {};

                if (matchesDomainSet(reqHost, shieldState.trackerDomains)) {
                    queueBlockedStat(pageHost, true);
                    return { cancel: true };
                }

                if (matchesDomainSet(reqHost, shieldState.adDomains)) {
                    queueBlockedStat(pageHost, false);
                    return { cancel: true };
                }

                const lowerUrl = details.url.toLowerCase();
                for (let i = 0; i < shieldState.urlPatterns.length; i++) {
                    if (lowerUrl.includes(shieldState.urlPatterns[i])) {
                        queueBlockedStat(pageHost, false);
                        return { cancel: true };
                    }
                }
            } catch (_e) {}
            return {};
        },
        { urls: ["http://*/*", "https://*/*"] },
        ["blocking"]
    );
}

// Inject DNT / Sec-GPC headers cleanly when enabled
if (api.webRequest && api.webRequest.onBeforeSendHeaders) {
    api.webRequest.onBeforeSendHeaders.addListener(
        (details) => {
            if (!shieldState.dnt && !shieldState.gpc) return {};
            const headers = details.requestHeaders ? details.requestHeaders.slice() : [];
            if (shieldState.dnt && !headers.some((h) => h.name.toLowerCase() === "dnt")) {
                headers.push({ name: "DNT", value: "1" });
            }
            if (shieldState.gpc && !headers.some((h) => h.name.toLowerCase() === "sec-gpc")) {
                headers.push({ name: "Sec-GPC", value: "1" });
            }
            return { requestHeaders: headers };
        },
        { urls: ["http://*/*", "https://*/*"] },
        ["blocking", "requestHeaders"]
    );
}

if (api.tabs && api.tabs.onRemoved) {
    api.tabs.onRemoved.addListener((tabId) => {
        tabUrlMap.delete(tabId);
    });
}

// -----------------------------------------------------------------------------
// Native Port & Bridge Messaging
// -----------------------------------------------------------------------------
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
            if (msg.action === "APPLY_PRIVACY_CONFIG" && msg.config) {
                applyRuntimeConfig(msg.config);
                return;
            }
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

// Bootstrap runtime privacy config immediately on extension startup
api.runtime.sendNativeMessage("uhApp", { type: "GET_PRIVACY_RUNTIME" })
    .then((cfg) => { if (cfg) applyRuntimeConfig(cfg); })
    .catch(() => {});

// Return Promises directly for Firefox/GeckoView WebExtension runtime.onMessage
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
    if (message.type === "UH_NATIVE_CALL") {
        const payload = Object.assign({}, message.payload || {}, {
            pageUrl: message.pageUrl || ""
        });
        return api.runtime.sendNativeMessage("uhApp", payload).catch(() => null);
    }
    return undefined;
});
