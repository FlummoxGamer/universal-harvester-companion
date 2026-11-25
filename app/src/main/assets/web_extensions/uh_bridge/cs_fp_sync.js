// -----------------------------------------------------------------------------
// UH Bridge Content Module 0: Zero-Race Synchronous document_start Guard
// Brave-Grade Stealth Farbling (Canvas, Masked+Farbled WebGL, Full-Buffer Audio,
// Clean Fallback-Locked Fonts, Auto DNT Off) + Precision Click-Hijack Defuser
// with Universal Captcha/Verification Exemption & Prototype-Level Stealth.
// -----------------------------------------------------------------------------
(function () {
    "use strict";

    function __uhSyncPageGuard(bootCfg) {
        if (window.__uhSyncArmed) return;
        window.__uhSyncArmed = true;

        var cfg = bootCfg || {};

        // Auto-exempt all captcha & human-verification frames cleanly
        function isCaptchaFrame() {
            try {
                var h = (window.location.hostname || "").toLowerCase().replace(/^www\./, "");
                var p = (window.location.pathname || "").toLowerCase();
                if (p.indexOf("/cdn-cgi/") === 0 && p.indexOf("/cdn-cgi/rum") !== 0) return true;
                if (
                    h === "challenges.cloudflare.com" ||
                    h === "hcaptcha.com" || h.endsWith(".hcaptcha.com") ||
                    h === "recaptcha.net" || h.endsWith(".recaptcha.net") ||
                    h === "arkoselabs.com" || h.endsWith(".arkoselabs.com") ||
                    h === "funcaptcha.com" || h.endsWith(".funcaptcha.com") ||
                    h === "geetest.com" || h.endsWith(".geetest.com") ||
                    h === "friendlycaptcha.com" || h.endsWith(".friendlycaptcha.com") ||
                    h === "frcapi.com" || h.endsWith(".frcapi.com") ||
                    h === "captcha-delivery.com" || h.endsWith(".captcha-delivery.com")
                ) {
                    return true;
                }
                if ((h === "google.com" || h.endsWith(".google.com") || h === "gstatic.com" || h.endsWith(".gstatic.com")) &&
                    p.indexOf("/recaptcha/") >= 0) {
                    return true;
                }
            } catch (_e) {}
            return false;
        }
        if (isCaptchaFrame()) return;

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
        // Native-Code Stealth Cloak (Prototype-level descriptors, zero own-prop leaks)
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

        // Always define getters on the PROTOTYPE (never instance own-properties) to pass anti-bot checks
        function defineProtoGetter(proto, prop, getter) {
            if (!proto) return;
            var cloaked = cloak(getter, "get " + prop);
            try {
                Object.defineProperty(proto, prop, {
                    get: cloaked,
                    enumerable: true,
                    configurable: true
                });
            } catch (_e) {}
        }

        try {
            Object.defineProperty(window, "__uhCloakNative", {
                value: cloak,
                writable: false,
                enumerable: false,
                configurable: true
            });
        } catch (_e) {}

        // ---------------------------------------------------------------------
        // PART 1: Brave-Grade Stealth Anti-Fingerprinting (Zero Bot Flags)
        // Preserves genuine Gecko UA, real screen/DPR, real PluginArray, and real timezone
        // while masking GPU hardware and farbling Canvas, WebGL, Audio, Fonts & ClientRects.
        // ---------------------------------------------------------------------
        if (cfg.antiFingerprint !== false && !cfg.paused) {
            window.__uhFpHardened = true;
            window.__uhStrictFpInstalled = true;

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

            // 1) Prototype-Level Navigator Hygiene (NO fake UA/screen/plugins — zero Turnstile/DataDome flags!)
            try {
                var navProto = (typeof Navigator !== "undefined" && Navigator.prototype) || Object.getPrototypeOf(navigator);
                if (navProto) {
                    // Strip "; wv" WebView token ONLY if ever present, keeping genuine Gecko version 100% intact
                    var origUaDesc = Object.getOwnPropertyDescriptor(navProto, "userAgent");
                    if (origUaDesc && typeof origUaDesc.get === "function") {
                        var origUaGetter = origUaDesc.get;
                        var rawUa = "";
                        try { rawUa = String(origUaGetter.call(navigator) || ""); } catch (_e) {}
                        if (/;\s*wv\b/i.test(rawUa)) {
                            var cleanUa = rawUa.replace(/;\s*wv\b/gi, "").replace(/\s{2,}/g, " ").trim();
                            defineProtoGetter(navProto, "userAgent", function () { return cleanUa; });
                        }
                    }
                    // Force DNT off when Anti-Fingerprinting is active (eliminates +2.02 bit leak)
                    defineProtoGetter(navProto, "doNotTrack", function () { return "unspecified"; });
                    // Bucket hardwareConcurrency to common mobile value (4) on prototype
                    defineProtoGetter(navProto, "hardwareConcurrency", function () { return 4; });
                }
            } catch (_e) {}

            // 2) Battery, MediaDevices, WebGPU & SpeechSynthesis Privacy Hardening
            try {
                var nProto = (typeof Navigator !== "undefined" && Navigator.prototype) || Object.getPrototypeOf(navigator);
                if (nProto && typeof nProto.getBattery === "function") {
                    var fakeBattery = {
                        charging: true, chargingTime: 0, dischargingTime: Infinity, level: 1.0,
                        onchargingchange: null, onchargingtimechange: null, ondischargingtimechange: null, onlevelchange: null,
                        addEventListener: cloak(function addEventListener() {}, "addEventListener"),
                        removeEventListener: cloak(function removeEventListener() {}, "removeEventListener"),
                        dispatchEvent: cloak(function dispatchEvent() { return false; }, "dispatchEvent")
                    };
                    nProto.getBattery = cloak(function getBattery() { return Promise.resolve(fakeBattery); }, "getBattery");
                }
            } catch (_e) {}

            try {
                if (typeof MediaDevices !== "undefined" && MediaDevices.prototype && typeof MediaDevices.prototype.enumerateDevices === "function") {
                    var origEnum = MediaDevices.prototype.enumerateDevices;
                    MediaDevices.prototype.enumerateDevices = cloak(function enumerateDevices() {
                        return origEnum.apply(this, arguments).then(function (list) {
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
                if (typeof SpeechSynthesis !== "undefined" && SpeechSynthesis.prototype && typeof SpeechSynthesis.prototype.getVoices === "function") {
                    SpeechSynthesis.prototype.getVoices = cloak(function getVoices() { return []; }, "getVoices");
                }
            } catch (_e) {}

            try {
                if (typeof GPU !== "undefined" && GPU.prototype && typeof GPU.prototype.requestAdapter === "function") {
                    var origReqAdapter = GPU.prototype.requestAdapter;
                    var maskedGpuInfo = Object.freeze({ vendor: "mozilla", architecture: "", device: "", description: "" });
                    GPU.prototype.requestAdapter = cloak(function requestAdapter() {
                        return origReqAdapter.apply(this, arguments).then(function (adapter) {
                            if (!adapter) return adapter;
                            try {
                                return new Proxy(adapter, {
                                    get: function (target, prop) {
                                        if (prop === "info") return maskedGpuInfo;
                                        if (prop === "requestAdapterInfo") {
                                            return cloak(function requestAdapterInfo() { return Promise.resolve(maskedGpuInfo); }, "requestAdapterInfo");
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

            // 3) Canvas 2D + WebGL Canvas + OffscreenCanvas Brave-Style Farbling
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
                    var steps = Math.min(10, pixelCount);
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
            } catch (_e) {}

            // 4) WebGL: Mask Real GPU Hardware + Per-Origin/Per-Session Farbling On Top
            // Real GPU vendor/renderer is 100% masked ("Mozilla" Firefox-crowd mask) while
            // WebGL pixels, extensions, and shader precision/ranges are farbled per-origin & per-session.
            try {
                var patchWebGL = function (proto) {
                    if (!proto) return;
                    var origGetParam = proto.getParameter;
                    if (typeof origGetParam === "function") {
                        proto.getParameter = cloak(function getParameter(param) {
                            // Mask real GPU hardware completely (never leak real Snapdragon/Mali or static Adreno 640)
                            if (param === 37445) return "Mozilla"; // UNMASKED_VENDOR_WEBGL
                            if (param === 37446) return "Mozilla"; // UNMASKED_RENDERER_WEBGL
                            if (param === 7936) return "Mozilla";  // VENDOR
                            if (param === 7937) return "Mozilla";  // RENDERER
                            if (param === 3379) return 4096;       // MAX_TEXTURE_SIZE
                            if (param === 34024) return 4096;      // MAX_RENDERBUFFER_SIZE
                            if (param === 34921) return 16;        // MAX_VERTEX_ATTRIBS
                            var val = origGetParam.apply(this, arguments);
                            // Subtle per-origin farbling on WebGL numeric ranges/caps
                            if (param === 33901 || param === 33902) { // ALIASED_POINT_SIZE_RANGE / ALIASED_LINE_WIDTH_RANGE
                                if (val && val.length === 2) {
                                    var copyRange = new Float32Array(val);
                                    copyRange[1] = copyRange[1] + deltaSign(61) * ((fnv1a(fpSeed + ":glp:" + param) & 7) * 0.001);
                                    return copyRange;
                                }
                            }
                            return val;
                        }, "getParameter");
                    }
                    var origReadPixels = proto.readPixels;
                    if (typeof origReadPixels === "function") {
                        proto.readPixels = cloak(function readPixels(x, y, w, h, format, type, pixels) {
                            var res = origReadPixels.apply(this, arguments);
                            try {
                                if (pixels && pixels.length >= 16) {
                                    if (pixels instanceof Uint8Array || pixels instanceof Uint8ClampedArray) {
                                        for (var k = 0; k < 8; k++) {
                                            var idx = fnv1a(fpSeed + ":glr:" + k + ":" + pixels.length) % pixels.length;
                                            var v = pixels[idx];
                                            pixels[idx] = v === 0 ? 1 : (v === 255 ? 254 : v + deltaSign(k + 41));
                                        }
                                    } else if (pixels instanceof Float32Array) {
                                        for (var f = 0; f < 6; f++) {
                                            var fi = fnv1a(fpSeed + ":glf:" + f + ":" + pixels.length) % pixels.length;
                                            pixels[fi] = pixels[fi] + deltaSign(f + 51) * 1e-4;
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
                                    // Deterministic per-origin/per-session permutation on top of masked GPU
                                    var swapA = fnv1a(fpSeed + ":extA") % (copy.length - 1);
                                    var tmpA = copy[swapA];
                                    copy[swapA] = copy[swapA + 1];
                                    copy[swapA + 1] = tmpA;
                                    var swapB = fnv1a(fpSeed + ":extB") % (copy.length - 1);
                                    var tmpB = copy[swapB];
                                    copy[swapB] = copy[swapB + 1];
                                    copy[swapB + 1] = tmpB;
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

            // 5) Full-Buffer AudioContext Farbling (Kills Static 35.749966... Hash!)
            // Applies deterministic per-origin/per-session micro-gain + dither across ALL samples
            // (including tail indices 4500..5000 summed by Cover Your Tracks) via getChannelData,
            // copyFromChannel, OfflineAudioContext (both Promise AND oncomplete event), & AnalyserNode.
            try {
                var patchedAudioBufs = new WeakSet();
                // Imperceptible ±0.25% gain shift unique per site & session
                var audioGain = 1 + ((((fnv1a(fpSeed + ":augain") & 4095) / 4095) - 0.5) * 0.005);
                var audioBias = ((((fnv1a(fpSeed + ":aubias") & 2047) / 2047) - 0.5) * 1e-5);

                var farbleAudioArray = function (arr, channelTag) {
                    if (!arr || arr.length <= 16 || patchedAudioBufs.has(arr)) return;
                    patchedAudioBufs.add(arr);
                    var len = arr.length;
                    for (var i = 0; i < len; i++) {
                        var s = arr[i];
                        if (s !== 0) {
                            arr[i] = s * audioGain + audioBias;
                        }
                    }
                    // Add deterministic sparse micro-dither in tail window
                    var tailStart = Math.max(0, len - 600);
                    for (var k = 0; k < 12; k++) {
                        var ti = tailStart + (fnv1a(fpSeed + ":aut:" + channelTag + ":" + k) % Math.max(1, len - tailStart));
                        if (ti < len && arr[ti] !== 0) {
                            arr[ti] = arr[ti] + deltaSign(k + 71) * 1e-5;
                        }
                    }
                };

                var farbleAudioBuffer = function (buf, tag) {
                    if (!buf || patchedAudioBufs.has(buf)) return;
                    patchedAudioBufs.add(buf);
                    try {
                        var chCount = typeof buf.numberOfChannels === "number" ? buf.numberOfChannels : 0;
                        for (var c = 0; c < chCount; c++) {
                            var chData = origGetChannelDataRef.call(buf, c);
                            farbleAudioArray(chData, tag + "_" + c);
                        }
                    } catch (_e) {}
                };

                var origGetChannelDataRef = (typeof AudioBuffer !== "undefined" && AudioBuffer.prototype)
                    ? AudioBuffer.prototype.getChannelData
                    : null;

                if (typeof AudioBuffer !== "undefined" && AudioBuffer.prototype) {
                    if (typeof origGetChannelDataRef === "function") {
                        AudioBuffer.prototype.getChannelData = cloak(function getChannelData(ch) {
                            var data = origGetChannelDataRef.apply(this, arguments);
                            try { farbleAudioArray(data, "ch" + (ch || 0)); } catch (_e) {}
                            return data;
                        }, "getChannelData");
                    }
                    if (typeof AudioBuffer.prototype.copyFromChannel === "function") {
                        var origCopyFromChannel = AudioBuffer.prototype.copyFromChannel;
                        AudioBuffer.prototype.copyFromChannel = cloak(function copyFromChannel(dest, ch) {
                            var res = origCopyFromChannel.apply(this, arguments);
                            try { farbleAudioArray(dest, "cp" + (ch || 0)); } catch (_e) {}
                            return res;
                        }, "copyFromChannel");
                    }
                }

                if (typeof OfflineAudioContext !== "undefined" && OfflineAudioContext.prototype) {
                    // Intercept BOTH Promise-based startRendering AND legacy oncomplete callback
                    var origOnCompleteDesc = Object.getOwnPropertyDescriptor(OfflineAudioContext.prototype, "oncomplete");
                    if (origOnCompleteDesc && typeof origOnCompleteDesc.set === "function") {
                        var origSetOnComplete = origOnCompleteDesc.set;
                        var origGetOnComplete = origOnCompleteDesc.get;
                        Object.defineProperty(OfflineAudioContext.prototype, "oncomplete", {
                            configurable: true,
                            enumerable: true,
                            get: origGetOnComplete ? cloak(function () { return origGetOnComplete.call(this); }, "get oncomplete") : undefined,
                            set: cloak(function (handler) {
                                if (typeof handler !== "function") {
                                    return origSetOnComplete.call(this, handler);
                                }
                                var wrapped = function (ev) {
                                    try {
                                        if (ev && ev.renderedBuffer) {
                                            farbleAudioBuffer(ev.renderedBuffer, "ev");
                                        }
                                    } catch (_e) {}
                                    return handler.call(this, ev);
                                };
                                return origSetOnComplete.call(this, wrapped);
                            }, "set oncomplete")
                        });
                    }
                    if (typeof OfflineAudioContext.prototype.startRendering === "function") {
                        var origStartRendering = OfflineAudioContext.prototype.startRendering;
                        OfflineAudioContext.prototype.startRendering = cloak(function startRendering() {
                            var p = origStartRendering.apply(this, arguments);
                            if (p && typeof p.then === "function") {
                                return p.then(function (renderedBuffer) {
                                    try { farbleAudioBuffer(renderedBuffer, "pr"); } catch (_e) {}
                                    return renderedBuffer;
                                });
                            }
                            return p;
                        }, "startRendering");
                    }
                }

                if (typeof AnalyserNode !== "undefined" && AnalyserNode.prototype) {
                    var patchFloatAnalyser = function (methodName, tag) {
                        var orig = AnalyserNode.prototype[methodName];
                        if (typeof orig !== "function") return;
                        AnalyserNode.prototype[methodName] = cloak(function (arr) {
                            orig.apply(this, arguments);
                            try {
                                if (arr && arr.length > 4) {
                                    for (var k = 0; k < 4; k++) {
                                        var idx = fnv1a(fpSeed + ":" + tag + ":" + k) % arr.length;
                                        if (isFinite(arr[idx])) arr[idx] += deltaSign(21 + k) * 1e-3;
                                    }
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
                                    for (var k = 0; k < 4; k++) {
                                        var idx = fnv1a(fpSeed + ":" + tag + ":" + k) % arr.length;
                                        if (arr[idx] > 0 && arr[idx] < 255) arr[idx] += deltaSign(26 + k);
                                    }
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

            // 6) Clean Fallback-Locked System Font Probe Defense (ZERO Fake Windows Fonts!)
            // Locks non-Android desktop font probes to their generic fallback baseline so
            // Cover Your Tracks never hallucinates 60 Windows/Mac fonts on Android.
            try {
                var STANDARD_ANDROID_FONTS = {
                    "roboto": 1, "noto sans": 1, "noto serif": 1, "noto color emoji": 1,
                    "droid sans": 1, "droid serif": 1, "droid sans mono": 1,
                    "sans-serif": 1, "serif": 1, "monospace": 1, "cursive": 1, "fantasy": 1,
                    "system-ui": 1, "ui-sans-serif": 1, "ui-serif": 1, "ui-monospace": 1
                };
                var GENERIC_FALLBACKS = { "sans-serif": 1, "serif": 1, "monospace": 1, "cursive": 1, "fantasy": 1 };
                var origOffsetWDesc = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "offsetWidth");
                var origOffsetHDesc = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "offsetHeight");
                var fallbackMetricCache = Object.create(null);

                var parseFontProbeFallback = function (el) {
                    if (!el || !el.style || el.children.length !== 0) return null;
                    var ff = el.style.fontFamily || "";
                    if (!ff || ff.indexOf(",") < 0) return null;
                    var tag = el.tagName;
                    if (tag !== "SPAN" && tag !== "DIV" && tag !== "P") return null;
                    var fs = parseFloat(el.style.fontSize || "0");
                    var isProbe = el.style.visibility === "hidden" || el.style.position === "absolute" || fs >= 32;
                    if (!isProbe) return null;
                    var parts = ff.split(",");
                    if (parts.length < 2) return null;
                    var primary = parts[0].trim().replace(/^['"]|['"]$/g, "").toLowerCase();
                    var fallback = parts[parts.length - 1].trim().replace(/^['"]|['"]$/g, "").toLowerCase();
                    if (!primary || STANDARD_ANDROID_FONTS[primary] || !GENERIC_FALLBACKS[fallback]) return null;
                    return fallback;
                };

                var measureFallbackBaseline = function (el, fallbackFamily, origW, origH) {
                    var txt = (el.textContent || "").slice(0, 80);
                    var key = fallbackFamily + "|" + (el.style.fontSize || "") + "|" + (el.style.fontWeight || "") + "|" + txt;
                    if (fallbackMetricCache[key]) return fallbackMetricCache[key];
                    var prevFF = el.style.fontFamily;
                    try {
                        el.style.fontFamily = fallbackFamily;
                        var m = { w: origW.call(el), h: origH.call(el) };
                        fallbackMetricCache[key] = m;
                        return m;
                    } catch (_e) {
                        return null;
                    } finally {
                        try { el.style.fontFamily = prevFF; } catch (_e) {}
                    }
                };

                if (origOffsetWDesc && origOffsetHDesc && typeof origOffsetWDesc.get === "function" && typeof origOffsetHDesc.get === "function") {
                    var origOW = origOffsetWDesc.get;
                    var origOH = origOffsetHDesc.get;
                    var inFontMeasure = false;
                    defineProtoGetter(HTMLElement.prototype, "offsetWidth", function () {
                        if (!inFontMeasure) {
                            try {
                                var fb = parseFontProbeFallback(this);
                                if (fb) {
                                    inFontMeasure = true;
                                    var base = measureFallbackBaseline(this, fb, origOW, origOH);
                                    inFontMeasure = false;
                                    if (base) return base.w;
                                }
                            } catch (_e) {
                                inFontMeasure = false;
                            }
                        }
                        return origOW.call(this);
                    });
                    defineProtoGetter(HTMLElement.prototype, "offsetHeight", function () {
                        if (!inFontMeasure) {
                            try {
                                var fb = parseFontProbeFallback(this);
                                if (fb) {
                                    inFontMeasure = true;
                                    var base = measureFallbackBaseline(this, fb, origOW, origOH);
                                    inFontMeasure = false;
                                    if (base) return base.h;
                                }
                            } catch (_e) {
                                inFontMeasure = false;
                            }
                        }
                        return origOH.call(this);
                    });
                }
            } catch (_e) {}

            // 7) Subtle Sub-Pixel ClientRects Noise (defeats rect hashing without breaking layout)
            try {
                var adjustRect = function (r, keyHint) {
                    if (!r || typeof r.width !== "number" || (r.width === 0 && r.height === 0)) return r;
                    try {
                        var h = fnv1a(fpSeed + ":rc:" + (keyHint || ""));
                        var jitter = (((h & 1023) / 1023) - 0.5) * 0.00015;
                        return new DOMRect(r.x + jitter, r.y + jitter, r.width + jitter, r.height + jitter);
                    } catch (_e) {
                        return r;
                    }
                };
                var origGetBCR = Element.prototype.getBoundingClientRect;
                Element.prototype.getBoundingClientRect = cloak(function getBoundingClientRect() {
                    var hint = (this.tagName || "") + "|" + (this.id || "");
                    return adjustRect(origGetBCR.apply(this, arguments), hint);
                }, "getBoundingClientRect");

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
            } catch (_e) {}
        }

        // ---------------------------------------------------------------------
        // PART 2: Surgical Click-Hijack & Betting-Popunder Defuser
        // Zero global EventTarget.prototype.addEventListener monkey-patching so
        // anti-bot verification (Turnstile / DataDome / PerimeterX) stays 100% clean.
        // ---------------------------------------------------------------------
        var shieldActive = !cfg.paused && (cfg.shieldLevel === undefined || cfg.shieldLevel > 0);
        if (cfg.blockPopups !== false && shieldActive) {
            window.__uhPopupGuardInstalled = true;

            var BETTING_POPUNDER_RE = /(?:jaya9|babu88|krikya|baji(?:live|999|88)|bj88|crickex|jeebet|jeetbuzz|jeetwin|mcw\d+|casinomcw|nagad88|1xbet|1xbit|melbet|mostbet|betwinner|parimatch|linebet|megapari|4rabet|22bet|1win\.|popads|popcash|propellerads|propellerclick|exoclick|exosrv|realsrv|magsrv|pemsrv|juicyads|trafficjunky|trafficstars|adsterra|hilltopads|clickadu|monetag|pushame|popunder|afu\.php|adclick\.php|admaven|galaksion|richads|evadav|onclickalgo|highperformanceformat|profitablecpmrate|acscdn|effectivegatecpm|topcreativeformat|directlink|smartlink|wpadmngr|go2affise|offergate)/i;
            var AFFILIATE_TRAP_RE = /[?&](?:af|btag|zoneid|aff_id|clickid)=[a-z0-9_-]{4,}/i;
            var GAMBLING_HINT_RE = /(casino|bet\d*|888|999|777|365|win\d*|slot|spin|bangla|bdt|bonus|pop|clk|trk|redir)/i;

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
                    var empty = (el.textContent || "").trim().length === 0 && !el.querySelector("video, input, form, img, iframe");
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

            // A) Surgical window.open guard (NO arbitrary timeout blocking legitimate links!)
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

            // B) Guard synthetic anchor.click() & form.submit() ONLY for confirmed popunder/betting traps
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

            // C) Capture-phase click guard for transparent clickjack overlays & betting anchors
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

            // D) Defuse <meta http-equiv="refresh"> ONLY for confirmed popunder/betting traps
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
        strictFingerprint: false,
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

    // Early outer bailout: never inject even a <script> node or flag into captcha/verification frames
    try {
        var outerHost = (window.location.hostname || "").toLowerCase().replace(/^www\./, "");
        var outerPath = (window.location.pathname || "").toLowerCase();
        if (
            (outerPath.indexOf("/cdn-cgi/") === 0 && outerPath.indexOf("/cdn-cgi/rum") !== 0) ||
            outerHost === "challenges.cloudflare.com" ||
            outerHost === "hcaptcha.com" || outerHost.endsWith(".hcaptcha.com") ||
            outerHost === "recaptcha.net" || outerHost.endsWith(".recaptcha.net") ||
            outerHost === "arkoselabs.com" || outerHost.endsWith(".arkoselabs.com") ||
            outerHost === "funcaptcha.com" || outerHost.endsWith(".funcaptcha.com") ||
            outerHost === "geetest.com" || outerHost.endsWith(".geetest.com") ||
            outerHost === "friendlycaptcha.com" || outerHost.endsWith(".friendlycaptcha.com") ||
            outerHost === "frcapi.com" || outerHost.endsWith(".frcapi.com") ||
            outerHost === "captcha-delivery.com" || outerHost.endsWith(".captcha-delivery.com") ||
            ((outerHost === "google.com" || outerHost.endsWith(".google.com") || outerHost === "gstatic.com" || outerHost.endsWith(".gstatic.com")) &&
                outerPath.indexOf("/recaptcha/") >= 0)
        ) {
            return;
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
    // Keeps genuine Gecko UA/screen/plugins while arming Canvas, Masked+Farbled WebGL,
    // Full-Buffer AudioContext & Auto DNT Off on strict-CSP pages.
    try {
        var pageWin = window.wrappedJSObject;
        if (pageWin && !pageWin.__uhSyncArmed && typeof exportFunction === "function") {
            var cspHost = (window.location.hostname || "").toLowerCase().replace(/^www\./, "");
            var cspPath = (window.location.pathname || "").toLowerCase();
            var isCspCaptcha = (cspPath.indexOf("/cdn-cgi/") === 0 && cspPath.indexOf("/cdn-cgi/rum") !== 0) ||
                cspHost === "challenges.cloudflare.com" ||
                cspHost === "hcaptcha.com" || cspHost.endsWith(".hcaptcha.com") ||
                cspHost === "recaptcha.net" || cspHost.endsWith(".recaptcha.net") ||
                cspHost === "arkoselabs.com" || cspHost.endsWith(".arkoselabs.com") ||
                cspHost === "funcaptcha.com" || cspHost.endsWith(".funcaptcha.com") ||
                cspHost === "geetest.com" || cspHost.endsWith(".geetest.com") ||
                cspHost === "friendlycaptcha.com" || cspHost.endsWith(".friendlycaptcha.com") ||
                cspHost === "captcha-delivery.com" || cspHost.endsWith(".captcha-delivery.com") ||
                ((cspHost === "google.com" || cspHost.endsWith(".google.com")) && cspPath.indexOf("/recaptcha/") >= 0);

            pageWin.__uhSyncArmed = true;
            if (!isCspCaptcha && bootCfg.antiFingerprint !== false && !bootCfg.paused) {
                pageWin.__uhFpHardened = true;
                pageWin.__uhStrictFpInstalled = true;

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
                var cspNavProto = pageWin.Navigator && pageWin.Navigator.prototype;
                if (cspNavProto) {
                    try {
                        Object.defineProperty(cspNavProto, "doNotTrack", {
                            get: exportFunction(function () { return "unspecified"; }, pageWin),
                            enumerable: true,
                            configurable: true
                        });
                        Object.defineProperty(cspNavProto, "hardwareConcurrency", {
                            get: exportFunction(function () { return 4; }, pageWin),
                            enumerable: true,
                            configurable: true
                        });
                    } catch (_e) {}
                }
                // Canvas 2D + WebGL farbling under strict CSP
                var cspFarble = function (data, tag) {
                    if (!data || data.length < 16) return;
                    var pxCount = (data.length / 4) | 0;
                    var steps = Math.min(10, pxCount);
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
                            if (p === 37445 || p === 37446 || p === 7936 || p === 7937) return "Mozilla";
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
                    var cspAudioGain = 1 + ((((cspHash(cspSeed + ":augain") & 4095) / 4095) - 0.5) * 0.005);
                    var cspAudioBias = ((((cspHash(cspSeed + ":aubias") & 2047) / 2047) - 0.5) * 1e-5);
                    pageWin.AudioBuffer.prototype.getChannelData = exportFunction(function () {
                        var arr = origCspAudio.apply(this, arguments);
                        try {
                            if (arr && arr.length > 16 && !cspAudioSeen.has(arr)) {
                                cspAudioSeen.add(arr);
                                for (var i = 0; i < arr.length; i++) {
                                    if (arr[i] !== 0) arr[i] = arr[i] * cspAudioGain + cspAudioBias;
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
