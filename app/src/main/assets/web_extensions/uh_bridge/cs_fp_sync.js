// -----------------------------------------------------------------------------
// UH Bridge Content Module 0: Zero-Race Synchronous document_start Guard
// Arms Unified Anti-Fingerprinting (Brave Randomization + Tor Alignment) and
// Precision Click-Hijack / Betting-Popunder Defuser BEFORE byte 1 of page JS.
// -----------------------------------------------------------------------------
(function () {
    "use strict";

    // This function is serialized and executed synchronously in the MAIN page world
    // via inline <script>, with automatic Gecko wrappedJSObject fallback for strict CSP.
    function __uhSyncPageGuard(bootCfg) {
        if (window.__uhSyncArmed) return;
        window.__uhSyncArmed = true;

        var cfg = bootCfg || {};
        var UNIFIED_UA = "Mozilla/5.0 (Android 14; Mobile; rv:132.0) Gecko/132.0 Firefox/132.0";

        function isCfChallenge() {
            try {
                var h = (window.location.hostname || "").toLowerCase();
                if (h === "challenges.cloudflare.com") return true;
                var p = (window.location.pathname || "").toLowerCase();
                if (p.indexOf("/cdn-cgi/") === 0) return true;
            } catch (_e) {}
            return false;
        }
        if (isCfChallenge()) return;

        function getRegistrableBase(host) {
            var clean = String(host || "").toLowerCase().replace(/^www\./, "");
            var parts = clean.split(".");
            if (parts.length <= 2) return clean;
            var last2 = parts.slice(-2).join(".");
            if (
                last2.length <= 6 &&
                (last2.indexOf("co.") === 0 ||
                    last2.indexOf("com.") === 0 ||
                    last2.indexOf("org.") === 0 ||
                    last2.indexOf("net.") === 0 ||
                    last2.indexOf("gov.") === 0)
            ) {
                return parts.slice(-3).join(".");
            }
            return last2;
        }

        function fnv1a(str) {
            var h = 0x811c9dc5;
            for (var i = 0; i < str.length; i++) {
                h ^= str.charCodeAt(i);
                h = Math.imul(h, 0x01000193);
            }
            return h >>> 0;
        }

        // ---------------------------------------------------------------------
        // Native-Code Stealth Cloak (makes all patched APIs pass toString checks)
        // ---------------------------------------------------------------------
        var nativeCloakMap = new WeakMap();
        var origFnToString = Function.prototype.toString;
        try {
            var cloakedToString = function toString() {
                if (nativeCloakMap.has(this)) {
                    return nativeCloakMap.get(this);
                }
                return origFnToString.apply(this, arguments);
            };
            nativeCloakMap.set(cloakedToString, "function toString() { [native code] }");
            Object.defineProperty(Function.prototype, "toString", {
                value: cloakedToString,
                writable: true,
                enumerable: false,
                configurable: true
            });
        } catch (_e) {}

        function cloak(fn, name) {
            if (typeof fn !== "function") return fn;
            var label = name ? String(name) : (fn.name || "");
            nativeCloakMap.set(fn, "function " + label + "() { [native code] }");
            try {
                Object.defineProperty(fn, "name", { value: label, configurable: true });
            } catch (_e) {}
            return fn;
        }

        function defineGetter(target, prop, getter) {
            if (!target) return;
            var cloaked = cloak(getter, "get " + prop);
            try {
                Object.defineProperty(target, prop, {
                    get: cloaked,
                    enumerable: true,
                    configurable: true
                });
            } catch (_e) {}
        }

        // Expose cloak helper so scriptlets.js shares the exact same WeakMap
        try {
            Object.defineProperty(window, "__uhCloakNative", {
                value: cloak,
                writable: false,
                enumerable: false,
                configurable: true
            });
        } catch (_e) {}

        // ---------------------------------------------------------------------
        // PART 1: Unified Anti-Fingerprinting (Brave Randomization + Tor Alignment)
        // ---------------------------------------------------------------------
        if (cfg.antiFingerprint !== false && !cfg.paused) {
            window.__uhFpHardened = true;
            window.__uhStrictFpInstalled = true;

            // Per-session + per-origin cryptographic seed (rotates across sessions & sites)
            var originBase = getRegistrableBase(window.location.hostname || "local");
            var salt = cfg.fpSessionSalt || "";
            if (!salt) {
                try {
                    salt = sessionStorage.getItem("__uh_fp_rand") || "";
                    if (!salt) {
                        if (typeof crypto !== "undefined" && crypto.getRandomValues) {
                            var buf = new Uint32Array(2);
                            crypto.getRandomValues(buf);
                            salt = buf[0].toString(16) + buf[1].toString(16);
                        } else {
                            salt = String(Math.random()).slice(2) + String(Date.now());
                        }
                        sessionStorage.setItem("__uh_fp_rand", salt);
                    }
                } catch (_e) {
                    salt = String(Math.random()).slice(2) + "_" + Math.floor(performance.timeOrigin || 1);
                }
            }
            var fpSeed = fnv1a(salt + "|" + originBase);
            var deltaSign = function (idx) {
                return (fnv1a(fpSeed + ":s:" + idx) & 1) === 0 ? 1 : -1;
            };

            // 1) Gecko-Native Navigator & Hardware Alignment (100% consistent with HTTP headers)
            // IMPORTANT: Leave navigator.deviceMemory, navigator.cpuClass, navigator.userAgentData undefined
            // because real Firefox/Gecko does NOT expose them (defining them creates a unique contradiction).
            var uniformLangs = Object.freeze(["en-US", "en"]);
            defineGetter(navigator, "userAgent", function () { return UNIFIED_UA; });
            defineGetter(navigator, "appVersion", function () { return "5.0 (Android 14)"; });
            defineGetter(navigator, "platform", function () { return "Linux aarch64"; });
            defineGetter(navigator, "oscpu", function () { return "Linux aarch64"; });
            defineGetter(navigator, "vendor", function () { return ""; });
            defineGetter(navigator, "productSub", function () { return "20100101"; });
            defineGetter(navigator, "hardwareConcurrency", function () { return 4; });
            defineGetter(navigator, "maxTouchPoints", function () { return 5; });
            defineGetter(navigator, "language", function () { return "en-US"; });
            defineGetter(navigator, "languages", function () { return uniformLangs; });
            defineGetter(navigator, "doNotTrack", function () { return cfg.dnt === true ? "1" : "unspecified"; });

            try {
                var emptyPlugins = Object.assign([], {
                    item: cloak(function item() { return null; }, "item"),
                    namedItem: cloak(function namedItem() { return null; }, "namedItem"),
                    refresh: cloak(function refresh() {}, "refresh")
                });
                defineGetter(navigator, "plugins", function () { return emptyPlugins; });
                defineGetter(navigator, "mimeTypes", function () { return emptyPlugins; });
            } catch (_e) {}

            // 2) Tor-Style Timezone (UTC) & Locale (en-US) Full Alignment
            try {
                Date.prototype.getTimezoneOffset = cloak(function getTimezoneOffset() {
                    return 0;
                }, "getTimezoneOffset");

                var origDateToString = Date.prototype.toString;
                var origDateToTimeString = Date.prototype.toTimeString;
                var DAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
                var MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
                var pad2 = function (n) { return n < 10 ? "0" + n : String(n); };

                Date.prototype.toString = cloak(function toString() {
                    var t = this.getTime();
                    if (isNaN(t)) return origDateToString.call(this);
                    return (
                        DAYS[this.getUTCDay()] + " " +
                        MONTHS[this.getUTCMonth()] + " " +
                        pad2(this.getUTCDate()) + " " +
                        this.getUTCFullYear() + " " +
                        pad2(this.getUTCHours()) + ":" +
                        pad2(this.getUTCMinutes()) + ":" +
                        pad2(this.getUTCSeconds()) + " GMT+0000 (Coordinated Universal Time)"
                    );
                }, "toString");

                Date.prototype.toTimeString = cloak(function toTimeString() {
                    var t = this.getTime();
                    if (isNaN(t)) return origDateToTimeString.call(this);
                    return (
                        pad2(this.getUTCHours()) + ":" +
                        pad2(this.getUTCMinutes()) + ":" +
                        pad2(this.getUTCSeconds()) + " GMT+0000 (Coordinated Universal Time)"
                    );
                }, "toTimeString");
            } catch (_e) {}

            try {
                if (typeof Intl !== "undefined" && Intl.DateTimeFormat && Intl.DateTimeFormat.prototype) {
                    var origResolved = Intl.DateTimeFormat.prototype.resolvedOptions;
                    if (typeof origResolved === "function") {
                        Intl.DateTimeFormat.prototype.resolvedOptions = cloak(function resolvedOptions() {
                            var res = origResolved.apply(this, arguments);
                            if (res && typeof res === "object") {
                                res.timeZone = "UTC";
                                res.locale = "en-US";
                            }
                            return res;
                        }, "resolvedOptions");
                    }
                }
            } catch (_e) {}

            // 3) Tor-Style Screen, Window Outer Dimensions, DPR & CSS @media Alignment
            var BUCKET_W = 390;
            var BUCKET_H = 844;
            var BUCKET_DPR = 2;
            defineGetter(screen, "width", function () { return BUCKET_W; });
            defineGetter(screen, "height", function () { return BUCKET_H; });
            defineGetter(screen, "availWidth", function () { return BUCKET_W; });
            defineGetter(screen, "availHeight", function () { return BUCKET_H; });
            defineGetter(screen, "availLeft", function () { return 0; });
            defineGetter(screen, "availTop", function () { return 0; });
            defineGetter(screen, "left", function () { return 0; });
            defineGetter(screen, "top", function () { return 0; });
            defineGetter(screen, "colorDepth", function () { return 24; });
            defineGetter(screen, "pixelDepth", function () { return 24; });
            defineGetter(window, "outerWidth", function () { return BUCKET_W; });
            defineGetter(window, "outerHeight", function () { return BUCKET_H; });
            defineGetter(window, "screenX", function () { return 0; });
            defineGetter(window, "screenY", function () { return 0; });
            defineGetter(window, "screenLeft", function () { return 0; });
            defineGetter(window, "screenTop", function () { return 0; });
            defineGetter(window, "devicePixelRatio", function () { return BUCKET_DPR; });

            try {
                if (typeof window.matchMedia === "function") {
                    var origMatchMedia = window.matchMedia.bind(window);
                    var evalAlignedMedia = function (q) {
                        var s = String(q || "").toLowerCase();
                        var m;
                        if ((m = s.match(/\(\s*(min-|max-)?device-width\s*:\s*([\d.]+)px\s*\)/))) {
                            var v = parseFloat(m[2]);
                            if (m[1] === "min-") return BUCKET_W >= v;
                            if (m[1] === "max-") return BUCKET_W <= v;
                            return BUCKET_W === v;
                        }
                        if ((m = s.match(/\(\s*(min-|max-)?device-height\s*:\s*([\d.]+)px\s*\)/))) {
                            var vh = parseFloat(m[2]);
                            if (m[1] === "min-") return BUCKET_H >= vh;
                            if (m[1] === "max-") return BUCKET_H <= vh;
                            return BUCKET_H === vh;
                        }
                        if ((m = s.match(/\(\s*(min-|max-)?(?:resolution|-webkit-(?:min-|max-)?device-pixel-ratio)\s*:\s*([\d.]+)(dppx|dpi|x)?\s*\)/))) {
                            var val = parseFloat(m[2]);
                            var unit = m[3] || "x";
                            var dprVal = unit === "dpi" ? (val / 96) : val;
                            var isMin = m[1] === "min-" || s.indexOf("-webkit-min-") >= 0;
                            var isMax = m[1] === "max-" || s.indexOf("-webkit-max-") >= 0;
                            if (isMin) return BUCKET_DPR >= dprVal - 0.001;
                            if (isMax) return BUCKET_DPR <= dprVal + 0.001;
                            return Math.abs(BUCKET_DPR - dprVal) < 0.01;
                        }
                        if ((m = s.match(/\(\s*(min-|max-)?color\s*:\s*(\d+)\s*\)/))) {
                            var c = parseInt(m[2], 10);
                            if (m[1] === "min-") return 8 >= c;
                            if (m[1] === "max-") return 8 <= c;
                            return 8 === c;
                        }
                        return null;
                    };
                    window.matchMedia = cloak(function matchMedia(query) {
                        var mql = origMatchMedia(query);
                        var override = evalAlignedMedia(query);
                        if (override === null || !mql) return mql;
                        try {
                            return new Proxy(mql, {
                                get: function (target, prop) {
                                    if (prop === "matches") return override;
                                    var val = Reflect.get(target, prop, target);
                                    return typeof val === "function" ? val.bind(target) : val;
                                }
                            });
                        } catch (_e) {
                            return mql;
                        }
                    }, "matchMedia");
                }
            } catch (_e) {}

            // 4) Battery, Network Info, MediaDevices, WebGPU & SpeechSynthesis
            try {
                if (typeof navigator.getBattery === "function") {
                    var fakeBattery = {
                        charging: true, chargingTime: 0, dischargingTime: Infinity, level: 1.0,
                        onchargingchange: null, onchargingtimechange: null, ondischargingtimechange: null, onlevelchange: null,
                        addEventListener: cloak(function addEventListener() {}, "addEventListener"),
                        removeEventListener: cloak(function removeEventListener() {}, "removeEventListener"),
                        dispatchEvent: cloak(function dispatchEvent() { return false; }, "dispatchEvent")
                    };
                    navigator.getBattery = cloak(function getBattery() { return Promise.resolve(fakeBattery); }, "getBattery");
                }
            } catch (_e) {}

            try {
                if ("connection" in navigator) {
                    var fakeConn = {
                        effectiveType: "4g", rtt: 50, downlink: 10, saveData: false, onchange: null,
                        addEventListener: cloak(function addEventListener() {}, "addEventListener"),
                        removeEventListener: cloak(function removeEventListener() {}, "removeEventListener"),
                        dispatchEvent: cloak(function dispatchEvent() { return false; }, "dispatchEvent")
                    };
                    defineGetter(navigator, "connection", function () { return fakeConn; });
                }
            } catch (_e) {}

            try {
                if (navigator.mediaDevices && typeof navigator.mediaDevices.enumerateDevices === "function") {
                    var origEnum = navigator.mediaDevices.enumerateDevices.bind(navigator.mediaDevices);
                    navigator.mediaDevices.enumerateDevices = cloak(function enumerateDevices() {
                        return origEnum().then(function (list) {
                            if (!Array.isArray(list)) return list;
                            return list.map(function (d) {
                                return {
                                    deviceId: "", groupId: "", kind: d.kind || "audioinput", label: "",
                                    toJSON: function () { return { deviceId: "", groupId: "", kind: this.kind, label: "" }; }
                                };
                            });
                        });
                    }, "enumerateDevices");
                }
            } catch (_e) {}

            try {
                if (typeof window.speechSynthesis !== "undefined" && typeof window.speechSynthesis.getVoices === "function") {
                    window.speechSynthesis.getVoices = cloak(function getVoices() { return []; }, "getVoices");
                }
            } catch (_e) {}

            try {
                if (navigator.gpu && typeof navigator.gpu.requestAdapter === "function") {
                    var origReqAdapter = navigator.gpu.requestAdapter.bind(navigator.gpu);
                    var uniformGpuInfo = Object.freeze({ vendor: "qualcomm", architecture: "adreno", device: "", description: "" });
                    navigator.gpu.requestAdapter = cloak(function requestAdapter() {
                        return origReqAdapter.apply(navigator.gpu, arguments).then(function (adapter) {
                            if (!adapter) return adapter;
                            try {
                                return new Proxy(adapter, {
                                    get: function (target, prop) {
                                        if (prop === "info") return uniformGpuInfo;
                                        if (prop === "requestAdapterInfo") {
                                            return cloak(function requestAdapterInfo() { return Promise.resolve(uniformGpuInfo); }, "requestAdapterInfo");
                                        }
                                        var val = Reflect.get(target, prop, target);
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

            // 5) Canvas 2D + WebGL Canvas + OffscreenCanvas Brave-Style Farbling
            var webglCanvases = new WeakMap();
            try {
                var origGetContext = HTMLCanvasElement.prototype.getContext;
                HTMLCanvasElement.prototype.getContext = cloak(function getContext(type, attrs) {
                    var t = String(type || "").toLowerCase();
                    if (t === "webgl" || t === "webgl2" || t === "experimental-webgl") {
                        var nextAttrs = Object.assign({}, attrs || {}, { preserveDrawingBuffer: true });
                        var glCtx = origGetContext.call(this, type, nextAttrs);
                        if (glCtx) webglCanvases.set(this, glCtx);
                        return glCtx;
                    }
                    return origGetContext.apply(this, arguments);
                }, "getContext");

                var farblePixels = function (data, tag) {
                    if (!data || data.length < 16) return;
                    var pixelCount = (data.length / 4) | 0;
                    var steps = Math.min(8, pixelCount);
                    for (var k = 0; k < steps; k++) {
                        var pIdx = (fnv1a(fpSeed + ":" + tag + ":" + k + ":" + pixelCount) % pixelCount) * 4;
                        var ch = k % 3;
                        var v = data[pIdx + ch];
                        var d = deltaSign(k + (tag === "gl" ? 31 : 7));
                        data[pIdx + ch] = v === 0 ? 1 : (v === 255 ? 254 : v + d);
                    }
                };

                var origGetImageData = CanvasRenderingContext2D.prototype.getImageData;
                var cloneAndFarble = function (srcCanvas) {
                    if (!srcCanvas || srcCanvas.width <= 0 || srcCanvas.height <= 0 || srcCanvas.width > 1600 || srcCanvas.height > 1200) {
                        return null;
                    }
                    var clone = document.createElement("canvas");
                    clone.width = srcCanvas.width;
                    clone.height = srcCanvas.height;
                    var ctx2d = origGetContext.call(clone, "2d");
                    if (!ctx2d) return null;
                    var isGl = webglCanvases.has(srcCanvas);
                    ctx2d.drawImage(srcCanvas, 0, 0);
                    var imgData = origGetImageData.call(ctx2d, 0, 0, clone.width, clone.height);
                    farblePixels(imgData.data, isGl ? "gl" : "2d");
                    ctx2d.putImageData(imgData, 0, 0);
                    return clone;
                };

                CanvasRenderingContext2D.prototype.getImageData = cloak(function getImageData() {
                    var imgData = origGetImageData.apply(this, arguments);
                    try {
                        if (imgData && imgData.data) farblePixels(imgData.data, "2d");
                    } catch (_e) {}
                    return imgData;
                }, "getImageData");

                var origToDataURL = HTMLCanvasElement.prototype.toDataURL;
                HTMLCanvasElement.prototype.toDataURL = cloak(function toDataURL() {
                    try {
                        var clone = cloneAndFarble(this);
                        if (clone) return origToDataURL.apply(clone, arguments);
                    } catch (_e) {}
                    return origToDataURL.apply(this, arguments);
                }, "toDataURL");

                var origToBlob = HTMLCanvasElement.prototype.toBlob;
                if (typeof origToBlob === "function") {
                    HTMLCanvasElement.prototype.toBlob = cloak(function toBlob() {
                        try {
                            var clone = cloneAndFarble(this);
                            if (clone) return origToBlob.apply(clone, arguments);
                        } catch (_e) {}
                        return origToBlob.apply(this, arguments);
                    }, "toBlob");
                }

                if (typeof OffscreenCanvas !== "undefined" && OffscreenCanvas.prototype && typeof OffscreenCanvas.prototype.convertToBlob === "function") {
                    var origConvertToBlob = OffscreenCanvas.prototype.convertToBlob;
                    OffscreenCanvas.prototype.convertToBlob = cloak(function convertToBlob() {
                        try {
                            if (this.width > 0 && this.height > 0 && this.width <= 1200 && this.height <= 900) {
                                var ctx = this.getContext("2d");
                                if (ctx) {
                                    var px = ctx.getImageData(0, 0, Math.min(4, this.width), Math.min(4, this.height));
                                    farblePixels(px.data, "off");
                                    ctx.putImageData(px, 0, 0);
                                }
                            }
                        } catch (_e) {}
                        return origConvertToBlob.apply(this, arguments);
                    }, "convertToBlob");
                }

                // Canvas measureText font-probe defense
                var origMeasureText = CanvasRenderingContext2D.prototype.measureText;
                if (typeof origMeasureText === "function") {
                    CanvasRenderingContext2D.prototype.measureText = cloak(function measureText(text) {
                        var metrics = origMeasureText.apply(this, arguments);
                        try {
                            if (metrics && typeof metrics.width === "number" && metrics.width > 0) {
                                var fontKey = (this.font || "") + "|" + String(text || "").slice(0, 40);
                                var jitter = (((fnv1a(fpSeed + ":ft:" + fontKey) & 1023) / 1023) - 0.5) * 0.002;
                                var fakeW = metrics.width + jitter;
                                return new Proxy(metrics, {
                                    get: function (target, prop) {
                                        if (prop === "width") return fakeW;
                                        var val = Reflect.get(target, prop, target);
                                        return typeof val === "function" ? val.bind(target) : val;
                                    }
                                });
                            }
                        } catch (_e) {}
                        return metrics;
                    }, "measureText");
                }
            } catch (_e) {}

            // 6) WebGL Gecko-Consistent Vendor/Renderer + Per-Origin Parameter/ReadPixels/Extension Farbling
            try {
                var patchWebGL = function (proto) {
                    if (!proto) return;
                    var origGetParam = proto.getParameter;
                    if (typeof origGetParam === "function") {
                        proto.getParameter = cloak(function getParameter(param) {
                            // Genuine Gecko Android Firefox GPU profile (NEVER Chrome's ANGLE string!)
                            if (param === 37445) return "Qualcomm";
                            if (param === 37446) return "Adreno (TM) 640";
                            if (param === 7936) return "Mozilla";
                            if (param === 7937) return "Mozilla";
                            if (param === 3379) return 4096; // MAX_TEXTURE_SIZE
                            if (param === 34024) return 4096; // MAX_RENDERBUFFER_SIZE
                            if (param === 34921) return 16; // MAX_VERTEX_ATTRIBS
                            return origGetParam.apply(this, arguments);
                        }, "getParameter");
                    }
                    var origReadPixels = proto.readPixels;
                    if (typeof origReadPixels === "function") {
                        proto.readPixels = cloak(function readPixels(x, y, w, h, format, type, pixels) {
                            var res = origReadPixels.apply(this, arguments);
                            try {
                                if (pixels && pixels.length >= 16) {
                                    if (pixels instanceof Uint8Array || pixels instanceof Uint8ClampedArray) {
                                        for (var k = 0; k < 6; k++) {
                                            var idx = fnv1a(fpSeed + ":glr:" + k + ":" + pixels.length) % pixels.length;
                                            var v = pixels[idx];
                                            pixels[idx] = v === 0 ? 1 : (v === 255 ? 254 : v + deltaSign(k + 41));
                                        }
                                    } else if (pixels instanceof Float32Array) {
                                        for (var f = 0; f < 4; f++) {
                                            var fi = fnv1a(fpSeed + ":glf:" + f + ":" + pixels.length) % pixels.length;
                                            pixels[fi] = pixels[fi] + deltaSign(f + 51) * 1e-5;
                                        }
                                    }
                                }
                            } catch (_e) {}
                            return res;
                        }, "readPixels");
                    }
                    var origGetExts = proto.getSupportedExtensions;
                    if (typeof origGetExts === "function") {
                        proto.getSupportedExtensions = cloak(function getSupportedExtensions() {
                            var exts = origGetExts.apply(this, arguments);
                            try {
                                if (Array.isArray(exts) && exts.length > 4) {
                                    var copy = exts.slice();
                                    // Deterministic per-origin permutation of non-critical extension order
                                    var swapIdx = fnv1a(fpSeed + ":ext") % (copy.length - 1);
                                    var tmp = copy[swapIdx];
                                    copy[swapIdx] = copy[swapIdx + 1];
                                    copy[swapIdx + 1] = tmp;
                                    return copy;
                                }
                            } catch (_e) {}
                            return exts;
                        }, "getSupportedExtensions");
                    }
                };
                if (typeof WebGLRenderingContext !== "undefined") patchWebGL(WebGLRenderingContext.prototype);
                if (typeof WebGL2RenderingContext !== "undefined") patchWebGL(WebGL2RenderingContext.prototype);
            } catch (_e) {}

            // 7) Complete AudioContext Farbling (getChannelData, copyFromChannel, OfflineAudioContext, AnalyserNode)
            try {
                var patchedAudioBufs = new WeakSet();
                var farbleFloatSamples = function (arr, channelTag) {
                    if (!arr || arr.length <= 16 || patchedAudioBufs.has(arr)) return;
                    patchedAudioBufs.add(arr);
                    var step = Math.max(1, (arr.length / 24) | 0);
                    for (var i = 0; i < arr.length; i += step) {
                        var n = (((fnv1a(fpSeed + ":au:" + channelTag + ":" + i) & 511) / 511) - 0.5) * 2e-6;
                        arr[i] = arr[i] + n;
                    }
                };

                if (typeof AudioBuffer !== "undefined" && AudioBuffer.prototype) {
                    if (typeof AudioBuffer.prototype.getChannelData === "function") {
                        var origGetChannelData = AudioBuffer.prototype.getChannelData;
                        AudioBuffer.prototype.getChannelData = cloak(function getChannelData(ch) {
                            var data = origGetChannelData.apply(this, arguments);
                            try { farbleFloatSamples(data, ch || 0); } catch (_e) {}
                            return data;
                        }, "getChannelData");
                    }
                    if (typeof AudioBuffer.prototype.copyFromChannel === "function") {
                        var origCopyFromChannel = AudioBuffer.prototype.copyFromChannel;
                        AudioBuffer.prototype.copyFromChannel = cloak(function copyFromChannel(dest, ch) {
                            var res = origCopyFromChannel.apply(this, arguments);
                            try { farbleFloatSamples(dest, "c" + (ch || 0)); } catch (_e) {}
                            return res;
                        }, "copyFromChannel");
                    }
                }

                if (typeof OfflineAudioContext !== "undefined" && OfflineAudioContext.prototype && typeof OfflineAudioContext.prototype.startRendering === "function") {
                    var origStartRendering = OfflineAudioContext.prototype.startRendering;
                    OfflineAudioContext.prototype.startRendering = cloak(function startRendering() {
                        var p = origStartRendering.apply(this, arguments);
                        if (p && typeof p.then === "function") {
                            return p.then(function (renderedBuffer) {
                                try {
                                    if (renderedBuffer && typeof renderedBuffer.numberOfChannels === "number") {
                                        for (var c = 0; c < renderedBuffer.numberOfChannels; c++) {
                                            farbleFloatSamples(renderedBuffer.getChannelData(c), "off" + c);
                                        }
                                    }
                                } catch (_e) {}
                                return renderedBuffer;
                            });
                        }
                        return p;
                    }, "startRendering");
                }

                if (typeof AnalyserNode !== "undefined" && AnalyserNode.prototype) {
                    var patchFloatAnalyser = function (methodName, tag) {
                        var orig = AnalyserNode.prototype[methodName];
                        if (typeof orig !== "function") return;
                        AnalyserNode.prototype[methodName] = cloak(function (arr) {
                            orig.apply(this, arguments);
                            try {
                                if (arr && arr.length > 4) {
                                    var idx = fnv1a(fpSeed + ":" + tag) % arr.length;
                                    if (isFinite(arr[idx])) arr[idx] += deltaSign(21) * 1e-4;
                                }
                            } catch (_e) {}
                        }, methodName);
                    };
                    var patchByteAnalyser = function (methodName, tag) {
                        var orig = AnalyserNode.prototype[methodName];
                        if (typeof orig !== "function") return;
                        AnalyserNode.prototype[methodName] = cloak(function (arr) {
                            orig.apply(this, arguments);
                            try {
                                if (arr && arr.length > 4) {
                                    var idx = fnv1a(fpSeed + ":" + tag) % arr.length;
                                    if (arr[idx] > 0 && arr[idx] < 255) arr[idx] += deltaSign(22);
                                }
                            } catch (_e) {}
                        }, methodName);
                    };
                    patchFloatAnalyser("getFloatFrequencyData", "aff");
                    patchFloatAnalyser("getFloatTimeDomainData", "aft");
                    patchByteAnalyser("getByteFrequencyData", "abf");
                    patchByteAnalyser("getByteTimeDomainData", "abt");
                }
            } catch (_e) {}

            // 8) ClientRects & DOM System Font Enumeration Defense (Cover Your Tracks font test)
            try {
                var adjustRect = function (r, keyHint) {
                    if (!r || typeof r.width !== "number" || (r.width === 0 && r.height === 0)) return r;
                    try {
                        var h = fnv1a(fpSeed + ":rc:" + (keyHint || ""));
                        var jitter = (((h & 1023) / 1023) - 0.5) * 0.0002;
                        return new DOMRect(r.x + jitter, r.y + jitter, r.width + jitter, r.height + jitter);
                    } catch (_e) {
                        return r;
                    }
                };
                var origGetBCR = Element.prototype.getBoundingClientRect;
                Element.prototype.getBoundingClientRect = cloak(function getBoundingClientRect() {
                    var hint = (this.tagName || "") + "|" + ((this.style && this.style.fontFamily) || "");
                    return adjustRect(origGetBCR.apply(this, arguments), hint);
                }, "getBoundingClientRect");

                var origElClientRects = Element.prototype.getClientRects;
                if (typeof origElClientRects === "function") {
                    Element.prototype.getClientRects = cloak(function getClientRects() {
                        var list = origElClientRects.apply(this, arguments);
                        try {
                            if (!list || list.length === 0) return list;
                            var hint = (this.tagName || "") + "|" + ((this.style && this.style.fontFamily) || "");
                            var arr = [];
                            for (var i = 0; i < list.length; i++) arr.push(adjustRect(list[i], hint + ":" + i));
                            arr.item = cloak(function item(idx) { return arr[idx] || null; }, "item");
                            return arr;
                        } catch (_e) {
                            return list;
                        }
                    }, "getClientRects");
                }

                if (typeof Range !== "undefined" && Range.prototype && typeof Range.prototype.getClientRects === "function") {
                    var origRangeClientRects = Range.prototype.getClientRects;
                    Range.prototype.getClientRects = cloak(function getClientRects() {
                        var list = origRangeClientRects.apply(this, arguments);
                        try {
                            if (!list || list.length === 0) return list;
                            var arr = [];
                            for (var i = 0; i < list.length; i++) arr.push(adjustRect(list[i], "rg:" + i));
                            arr.item = cloak(function item(idx) { return arr[idx] || null; }, "item");
                            return arr;
                        } catch (_e) {
                            return list;
                        }
                    }, "getClientRects");
                }

                // Surgical DOM System Font Enumeration Probe Defense:
                // Only perturbs offscreen/hidden font-probe spans that specify a font-family fallback list
                var origOffsetWDesc = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "offsetWidth");
                var origOffsetHDesc = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "offsetHeight");
                var isFontProbeEl = function (el) {
                    if (!el || !el.style) return false;
                    var ff = el.style.fontFamily || "";
                    if (!ff || ff.indexOf(",") < 0) return false;
                    var tag = el.tagName;
                    if (tag !== "SPAN" && tag !== "DIV" && tag !== "P") return false;
                    var fs = parseFloat(el.style.fontSize || "0");
                    var isHiddenProbe =
                        el.style.visibility === "hidden" ||
                        el.style.position === "absolute" ||
                        fs >= 40;
                    return isHiddenProbe && el.children.length === 0;
                };
                if (origOffsetWDesc && typeof origOffsetWDesc.get === "function") {
                    var origOW = origOffsetWDesc.get;
                    defineGetter(HTMLElement.prototype, "offsetWidth", function () {
                        var val = origOW.call(this);
                        try {
                            if (val > 20 && isFontProbeEl(this)) {
                                var fh = fnv1a(fpSeed + ":fow:" + this.style.fontFamily);
                                return val + ((fh % 3) - 1);
                            }
                        } catch (_e) {}
                        return val;
                    });
                }
                if (origOffsetHDesc && typeof origOffsetHDesc.get === "function") {
                    var origOH = origOffsetHDesc.get;
                    defineGetter(HTMLElement.prototype, "offsetHeight", function () {
                        var val = origOH.call(this);
                        try {
                            if (val > 10 && isFontProbeEl(this)) {
                                var fh = fnv1a(fpSeed + ":foh:" + this.style.fontFamily);
                                return val + ((fh & 1) === 0 ? 1 : 0);
                            }
                        } catch (_e) {}
                        return val;
                    });
                }
            } catch (_e) {}
        }

        // ---------------------------------------------------------------------
        // PART 2: Precision Click-Hijack & Betting-Popunder Defuser
        // Neutralizes mlsbd -> jaya9bangla.com traps WITHOUT ever breaking normal sites
        // ---------------------------------------------------------------------
        var shieldActive = !cfg.paused && (cfg.shieldLevel === undefined || cfg.shieldLevel > 0);
        if (cfg.blockPopups !== false && shieldActive) {
            window.__uhPopupGuardInstalled = true;

            var BETTING_POPUNDER_RE = /(?:jaya9|babu88|krikya|baji(?:live|999|88)|bj88|crickex|jeebet|jeetbuzz|jeetwin|mcw\d+|casinomcw|nagad88|1xbet|1xbit|melbet|mostbet|betwinner|parimatch|linebet|megapari|4rabet|22bet|1win\.|popads|popcash|propellerads|propellerclick|exoclick|exosrv|realsrv|magsrv|pemsrv|juicyads|trafficjunky|trafficstars|adsterra|hilltopads|clickadu|monetag|pushame|popunder|afu\.php|adclick\.php|admaven|galaksion|richads|evadav|onclickalgo|highperformanceformat|profitablecpmrate|acscdn|effectivegatecpm|topcreativeformat|directlink|smartlink|wpadmngr|go2affise|offergate)/i;
            var AFFILIATE_TRAP_RE = /[?&](?:af|btag|zoneid|aff_id|clickid)=[a-z0-9_-]{4,}/i;
            var GAMBLING_HINT_RE = /(casino|bet\d*|888|999|777|365|win\d*|slot|spin|bangla|bdt|bonus|pop|clk|trk|redir)/i;
            var HIJACK_LISTENER_RE = /(?:window\.open|open\s*\(\s*['"]https?:\/\/|jaya9|babu88|krikya|1xbet|melbet|popunder|adsterra|monetag|propeller|onclickalgo|btag=|\?af=)/i;

            var isCrossOrigin = function (urlStr) {
                try {
                    var u = new URL(String(urlStr), window.location.href);
                    if (u.protocol !== "http:" && u.protocol !== "https:") return false;
                    return getRegistrableBase(u.hostname) !== getRegistrableBase(window.location.hostname);
                } catch (_e) {
                    return false;
                }
            };

            var isPopunderOrBettingUrl = function (urlStr) {
                if (!urlStr) return false;
                var s = String(urlStr);
                if (BETTING_POPUNDER_RE.test(s)) return true;
                if (AFFILIATE_TRAP_RE.test(s) && isCrossOrigin(s)) {
                    try {
                        var u = new URL(s, window.location.href);
                        if (GAMBLING_HINT_RE.test(u.hostname) || u.pathname === "/") {
                            return true;
                        }
                    } catch (_e) {}
                }
                return false;
            };

            var reportBlocked = function (targetUrl, isPopupTab) {
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
            };

            var isOverlayTrap = function (el) {
                if (!el || el.nodeType !== 1) return false;
                try {
                    var cs = getComputedStyle(el);
                    if (cs.position !== "fixed" && cs.position !== "absolute") return false;
                    var z = parseInt(cs.zIndex, 10) || 0;
                    var rect = el.getBoundingClientRect();
                    var coversArea = rect.width > window.innerWidth * 0.45 && rect.height > window.innerHeight * 0.35;
                    var isTransparent = parseFloat(cs.opacity) < 0.08 || cs.backgroundColor === "rgba(0, 0, 0, 0)" || cs.backgroundColor === "transparent";
                    var empty = (el.textContent || "").trim().length === 0 && !el.querySelector("video, input, form, img");
                    return z >= 50 && coversArea && isTransparent && empty;
                } catch (_e) {
                    return false;
                }
            };

            var lastTapTarget = null;
            var trackTap = function (ev) {
                if (ev && ev.isTrusted) {
                    lastTapTarget = ev.target || null;
                }
            };
            window.addEventListener("pointerdown", trackTap, true);
            window.addEventListener("click", trackTap, true);

            // A) Disarm global document/body/window first-click popunder hijack listeners BEFORE they attach
            try {
                var origAddEvent = EventTarget.prototype.addEventListener;
                EventTarget.prototype.addEventListener = cloak(function addEventListener(type, listener, opts) {
                    try {
                        var t = String(type || "").toLowerCase();
                        if (
                            (t === "click" || t === "mousedown" || t === "mouseup" || t === "pointerdown" || t === "pointerup" || t === "touchstart" || t === "touchend") &&
                            (this === window || this === document || this === document.documentElement || this === document.body)
                        ) {
                            var fnSrc = typeof listener === "function" ? origFnToString.call(listener) : "";
                            if (fnSrc && HIJACK_LISTENER_RE.test(fnSrc)) {
                                return;
                            }
                        }
                    } catch (_e) {}
                    return origAddEvent.apply(this, arguments);
                }, "addEventListener");
            } catch (_e) {}

            // B) Surgical window.open guard (NO arbitrary timeout blocking legitimate links!)
            try {
                var origOpen = window.open;
                var createBlankProxy = function (target, features) {
                    var realWin = null;
                    var closed = false;
                    var handleNav = function (dest) {
                        var destStr = String(dest || "");
                        if (!destStr) return;
                        if (isPopunderOrBettingUrl(destStr)) {
                            closed = true;
                            reportBlocked(destStr, true);
                            return;
                        }
                        try {
                            realWin = origOpen.call(window, destStr, target, features);
                        } catch (_e) {}
                    };
                    var locProxy = {
                        get href() { return "about:blank"; },
                        set href(v) { handleNav(v); },
                        assign: function (v) { handleNav(v); },
                        replace: function (v) { handleNav(v); }
                    };
                    return {
                        get closed() { return realWin ? realWin.closed : closed; },
                        get location() { return realWin ? realWin.location : locProxy; },
                        set location(v) { handleNav(v); },
                        close: function () { closed = true; if (realWin) { try { realWin.close(); } catch (_e) {} } },
                        focus: function () {},
                        blur: function () {},
                        postMessage: function () {},
                        document: { write: function () {}, writeln: function () {}, open: function () {}, close: function () {} }
                    };
                };

                window.open = cloak(function open(url, target, features) {
                    var urlStr = url == null ? "" : String(url).trim();
                    if (isPopunderOrBettingUrl(urlStr)) {
                        reportBlocked(urlStr, true);
                        return null;
                    }
                    if (lastTapTarget && isOverlayTrap(lastTapTarget)) {
                        if (!urlStr || urlStr === "about:blank") {
                            return createBlankProxy(target, features);
                        }
                        if (isCrossOrigin(urlStr)) {
                            reportBlocked(urlStr, true);
                            return null;
                        }
                    }
                    return origOpen.apply(this, arguments);
                }, "open");
            } catch (_e) {}

            // C) Guard synthetic anchor.click() & form.submit() ONLY for confirmed popunder/betting traps
            try {
                var origAnchorClick = HTMLAnchorElement.prototype.click;
                HTMLAnchorElement.prototype.click = cloak(function click() {
                    try {
                        var href = this.href || "";
                        if (isPopunderOrBettingUrl(href)) {
                            reportBlocked(href, (this.target || "").toLowerCase() === "_blank");
                            return;
                        }
                    } catch (_e) {}
                    return origAnchorClick.apply(this, arguments);
                }, "click");
            } catch (_e) {}

            try {
                var origFormSubmit = HTMLFormElement.prototype.submit;
                HTMLFormElement.prototype.submit = cloak(function submit() {
                    try {
                        var action = this.action || "";
                        if (isPopunderOrBettingUrl(action)) {
                            reportBlocked(action, (this.target || "").toLowerCase() === "_blank");
                            return;
                        }
                    } catch (_e) {}
                    return origFormSubmit.apply(this, arguments);
                }, "submit");
            } catch (_e) {}

            // D) Capture-phase click guard for transparent clickjack overlays & betting anchors
            try {
                document.addEventListener("click", function (ev) {
                    try {
                        var target = ev.target;
                        if (target && isOverlayTrap(target)) {
                            ev.preventDefault();
                            ev.stopImmediatePropagation();
                            target.style.setProperty("display", "none", "important");
                            target.style.setProperty("pointer-events", "none", "important");
                            return;
                        }
                        var anchor = target && target.closest ? target.closest("a[href]") : null;
                        if (anchor && isPopunderOrBettingUrl(anchor.href || "")) {
                            ev.preventDefault();
                            ev.stopImmediatePropagation();
                            reportBlocked(anchor.href, (anchor.target || "").toLowerCase() === "_blank");
                        }
                    } catch (_e) {}
                }, true);
            } catch (_e) {}

            // E) Defuse <meta http-equiv="refresh"> ONLY for confirmed popunder/betting traps
            try {
                var scrubMeta = function () {
                    var metas = document.querySelectorAll('meta[http-equiv="refresh" i]');
                    for (var i = 0; i < metas.length; i++) {
                        var content = metas[i].getAttribute("content") || "";
                        var m = content.match(/url\s*=\s*['"]?([^'"\s;]+)/i);
                        if (m && m[1]) {
                            try {
                                var abs = new URL(m[1], window.location.href).href;
                                if (isPopunderOrBettingUrl(abs)) {
                                    metas[i].remove();
                                    reportBlocked(abs, false);
                                }
                            } catch (_e) {}
                        }
                    }
                };
                scrubMeta();
                document.addEventListener("DOMContentLoaded", scrubMeta, { once: true });
            } catch (_e) {}
        }
    }

    // Build synchronous boot config from sessionStorage snapshot (zero async wait)
    var bootCfg = {
        shieldLevel: 2,
        paused: false,
        blockPopups: true,
        antiFingerprint: true,
        strictFingerprint: true,
        dnt: false,
        fpSessionSalt: ""
    };
    try {
        var cached = sessionStorage.getItem("__uh_boot_cfg");
        if (cached) {
            var parsed = JSON.parse(cached);
            if (parsed && typeof parsed === "object") {
                bootCfg = Object.assign(bootCfg, parsed);
            }
        }
    } catch (_e) {}

    // 1) Primary path: synchronous inline <script> injection BEFORE any page script runs
    try {
        var root = document.documentElement || document.head || document;
        if (root) {
            var s = document.createElement("script");
            s.textContent = "(" + __uhSyncPageGuard.toString() + ")(" + JSON.stringify(bootCfg) + ");";
            if (root.firstChild) {
                root.insertBefore(s, root.firstChild);
            } else {
                root.appendChild(s);
            }
            s.remove();
        }
    } catch (_e) {}

    // 2) Strict-CSP Fallback: Gecko wrappedJSObject direct synchronous injection
    // Ensures even pages with strict script-src CSP (no unsafe-inline) get full Randomized Anti-FP!
    try {
        var pageWin = window.wrappedJSObject;
        if (pageWin && !pageWin.__uhSyncArmed && typeof exportFunction === "function") {
            pageWin.__uhSyncArmed = true;
            if (bootCfg.antiFingerprint !== false && !bootCfg.paused) {
                pageWin.__uhFpHardened = true;
                pageWin.__uhStrictFpInstalled = true;
                var UA = "Mozilla/5.0 (Android 14; Mobile; rv:132.0) Gecko/132.0 Firefox/132.0";
                var cspSalt = bootCfg.fpSessionSalt || "";
                if (!cspSalt) {
                    try {
                        cspSalt = sessionStorage.getItem("__uh_fp_rand") || String(Math.random()).slice(2);
                        sessionStorage.setItem("__uh_fp_rand", cspSalt);
                    } catch (_e) {
                        cspSalt = String(Math.random()).slice(2);
                    }
                }
                var cspHash = function (str) {
                    var h = 0x811c9dc5;
                    for (var i = 0; i < str.length; i++) {
                        h ^= str.charCodeAt(i);
                        h = Math.imul(h, 0x01000193);
                    }
                    return h >>> 0;
                };
                var cspSeed = cspHash(cspSalt + "|" + (window.location.hostname || "local"));
                var defProp = function (obj, prop, getterFn) {
                    try {
                        Object.defineProperty(obj, prop, {
                            get: exportFunction(getterFn, pageWin),
                            enumerable: true,
                            configurable: true
                        });
                    } catch (_e) {}
                };
                var pNav = pageWin.navigator;
                var pScr = pageWin.screen;
                if (pNav) {
                    defProp(pNav, "userAgent", function () { return UA; });
                    defProp(pNav, "appVersion", function () { return "5.0 (Android 14)"; });
                    defProp(pNav, "platform", function () { return "Linux aarch64"; });
                    defProp(pNav, "oscpu", function () { return "Linux aarch64"; });
                    defProp(pNav, "vendor", function () { return ""; });
                    defProp(pNav, "productSub", function () { return "20100101"; });
                    defProp(pNav, "hardwareConcurrency", function () { return 4; });
                    defProp(pNav, "maxTouchPoints", function () { return 5; });
                    defProp(pNav, "language", function () { return "en-US"; });
                    defProp(pNav, "doNotTrack", function () { return bootCfg.dnt === true ? "1" : "unspecified"; });
                    if (typeof cloneInto === "function") {
                        var langs = cloneInto(["en-US", "en"], pageWin);
                        defProp(pNav, "languages", function () { return langs; });
                    }
                }
                if (pScr) {
                    defProp(pScr, "width", function () { return 390; });
                    defProp(pScr, "height", function () { return 844; });
                    defProp(pScr, "availWidth", function () { return 390; });
                    defProp(pScr, "availHeight", function () { return 844; });
                    defProp(pScr, "colorDepth", function () { return 24; });
                    defProp(pScr, "pixelDepth", function () { return 24; });
                }
                defProp(pageWin, "outerWidth", function () { return 390; });
                defProp(pageWin, "outerHeight", function () { return 844; });
                defProp(pageWin, "devicePixelRatio", function () { return 2; });
                if (pageWin.Date && pageWin.Date.prototype) {
                    pageWin.Date.prototype.getTimezoneOffset = exportFunction(function () { return 0; }, pageWin);
                }
                if (pageWin.Intl && pageWin.Intl.DateTimeFormat && pageWin.Intl.DateTimeFormat.prototype) {
                    var origCspResolved = pageWin.Intl.DateTimeFormat.prototype.resolvedOptions;
                    pageWin.Intl.DateTimeFormat.prototype.resolvedOptions = exportFunction(function () {
                        var res = origCspResolved.apply(this, arguments);
                        try {
                            if (res) {
                                res.timeZone = "UTC";
                                res.locale = "en-US";
                            }
                        } catch (_e) {}
                        return res;
                    }, pageWin);
                }
                // Canvas 2D + WebGL farbling under strict CSP
                var cspFarble = function (data, tag) {
                    if (!data || data.length < 16) return;
                    var pxCount = (data.length / 4) | 0;
                    var steps = Math.min(8, pxCount);
                    for (var k = 0; k < steps; k++) {
                        var idx = (cspHash(cspSeed + ":" + tag + ":" + k + ":" + pxCount) % pxCount) * 4 + (k % 3);
                        var v = data[idx];
                        var d = ((cspHash(cspSeed + ":d:" + k) & 1) === 0) ? 1 : -1;
                        data[idx] = v === 0 ? 1 : (v === 255 ? 254 : v + d);
                    }
                };
                if (pageWin.CanvasRenderingContext2D && pageWin.CanvasRenderingContext2D.prototype) {
                    var origCspImgData = pageWin.CanvasRenderingContext2D.prototype.getImageData;
                    pageWin.CanvasRenderingContext2D.prototype.getImageData = exportFunction(function () {
                        var img = origCspImgData.apply(this, arguments);
                        try { if (img && img.data) cspFarble(img.data, "2d"); } catch (_e) {}
                        return img;
                    }, pageWin);
                }
                if (pageWin.HTMLCanvasElement && pageWin.HTMLCanvasElement.prototype) {
                    var origCspGetCtx = pageWin.HTMLCanvasElement.prototype.getContext;
                    pageWin.HTMLCanvasElement.prototype.getContext = exportFunction(function (t, a) {
                        var lt = String(t || "").toLowerCase();
                        if (lt === "webgl" || lt === "webgl2" || lt === "experimental-webgl") {
                            var merged = typeof cloneInto === "function"
                                ? cloneInto(Object.assign({}, a || {}, { preserveDrawingBuffer: true }), pageWin)
                                : a;
                            return origCspGetCtx.call(this, t, merged);
                        }
                        return origCspGetCtx.apply(this, arguments);
                    }, pageWin);
                    var origCspToData = pageWin.HTMLCanvasElement.prototype.toDataURL;
                    pageWin.HTMLCanvasElement.prototype.toDataURL = exportFunction(function () {
                        try {
                            if (this.width > 0 && this.height > 0 && this.width <= 1600 && this.height <= 1200) {
                                var cl = pageWin.document.createElement("canvas");
                                cl.width = this.width;
                                cl.height = this.height;
                                var c2 = origCspGetCtx.call(cl, "2d");
                                if (c2) {
                                    c2.drawImage(this, 0, 0);
                                    var idata = c2.getImageData(0, 0, cl.width, cl.height);
                                    cspFarble(idata.data, "gl");
                                    c2.putImageData(idata, 0, 0);
                                    return origCspToData.apply(cl, arguments);
                                }
                            }
                        } catch (_e) {}
                        return origCspToData.apply(this, arguments);
                    }, pageWin);
                }
                var patchCspGl = function (proto) {
                    if (!proto) return;
                    var origP = proto.getParameter;
                    if (typeof origP === "function") {
                        proto.getParameter = exportFunction(function (p) {
                            if (p === 37445) return "Qualcomm";
                            if (p === 37446) return "Adreno (TM) 640";
                            if (p === 7936 || p === 7937) return "Mozilla";
                            return origP.apply(this, arguments);
                        }, pageWin);
                    }
                    var origR = proto.readPixels;
                    if (typeof origR === "function") {
                        proto.readPixels = exportFunction(function (x, y, w, h, f, t, pixels) {
                            var r = origR.apply(this, arguments);
                            try { if (pixels && pixels.length >= 16) cspFarble(pixels, "glr"); } catch (_e) {}
                            return r;
                        }, pageWin);
                    }
                };
                if (pageWin.WebGLRenderingContext) patchCspGl(pageWin.WebGLRenderingContext.prototype);
                if (pageWin.WebGL2RenderingContext) patchCspGl(pageWin.WebGL2RenderingContext.prototype);
                if (pageWin.AudioBuffer && pageWin.AudioBuffer.prototype && typeof pageWin.AudioBuffer.prototype.getChannelData === "function") {
                    var origCspAudio = pageWin.AudioBuffer.prototype.getChannelData;
                    var cspAudioSeen = new WeakSet();
                    pageWin.AudioBuffer.prototype.getChannelData = exportFunction(function (ch) {
                        var arr = origCspAudio.apply(this, arguments);
                        try {
                            if (arr && arr.length > 16 && !cspAudioSeen.has(arr)) {
                                cspAudioSeen.add(arr);
                                var step = Math.max(1, (arr.length / 24) | 0);
                                for (var i = 0; i < arr.length; i += step) {
                                    arr[i] = arr[i] + ((((cspHash(cspSeed + ":au:" + (ch || 0) + ":" + i) & 511) / 511) - 0.5) * 2e-6);
                                }
                            }
                        } catch (_e) {}
                        return arr;
                    }, pageWin);
                }
            }
        }
    } catch (_e) {}
})();
