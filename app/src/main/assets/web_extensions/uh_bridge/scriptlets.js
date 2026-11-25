(function () {
    "use strict";
    if (window.__uhScriptletsLoaded) return;
    window.__uhScriptletsLoaded = true;

    const executedScriptlets = new Set();

    function isCloudflareChallengePage() {
        try {
            const path = (window.location.pathname || "").toLowerCase();
            if (path.startsWith("/cdn-cgi/")) return true;
            const title = (document.title || "").toLowerCase();
            if (title.includes("just a moment") || title.includes("attention required") || title.includes("checking your browser")) {
                return true;
            }
            if (document.getElementById("challenge-running") || document.getElementById("cf-challenge-running") || document.querySelector(".cf-turnstile")) {
                return true;
            }
        } catch (_e) {}
        return false;
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
    // Runtime Guards: Background Play, WebRTC, Popunder Trap Defuser, Fingerprint
    // -------------------------------------------------------------------------
    const POPUNDER_REGEX = /(popads|popcash|propellerads|exoclick|exosrv|juicyads|trafficjunky|trafficstars|adsterra|hilltopads|clickadu|monetag|pushame|popunder|afu\.php|click\.php\?|admaven|galaksion|richads|evadav|onclickalgo|highperformanceformat|profitablecpmrate)/i;

    function installBackgroundPlay() {
        if (window.__uhBgPlayInstalled) return;
        // Never spoof visibility on Cloudflare Turnstile/challenge pages
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

    function installPopunderDefuser() {
        if (window.__uhPopupGuardInstalled) return;
        if (isCloudflareChallengePage()) return;
        window.__uhPopupGuardInstalled = true;
        try {
            const origOpen = window.open;
            let lastUserTap = 0;
            let lastTapTarget = null;
            const markTap = (ev) => {
                if (ev && ev.isTrusted) {
                    lastUserTap = Date.now();
                    lastTapTarget = ev.target || null;
                }
            };
            window.addEventListener("pointerdown", markTap, true);
            window.addEventListener("click", markTap, true);
            window.addEventListener("keydown", markTap, true);

            const isSuspiciousPopupUrl = (urlStr) => {
                if (!urlStr) return false;
                return POPUNDER_REGEX.test(String(urlStr));
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

            window.open = function (url, target, features) {
                const sinceTap = Date.now() - lastUserTap;
                if (sinceTap > 1200) {
                    return null;
                }
                if (isSuspiciousPopupUrl(url)) {
                    return null;
                }
                if (lastTapTarget && (isOverlayTrapElement(lastTapTarget) || lastTapTarget.tagName === "VIDEO")) {
                    try {
                        const targetHost = new URL(String(url || ""), window.location.href).hostname;
                        if (targetHost && targetHost !== window.location.hostname) {
                            return null;
                        }
                    } catch (_e) {}
                }
                return origOpen.apply(this, arguments);
            };

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
                    if (anchor && anchor.target === "_blank") {
                        const href = anchor.href || "";
                        if (isSuspiciousPopupUrl(href) || (!ev.isTrusted && href && !href.startsWith(window.location.origin))) {
                            ev.preventDefault();
                            ev.stopImmediatePropagation();
                        }
                    }
                } catch (_e) {}
            }, true);
        } catch (_e) {}
    }

    function installFingerprintHardening() {
        if (window.__uhFpHardened) return;
        if (isCloudflareChallengePage()) return;
        window.__uhFpHardened = true;
        try {
            const seed = (Math.random() * 100000) | 0;
            const origToDataURL = HTMLCanvasElement.prototype.toDataURL;
            HTMLCanvasElement.prototype.toDataURL = function () {
                try {
                    if (this.width > 0 && this.height > 0 && this.width <= 600 && this.height <= 300) {
                        const ctx = this.getContext("2d");
                        if (ctx) {
                            const px = ctx.getImageData(0, 0, 1, 1);
                            px.data[0] = (px.data[0] ^ ((seed & 1) + 1)) & 255;
                            ctx.putImageData(px, 0, 0);
                        }
                    }
                } catch (_e) {}
                return origToDataURL.apply(this, arguments);
            };
            if (navigator && typeof navigator.hardwareConcurrency === "number" && navigator.hardwareConcurrency > 4) {
                Object.defineProperty(navigator, "hardwareConcurrency", { get: () => 4, configurable: true });
            }
            if (navigator && typeof navigator.deviceMemory === "number") {
                Object.defineProperty(navigator, "deviceMemory", { get: () => 4, configurable: true });
            }
        } catch (_e) {}
    }

    function applyConfig(cfg) {
        if (!cfg || typeof cfg !== "object") return;
        if (isCloudflareChallengePage()) return;
        if (cfg.backgroundPlay === true) installBackgroundPlay();
        if (cfg.webrtcPolicy === 3) installWebRtcBlock();
        if (cfg.blockPopups === true && !cfg.paused && (cfg.shieldLevel || 0) > 0) {
            installPopunderDefuser();
        }
        if (cfg.antiFingerprint === true && !cfg.paused) {
            installFingerprintHardening();
        }
        if (Array.isArray(cfg.scriptlets) && !cfg.paused && (cfg.shieldLevel || 0) > 0) {
            for (let i = 0; i < cfg.scriptlets.length; i++) {
                const s = cfg.scriptlets[i];
                if (s && s.name) {
                    runScriptlet(s.name, s.args || []);
                }
            }
        }
    }

    document.addEventListener("UH_APPLY_SCRIPTLET_CONFIG", (ev) => {
        if (ev && ev.detail) applyConfig(ev.detail);
    });

    document.dispatchEvent(new CustomEvent("UH_SCRIPTLETS_READY"));
})();
