const api = typeof browser !== "undefined" ? browser : chrome;

let nativePort = null;

// -----------------------------------------------------------------------------
// UH Shield v4 Runtime State — Full ABP/uBO Engine with Chunked Zero-Cap Sync
// -----------------------------------------------------------------------------
const TYPE_SCRIPT = 1 << 0;
const TYPE_IMAGE = 1 << 1;
const TYPE_STYLESHEET = 1 << 2;
const TYPE_SUBDOCUMENT = 1 << 3;
const TYPE_XHR = 1 << 4;
const TYPE_MEDIA = 1 << 5;
const TYPE_FONT = 1 << 6;
const TYPE_PING = 1 << 7;
const TYPE_OBJECT = 1 << 8;
const TYPE_OTHER = 1 << 9;
const TYPE_DOCUMENT = 1 << 10;
const TYPE_POPUP = 1 << 11;

const FLAG_REGEX = 1;
const FLAG_EXCEPTION = 2;
const FLAG_TRACKER = 4;
const FLAG_THIRD_PARTY = 8;
const FLAG_FIRST_PARTY = 16;
const FLAG_POPUP = 32;
const FLAG_IMPORTANT = 64;

const REDIRECT_DATA_URLS = {
    js: "data:application/javascript;base64,KGZ1bmN0aW9uKCl7fSkoKTs=",
    gif: "data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw==",
    html: "data:text/html;base64,PCFET0NUWVBFIGh0bWw+PGh0bWw+PGhlYWQ+PC9oZWFkPjxib2R5PjwvYm9keT48L2h0bWw+",
    css: "data:text/css;base64,Lyogbm9vcCAqLw==",
    empty: "data:text/plain;base64,"
};

function createEmptyRuleEngine() {
    return {
        adDomains: new Set(),
        trackerDomains: new Set(),
        thirdPartyAdDomains: new Set(),
        thirdPartyTrackerDomains: new Set(),
        popupDomains: new Set(),
        exceptionDomains: new Set(),
        thirdPartyExceptionDomains: new Set(),
        // Structured blocking rules indexed for sub-millisecond evaluation
        blockByDomain: new Map(),
        blockByToken: new Map(),
        blockGeneric: [],
        importantRules: [],
        // Structured @@ exception rules indexed identically
        excByDomain: new Map(),
        excByToken: new Map(),
        excGeneric: [],
        // Structured $popup rules
        popupRules: []
    };
}

let activeRules = createEmptyRuleEngine();
let stagingSyncId = null;
let stagingRules = null;
let stagingSettings = null;

const shieldState = {
    shieldLevel: 2, // 0=Off, 1=Standard, 2=Aggressive
    pausedHosts: new Set(),
    smartBlockJs: false,
    jsAllowOnceHosts: new Set(),
    jsAllowAlwaysHosts: new Set(),
    cookieBanners: true,
    clearUrls: true,
    deAmp: true,
    blockPopups: true,
    antiFingerprint: true,
    dnt: false,
    gpc: true,
    webrtcPolicy: 1,
    backgroundPlay: true,
    forceDarkWeb: false,
    enhanceDark: false,
    darkBypassHosts: new Set()
};

const tabUrlMap = new Map();
const tabJsBlockedMap = new Map(); // tabId -> { host, total, domains: Map<domain, count> }
const recentPopupCandidates = new Map(); // tabId -> { openerHost, createdAt }
const pendingBlockedByHost = new Map();
let flushTimer = null;
let jsReportTimer = null;
const pendingJsReportHosts = new Set();

const TRACKING_QUERY_PARAMS = new Set([
    "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "twclid",
    "yclid", "ymclid", "igshid", "igsh", "si", "feature", "pp", "ref_src", "ref_url",
    "oly_anon_id", "oly_enc_id", "vero_id", "wickedid",
    "_hsenc", "_hsmi", "hsctatracking", "mkt_tok", "trk", "trkid",
    "spm", "scm", "aff_id", "aff_sub", "click_id", "clickid", "irclickid",
    "s_cid", "s_kwcid", "ef_id", "icid", "ncid", "sr_share", "share_id",
    "__cf_chl_tk", "guccounter", "guce_referrer", "guce_referrer_sig",
    "is_from_webapp", "sender_device", "sender_web_id", "_t", "_r",
    "rb_clickid", "scid", "sscid", "ttclid", "li_fat_id", "epik", "ndclid",
    "oborigurl", "dicbo", "ad_id", "adset_id", "campaign_id",
    "ref_", "dib", "dib_tag", "ascsubtag", "linkcode", "linkid",
    "cxt", "ref_campaign", "ref_source", "share_source"
]);

const KNOWN_POPUNDER_PATH_HINTS = [
    "/popunder", "/afu.php", "/click.php?", "/redirect/pop", "/pops/", "/interstitial/ad"
];

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
    if (last2.length <= 6 && (last2.startsWith("co.") || last2.startsWith("com.") || last2.startsWith("org.") || last2.startsWith("net.") || last2.startsWith("gov."))) {
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

function matchesDomainList(host, list) {
    if (!host || !list || list.length === 0) return false;
    for (let i = 0; i < list.length; i++) {
        const d = list[i];
        if (host === d || host.endsWith("." + d)) return true;
    }
    return false;
}

function mapDetailsTypeToBit(type) {
    switch (type) {
        case "script": return TYPE_SCRIPT;
        case "image":
        case "imageset": return TYPE_IMAGE;
        case "stylesheet": return TYPE_STYLESHEET;
        case "sub_frame": return TYPE_SUBDOCUMENT;
        case "xmlhttprequest":
        case "fetch": return TYPE_XHR;
        case "media": return TYPE_MEDIA;
        case "font": return TYPE_FONT;
        case "ping":
        case "beacon": return TYPE_PING;
        case "object":
        case "object_subrequest": return TYPE_OBJECT;
        case "main_frame": return TYPE_DOCUMENT;
        default: return TYPE_OTHER;
    }
}

function escapeRegexLiteral(str) {
    return str.replace(/[.+?{}()|[\]\\]/g, "\\$&");
}

function compileAbpPatternToRegex(pat) {
    let p = pat;
    let prefix = "";
    let suffix = "";

    if (p.startsWith("||")) {
        prefix = "^https?:\\/\\/(?:[^\\/]*\\.)?";
        p = p.slice(2);
    } else if (p.startsWith("|")) {
        prefix = "^";
        p = p.slice(1);
    }

    if (p.endsWith("|")) {
        suffix = "$";
        p = p.slice(0, -1);
    }

    let body = "";
    for (let i = 0; i < p.length; i++) {
        const ch = p[i];
        if (ch === "*") {
            body += ".*";
        } else if (ch === "^") {
            body += "(?:[^\\w\\-.%]|$)";
        } else {
            body += escapeRegexLiteral(ch);
        }
    }

    try {
        return new RegExp(prefix + body + suffix, "i");
    } catch (_e) {
        return null;
    }
}

function extractBestToken(pattern, isRegex) {
    if (isRegex) return null;
    const cleaned = pattern.replace(/^\|\|/, "").replace(/^\||\|$/g, "");
    const parts = cleaned.split(/[^a-z0-9]+/i);
    let best = "";
    for (let i = 0; i < parts.length; i++) {
        const tok = parts[i].toLowerCase();
        if (tok.length < 4) continue;
        if (tok === "http" || tok === "https" || tok === "www" || tok === "html" || tok === "com" || tok === "net" || tok === "org") {
            continue;
        }
        if (tok.length > best.length) best = tok;
    }
    return best.length >= 4 ? best : null;
}

function tokenizeUrl(lowerUrl) {
    const tokens = new Set();
    let start = -1;
    for (let i = 0; i <= lowerUrl.length; i++) {
        const code = i < lowerUrl.length ? lowerUrl.charCodeAt(i) : 0;
        const isAlnum = (code >= 97 && code <= 122) || (code >= 48 && code <= 57);
        if (isAlnum) {
            if (start < 0) start = i;
        } else if (start >= 0) {
            if (i - start >= 4) {
                tokens.add(lowerUrl.slice(start, i));
            }
            start = -1;
        }
    }
    return tokens;
}

function addToMapArray(map, key, val) {
    let arr = map.get(key);
    if (!arr) {
        arr = [];
        map.set(key, arr);
    }
    arr.push(val);
}

function compileAndIndexWireRule(engine, wire) {
    if (!Array.isArray(wire) || wire.length < 5) return;
    const pattern = wire[0];
    const flags = wire[1] || 0;
    const anchorDomain = wire[2] || "";
    const includeTypes = wire[3] || 0;
    const excludeTypes = wire[4] || 0;
    const includeDomains = Array.isArray(wire[5]) ? wire[5] : null;
    const excludeDomains = Array.isArray(wire[6]) ? wire[6] : null;
    const redirect = wire[7] || "";

    const isRegex = (flags & FLAG_REGEX) !== 0;
    const isException = (flags & FLAG_EXCEPTION) !== 0;
    const isTracker = (flags & FLAG_TRACKER) !== 0;
    const thirdPartyOnly = (flags & FLAG_THIRD_PARTY) !== 0;
    const firstPartyOnly = (flags & FLAG_FIRST_PARTY) !== 0;
    const isPopup = (flags & FLAG_POPUP) !== 0;
    const important = (flags & FLAG_IMPORTANT) !== 0;

    let regex = null;
    let substring = null;

    if (isRegex) {
        try {
            regex = new RegExp(pattern, "i");
        } catch (_e) {
            return;
        }
    } else if (pattern.includes("*") || pattern.includes("^") || pattern.includes("|")) {
        regex = compileAbpPatternToRegex(pattern);
        if (!regex) return;
    } else {
        substring = pattern.toLowerCase();
    }

    const rule = {
        regex,
        substring,
        isException,
        isTracker,
        thirdPartyOnly,
        firstPartyOnly,
        includeTypes,
        excludeTypes,
        includeDomains,
        excludeDomains,
        isPopup,
        important,
        redirect
    };

    if (isPopup && !isException) {
        engine.popupRules.push(rule);
        if (includeTypes === TYPE_POPUP) return;
    }

    if (important && !isException) {
        engine.importantRules.push(rule);
    }

    const domainMap = isException ? engine.excByDomain : engine.blockByDomain;
    const tokenMap = isException ? engine.excByToken : engine.blockByToken;
    const genericArr = isException ? engine.excGeneric : engine.blockGeneric;

    if (anchorDomain) {
        addToMapArray(domainMap, anchorDomain, rule);
    } else {
        const tok = extractBestToken(pattern, isRegex);
        if (tok) {
            addToMapArray(tokenMap, tok, rule);
        } else {
            genericArr.push(rule);
        }
    }
}

function matchesStructuredRule(rule, lowerUrl, pageHost, isThirdParty, typeBit) {
    if (rule.thirdPartyOnly && !isThirdParty) return false;
    if (rule.firstPartyOnly && isThirdParty) return false;

    if (rule.includeTypes !== 0 && (rule.includeTypes & typeBit) === 0) return false;
    if (rule.excludeTypes !== 0 && (rule.excludeTypes & typeBit) !== 0) return false;

    if (rule.includeDomains && !matchesDomainList(pageHost, rule.includeDomains)) return false;
    if (rule.excludeDomains && matchesDomainList(pageHost, rule.excludeDomains)) return false;

    if (rule.substring !== null) {
        return lowerUrl.includes(rule.substring);
    }
    if (rule.regex !== null) {
        return rule.regex.test(lowerUrl);
    }
    return false;
}

function findMatchingRuleInIndex(domainMap, tokenMap, genericArr, reqHost, lowerUrl, urlTokens, pageHost, isThirdParty, typeBit) {
    // 1. Check rules anchored to reqHost or any parent domain suffix
    if (domainMap.size > 0 && reqHost) {
        let cur = reqHost;
        while (cur) {
            const list = domainMap.get(cur);
            if (list) {
                for (let i = 0; i < list.length; i++) {
                    if (matchesStructuredRule(list[i], lowerUrl, pageHost, isThirdParty, typeBit)) {
                        return list[i];
                    }
                }
            }
            const dot = cur.indexOf(".");
            if (dot < 0) break;
            cur = cur.slice(dot + 1);
        }
    }

    // 2. Check rules indexed by URL literal tokens
    if (tokenMap.size > 0) {
        for (const tok of urlTokens) {
            const list = tokenMap.get(tok);
            if (!list) continue;
            for (let i = 0; i < list.length; i++) {
                if (matchesStructuredRule(list[i], lowerUrl, pageHost, isThirdParty, typeBit)) {
                    return list[i];
                }
            }
        }
    }

    // 3. Check tokenless generic regex rules
    for (let i = 0; i < genericArr.length; i++) {
        if (matchesStructuredRule(genericArr[i], lowerUrl, pageHost, isThirdParty, typeBit)) {
            return genericArr[i];
        }
    }
    return null;
}

// -----------------------------------------------------------------------------
// Stats & Smart JS Reporting (Batched for zero IPC overhead)
// -----------------------------------------------------------------------------
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
        flushTimer = setTimeout(flushBlockedStats, 650);
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

function recordBlockedScript(tabId, pageHost, scriptHost) {
    if (typeof tabId !== "number" || tabId < 0 || !pageHost) return;
    let entry = tabJsBlockedMap.get(tabId);
    if (!entry || entry.host !== pageHost) {
        entry = { host: pageHost, total: 0, domains: new Map() };
        tabJsBlockedMap.set(tabId, entry);
    }
    entry.total += 1;
    const domKey = scriptHost || "third-party";
    entry.domains.set(domKey, (entry.domains.get(domKey) || 0) + 1);
    pendingJsReportHosts.add(tabId);

    if (!jsReportTimer) {
        jsReportTimer = setTimeout(flushJsBlockedReports, 280);
    }
}

function flushJsBlockedReports() {
    jsReportTimer = null;
    if (pendingJsReportHosts.size === 0) return;
    const tabIds = Array.from(pendingJsReportHosts);
    pendingJsReportHosts.clear();

    for (let i = 0; i < tabIds.length; i++) {
        const entry = tabJsBlockedMap.get(tabIds[i]);
        if (!entry) continue;
        const domainsObj = {};
        for (const [d, c] of entry.domains.entries()) {
            domainsObj[d] = c;
        }
        api.runtime.sendNativeMessage("uhApp", {
            type: "JS_BLOCKED_REPORT",
            host: entry.host,
            totalBlocked: entry.total,
            domains: domainsObj
        }).catch(() => {});
    }
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

function applySettingsObject(cfg) {
    if (!cfg || typeof cfg !== "object") return;
    if (typeof cfg.shieldLevel === "number") shieldState.shieldLevel = cfg.shieldLevel;
    if (Array.isArray(cfg.pausedHosts)) shieldState.pausedHosts = new Set(cfg.pausedHosts);
    if (typeof cfg.smartBlockJs === "boolean") shieldState.smartBlockJs = cfg.smartBlockJs;
    if (Array.isArray(cfg.jsAllowOnceHosts)) shieldState.jsAllowOnceHosts = new Set(cfg.jsAllowOnceHosts);
    if (Array.isArray(cfg.jsAllowAlwaysHosts)) shieldState.jsAllowAlwaysHosts = new Set(cfg.jsAllowAlwaysHosts);
    if (typeof cfg.cookieBanners === "boolean") shieldState.cookieBanners = cfg.cookieBanners;
    if (typeof cfg.clearUrls === "boolean") shieldState.clearUrls = cfg.clearUrls;
    if (typeof cfg.deAmp === "boolean") shieldState.deAmp = cfg.deAmp;
    if (typeof cfg.blockPopups === "boolean") shieldState.blockPopups = cfg.blockPopups;
    if (typeof cfg.antiFingerprint === "boolean") shieldState.antiFingerprint = cfg.antiFingerprint;
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
}

function broadcastLiveSettingsToTabs() {
    const tabMsg = {
        action: "APPLY_LIVE_PAGE_CONFIG",
        shieldLevel: shieldState.shieldLevel,
        pausedHosts: Array.from(shieldState.pausedHosts),
        cookieBanners: shieldState.cookieBanners,
        blockPopups: shieldState.blockPopups,
        antiFingerprint: shieldState.antiFingerprint,
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

function handleChunkedConfigMessage(msg) {
    if (msg.action === "SHIELD_CONFIG_BEGIN") {
        stagingSyncId = msg.syncId;
        stagingRules = createEmptyRuleEngine();
        stagingSettings = msg.settings || null;
        if (stagingSettings) {
            applySettingsObject(stagingSettings);
        }
        return true;
    }

    if (msg.action === "SHIELD_CONFIG_CHUNK") {
        if (!stagingRules || msg.syncId !== stagingSyncId) return true;
        const items = msg.items;
        if (!Array.isArray(items)) return true;

        switch (msg.kind) {
            case "adDomains":
                for (let i = 0; i < items.length; i++) stagingRules.adDomains.add(items[i]);
                break;
            case "trackerDomains":
                for (let i = 0; i < items.length; i++) stagingRules.trackerDomains.add(items[i]);
                break;
            case "thirdPartyAdDomains":
                for (let i = 0; i < items.length; i++) stagingRules.thirdPartyAdDomains.add(items[i]);
                break;
            case "thirdPartyTrackerDomains":
                for (let i = 0; i < items.length; i++) stagingRules.thirdPartyTrackerDomains.add(items[i]);
                break;
            case "popupDomains":
                for (let i = 0; i < items.length; i++) stagingRules.popupDomains.add(items[i]);
                break;
            case "exceptionDomains":
                for (let i = 0; i < items.length; i++) stagingRules.exceptionDomains.add(items[i]);
                break;
            case "thirdPartyExceptionDomains":
                for (let i = 0; i < items.length; i++) stagingRules.thirdPartyExceptionDomains.add(items[i]);
                break;
            case "networkRules":
                for (let i = 0; i < items.length; i++) compileAndIndexWireRule(stagingRules, items[i]);
                break;
        }
        return true;
    }

    if (msg.action === "SHIELD_CONFIG_COMMIT") {
        if (stagingRules && msg.syncId === stagingSyncId) {
            activeRules = stagingRules;
            stagingRules = null;
            stagingSyncId = null;
            if (stagingSettings) {
                applySettingsObject(stagingSettings);
                stagingSettings = null;
            }
            broadcastLiveSettingsToTabs();
        }
        return true;
    }

    return false;
}

// -----------------------------------------------------------------------------
// Expanded ClearURLs + Redirect Link Unwrapper + Auto De-AMP
// -----------------------------------------------------------------------------
function unwrapRedirectLink(urlStr) {
    try {
        const u = new URL(urlStr);
        const host = u.hostname.toLowerCase();
        const path = u.pathname.toLowerCase();
        let target = null;

        if (host.includes("google.") && path === "/url") {
            target = u.searchParams.get("q") || u.searchParams.get("url");
        } else if ((host === "l.facebook.com" || host === "lm.facebook.com" || host === "l.messenger.com") && path.startsWith("/l.php")) {
            target = u.searchParams.get("u");
        } else if (host === "l.instagram.com") {
            target = u.searchParams.get("u");
        } else if (host === "out.reddit.com") {
            target = u.searchParams.get("url");
        } else if (host === "away.vk.com" && path.startsWith("/away.php")) {
            target = u.searchParams.get("to");
        } else if (host === "steamcommunity.com" && path.startsWith("/linkfilter")) {
            target = u.searchParams.get("u") || u.searchParams.get("url");
        } else if (host === "exit.sc") {
            target = u.searchParams.get("url");
        } else if (host === "href.li" && u.search && u.search.startsWith("?http")) {
            target = u.search.slice(1);
        }

        if (target && (target.startsWith("https://") || target.startsWith("http://"))) {
            return target;
        }
    } catch (_e) {}
    return urlStr;
}

function cleanTrackingUrl(urlStr, allowRedirectUnwrap) {
    if (!urlStr || (!urlStr.startsWith("http://") && !urlStr.startsWith("https://"))) return null;
    let next = urlStr;
    let changed = false;

    if (shieldState.clearUrls && allowRedirectUnwrap) {
        const unwrapped = unwrapRedirectLink(next);
        if (unwrapped !== next) {
            next = unwrapped;
            changed = true;
        }
    }

    if (shieldState.deAmp && allowRedirectUnwrap) {
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
                if (
                    k.startsWith("utm_") ||
                    k.startsWith("hsa_") ||
                    k.startsWith("mtm_") ||
                    k.startsWith("pk_") ||
                    k.startsWith("pd_rd_") ||
                    k.startsWith("pf_rd_") ||
                    k.startsWith("ga_") ||
                    k.startsWith("mc_") ||
                    TRACKING_QUERY_PARAMS.has(k)
                ) {
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

function notifyElementCollapse(details) {
    if (typeof details.tabId !== "number" || details.tabId < 0) return;
    if (details.type !== "image" && details.type !== "imageset" && details.type !== "sub_frame" && details.type !== "object" && details.type !== "media") {
        return;
    }
    try {
        const opts = typeof details.frameId === "number" && details.frameId >= 0 ? { frameId: details.frameId } : undefined;
        api.tabs.sendMessage(
            details.tabId,
            { action: "COLLAPSE_BLOCKED_URL", url: details.url, reqType: details.type },
            opts
        ).catch(() => {});
    } catch (_e) {}
}

// -----------------------------------------------------------------------------
// Layer 1 Network Blocker + Smart 3rd-Party JS Blocker + ClearURLs
// -----------------------------------------------------------------------------
if (api.webRequest && api.webRequest.onBeforeRequest) {
    api.webRequest.onBeforeRequest.addListener(
        (details) => {
            try {
                if (details.type === "main_frame") {
                    const nextHost = normalizeHost(details.url);
                    if (typeof details.tabId === "number" && details.tabId >= 0) {
                        tabUrlMap.set(details.tabId, details.url);
                        tabJsBlockedMap.delete(details.tabId);
                        if (nextHost) {
                            api.runtime.sendNativeMessage("uhApp", {
                                type: "PAGE_NAVIGATED",
                                host: nextHost
                            }).catch(() => {});
                        }
                    }
                    if (details.method === "GET") {
                        const cleaned = cleanTrackingUrl(details.url, true);
                        if (cleaned) {
                            return { redirectUrl: cleaned };
                        }
                    }
                    return {};
                }

                const pageUrl = details.documentUrl || details.originUrl || tabUrlMap.get(details.tabId) || "";
                const pageHost = normalizeHost(pageUrl);
                const isPaused = !!(pageHost && shieldState.pausedHosts.has(pageHost));

                const reqHost = normalizeHost(details.url);
                if (!reqHost) return {};
                const isThirdParty = !pageHost || getRegistrableBase(reqHost) !== getRegistrableBase(pageHost);
                const typeBit = mapDetailsTypeToBit(details.type);

                // 1) Smart JavaScript Blocking: block 3rd-party scripts cleanly with in-page recovery UI
                if (shieldState.smartBlockJs && typeBit === TYPE_SCRIPT && isThirdParty && !isPaused) {
                    const jsAllowed = pageHost && (
                        shieldState.jsAllowOnceHosts.has(pageHost) ||
                        shieldState.jsAllowAlwaysHosts.has(pageHost)
                    );
                    if (!jsAllowed) {
                        recordBlockedScript(details.tabId, pageHost, reqHost);
                        return { cancel: true };
                    }
                }

                // 2) UH Shield Network Filtering
                if (shieldState.shieldLevel !== 0 && !isPaused) {
                    const rules = activeRules;
                    const lowerUrl = details.url.toLowerCase();
                    let urlTokens = null;
                    const getTokens = () => {
                        if (!urlTokens) urlTokens = tokenizeUrl(lowerUrl);
                        return urlTokens;
                    };

                    // 2A) Check $important rules first (override @@ exceptions)
                    if (rules.importantRules.length > 0) {
                        for (let i = 0; i < rules.importantRules.length; i++) {
                            const r = rules.importantRules[i];
                            if (matchesStructuredRule(r, lowerUrl, pageHost, isThirdParty, typeBit)) {
                                queueBlockedStat(pageHost, r.isTracker);
                                if (r.redirect && REDIRECT_DATA_URLS[r.redirect]) {
                                    return { redirectUrl: REDIRECT_DATA_URLS[r.redirect] };
                                }
                                notifyElementCollapse(details);
                                return { cancel: true };
                            }
                        }
                    }

                    // 2B) Check @@ Exception rules BEFORE blocking (critical for streaming/video players!)
                    let isExempt = matchesDomainSet(reqHost, rules.exceptionDomains);
                    if (!isExempt && isThirdParty) {
                        isExempt = matchesDomainSet(reqHost, rules.thirdPartyExceptionDomains);
                    }
                    if (!isExempt && (rules.excByDomain.size > 0 || rules.excByToken.size > 0 || rules.excGeneric.length > 0)) {
                        const excMatch = findMatchingRuleInIndex(
                            rules.excByDomain,
                            rules.excByToken,
                            rules.excGeneric,
                            reqHost,
                            lowerUrl,
                            getTokens(),
                            pageHost,
                            isThirdParty,
                            typeBit
                        );
                        if (excMatch) isExempt = true;
                    }

                    if (!isExempt) {
                        // 2C) O(1) Domain Hash Sets
                        const allowDomainFirstParty = shieldState.shieldLevel >= 2;

                        if ((allowDomainFirstParty || isThirdParty) && matchesDomainSet(reqHost, rules.trackerDomains)) {
                            queueBlockedStat(pageHost, true);
                            notifyElementCollapse(details);
                            return { cancel: true };
                        }
                        if ((allowDomainFirstParty || isThirdParty) && matchesDomainSet(reqHost, rules.adDomains)) {
                            queueBlockedStat(pageHost, false);
                            notifyElementCollapse(details);
                            return { cancel: true };
                        }
                        if (isThirdParty) {
                            if (matchesDomainSet(reqHost, rules.thirdPartyTrackerDomains)) {
                                queueBlockedStat(pageHost, true);
                                notifyElementCollapse(details);
                                return { cancel: true };
                            }
                            if (matchesDomainSet(reqHost, rules.thirdPartyAdDomains)) {
                                queueBlockedStat(pageHost, false);
                                notifyElementCollapse(details);
                                return { cancel: true };
                            }
                        }

                        // 2D) Structured Rules (paths, wildcards, static ad images/GIFs, regex, $domain, $image/$script/$xhr, $redirect)
                        if (rules.blockByDomain.size > 0 || rules.blockByToken.size > 0 || rules.blockGeneric.length > 0) {
                            const matched = findMatchingRuleInIndex(
                                rules.blockByDomain,
                                rules.blockByToken,
                                rules.blockGeneric,
                                reqHost,
                                lowerUrl,
                                getTokens(),
                                pageHost,
                                isThirdParty,
                                typeBit
                            );
                            if (matched) {
                                // Standard mode respects first-party safety unless rule explicitly specifies first-party or image/subdocument ad path
                                const allowInStandard = isThirdParty || matched.firstPartyOnly || (typeBit === TYPE_IMAGE) || (typeBit === TYPE_SUBDOCUMENT);
                                if (shieldState.shieldLevel >= 2 || allowInStandard) {
                                    queueBlockedStat(pageHost, matched.isTracker);
                                    if (matched.redirect && REDIRECT_DATA_URLS[matched.redirect]) {
                                        return { redirectUrl: REDIRECT_DATA_URLS[matched.redirect] };
                                    }
                                    notifyElementCollapse(details);
                                    return { cancel: true };
                                }
                            }
                        }
                    }
                }

                // 3) Expanded ClearURLs on sub_frame & xmlhttprequest GET requests
                if (
                    shieldState.clearUrls &&
                    !isPaused &&
                    details.method === "GET" &&
                    (details.type === "sub_frame" || details.type === "xmlhttprequest") &&
                    details.url.includes("?")
                ) {
                    const cleanedSub = cleanTrackingUrl(details.url, false);
                    if (cleanedSub) {
                        return { redirectUrl: cleanedSub };
                    }
                }
            } catch (_e) {}
            return {};
        },
        { urls: ["http://*/*", "https://*/*"] },
        ["blocking"]
    );
}

// -----------------------------------------------------------------------------
// Popunder Ad-Tab Trap Blocker ($popup rules + cross-origin ad tab guard)
// -----------------------------------------------------------------------------
function isBlockedPopunderUrl(urlStr, openerHost) {
    if (!urlStr || (!urlStr.startsWith("http://") && !urlStr.startsWith("https://"))) return false;
    const reqHost = normalizeHost(urlStr);
    if (!reqHost) return false;
    const isThirdParty = !openerHost || getRegistrableBase(reqHost) !== getRegistrableBase(openerHost);
    if (!isThirdParty) return false;

    const rules = activeRules;
    if (matchesDomainSet(reqHost, rules.exceptionDomains)) return false;

    if (
        matchesDomainSet(reqHost, rules.popupDomains) ||
        matchesDomainSet(reqHost, rules.adDomains) ||
        matchesDomainSet(reqHost, rules.thirdPartyAdDomains) ||
        matchesDomainSet(reqHost, rules.trackerDomains)
    ) {
        return true;
    }

    const lower = urlStr.toLowerCase();
    for (let i = 0; i < KNOWN_POPUNDER_PATH_HINTS.length; i++) {
        if (lower.includes(KNOWN_POPUNDER_PATH_HINTS[i])) return true;
    }

    for (let i = 0; i < rules.popupRules.length; i++) {
        if (matchesStructuredRule(rules.popupRules[i], lower, openerHost || "", true, TYPE_POPUP)) {
            return true;
        }
    }
    return false;
}

function checkAndClosePopupTab(tabId, urlStr, openerHost) {
    if (!shieldState.blockPopups || shieldState.shieldLevel === 0) return;
    if (openerHost && shieldState.pausedHosts.has(openerHost)) return;
    if (isBlockedPopunderUrl(urlStr, openerHost)) {
        recentPopupCandidates.delete(tabId);
        queueBlockedStat(openerHost || normalizeHost(urlStr), false);
        try {
            api.tabs.remove(tabId).catch(() => {});
        } catch (_e) {}
    }
}

if (api.webNavigation && api.webNavigation.onCreatedNavigationTarget) {
    api.webNavigation.onCreatedNavigationTarget.addListener((details) => {
        try {
            if (!shieldState.blockPopups || shieldState.shieldLevel === 0) return;
            const openerUrl = tabUrlMap.get(details.sourceTabId) || "";
            const openerHost = normalizeHost(openerUrl);
            recentPopupCandidates.set(details.tabId, { openerHost, createdAt: Date.now() });
            if (details.url) {
                checkAndClosePopupTab(details.tabId, details.url, openerHost);
            }
        } catch (_e) {}
    });
}

if (api.tabs && api.tabs.onCreated) {
    api.tabs.onCreated.addListener((tab) => {
        try {
            if (!shieldState.blockPopups || shieldState.shieldLevel === 0) return;
            if (typeof tab.openerTabId === "number" && tab.openerTabId >= 0) {
                const openerUrl = tabUrlMap.get(tab.openerTabId) || "";
                const openerHost = normalizeHost(openerUrl);
                recentPopupCandidates.set(tab.id, { openerHost, createdAt: Date.now() });
                const candUrl = tab.pendingUrl || tab.url || "";
                if (candUrl) {
                    checkAndClosePopupTab(tab.id, candUrl, openerHost);
                }
            }
        } catch (_e) {}
    });
}

if (api.tabs && api.tabs.onUpdated) {
    api.tabs.onUpdated.addListener((tabId, changeInfo) => {
        try {
            const url = changeInfo && changeInfo.url;
            if (!url) return;
            const cand = recentPopupCandidates.get(tabId);
            if (cand) {
                if (Date.now() - cand.createdAt > 4000) {
                    recentPopupCandidates.delete(tabId);
                } else {
                    checkAndClosePopupTab(tabId, url, cand.openerHost);
                }
            }
        } catch (_e) {}
    });
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
        tabJsBlockedMap.delete(tabId);
        recentPopupCandidates.delete(tabId);
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
            if (handleChunkedConfigMessage(msg)) {
                return;
            }
            if (msg.action === "APPLY_PRIVACY_CONFIG" && msg.config) {
                applySettingsObject(msg.config);
                broadcastLiveSettingsToTabs();
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

// Bootstrap runtime privacy config immediately on extension startup (triggers chunked rule stream)
api.runtime.sendNativeMessage("uhApp", { type: "GET_PRIVACY_RUNTIME" })
    .then((cfg) => { if (cfg) applySettingsObject(cfg); })
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
    if (message.type === "UH_CHECK_POPUP_URL") {
        const blocked = isBlockedPopunderUrl(message.url || "", message.openerHost || "");
        if (blocked) {
            queueBlockedStat(message.openerHost || "", false);
        }
        return Promise.resolve({ blocked });
    }
    if (message.type === "UH_NATIVE_CALL") {
        const payload = Object.assign({}, message.payload || {}, {
            pageUrl: message.pageUrl || ""
        });
        return api.runtime.sendNativeMessage("uhApp", payload).catch(() => null);
    }
    return undefined;
});
