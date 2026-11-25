const api = typeof browser !== "undefined" ? browser : chrome;

let nativePort = null;

// -----------------------------------------------------------------------------
// UH Shield v5 Runtime Engine — Lazy-RegExp Inverted Index, Zero-Freeze Sync,
// Accurate Live Stats, Cloudflare-Safe URL Cleaner & Clean Redirect Shims
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
    noopjs: "data:application/javascript;base64,KGZ1bmN0aW9uKCl7fSkoKTs=",
    adsbygoogle: "data:application/javascript;base64,KHdpbmRvdy5hZHNieWdvb2dsZT13aW5kb3cuYWRzYnlnb29nbGV8fFtdKS5wdXNoPWZ1bmN0aW9uKCl7fTs=",
    gpt: "data:application/javascript;base64,KHdpbmRvdy5nb29nbGV0YWc9d2luZG93Lmdvb2dsZXRhZ3x8e2NtZDpbXX0pLmFwaVJlYWR5PXRydWU7",
    popads: "data:application/javascript;base64,KGZ1bmN0aW9uKCl7fSkoKTs=",
    vast: "data:application/xml;base64,PFZBU1QgdmVyc2lvbj0iMy4wIi8+",
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
        blockByDomain: new Map(),
        blockByToken: new Map(),
        blockGeneric: [],
        importantRules: [],
        excByDomain: new Map(),
        excByToken: new Map(),
        excGeneric: [],
        popupRules: []
    };
}

let activeRules = createEmptyRuleEngine();
let stagingSyncId = null;
let stagingRules = null;
let stagingSettings = null;
let activeMainHost = "";

const shieldState = {
    shieldLevel: 2,
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
const tabJsBlockedMap = new Map();
const recentPopupCandidates = new Map();
const pendingBlockedByHost = new Map();
let flushTimer = null;
let jsReportTimer = null;
const pendingJsReportHosts = new Set();

// Safe tracking params ONLY — never strip Cloudflare (__cf_chl_tk), auth, or streaming redirect tokens (_t, _r, feature, pp)
const TRACKING_QUERY_PARAMS = new Set([
    "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "twclid",
    "yclid", "ymclid", "igshid", "igsh", "ref_src", "ref_url",
    "oly_anon_id", "oly_enc_id", "vero_id", "wickedid",
    "_hsenc", "_hsmi", "hsctatracking", "mkt_tok", "trk", "trkid",
    "spm", "scm", "aff_id", "aff_sub", "click_id", "clickid", "irclickid",
    "s_cid", "s_kwcid", "ef_id", "icid", "ncid", "sr_share", "share_id",
    "guccounter", "guce_referrer", "guce_referrer_sig",
    "is_from_webapp", "sender_device", "sender_web_id",
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

// LAZY RegExp compilation: ingesting 500k rules takes milliseconds instead of freezing on 40k new RegExp() calls!
function indexWireRule(engine, wire) {
    if (!Array.isArray(wire) || wire.length < 5) return;
    const pattern = wire[0];
    if (!pattern) return;
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

    let substring = null;
    let needsRegex = false;
    if (isRegex || pattern.includes("*") || pattern.includes("^") || pattern.includes("|")) {
        needsRegex = true;
    } else {
        substring = pattern.toLowerCase();
    }

    const rule = {
        rawPattern: needsRegex ? pattern : null,
        isRegex,
        regex: undefined, // lazily compiled on first pre-filter match
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

function getOrCompileRegex(rule) {
    if (rule.regex !== undefined) return rule.regex;
    if (!rule.rawPattern) {
        rule.regex = null;
        return null;
    }
    if (rule.isRegex) {
        try {
            rule.regex = new RegExp(rule.rawPattern, "i");
        } catch (_e) {
            rule.regex = null;
        }
    } else {
        rule.regex = compileAbpPatternToRegex(rule.rawPattern);
    }
    return rule.regex;
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
    const re = getOrCompileRegex(rule);
    return re !== null ? re.test(lowerUrl) : false;
}

function findMatchingRuleInIndex(domainMap, tokenMap, genericArr, reqHost, lowerUrl, urlTokens, pageHost, isThirdParty, typeBit) {
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

    for (let i = 0; i < genericArr.length; i++) {
        if (matchesStructuredRule(genericArr[i], lowerUrl, pageHost, isThirdParty, typeBit)) {
            return genericArr[i];
        }
    }
    return null;
}

function resolveSmartRedirect(rule, details, lowerUrl) {
    if (rule && rule.redirect && REDIRECT_DATA_URLS[rule.redirect]) {
        return REDIRECT_DATA_URLS[rule.redirect];
    }
    if (details.type === "script") {
        if (lowerUrl.includes("adsbygoogle.js")) return REDIRECT_DATA_URLS.adsbygoogle;
        if (lowerUrl.includes("gpt.js") || lowerUrl.includes("pubads_impl")) return REDIRECT_DATA_URLS.gpt;
    }
    if (details.type === "xmlhttprequest" && (lowerUrl.includes("vast") || lowerUrl.includes("vmap"))) {
        return REDIRECT_DATA_URLS.vast;
    }
    return null;
}

// -----------------------------------------------------------------------------
// Live Stats & Smart JS Reporting (Fast 350ms Batch Flush)
// -----------------------------------------------------------------------------
function queueBlockedStat(pageHost, isTracker, fallbackHost) {
    const key = pageHost || activeMainHost || fallbackHost || "web";
    let entry = pendingBlockedByHost.get(key);
    if (!entry) {
        entry = { ads: 0, trackers: 0 };
        pendingBlockedByHost.set(key, entry);
    }
    if (isTracker) entry.trackers += 1;
    else entry.ads += 1;

    if (!flushTimer) {
        flushTimer = setTimeout(flushBlockedStats, 350);
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
                for (let i = 0; i < items.length; i++) indexWireRule(stagingRules, items[i]);
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
// Cloudflare-Safe ClearURLs + Redirect Link Unwrapper + Auto De-AMP
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
    // Never rewrite Cloudflare challenge or clearance URLs
    if (urlStr.includes("/cdn-cgi/") || urlStr.includes("__cf_chl")) return null;

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
                    if (nextHost) activeMainHost = nextHost;
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

                const tabUrl = (typeof details.tabId === "number" && details.tabId >= 0) ? (tabUrlMap.get(details.tabId) || "") : "";
                const pageUrl = tabUrl || details.documentUrl || details.originUrl || "";
                const pageHost = normalizeHost(pageUrl) || activeMainHost;
                const isPaused = !!(pageHost && shieldState.pausedHosts.has(pageHost));

                const reqHost = normalizeHost(details.url);
                if (!reqHost) return {};
                const isThirdParty = !pageHost || getRegistrableBase(reqHost) !== getRegistrableBase(pageHost);
                const typeBit = mapDetailsTypeToBit(details.type);

                // Never block Cloudflare Turnstile / challenge assets
                if (reqHost === "challenges.cloudflare.com" || details.url.includes("/cdn-cgi/")) {
                    return {};
                }

                // 1) Smart JavaScript Blocking
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

                    // 2A) $important rules
                    if (rules.importantRules.length > 0) {
                        for (let i = 0; i < rules.importantRules.length; i++) {
                            const r = rules.importantRules[i];
                            if (matchesStructuredRule(r, lowerUrl, pageHost, isThirdParty, typeBit)) {
                                queueBlockedStat(pageHost, r.isTracker, reqHost);
                                const shim = resolveSmartRedirect(r, details, lowerUrl);
                                if (shim) return { redirectUrl: shim };
                                notifyElementCollapse(details);
                                return { cancel: true };
                            }
                        }
                    }

                    // 2B) @@ Exception rules
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
                        const allowDomainFirstParty = shieldState.shieldLevel >= 2;

                        if ((allowDomainFirstParty || isThirdParty) && matchesDomainSet(reqHost, rules.trackerDomains)) {
                            queueBlockedStat(pageHost, true, reqHost);
                            notifyElementCollapse(details);
                            return { cancel: true };
                        }
                        if ((allowDomainFirstParty || isThirdParty) && matchesDomainSet(reqHost, rules.adDomains)) {
                            queueBlockedStat(pageHost, false, reqHost);
                            const shim = resolveSmartRedirect(null, details, lowerUrl);
                            if (shim) return { redirectUrl: shim };
                            notifyElementCollapse(details);
                            return { cancel: true };
                        }
                        if (isThirdParty) {
                            if (matchesDomainSet(reqHost, rules.thirdPartyTrackerDomains)) {
                                queueBlockedStat(pageHost, true, reqHost);
                                notifyElementCollapse(details);
                                return { cancel: true };
                            }
                            if (matchesDomainSet(reqHost, rules.thirdPartyAdDomains)) {
                                queueBlockedStat(pageHost, false, reqHost);
                                const shim = resolveSmartRedirect(null, details, lowerUrl);
                                if (shim) return { redirectUrl: shim };
                                notifyElementCollapse(details);
                                return { cancel: true };
                            }
                        }

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
                                const allowInStandard = isThirdParty || matched.firstPartyOnly || (typeBit === TYPE_IMAGE) || (typeBit === TYPE_SUBDOCUMENT);
                                if (shieldState.shieldLevel >= 2 || allowInStandard) {
                                    queueBlockedStat(pageHost, matched.isTracker, reqHost);
                                    const shim = resolveSmartRedirect(matched, details, lowerUrl);
                                    if (shim) return { redirectUrl: shim };
                                    notifyElementCollapse(details);
                                    return { cancel: true };
                                }
                            }
                        }
                    }
                }

                // 3) ClearURLs on sub_frame & xmlhttprequest GET
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
// Popunder Ad-Tab Trap Blocker
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
        queueBlockedStat(openerHost || normalizeHost(urlStr), false, normalizeHost(urlStr));
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
            tabUrlMap.set(tabId, url);
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

// Seed tabUrlMap for any already-open tabs
if (api.tabs && api.tabs.query) {
    api.tabs.query({}).then((tabs) => {
        if (!tabs) return;
        for (const t of tabs) {
            if (t.url) {
                tabUrlMap.set(t.id, t.url);
                if (t.active) {
                    const h = normalizeHost(t.url);
                    if (h) activeMainHost = h;
                }
            }
        }
    }).catch(() => {});
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
    .then((cfg) => { if (cfg) applySettingsObject(cfg); })
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
        const blocked = isBlockedPopunderUrl(message.url || "", message.openerHost || "");
        if (blocked) {
            queueBlockedStat(message.openerHost || "", false, normalizeHost(message.url || ""));
        }
        return Promise.resolve({ blocked });
    }
    if (message.type === "UH_CONTENT_BLOCKED_AD") {
        queueBlockedStat(message.host || activeMainHost, false, message.host || "");
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
