// -----------------------------------------------------------------------------
// UH Bridge Background Module 1: Shared State, Smart JS Engine & Rule Indexer
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

    // Captcha & human-verification infrastructure hosts (auto-exempt from script hooks & blocking)
    const CAPTCHA_VERIFICATION_DOMAINS = new Set([
        "challenges.cloudflare.com",
        "hcaptcha.com",
        "recaptcha.net",
        "arkoselabs.com",
        "funcaptcha.com",
        "geetest.com",
        "friendlycaptcha.com",
        "frcapi.com",
        "captcha-delivery.com"
    ]);

    function isCaptchaOrChallengeUrl(urlStr, reqHost) {
        const lowerUrl = String(urlStr || "").toLowerCase();
        const host = (reqHost ? String(reqHost).toLowerCase().replace(/^www\./, "") : normalizeHost(urlStr));
        if (lowerUrl.includes("/cdn-cgi/") && !lowerUrl.includes("/cdn-cgi/rum")) return true;
        if (lowerUrl.includes("friendly-challenge")) return true;
        if (!host) return false;
        if (matchesDomainSet(host, CAPTCHA_VERIFICATION_DOMAINS)) return true;
        if ((host === "google.com" || host.endsWith(".google.com") || host === "gstatic.com" || host.endsWith(".gstatic.com")) &&
            lowerUrl.includes("/recaptcha/")) {
            return true;
        }
        return false;
    }

    // Preserves genuine GeckoView release version (zero JA4/Turnstile mismatch) while stripping WebView "; wv" tokens
    function sanitizeGeckoUserAgent(rawUa) {
        if (!rawUa || typeof rawUa !== "string") return rawUa;
        return rawUa.replace(/;\s*wv\b/gi, "").replace(/\s{2,}/g, " ").trim();
    }

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

    // Tier-1 Smart JavaScript Dependency Allowlist (core CDNs, frameworks, auth, captcha, payments, media)
    // Evaluated AFTER UH Shield ad/tracker filtering so trackers hosted on shared CDNs are still blocked.
    const ESSENTIAL_JS_ALLOW_DOMAINS = new Set([
        // Framework & Library CDNs
        "cdnjs.cloudflare.com", "cdn.jsdelivr.net", "jsdelivr.net", "unpkg.com", "esm.sh",
        "cdn.skypack.dev", "ajax.googleapis.com", "fonts.googleapis.com", "apis.google.com",
        "gstatic.com", "code.jquery.com", "jquery.com", "bootstrapcdn.com", "maxcdn.bootstrapcdn.com",
        "stackpath.bootstrapcdn.com", "cdn.tailwindcss.com", "ajax.aspnetcdn.com", "aspnetcdn.com",
        "use.fontawesome.com", "kit.fontawesome.com", "use.typekit.net", "polyfill.io",
        "cdn.polyfill.io", "yastatic.net", "cdn.bootcdn.net", "lib.baomitu.com", "staticfile.org",
        "ga.jspm.io", "cdn.unpkg.com",
        // Security, Anti-Bot, Captcha & Auth/SSO
        "challenges.cloudflare.com", "hcaptcha.com", "js.hcaptcha.com", "newassets.hcaptcha.com",
        "recaptcha.net", "accounts.google.com", "appleid.cdn-apple.com", "auth0.com",
        "cdn.auth0.com", "okta.com", "oktacdn.com", "clerk.accounts.dev", "clerk.com",
        "login.microsoftonline.com", "msauth.net", "msftauth.net", "aadcdn.msftauth.net",
        "arkoselabs.com", "funcaptcha.com", "geetest.com", "friendlycaptcha.com",
        // Payments & Checkout
        "js.stripe.com", "stripe.com", "m.stripe.network", "checkout.stripe.com",
        "paypal.com", "paypalobjects.com", "braintreegateway.com", "js.braintreegateway.com",
        "adyen.com", "checkoutshopper-live.adyen.com", "squareup.com", "squarecdn.com",
        "razorpay.com", "checkout.razorpay.com", "pay.google.com", "cdn.shopify.com",
        "shopifycdn.com", "klarnacdn.net",
        // Media, Video Players & Major App Asset CDNs
        "ytimg.com", "googlevideo.com", "ggpht.com", "vimeocdn.com", "jwpcdn.com",
        "cdn.bitmovin.com", "vjs.zencdn.net", "players.brightcove.net", "twimg.com",
        "fbcdn.net", "cdninstagram.com", "githubassets.com", "githubusercontent.com",
        "reddstatic.com", "redditstatic.com", "media-amazon.com", "ssl-images-amazon.com",
        "images-na.ssl-images-amazon.com", "nflxext.com", "nflxso.net", "scdn.co",
        "twitchcdn.net", "jtvnw.net", "cdn.discordapp.com", "wikimedia.org", "wp.com",
        "gravatar.com", "disquscdn.com", "s.imgur.com", "sndcdn.com", "tiktokcdn.com",
        "alicdn.com", "akamaized.net", "edgekey.net", "cloudfront.net", "fastly.net",
        "azureedge.net", "msecnd.net", "b-cdn.net", "bunny.net", "wixstatic.com",
        "static1.squarespace.com", "assets-global.website-files.com", "cloudflare.com"
    ]);

    // Known popunder / gambling / betting redirector URL patterns & domains
    const KNOWN_POPUNDER_PATH_HINTS = [
        "/popunder", "/afu.php", "/redirect/pop", "/pops/",
        "/interstitial/ad", "/pop.php", "/jump/next.php", "/adclick.php",
        "/directlink", "/smartlink", "/onclick.php", "/pop.html"
    ];

    const BETTING_POPUNDER_HOST_RE = /(?:^|[.-])(jaya9\w*|babu88\w*|krikya\w*|baji(?:live|999|88)?\w*|bj88\w*|crickex\w*|jeebet\w*|jeetbuzz\w*|jeetwin\w*|mcw\d+\w*|casinomcw\w*|nagad88\w*|1xbet\w*|1xbit\w*|melbet\w*|mostbet\w*|betwinner\w*|parimatch\w*|linebet\w*|megapari\w*|4rabet\w*|22bet\w*|1win\w*|popads\w*|popcash\w*|propeller(?:ads|click)\w*|adsterra\w*|monetag\w*|clickadu\w*|hilltopads\w*|exoclick\w*|exosrv\w*|realsrv\w*|magsrv\w*|pemsrv\w*|admaven\w*|galaksion\w*|evadav\w*|richads\w*|pushame\w*|highperformanceformat\w*|profitablecpmrate\w*|effectivegatecpm\w*|topcreativeformat\w*|onclickalgo\w*|onclickperformance\w*|onclkds\w*|go2affise\w*|offergate\w*|wpadmngr\w*)\./i;

    const AFFILIATE_TRAP_QUERY_RE = /[?&](?:af|aff|aff_id|btag|zoneid|subid|clickid|campaign_id)=[a-z0-9_-]{4,}/i;
    const GAMBLING_OR_REDIRECT_HOST_RE = /(casino|bet\d*|888|999|777|365|win\d*|slot|spin|poker|ludo|rummy|bangla|bdt|cricketbet|jackpot|bonus|promo|pop|clk|trk|redir)/i;
    const REDIRECT_PATH_RE = /^\/(?:click|go|out|track|visit|r|redir|redirect|offer|jump|link)\b/i;

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

    function extractBrandToken(host) {
        const base = getRegistrableBase(host);
        if (!base) return "";
        const label = base.split(".")[0] || "";
        return label.replace(/[^a-z0-9]/g, "");
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

    // 3-Tier Smart JavaScript Dependency Resolver:
    // Allows core CDNs/auth/captcha/payment/media + same-brand companion asset hosts
    // while blocking untrusted 3rd-party scripts cleanly without site breakage.
    function isEssentialSmartJsHost(reqHost, pageHost, urlStr) {
        if (!reqHost) return true;
        // Tier 1: Core infrastructure allowlist
        if (matchesDomainSet(reqHost, ESSENTIAL_JS_ALLOW_DOMAINS)) {
            // Never exempt Google One-Tap or Cloudflare RUM telemetry scripts
            if (reqHost === "accounts.google.com" && urlStr && urlStr.includes("/gsi/")) return false;
            if (reqHost === "static.cloudflareinsights.com") return false;
            return true;
        }
        // Google reCAPTCHA & YouTube iframe API hosted on google.com / youtube.com
        if (reqHost === "google.com" && urlStr && urlStr.includes("/recaptcha/")) return true;
        if (reqHost === "youtube.com" && urlStr && (urlStr.includes("/iframe_api") || urlStr.includes("/s/player/"))) return true;

        // Tier 2: Same-brand companion asset host (e.g., site.com -> cdn-site.net / sitestatic.com)
        if (pageHost) {
            const pageBrand = extractBrandToken(pageHost);
            const reqBrand = extractBrandToken(reqHost);
            if (pageBrand.length >= 4 && reqBrand.length >= 4) {
                if (reqBrand === pageBrand || reqBrand.includes(pageBrand) || pageBrand.includes(reqBrand)) {
                    return true;
                }
            }
            // Subdomain-labeled asset mirror (e.g., cdn.site.net, static.site.org, assets.site.io)
            if (pageBrand.length >= 4 && /^(?:cdn|static|assets|media|js|s\d*|img|player)\./i.test(reqHost) && reqHost.includes(pageBrand)) {
                return true;
            }
        }
        return false;
    }

    function isConfirmedPopunderOrBettingTrap(urlStr, reqHost, sourceHost, rules) {
        if (!urlStr || !reqHost) return false;
        const lower = urlStr.toLowerCase();

        // 1) Explicit $popup domain lists (EasyList, uAssets, IndianList + ALWAYS_POPUP_DOMAINS)
        if (rules && matchesDomainSet(reqHost, rules.popupDomains)) {
            return true;
        }

        // 2) Known betting / gambling / popunder redirector host families (catches rotating mirror domains)
        if (BETTING_POPUNDER_HOST_RE.test(reqHost)) {
            return true;
        }

        // 3) Confirmed popunder URL path signatures
        for (let i = 0; i < KNOWN_POPUNDER_PATH_HINTS.length; i++) {
            if (lower.includes(KNOWN_POPUNDER_PATH_HINTS[i])) return true;
        }

        // 4) Affiliate trap query (?af=, ?aff=, ?btag=, ?zoneid=, ?clickid=) paired with gambling/redirect host or path
        if (AFFILIATE_TRAP_QUERY_RE.test(urlStr)) {
            if (GAMBLING_OR_REDIRECT_HOST_RE.test(reqHost)) return true;
            try {
                const u = new URL(urlStr);
                if (u.pathname === "/" || REDIRECT_PATH_RE.test(u.pathname)) {
                    // Only treat bare-root ?af= / ?btag= as a trap when cross-origin from another site
                    if (sourceHost && getRegistrableBase(reqHost) !== getRegistrableBase(sourceHost)) {
                        if (/[?&](?:af|btag|zoneid)=/i.test(urlStr)) return true;
                    }
                }
            } catch (_e) {}
        }

        // 5) Explicit $popup structured rules from compiled filter lists
        if (rules && rules.popupRules && rules.popupRules.length > 0) {
            for (let i = 0; i < rules.popupRules.length; i++) {
                if (matchesStructuredRule(rules.popupRules[i], lower, sourceHost || "", true, TYPE_POPUP)) {
                    return true;
                }
            }
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
        isCaptchaOrChallengeUrl,
        sanitizeGeckoUserAgent,
        isEssentialSmartJsHost,
        isConfirmedPopunderOrBettingTrap,
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
