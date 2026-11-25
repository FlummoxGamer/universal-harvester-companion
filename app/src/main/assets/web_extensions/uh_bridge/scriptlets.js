(function () {
    "use strict";
    if (window.__uhScriptletsLoaded) return;
    window.__uhScriptletsLoaded = true;

    const executedScriptlets = new Set();

    function isCloudflareChallengePage() {
        try {
            const host = (window.location.hostname || "").toLowerCase().replace(/^www\./, "");
            const path = (window.location.pathname || "").toLowerCase();
            if (path.startsWith("/cdn-cgi/") && !path.startsWith("/cdn-cgi/rum")) return true;
            if (
                host === "challenges.cloudflare.com" ||
                host === "hcaptcha.com" || host.endsWith(".hcaptcha.com") ||
                host === "recaptcha.net" || host.endsWith(".recaptcha.net") ||
                host === "arkoselabs.com" || host.endsWith(".arkoselabs.com") ||
                host === "funcaptcha.com" || host.endsWith(".funcaptcha.com") ||
                host === "geetest.com" || host.endsWith(".geetest.com") ||
                host === "friendlycaptcha.com" || host.endsWith(".friendlycaptcha.com") ||
                host === "frcapi.com" || host.endsWith(".frcapi.com") ||
                host === "captcha-delivery.com" || host.endsWith(".captcha-delivery.com")
            ) {
                return true;
            }
            if ((host === "google.com" || host.endsWith(".google.com") || host === "gstatic.com" || host.endsWith(".gstatic.com")) &&
                path.includes("/recaptcha/")) {
                return true;
            }
            const title = (document.title || "").toLowerCase();
            if (title.includes("just a moment") || title.includes("attention required") || title.includes("checking your browser")) {
                return true;
            }
            if (document.getElementById("challenge-running") || document.getElementById("cf-challenge-running") || document.querySelector(".cf-turnstile, .h-captcha, .g-recaptcha")) {
                return true;
            }
        } catch (_e) {}
        return false;
    }

    function getRegistrableBase(host) {
        const clean = String(host || "").toLowerCase().replace(/^www\./, "");
        const parts = clean.split(".");
        if (parts.length <= 2) return clean;
        const last2 = parts.slice(-2).join(".");
        if (last2.length <= 6 && (last2.startsWith("co.") || last2.startsWith("com.") || last2.startsWith("org.") || last2.startsWith("net.") || last2.startsWith("gov."))) {
            return parts.slice(-3).join(".");
        }
        return last2;
    }

    function fnv1aHash(str) {
        let h = 0x811c9dc5;
        for (let i = 0; i < str.length; i++) {
            h ^= str.charCodeAt(i);
            h = Math.imul(h, 0x01000193);
        }
        return h >>> 0;
    }

    // -------------------------------------------------------------------------
    // Native-Code Stealth Cloak (makes patched APIs pass Function.prototype.toString checks)
    // -------------------------------------------------------------------------
    const nativeCloakMap = new WeakMap();
    let nativeToStringPatched = false;
    function ensureNativeCloakInstalled() {
        if (nativeToStringPatched) return;
        nativeToStringPatched = true;
        try {
            const origToString = Function.prototype.toString;
            const cloakedToString = function toString() {
                if (nativeCloakMap.has(this)) {
                    return nativeCloakMap.get(this);
                }
                return origToString.apply(this, arguments);
            };
            nativeCloakMap.set(cloakedToString, "function toString() { [native code] }");
            Object.defineProperty(Function.prototype, "toString", {
                value: cloakedToString,
                writable: true,
                enumerable: false,
                configurable: true
            });
        } catch (_e) {}
    }

    function cloakNative(fn, name) {
        if (typeof fn !== "function") return fn;
        ensureNativeCloakInstalled();
        const label = name ? String(name) : (fn.name || "");
        nativeCloakMap.set(fn, "function " + label + "() { [native code] }");
        try {
            Object.defineProperty(fn, "name", { value: label, configurable: true });
        } catch (_e) {}
        return fn;
    }

    function defineCloakedGetter(target, prop, getter) {
        if (!target) return;
        const cloaked = cloakNative(getter, "get " + prop);
        try {
            Object.defineProperty(target, prop, {
                get: cloaked,
                enumerable: true,
                configurable: true
            });
        } catch (_e) {}
    }

    function reportBlockedRedirect(targetUrl, isPopupTab) {
        try {
            if (!targetUrl) return;
            document.dispatchEvent(new CustomEvent("UH_SCRIPTLET_BLOCKED_REDIRECT", {
                detail: {
                    sourceHost: (window.location.hostname || "").toLowerCase().replace(/^www\./, ""),
                    targetUrl: String(targetUrl),
                    isPopupTab: isPopupTab === true
                }
            }));
        } catch (_e) {}
    }

    function makeMatcher(needle) {
        if (!needle) return () => true;
        if (needle.length > 2 && needle.startsWith("/") && needle.endsWith("/")) {
            try {
                const re = new RegExp(needle.slice(1, -1), "i");
                return (s) => re.test(String(s || ""));
            } catch (_e) {}
        }
        const lower = String(needle).toLowerCase();
        return (s) => String(s || "").toLowerCase().includes(lower);
    }

    function parseConstantValue(raw) {
        switch (raw) {
            case "undefined": return undefined;
            case "false": return false;
            case "true": return true;
            case "null": return null;
            case "noopFunc": return function () {};
            case "trueFunc": return function () { return true; };
            case "falseFunc": return function () { return false; };
            case "emptyObj": return {};
            case "emptyArr": return [];
            case "''":
            case "\"\"":
            case "emptyStr": return "";
            default: {
                if (/^-?\d+$/.test(raw)) return parseInt(raw, 10);
                return raw;
            }
        }
    }

    function defineChainProperty(chain, descriptorFactory) {
        if (!chain) return;
        const parts = chain.split(".");
        let owner = window;
        for (let i = 0; i < parts.length - 1; i++) {
            const prop = parts[i];
            if (owner == null) return;
            if (typeof owner[prop] !== "object" || owner[prop] === null) {
                try { owner[prop] = {}; } catch (_e) { return; }
            }
            owner = owner[prop];
        }
        const leaf = parts[parts.length - 1];
        if (!leaf || owner == null) return;
        try {
            Object.defineProperty(owner, leaf, descriptorFactory(owner[leaf]));
        } catch (_e) {}
    }

    const ABORT_TOKEN = "__uh_abort_" + Math.random().toString(36).slice(2, 10);

    function installAbortErrorFilter() {
        if (window.__uhAbortErrFiltered) return;
        window.__uhAbortErrFiltered = true;
        const origOnError = window.onerror;
        window.onerror = function (msg) {
            if (typeof msg === "string" && msg.includes(ABORT_TOKEN)) {
                return true;
            }
            if (typeof origOnError === "function") {
                return origOnError.apply(this, arguments);
            }
            return false;
        };
    }

    function runScriptlet(name, args) {
        const key = name + "::" + (args || []).join("|");
        if (executedScriptlets.has(key)) return;
        executedScriptlets.add(key);

        const a0 = (args && args[0]) || "";
        const a1 = (args && args[1]) || "";
        const a2 = (args && args[2]) || "";

        try {
            switch (name) {
                case "abort-on-property-read":
                case "aopr": {
                    if (!a0) return;
                    installAbortErrorFilter();
                    defineChainProperty(a0, () => ({
                        configurable: true,
                        get() { throw new ReferenceError(ABORT_TOKEN); },
                        set() {}
                    }));
                    break;
                }
                case "abort-on-property-write":
                case "aopw": {
                    if (!a0) return;
                    installAbortErrorFilter();
                    defineChainProperty(a0, () => ({
                        configurable: true,
                        get() { return undefined; },
                        set() { throw new ReferenceError(ABORT_TOKEN); }
                    }));
                    break;
                }
                case "abort-current-inline-script":
                case "acis": {
                    if (!a0) return;
                    installAbortErrorFilter();
                    const matcher = makeMatcher(a1);
                    defineChainProperty(a0, (initialVal) => {
                        let cur = initialVal;
                        return {
                            configurable: true,
                            get() {
                                const cs = document.currentScript;
                                if (cs && cs.tagName === "SCRIPT" && !cs.src && matcher(cs.textContent || "")) {
                                    throw new ReferenceError(ABORT_TOKEN);
                                }
                                return cur;
                            },
                            set(v) { cur = v; }
                        };
                    });
                    break;
                }
                case "set-constant":
                case "set": {
                    if (!a0) return;
                    const val = parseConstantValue(a1);
                    defineChainProperty(a0, () => ({
                        configurable: true,
                        get() { return val; },
                        set() {}
                    }));
                    break;
                }
                case "no-setTimeout-if":
                case "nostif": {
                    const matcher = makeMatcher(a0);
                    const delayMatch = a1 ? parseInt(a1, 10) : NaN;
                    const origSetTimeout = window.setTimeout;
                    window.setTimeout = function (fn, delay) {
                        const fnStr = typeof fn === "function" ? fn.toString() : String(fn || "");
                        const delayOk = isNaN(delayMatch) || Number(delay) === delayMatch;
                        if (delayOk && matcher(fnStr)) {
                            return 0;
                        }
                        return origSetTimeout.apply(this, arguments);
                    };
                    break;
                }
                case "no-setInterval-if":
                case "nosiif": {
                    const matcher = makeMatcher(a0);
                    const delayMatch = a1 ? parseInt(a1, 10) : NaN;
                    const origSetInterval = window.setInterval;
                    window.setInterval = function (fn, delay) {
                        const fnStr = typeof fn === "function" ? fn.toString() : String(fn || "");
                        const delayOk = isNaN(delayMatch) || Number(delay) === delayMatch;
                        if (delayOk && matcher(fnStr)) {
                            return 0;
                        }
                        return origSetInterval.apply(this, arguments);
                    };
                    break;
                }
                case "addEventListener-defuser":
                case "aeld": {
                    const typeMatcher = makeMatcher(a0);
                    const fnMatcher = makeMatcher(a1);
                    const origAdd = EventTarget.prototype.addEventListener;
                    EventTarget.prototype.addEventListener = function (type, listener) {
                        if (typeMatcher(String(type || ""))) {
                            const fnStr = typeof listener === "function" ? listener.toString() : String(listener || "");
                            if (fnMatcher(fnStr)) return;
                        }
                        return origAdd.apply(this, arguments);
                    };
                    break;
                }
                case "window.open-defuser":
                case "nowoif": {
                    const matcher = makeMatcher(a0);
                    const origOpen = window.open;
                    window.open = function (url) {
                        if (matcher(String(url || ""))) {
                            return null;
                        }
                        return origOpen.apply(this, arguments);
                    };
                    break;
                }
                case "json-prune": {
                    if (!a0) return;
                    const paths = a0.split(/\s+/).filter(Boolean);
                    const pruneObj = (obj) => {
                        if (!obj || typeof obj !== "object") return obj;
                        for (let i = 0; i < paths.length; i++) {
                            const segs = paths[i].split(".");
                            let cur = obj;
                            for (let s = 0; s < segs.length - 1; s++) {
                                if (!cur || typeof cur !== "object") { cur = null; break; }
                                cur = cur[segs[s]];
                            }
                            if (cur && typeof cur === "object") {
                                delete cur[segs[segs.length - 1]];
                            }
                        }
                        return obj;
                    };
                    const origParse = JSON.parse;
                    JSON.parse = function () {
                        return pruneObj(origParse.apply(this, arguments));
                    };
                    if (typeof Response !== "undefined" && Response.prototype && Response.prototype.json) {
                        const origRespJson = Response.prototype.json;
                        Response.prototype.json = function () {
                            return origRespJson.apply(this, arguments).then(pruneObj);
                        };
                    }
                    break;
                }
                case "noop-fetch":
                case "no-fetch-if": {
                    const matcher = makeMatcher(a0);
                    const origFetch = window.fetch;
                    if (typeof origFetch === "function") {
                        window.fetch = function (input) {
                            const url = typeof input === "string" ? input : (input && input.url) || "";
                            if (matcher(url)) {
                                return Promise.resolve(new Response("", { status: 200, statusText: "OK" }));
                            }
                            return origFetch.apply(this, arguments);
                        };
                    }
                    break;
                }
                case "remove-attr":
                case "ra": {
                    if (!a0) return;
                    const attrs = a0.split("|").filter(Boolean);
                    const sel = a1 || "*";
                    const clean = () => {
                        try {
                            const nodes = document.querySelectorAll(sel);
                            for (let i = 0; i < nodes.length; i++) {
                                for (let j = 0; j < attrs.length; j++) {
                                    nodes[i].removeAttribute(attrs[j]);
                                }
                            }
                        } catch (_e) {}
                    };
                    clean();
                    document.addEventListener("DOMContentLoaded", clean, { once: true });
                    break;
                }
                case "set-attr": {
                    if (!a0 || !a1) return;
                    const apply = () => {
                        try {
                            const nodes = document.querySelectorAll(a0);
                            for (let i = 0; i < nodes.length; i++) {
                                nodes[i].setAttribute(a1, a2);
                            }
                        } catch (_e) {}
                    };
                    apply();
                    document.addEventListener("DOMContentLoaded", apply, { once: true });
                    break;
                }
                case "overlay-buster": {
                    const bust = () => {
                        try {
                            if (document.documentElement) document.documentElement.style.setProperty("overflow", "auto", "important");
                            if (document.body) document.body.style.setProperty("overflow", "auto", "important");
                        } catch (_e) {}
                    };
                    setTimeout(bust, 600);
                    setTimeout(bust, 1800);
                    break;
                }
                case "bab-defuser": {
                    const fakeBab = {
                        onDetected() { return fakeBab; },
                        onNotDetected(fn) { try { if (typeof fn === "function") setTimeout(fn, 1); } catch (_e) {} return fakeBab; },
                        on(detected, fn) { if (!detected && typeof fn === "function") setTimeout(fn, 1); return fakeBab; },
                        check() { return false; },
                        setOption() { return fakeBab; },
                        clearEvent() { return fakeBab; }
                    };
                    const fakeCtor = function () { return fakeBab; };
                    try {
                        window.blockAdBlock = fakeBab;
                        window.fuckAdBlock = fakeBab;
                        window.sniffAdBlock = fakeBab;
                        window.BlockAdBlock = fakeCtor;
                        window.FuckAdBlock = fakeCtor;
                        window.canRunAds = true;
                        window.isAdBlockActive = false;
                    } catch (_e) {}
                    break;
                }
            }
        } catch (_e) {}
    }

    // -------------------------------------------------------------------------
    // Runtime Guards: Bounce-Tracker, Background Play, WebRTC, Popunder/Meta-Refresh,
    // Spam Push Blocker, YouTube Ad-JSON Pruner & Hybrid Anti-Fingerprinting
    // -------------------------------------------------------------------------
    const POPUNDER_REGEX = /(jaya9|babu88|krikya|baji(?:live|999|88)|bj88|crickex|jeebet|jeetbuzz|jeetwin|mcw\d+|casinomcw|nagad88|popads|popcash|propellerads|propellerclick|exoclick|exosrv|realsrv|magsrv|pemsrv|juicyads|trafficjunky|trafficstars|adsterra|hilltopads|clickadu|monetag|pushame|popunder|afu\.php|adclick\.php|admaven|galaksion|richads|evadav|onclickalgo|highperformanceformat|profitablecpmrate|acscdn|effectivegatecpm|topcreativeformat|directlink|smartlink|1xbet|melbet|mostbet|betwinner|parimatch)/i;
    const PUSH_SPAM_SW_REGEX = /(pushame|monetag|propeller|evadav|richads|galaksion|admaven|popads|sw-check-permissions|\/push\/sw|\/sw-push)/i;

    let lastUserTap = 0;
    let lastTapTarget = null;
    function installUserGestureTracker() {
        if (window.__uhGestureTracked) return;
        window.__uhGestureTracked = true;
        const markTap = (ev) => {
            if (ev && ev.isTrusted) {
                lastUserTap = Date.now();
                lastTapTarget = ev.target || null;
            }
        };
        window.addEventListener("pointerdown", markTap, true);
        window.addEventListener("click", markTap, true);
        window.addEventListener("keydown", markTap, true);
    }

    // Bounce-tracker window.name sanitizer (prevents cross-site ID carrying)
    function sanitizeCrossSiteWindowName() {
        if (window.__uhWindowNameCleaned) return;
        window.__uhWindowNameCleaned = true;
        try {
            if (window.self === window.top && window.name && document.referrer) {
                const refHost = new URL(document.referrer).hostname;
                if (refHost && getRegistrableBase(refHost) !== getRegistrableBase(window.location.hostname)) {
                    window.name = "";
                }
            }
        } catch (_e) {}
    }

    function installBackgroundPlay() {
        if (window.__uhBgPlayInstalled) return;
        if (isCloudflareChallengePage()) return;
        window.__uhBgPlayInstalled = true;
        try {
            Object.defineProperty(document, "hidden", { get: () => false, configurable: true });
            Object.defineProperty(document, "visibilityState", { get: () => "visible", configurable: true });
            window.addEventListener("visibilitychange", (e) => { e.stopImmediatePropagation(); }, true);
            window.addEventListener("webkitvisibilitychange", (e) => { e.stopImmediatePropagation(); }, true);
        } catch (_e) {}
    }

    function installWebRtcBlock() {
        if (window.__uhWebRtcBlocked) return;
        window.__uhWebRtcBlocked = true;
        try {
            const noop = function () { throw new Error("WebRTC disabled by UH Privacy Guard"); };
            window.RTCPeerConnection = noop;
            window.webkitRTCPeerConnection = noop;
            window.mozRTCPeerConnection = noop;
        } catch (_e) {}
    }

    function installSpamNotificationGuard() {
        if (window.__uhNotifGuardInstalled) return;
        if (isCloudflareChallengePage()) return;
        window.__uhNotifGuardInstalled = true;
        installUserGestureTracker();
        try {
            if (typeof window.Notification !== "undefined" && typeof window.Notification.requestPermission === "function") {
                const origReq = window.Notification.requestPermission.bind(window.Notification);
                window.Notification.requestPermission = function (cb) {
                    const sinceTap = Date.now() - lastUserTap;
                    const curHost = window.location.hostname || "";
                    if (sinceTap > 1500 || POPUNDER_REGEX.test(curHost)) {
                        if (typeof cb === "function") {
                            try { cb("denied"); } catch (_e) {}
                        }
                        return Promise.resolve("denied");
                    }
                    return origReq.apply(this, arguments);
                };
            }
        } catch (_e) {}

        try {
            if (navigator.serviceWorker && typeof navigator.serviceWorker.register === "function") {
                const origReg = navigator.serviceWorker.register.bind(navigator.serviceWorker);
                navigator.serviceWorker.register = function (scriptURL) {
                    const urlStr = String(scriptURL || "");
                    if (PUSH_SPAM_SW_REGEX.test(urlStr) || POPUNDER_REGEX.test(window.location.hostname || "")) {
                        return Promise.reject(new DOMException("Blocked ad push service worker", "SecurityError"));
                    }
                    return origReg.apply(this, arguments);
                };
            }
        } catch (_e) {}
    }

    function installPopunderDefuser() {
        if (window.__uhPopupGuardInstalled) return;
        if (isCloudflareChallengePage()) return;
        window.__uhPopupGuardInstalled = true;
        installUserGestureTracker();

        const isSuspiciousPopupUrl = (urlStr) => {
            if (!urlStr) return false;
            return POPUNDER_REGEX.test(String(urlStr));
        };

        const isCrossOriginUrl = (urlStr) => {
            try {
                const u = new URL(String(urlStr), window.location.href);
                if (u.protocol !== "http:" && u.protocol !== "https:") return false;
                return getRegistrableBase(u.hostname) !== getRegistrableBase(window.location.hostname);
            } catch (_e) {
                return false;
            }
        };

        const isOverlayTrapElement = (el) => {
            if (!el || el.nodeType !== 1) return false;
            try {
                const cs = getComputedStyle(el);
                if (cs.position !== "fixed" && cs.position !== "absolute") return false;
                const z = parseInt(cs.zIndex, 10) || 0;
                const rect = el.getBoundingClientRect();
                const coversLargeArea = rect.width > window.innerWidth * 0.45 && rect.height > window.innerHeight * 0.35;
                const isTransparent = parseFloat(cs.opacity) < 0.08 || cs.backgroundColor === "rgba(0, 0, 0, 0)" || cs.backgroundColor === "transparent";
                const hasNoRealContent = (el.textContent || "").trim().length === 0 && !el.querySelector("video, input, form, img");
                return z >= 99 && coversLargeArea && isTransparent && hasNoRealContent;
            } catch (_e) {
                return false;
            }
        };

        // 1) Guard window.open including delayed about:blank -> .location = adUrl traps
        try {
            const origOpen = window.open;
            const createBlankTrapProxy = (target, features) => {
                let realWin = null;
                let closed = false;
                const handleNav = (dest) => {
                    const destStr = String(dest || "");
                    if (!destStr) return;
                    if (isSuspiciousPopupUrl(destStr) || isCrossOriginUrl(destStr)) {
                        closed = true;
                        reportBlockedRedirect(destStr, true);
                        return;
                    }
                    try {
                        realWin = origOpen.call(window, destStr, target, features);
                    } catch (_e) {}
                };
                const locProxy = {
                    get href() { return "about:blank"; },
                    set href(v) { handleNav(v); },
                    assign(v) { handleNav(v); },
                    replace(v) { handleNav(v); }
                };
                return {
                    get closed() { return realWin ? realWin.closed : closed; },
                    get location() { return realWin ? realWin.location : locProxy; },
                    set location(v) { handleNav(v); },
                    close() { closed = true; if (realWin) { try { realWin.close(); } catch (_e) {} } },
                    focus() {},
                    blur() {},
                    postMessage() {},
                    document: { write() {}, writeln() {}, open() {}, close() {} }
                };
            };

            window.open = function (url, target, features) {
                const urlStr = url == null ? "" : String(url).trim();
                if (isSuspiciousPopupUrl(urlStr)) {
                    reportBlockedRedirect(urlStr, true);
                    return null;
                }
                if (lastTapTarget && isOverlayTrapElement(lastTapTarget)) {
                    if (!urlStr || urlStr === "about:blank") {
                        return createBlankTrapProxy(target, features);
                    }
                    if (isCrossOriginUrl(urlStr)) {
                        reportBlockedRedirect(urlStr, true);
                        return null;
                    }
                }
                return origOpen.apply(this, arguments);
            };
        } catch (_e) {}

        // 2) Guard synthetic anchor.click() and form.submit() ONLY for confirmed popunder/betting traps
        try {
            const origAnchorClick = HTMLAnchorElement.prototype.click;
            HTMLAnchorElement.prototype.click = function () {
                try {
                    const href = this.href || "";
                    if (isSuspiciousPopupUrl(href)) {
                        reportBlockedRedirect(href, (this.target || "").toLowerCase() === "_blank");
                        return;
                    }
                } catch (_e) {}
                return origAnchorClick.apply(this, arguments);
            };
        } catch (_e) {}

        try {
            const origFormSubmit = HTMLFormElement.prototype.submit;
            HTMLFormElement.prototype.submit = function () {
                try {
                    const action = this.action || "";
                    if (isSuspiciousPopupUrl(action)) {
                        reportBlockedRedirect(action, (this.target || "").toLowerCase() === "_blank");
                        return;
                    }
                } catch (_e) {}
                return origFormSubmit.apply(this, arguments);
            };
        } catch (_e) {}

        // 3) Neutralize click-hijack transparent overlays and confirmed betting/popunder anchors
        try {
            document.addEventListener("click", (ev) => {
                try {
                    const target = ev.target;
                    if (target && isOverlayTrapElement(target)) {
                        ev.preventDefault();
                        ev.stopImmediatePropagation();
                        target.style.setProperty("display", "none", "important");
                        target.style.setProperty("pointer-events", "none", "important");
                        return;
                    }
                    const anchor = target && target.closest ? target.closest("a[href]") : null;
                    if (anchor && isSuspiciousPopupUrl(anchor.href || "")) {
                        ev.preventDefault();
                        ev.stopImmediatePropagation();
                        reportBlockedRedirect(anchor.href, anchor.target === "_blank");
                    }
                } catch (_e) {}
            }, true);
        } catch (_e) {}

        // 4) Defuse <meta http-equiv="refresh"> ONLY for confirmed popunder/betting redirects
        try {
            const scrubMetaRefresh = () => {
                const metas = document.querySelectorAll('meta[http-equiv="refresh" i]');
                for (let i = 0; i < metas.length; i++) {
                    const content = metas[i].getAttribute("content") || "";
                    const match = content.match(/url\s*=\s*['"]?([^'"\s;]+)/i);
                    if (match && match[1]) {
                        try {
                            const abs = new URL(match[1], window.location.href).href;
                            if (isSuspiciousPopupUrl(abs)) {
                                metas[i].remove();
                                reportBlockedRedirect(abs, false);
                            }
                        } catch (_e) {}
                    }
                }
            };
            scrubMetaRefresh();
            document.addEventListener("DOMContentLoaded", scrubMetaRefresh, { once: true });
        } catch (_e) {}
    }

    // -------------------------------------------------------------------------
    // Built-In Video / YouTube Ad-JSON Pruner & Skipper
    // -------------------------------------------------------------------------
    function installVideoAdPruner() {
        if (window.__uhVideoAdPrunerInstalled) return;
        const host = (window.location.hostname || "").toLowerCase();
        const isYouTube = host === "youtube.com" || host.endsWith(".youtube.com");
        if (!isYouTube) return;
        window.__uhVideoAdPrunerInstalled = true;

        const AD_KEYS = ["adPlacements", "playerAds", "adSlots", "adBreakHeartbeatParams"];
        const pruneAdJson = (obj) => {
            if (!obj || typeof obj !== "object") return obj;
            for (let i = 0; i < AD_KEYS.length; i++) {
                if (AD_KEYS[i] in obj) {
                    if (Array.isArray(obj[AD_KEYS[i]])) obj[AD_KEYS[i]] = [];
                    else delete obj[AD_KEYS[i]];
                }
            }
            if (obj.playerResponse && typeof obj.playerResponse === "object") {
                pruneAdJson(obj.playerResponse);
            }
            return obj;
        };

        try {
            const origParse = JSON.parse;
            JSON.parse = function () {
                const res = origParse.apply(this, arguments);
                return pruneAdJson(res);
            };
        } catch (_e) {}

        try {
            if (typeof Response !== "undefined" && Response.prototype && Response.prototype.json) {
                const origJson = Response.prototype.json;
                Response.prototype.json = function () {
                    return origJson.apply(this, arguments).then(pruneAdJson);
                };
            }
        } catch (_e) {}

        try {
            let playerResp = window.ytInitialPlayerResponse;
            if (playerResp) pruneAdJson(playerResp);
            Object.defineProperty(window, "ytInitialPlayerResponse", {
                configurable: true,
                get() { return playerResp; },
                set(v) { playerResp = pruneAdJson(v); }
            });
        } catch (_e) {}

        // Lightweight fallback skipper if an in-stream ad overlay renders
        try {
            setInterval(() => {
                const player = document.querySelector(".html5-video-player.ad-showing");
                if (!player) return;
                const skipBtn = document.querySelector(".ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button");
                if (skipBtn) {
                    try { skipBtn.click(); } catch (_e) {}
                }
                const video = player.querySelector("video");
                if (video && isFinite(video.duration) && video.duration > 0 && video.currentTime < video.duration - 0.2) {
                    try {
                        video.muted = true;
                        video.currentTime = video.duration;
                    } catch (_e) {}
                }
            }, 600);
        } catch (_e) {}
    }

    // -------------------------------------------------------------------------
    // Hybrid Anti-Fingerprinting (Uniform Crowd Core + Deterministic Per-Origin Farbling)
    // -------------------------------------------------------------------------
    let currentFpSeed = 0;
    let fpSeedLocked = false;
    function updateFpSeed(sessionSalt) {
        if (fpSeedLocked) return;
        const baseOrigin = getRegistrableBase(window.location.hostname || "local");
        const salt = sessionSalt || ("uh_boot_" + Math.floor(performance.timeOrigin || 1));
        currentFpSeed = fnv1aHash(salt + "|" + baseOrigin);
    }

    function installFingerprintHardening(sessionSalt) {
        if (isCloudflareChallengePage()) return;
        if (sessionSalt) updateFpSeed(sessionSalt);
        if (window.__uhFpHardened) return;
        window.__uhFpHardened = true;

        if (!currentFpSeed) updateFpSeed(sessionSalt);
        const getSeed = () => {
            fpSeedLocked = true;
            return currentFpSeed;
        };
        const channelDelta = (idx) => {
            const h = fnv1aHash(getSeed() + ":" + idx);
            return (h & 1) === 0 ? 1 : -1;
        };

        // 1) Prototype-level Gecko-native hardware & DNT hygiene (keep real PluginArray & real screen!)
        try {
            const navProto = (typeof Navigator !== "undefined" && Navigator.prototype) || Object.getPrototypeOf(navigator);
            if (navProto) {
                defineCloakedGetter(navProto, "hardwareConcurrency", function () { return 4; });
                defineCloakedGetter(navProto, "doNotTrack", function () { return "unspecified"; });
            }
        } catch (_e) {}

        // 2) Standardized Battery & Network Information APIs
        try {
            if (typeof navigator.getBattery === "function") {
                const fakeBattery = {
                    charging: true,
                    chargingTime: 0,
                    dischargingTime: Infinity,
                    level: 1.0,
                    onchargingchange: null,
                    onchargingtimechange: null,
                    ondischargingtimechange: null,
                    onlevelchange: null,
                    addEventListener: cloakNative(function () {}, "addEventListener"),
                    removeEventListener: cloakNative(function () {}, "removeEventListener"),
                    dispatchEvent: cloakNative(function () { return false; }, "dispatchEvent")
                };
                navigator.getBattery = cloakNative(function getBattery() { return Promise.resolve(fakeBattery); }, "getBattery");
            }
        } catch (_e) {}

        try {
            if ("connection" in navigator) {
                const fakeConn = {
                    effectiveType: "4g",
                    rtt: 50,
                    downlink: 10,
                    saveData: false,
                    onchange: null,
                    addEventListener: cloakNative(function () {}, "addEventListener"),
                    removeEventListener: cloakNative(function () {}, "removeEventListener"),
                    dispatchEvent: cloakNative(function () { return false; }, "dispatchEvent")
                };
                defineCloakedGetter(navigator, "connection", function () { return fakeConn; });
            }
        } catch (_e) {}

        // 3) Redact MediaDevices enumeration fingerprints
        try {
            if (navigator.mediaDevices && typeof navigator.mediaDevices.enumerateDevices === "function") {
                const origEnum = navigator.mediaDevices.enumerateDevices.bind(navigator.mediaDevices);
                navigator.mediaDevices.enumerateDevices = cloakNative(function enumerateDevices() {
                    return origEnum().then((list) => {
                        if (!Array.isArray(list)) return list;
                        return list.map((d) => ({
                            deviceId: "",
                            groupId: "",
                            kind: d.kind || "audioinput",
                            label: "",
                            toJSON() { return { deviceId: "", groupId: "", kind: this.kind, label: "" }; }
                        }));
                    });
                }, "enumerateDevices");
            }
        } catch (_e) {}

        // 4) Deterministic Canvas 2D, OffscreenCanvas & Font measureText farbling
        try {
            const farbleImageData = (imgData) => {
                if (!imgData || !imgData.data || imgData.data.length < 16) return imgData;
                const d = imgData.data;
                const pixelCount = (d.length / 4) | 0;
                const steps = Math.min(4, pixelCount);
                const s = getSeed();
                for (let k = 0; k < steps; k++) {
                    const pIdx = (fnv1aHash(s + ":px:" + k + ":" + pixelCount) % pixelCount) * 4;
                    const ch = k % 3;
                    const v = d[pIdx + ch];
                    const delta = channelDelta(k);
                    d[pIdx + ch] = v === 0 ? 1 : (v === 255 ? 254 : v + delta);
                }
                return imgData;
            };

            const origGetImageData = CanvasRenderingContext2D.prototype.getImageData;
            const cloneAndFarbleCanvas = (srcCanvas) => {
                if (!srcCanvas || srcCanvas.width <= 0 || srcCanvas.height <= 0 || srcCanvas.width > 1200 || srcCanvas.height > 800) {
                    return null;
                }
                const clone = document.createElement("canvas");
                clone.width = srcCanvas.width;
                clone.height = srcCanvas.height;
                const ctx = clone.getContext("2d");
                if (!ctx) return null;
                ctx.drawImage(srcCanvas, 0, 0);
                const imgData = origGetImageData.call(ctx, 0, 0, clone.width, clone.height);
                farbleImageData(imgData);
                ctx.putImageData(imgData, 0, 0);
                return clone;
            };

            CanvasRenderingContext2D.prototype.getImageData = cloakNative(function getImageData() {
                const imgData = origGetImageData.apply(this, arguments);
                try { return farbleImageData(imgData); } catch (_e) { return imgData; }
            }, "getImageData");

            // Font enumeration defense: tiny deterministic sub-pixel jitter on measureText.width
            const origMeasureText = CanvasRenderingContext2D.prototype.measureText;
            if (typeof origMeasureText === "function") {
                CanvasRenderingContext2D.prototype.measureText = cloakNative(function measureText(text) {
                    const metrics = origMeasureText.apply(this, arguments);
                    try {
                        if (metrics && typeof metrics.width === "number" && metrics.width > 0) {
                            const fontKey = (this.font || "") + "|" + String(text || "").slice(0, 32);
                            const jitter = (((fnv1aHash(getSeed() + ":ft:" + fontKey) & 511) / 511) - 0.5) * 0.0004;
                            const fakeWidth = metrics.width + jitter;
                            return new Proxy(metrics, {
                                get(target, prop) {
                                    if (prop === "width") return fakeWidth;
                                    const val = Reflect.get(target, prop, target);
                                    return typeof val === "function" ? val.bind(target) : val;
                                }
                            });
                        }
                    } catch (_e) {}
                    return metrics;
                }, "measureText");
            }

            const origToDataURL = HTMLCanvasElement.prototype.toDataURL;
            HTMLCanvasElement.prototype.toDataURL = cloakNative(function toDataURL() {
                try {
                    const clone = cloneAndFarbleCanvas(this);
                    if (clone) return origToDataURL.apply(clone, arguments);
                } catch (_e) {}
                return origToDataURL.apply(this, arguments);
            }, "toDataURL");

            const origToBlob = HTMLCanvasElement.prototype.toBlob;
            if (typeof origToBlob === "function") {
                HTMLCanvasElement.prototype.toBlob = cloakNative(function toBlob() {
                    try {
                        const clone = cloneAndFarbleCanvas(this);
                        if (clone) return origToBlob.apply(clone, arguments);
                    } catch (_e) {}
                    return origToBlob.apply(this, arguments);
                }, "toBlob");
            }

            if (typeof OffscreenCanvas !== "undefined" && OffscreenCanvas.prototype && typeof OffscreenCanvas.prototype.convertToBlob === "function") {
                const origConvertToBlob = OffscreenCanvas.prototype.convertToBlob;
                OffscreenCanvas.prototype.convertToBlob = cloakNative(function convertToBlob() {
                    try {
                        if (this.width > 0 && this.height > 0 && this.width <= 800 && this.height <= 600) {
                            const ctx = this.getContext("2d");
                            if (ctx) {
                                const px = ctx.getImageData(0, 0, 1, 1);
                                farbleImageData(px);
                                ctx.putImageData(px, 0, 0);
                            }
                        }
                    } catch (_e) {}
                    return origConvertToBlob.apply(this, arguments);
                }, "convertToBlob");
            }
        } catch (_e) {}

        // 5) WebGL standardized vendor/renderer + deterministic readPixels farbling
        try {
            const patchWebGLProto = (proto) => {
                if (!proto) return;
                const origGetParam = proto.getParameter;
                if (typeof origGetParam === "function") {
                    proto.getParameter = cloakNative(function getParameter(param) {
                        if (param === 37445 || param === 37446 || param === 7936 || param === 7937) return "Mozilla";
                        return origGetParam.apply(this, arguments);
                    }, "getParameter");
                }
                const origReadPixels = proto.readPixels;
                if (typeof origReadPixels === "function") {
                    proto.readPixels = cloakNative(function readPixels(x, y, w, h, format, type, pixels) {
                        const res = origReadPixels.apply(this, arguments);
                        try {
                            if (pixels && pixels instanceof Uint8Array && pixels.length >= 16) {
                                const s = getSeed();
                                for (let k = 0; k < 4; k++) {
                                    const idx = fnv1aHash(s + ":gl:" + k + ":" + pixels.length) % pixels.length;
                                    const v = pixels[idx];
                                    pixels[idx] = v === 0 ? 1 : (v === 255 ? 254 : v + channelDelta(k + 10));
                                }
                            }
                        } catch (_e) {}
                        return res;
                    }, "readPixels");
                }
            };
            if (typeof WebGLRenderingContext !== "undefined") patchWebGLProto(WebGLRenderingContext.prototype);
            if (typeof WebGL2RenderingContext !== "undefined") patchWebGLProto(WebGL2RenderingContext.prototype);
        } catch (_e) {}

        // 5B) WebGPU Adapter Info Normalization (2026-2027 GPU fingerprinting surface)
        try {
            if (navigator.gpu && typeof navigator.gpu.requestAdapter === "function") {
                const origReqAdapter = navigator.gpu.requestAdapter.bind(navigator.gpu);
                const uniformGpuInfo = Object.freeze({
                    vendor: "qualcomm",
                    architecture: "adreno",
                    device: "",
                    description: ""
                });
                navigator.gpu.requestAdapter = cloakNative(function requestAdapter() {
                    return origReqAdapter.apply(navigator.gpu, arguments).then((adapter) => {
                        if (!adapter) return adapter;
                        try {
                            return new Proxy(adapter, {
                                get(target, prop) {
                                    if (prop === "info") return uniformGpuInfo;
                                    if (prop === "requestAdapterInfo") {
                                        return cloakNative(function requestAdapterInfo() {
                                            return Promise.resolve(uniformGpuInfo);
                                        }, "requestAdapterInfo");
                                    }
                                    const val = Reflect.get(target, prop, target);
                                    return typeof val === "function" ? val.bind(target) : val;
                                }
                            });
                        } catch (_e) {
                            return adapter;
                        }
                    });
                }, "requestAdapter");
            }
        } catch (_e) {}

        // 5C) SpeechSynthesis Voices Normalization (eliminates OEM TTS voice fingerprinting)
        try {
            if (typeof window.speechSynthesis !== "undefined" && typeof window.speechSynthesis.getVoices === "function") {
                window.speechSynthesis.getVoices = cloakNative(function getVoices() {
                    return [];
                }, "getVoices");
            }
        } catch (_e) {}

        // 6) Full-buffer AudioContext farbling (fallback when cs_fp_sync didn't run)
        try {
            const patchedBuffers = new WeakSet();
            if (typeof AudioBuffer !== "undefined" && AudioBuffer.prototype && typeof AudioBuffer.prototype.getChannelData === "function") {
                const origGetChannelData = AudioBuffer.prototype.getChannelData;
                AudioBuffer.prototype.getChannelData = cloakNative(function getChannelData() {
                    const data = origGetChannelData.apply(this, arguments);
                    try {
                        if (data && data.length > 16 && !patchedBuffers.has(data)) {
                            patchedBuffers.add(data);
                            const s = getSeed();
                            const gain = 1 + ((((fnv1aHash(s + ":augain") & 4095) / 4095) - 0.5) * 0.005);
                            const bias = ((((fnv1aHash(s + ":aubias") & 2047) / 2047) - 0.5) * 1e-5);
                            for (let i = 0; i < data.length; i++) {
                                if (data[i] !== 0) data[i] = data[i] * gain + bias;
                            }
                        }
                    } catch (_e) {}
                    return data;
                }, "getChannelData");
            }
            if (typeof AnalyserNode !== "undefined" && AnalyserNode.prototype) {
                const origFloatFreq = AnalyserNode.prototype.getFloatFrequencyData;
                if (typeof origFloatFreq === "function") {
                    AnalyserNode.prototype.getFloatFrequencyData = cloakNative(function getFloatFrequencyData(arr) {
                        origFloatFreq.apply(this, arguments);
                        try {
                            if (arr && arr.length > 4) {
                                const idx = fnv1aHash(getSeed() + ":aff") % arr.length;
                                if (isFinite(arr[idx])) arr[idx] += channelDelta(21) * 1e-4;
                            }
                        } catch (_e) {}
                    }, "getFloatFrequencyData");
                }
                const origByteFreq = AnalyserNode.prototype.getByteFrequencyData;
                if (typeof origByteFreq === "function") {
                    AnalyserNode.prototype.getByteFrequencyData = cloakNative(function getByteFrequencyData(arr) {
                        origByteFreq.apply(this, arguments);
                        try {
                            if (arr && arr.length > 4) {
                                const idx = fnv1aHash(getSeed() + ":abf") % arr.length;
                                if (arr[idx] > 0 && arr[idx] < 255) arr[idx] += channelDelta(22);
                            }
                        } catch (_e) {}
                    }, "getByteFrequencyData");
                }
            }
        } catch (_e) {}

        // 7) Deterministic sub-pixel ClientRects noise (imperceptible to layout, defeats rect hashing)
        try {
            const adjustRect = (r) => {
                if (!r || typeof r.width !== "number" || (r.width === 0 && r.height === 0)) return r;
                try {
                    const rectJitter = (((getSeed() & 1023) / 1023) - 0.5) * 0.0001;
                    return new DOMRect(r.x + rectJitter, r.y + rectJitter, r.width + rectJitter, r.height + rectJitter);
                } catch (_e) {
                    return r;
                }
            };
            const origGetBCR = Element.prototype.getBoundingClientRect;
            Element.prototype.getBoundingClientRect = cloakNative(function getBoundingClientRect() {
                return adjustRect(origGetBCR.apply(this, arguments));
            }, "getBoundingClientRect");
            if (typeof Range !== "undefined" && Range.prototype && typeof Range.prototype.getClientRects === "function") {
                const origGetClientRects = Range.prototype.getClientRects;
                Range.prototype.getClientRects = cloakNative(function getClientRects() {
                    const list = origGetClientRects.apply(this, arguments);
                    try {
                        if (!list || list.length === 0) return list;
                        const arr = [];
                        for (let i = 0; i < list.length; i++) arr.push(adjustRect(list[i]));
                        arr.item = cloakNative(function item(idx) { return arr[idx] || null; }, "item");
                        return arr;
                    } catch (_e) {
                        return list;
                    }
                }, "getClientRects");
            }
        } catch (_e) {}
    }

    // Legacy strict screen/UTC spoofing retired to preserve zero-captcha/zero-bot-block browsing
    function installStrictFingerprintHardening() {
        window.__uhStrictFpInstalled = true;
    }

    function applyConfig(cfg) {
        if (!cfg || typeof cfg !== "object") return;
        if (isCloudflareChallengePage()) return;

        sanitizeCrossSiteWindowName();
        if (cfg.backgroundPlay === true) installBackgroundPlay();
        if (cfg.webrtcPolicy === 3) installWebRtcBlock();

        const shieldActive = !cfg.paused && (cfg.shieldLevel || 0) > 0;
        if (cfg.blockPopups === true && shieldActive) {
            installPopunderDefuser();
            installSpamNotificationGuard();
        }
        if (shieldActive) {
            installVideoAdPruner();
        }
        if (cfg.antiFingerprint === true && !cfg.paused) {
            installFingerprintHardening(cfg.fpSessionSalt || "");
            if (cfg.strictFingerprint === true) {
                installStrictFingerprintHardening();
            }
        }
        if (Array.isArray(cfg.scriptlets) && shieldActive) {
            for (let i = 0; i < cfg.scriptlets.length; i++) {
                const s = cfg.scriptlets[i];
                if (s && s.name) {
                    runScriptlet(s.name, s.args || []);
                }
            }
        }
    }

    // Synchronous document_start boot (eliminates early fingerprint/popunder race condition)
    try {
        const root = document.documentElement;
        if (root && root.dataset && root.dataset.uhBoot) {
            const rawBoot = root.dataset.uhBoot;
            delete root.dataset.uhBoot;
            const bootCfg = JSON.parse(rawBoot);
            applyConfig(bootCfg);
        }
    } catch (_e) {}

    document.addEventListener("UH_APPLY_SCRIPTLET_CONFIG", (ev) => {
        if (ev && ev.detail) applyConfig(ev.detail);
    });

    document.dispatchEvent(new CustomEvent("UH_SCRIPTLETS_READY"));
})();
