// -----------------------------------------------------------------------------
// UH Bridge Background Module 1: Shared State & Lazy-RegExp Rule Indexer
// -----------------------------------------------------------------------------
(function () {
    const api = typeof browser !== "undefined" ? browser : chrome;

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
        vmap: "data:application/xml;base64,PHZtYXA6Vk1BUCB4bWxuczp2bWFwPSJodHRwOi8vd3d3LmlhYi5uZXQvdm1hcC0xLjAiIHZlcnNpb249IjEuMCIvPg==",
        ima: "data:application/javascript;base64,KGZ1bmN0aW9uKCl7d2luZG93Lmdvb2dsZT13aW5kb3cuZ29vZ2xlfHx7fTt3aW5kb3cuZ29vZ2xlLmltYT13aW5kb3cuZ29vZ2xlLmltYXx8e0Fkc0xvYWRlcjpmdW5jdGlvbigpe3RoaXMuYWRkRXZlbnRMaXN0ZW5lcj1mdW5jdGlvbigpe307dGhpcy5yZXF1ZXN0QWRzPWZ1bmN0aW9uKCl7fTt9fTt9KSgpOw==",
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
            popupRules: [],
            removeParamRules: []
        };
    }

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

    // LAZY RegExp compilation: ingesting 500k rules takes milliseconds instead of freezing on 40k new RegExp() calls
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
            if (lowerUrl.includes("imasdk.googleapis.com") || lowerUrl.includes("/ima3.js")) return REDIRECT_DATA_URLS.ima;
        }
        if (details.type === "xmlhttprequest" || details.type === "fetch" || details.type === "other") {
            if (lowerUrl.includes("vmap")) return REDIRECT_DATA_URLS.vmap;
            if (lowerUrl.includes("vast") || lowerUrl.includes("/gampad/ads")) return REDIRECT_DATA_URLS.vast;
        }
        return null;
    }

    // Local Network / Private-IP Guard (blocks public sites from port-scanning localhost & LAN)
    function isPrivateOrLanHost(host) {
        if (!host) return false;
        const h = host.toLowerCase();
        if (
            h === "localhost" ||
            h === "0.0.0.0" ||
            h === "::1" ||
            h === "[::1]" ||
            h.endsWith(".localhost") ||
            h.endsWith(".local") ||
            h.endsWith(".internal") ||
            h.endsWith(".lan") ||
            h.endsWith(".home.arpa")
        ) {
            return true;
        }
        // IPv4 private / loopback / link-local / CGNAT ranges
        const m = h.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
        if (m) {
            const a = Number(m[1]);
            const b = Number(m[2]);
            if (a === 127 || a === 10 || a === 0) return true;
            if (a === 192 && b === 168) return true;
            if (a === 169 && b === 254) return true;
            if (a === 172 && b >= 16 && b <= 31) return true;
            if (a === 100 && b >= 64 && b <= 127) return true;
        }
        // IPv6 ULA (fc00::/7) or link-local (fe80::/10)
        if (h.startsWith("[fc") || h.startsWith("[fd") || h.startsWith("[fe80:")) {
            return true;
        }
        return false;
    }

    // Known first-party proxied tracker & telemetry endpoints
    const FIRST_PARTY_TRACKER_PATH_RE = /(\/gtm\.js(?:[?#]|$)|\/g\/collect(?:[?#]|$)|\/j\/collect(?:[?#]|$)|\/r\/collect(?:[?#]|$)|\/_vercel\/insights|\/_vercel\/speed-insights|\/cdn-cgi\/rum(?:[?#]|$)|\/matomo\.js(?:[?#]|$)|\/piwik\.js(?:[?#]|$)|\/matomo\.php(?:[?#]|$)|\/piwik\.php(?:[?#]|$)|\/telemetry\/v\d|\/smetrics\/|\/b\/ss\/|\/analytics\/collect(?:[?#]|$)|\/events\/track(?:[?#]|$))/i;

    globalThis.__uhBg = {
        api,
        TYPE_SCRIPT,
        TYPE_IMAGE,
        TYPE_SUBDOCUMENT,
        TYPE_POPUP,
        activeRules: createEmptyRuleEngine(),
        activeMainHost: "",
        cnameBlockedHosts: new Map(),
        shieldState: {
            shieldLevel: 2,
            pausedHosts: new Set(),
            redirectAllowOnceHosts: new Set(),
            smartBlockJs: false,
            jsAllowOnceHosts: new Set(),
            jsAllowAlwaysHosts: new Set(),
            cookieBanners: true,
            clearUrls: true,
            deAmp: true,
            blockPopups: true,
            antiFingerprint: true,
            strictFingerprint: false,
            fpSessionSalt: "",
            refererTrim: true,
            lanGuard: true,
            annoyances: true,
            cnameUnmask: true,
            dnt: false,
            gpc: true,
            webrtcPolicy: 1,
            backgroundPlay: true,
            forceDarkWeb: false,
            enhanceDark: false,
            darkBypassHosts: new Set()
        },
        tabUrlMap: new Map(),
        tabJsBlockedMap: new Map(),
        recentPopupCandidates: new Map(),
        createEmptyRuleEngine,
        normalizeHost,
        getRegistrableBase,
        matchesDomainSet,
        matchesDomainList,
        mapDetailsTypeToBit,
        compileAbpPatternToRegex,
        tokenizeUrl,
        indexWireRule,
        matchesStructuredRule,
        findMatchingRuleInIndex,
        resolveSmartRedirect,
        isPrivateOrLanHost,
        FIRST_PARTY_TRACKER_PATH_RE
    };
})();
