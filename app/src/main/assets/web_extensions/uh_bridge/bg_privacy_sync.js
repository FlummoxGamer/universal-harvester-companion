// -----------------------------------------------------------------------------
// UH Bridge Background Module 2: Stats Batching, Settings Sync & ClearURLs
// -----------------------------------------------------------------------------
(function (B) {
    const api = B.api;
    const shieldState = B.shieldState;

    let stagingSyncId = null;
    let stagingRules = null;
    let stagingSettings = null;

    const pendingBlockedByHost = new Map();
    const recentBlockedDedup = new Map();
    const DEDUP_WINDOW_MS = 5000;
    let flushTimer = null;
    let jsReportTimer = null;
    const pendingJsReportHosts = new Set();

    // Live Request Inspector batching
    let pendingRequestLogs = [];
    let requestLogTimer = null;

    // Async CNAME resolution dedup cache (bounded)
    const requestedCnameHosts = new Set();

    // Bounce-tracker main-frame hop detector: tabId -> { host, base, arrivedAt }
    const tabLastHop = new Map();
    const BOUNCE_SAFE_BASES = new Set([
        "google.com", "accounts.google.com", "bing.com", "duckduckgo.com", "yahoo.com",
        "search.brave.com", "github.com", "microsoftonline.com", "login.live.com",
        "apple.com", "cloudflare.com", "paypal.com", "stripe.com", "auth0.com", "okta.com"
    ]);

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
        "cxt", "ref_campaign", "ref_source", "share_source",
        "si", "sxsrf", "ved", "ei", "gs_lcrp", "oq", "uact", "aqs",
        "ml_subscriber", "ml_subscriber_hash", "_branch_match_id", "_branch_referrer",
        "ck_subscriber_id", "mc_cid", "mc_eid", "oft_id", "oft_k", "oft_lk", "oft_d"
    ]);

    function queueBlockedStat(pageHost, isTracker, reqHost, reqType) {
        const key = pageHost || B.activeMainHost || reqHost || "web";
        const now = Date.now();
        const dedupKey = key + "|" + (reqHost || "dom") + "|" + (reqType || "req") + "|" + (isTracker ? "t" : "a");
        const prevTs = recentBlockedDedup.get(dedupKey);
        if (prevTs !== undefined && (now - prevTs) < DEDUP_WINDOW_MS) {
            return;
        }
        recentBlockedDedup.set(dedupKey, now);
        if (recentBlockedDedup.size > 900) {
            for (const [k, ts] of recentBlockedDedup.entries()) {
                if (now - ts >= DEDUP_WINDOW_MS) recentBlockedDedup.delete(k);
            }
        }

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

    function notifyBlockedRedirect(sourceHost, targetUrl, isPopupTab) {
        if (!targetUrl) return;
        api.runtime.sendNativeMessage("uhApp", {
            type: "AD_REDIRECT_BLOCKED",
            sourceHost: sourceHost || B.activeMainHost || "",
            targetUrl: targetUrl,
            isPopupTab: isPopupTab === true
        }).catch(() => {});
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
        let entry = B.tabJsBlockedMap.get(tabId);
        if (!entry || entry.host !== pageHost) {
            entry = { host: pageHost, total: 0, domains: new Map() };
            B.tabJsBlockedMap.set(tabId, entry);
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
            const entry = B.tabJsBlockedMap.get(tabIds[i]);
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
        if (Array.isArray(cfg.redirectAllowOnceHosts)) shieldState.redirectAllowOnceHosts = new Set(cfg.redirectAllowOnceHosts);
        if (typeof cfg.smartBlockJs === "boolean") shieldState.smartBlockJs = cfg.smartBlockJs;
        if (Array.isArray(cfg.jsAllowOnceHosts)) shieldState.jsAllowOnceHosts = new Set(cfg.jsAllowOnceHosts);
        if (Array.isArray(cfg.jsAllowAlwaysHosts)) shieldState.jsAllowAlwaysHosts = new Set(cfg.jsAllowAlwaysHosts);
        if (typeof cfg.cookieBanners === "boolean") shieldState.cookieBanners = cfg.cookieBanners;
        if (typeof cfg.clearUrls === "boolean") shieldState.clearUrls = cfg.clearUrls;
        if (typeof cfg.deAmp === "boolean") shieldState.deAmp = cfg.deAmp;
        if (typeof cfg.blockPopups === "boolean") shieldState.blockPopups = cfg.blockPopups;
        if (typeof cfg.antiFingerprint === "boolean") shieldState.antiFingerprint = cfg.antiFingerprint;
        if (typeof cfg.strictFingerprint === "boolean") shieldState.strictFingerprint = cfg.strictFingerprint;
        if (typeof cfg.fpSessionSalt === "string" && cfg.fpSessionSalt) shieldState.fpSessionSalt = cfg.fpSessionSalt;
        if (typeof cfg.refererTrim === "boolean") shieldState.refererTrim = cfg.refererTrim;
        if (typeof cfg.lanGuard === "boolean") shieldState.lanGuard = cfg.lanGuard;
        if (typeof cfg.annoyances === "boolean") shieldState.annoyances = cfg.annoyances;
        if (typeof cfg.cnameUnmask === "boolean") shieldState.cnameUnmask = cfg.cnameUnmask;
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
            annoyances: shieldState.annoyances,
            blockPopups: shieldState.blockPopups,
            antiFingerprint: shieldState.antiFingerprint,
            strictFingerprint: shieldState.strictFingerprint,
            fpSessionSalt: shieldState.fpSessionSalt,
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
            stagingRules = B.createEmptyRuleEngine();
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
                    for (let i = 0; i < items.length; i++) B.indexWireRule(stagingRules, items[i]);
                    break;
                case "removeParamRules":
                    for (let i = 0; i < items.length; i++) {
                        const w = items[i];
                        if (!Array.isArray(w) || w.length < 3) continue;
                        stagingRules.removeParamRules.push({
                            pattern: w[0] || "",
                            patternRe: undefined,
                            param: w[1] || "",
                            isRegexParam: w[2] === 1,
                            paramRe: undefined,
                            includeDomains: Array.isArray(w[3]) ? w[3] : null,
                            excludeDomains: Array.isArray(w[4]) ? w[4] : null
                        });
                    }
                    break;
            }
            return true;
        }

        if (msg.action === "SHIELD_CONFIG_COMMIT") {
            if (stagingRules && msg.syncId === stagingSyncId) {
                B.activeRules = stagingRules;
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
            } else if ((host === "youtube.com" || host === "www.youtube.com" || host === "m.youtube.com") && path === "/redirect") {
                target = u.searchParams.get("q");
            } else if (host === "l.tiktok.com" || host === "slack-redir.net" || host === "go.skimresources.com" || host === "go.redirectingat.com") {
                target = u.searchParams.get("url") || u.searchParams.get("target");
            } else if (host === "t.umblr.com" && path === "/redirect") {
                target = u.searchParams.get("z");
            } else if (host === "disq.us" && path === "/url") {
                const raw = u.searchParams.get("url");
                if (raw) target = raw.replace(/:[^/]+$/, "");
            } else if (host === "click.linksynergy.com" || host === "www.anrdoezrs.net" || host === "www.dpbolvw.net" || host === "www.tkqlhce.com") {
                target = u.searchParams.get("murl") || u.searchParams.get("url");
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

    function applyRemoveParamRules(urlStr, pageHost) {
        if (!urlStr || !urlStr.includes("?")) return null;
        if (urlStr.includes("/cdn-cgi/") || urlStr.includes("__cf_chl")) return null;
        const rules = B.activeRules && B.activeRules.removeParamRules;
        if (!rules || rules.length === 0) return null;

        let parsed;
        try {
            parsed = new URL(urlStr);
        } catch (_e) {
            return null;
        }
        const lowerUrl = urlStr.toLowerCase();
        let changed = false;

        for (let i = 0; i < rules.length; i++) {
            const r = rules[i];
            if (r.includeDomains && !B.matchesDomainList(pageHost, r.includeDomains)) continue;
            if (r.excludeDomains && B.matchesDomainList(pageHost, r.excludeDomains)) continue;

            if (r.pattern && r.pattern !== "*") {
                if (r.patternRe === undefined) {
                    r.patternRe = B.compileAbpPatternToRegex(r.pattern);
                }
                if (r.patternRe && !r.patternRe.test(lowerUrl)) continue;
            }

            if (!r.param) continue;
            if (r.isRegexParam) {
                if (r.paramRe === undefined) {
                    try { r.paramRe = new RegExp(r.param, "i"); } catch (_e) { r.paramRe = null; }
                }
                if (!r.paramRe) continue;
                const keysToDel = [];
                for (const k of parsed.searchParams.keys()) {
                    if (r.paramRe.test(k)) keysToDel.push(k);
                }
                for (let j = 0; j < keysToDel.length; j++) {
                    parsed.searchParams.delete(keysToDel[j]);
                    changed = true;
                }
            } else {
                const targetLower = r.param.toLowerCase();
                const keysToDel = [];
                for (const k of parsed.searchParams.keys()) {
                    if (k.toLowerCase() === targetLower) keysToDel.push(k);
                }
                for (let j = 0; j < keysToDel.length; j++) {
                    parsed.searchParams.delete(keysToDel[j]);
                    changed = true;
                }
            }
        }
        if (!changed) return null;
        const next = parsed.toString();
        return next !== urlStr ? next : null;
    }

    function extractPathHint(urlStr) {
        try {
            const u = new URL(urlStr);
            const p = u.pathname || "/";
            return p.length > 48 ? p.slice(0, 48) + "…" : p;
        } catch (_e) {
            return "";
        }
    }

    function queueRequestLog(pageHost, reqHost, urlStr, reqType, action, ruleSource) {
        const pHost = pageHost || B.activeMainHost || "";
        if (!pHost || !reqHost) return;
        pendingRequestLogs.push({
            pageHost: pHost,
            reqHost: reqHost,
            pathHint: extractPathHint(urlStr),
            type: reqType || "other",
            action: action || "ALLOWED",
            ruleSource: ruleSource || "",
            ts: Date.now()
        });
        if (pendingRequestLogs.length >= 40) {
            flushRequestLogs();
        } else if (!requestLogTimer) {
            requestLogTimer = setTimeout(flushRequestLogs, 400);
        }
    }

    function flushRequestLogs() {
        if (requestLogTimer) {
            clearTimeout(requestLogTimer);
            requestLogTimer = null;
        }
        if (pendingRequestLogs.length === 0) return;
        const batch = pendingRequestLogs;
        pendingRequestLogs = [];
        api.runtime.sendNativeMessage("uhApp", {
            type: "REQUEST_LOG_BATCH",
            entries: batch
        }).catch(() => {});
    }

    function requestCnameResolve(host) {
        if (!host || requestedCnameHosts.has(host)) return;
        if (requestedCnameHosts.size > 1200) {
            requestedCnameHosts.clear();
        }
        requestedCnameHosts.add(host);
        api.runtime.sendNativeMessage("uhApp", {
            type: "RESOLVE_CNAME",
            host: host
        }).catch(() => {});
    }

    // Automatic silent bounce-tracker cookie/storage eviction
    function trackMainFrameHop(tabId, urlStr) {
        if (typeof tabId !== "number" || tabId < 0 || !urlStr) return;
        const host = B.normalizeHost(urlStr);
        if (!host) return;
        const base = B.getRegistrableBase(host);
        const now = Date.now();
        const prev = tabLastHop.get(tabId);
        if (prev && prev.base && prev.base !== base) {
            const elapsed = now - prev.arrivedAt;
            // Rapid cross-site redirect chain (< 2500ms) through an intermediary domain
            if (elapsed > 0 && elapsed < 2500 && !BOUNCE_SAFE_BASES.has(prev.base) && !BOUNCE_SAFE_BASES.has(prev.host)) {
                api.runtime.sendNativeMessage("uhApp", {
                    type: "BOUNCE_TRACKER_EVICT",
                    host: prev.host
                }).catch(() => {});
            }
        }
        tabLastHop.set(tabId, { host, base, arrivedAt: now });
    }

    function clearTabHop(tabId) {
        tabLastHop.delete(tabId);
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

    B.queueBlockedStat = queueBlockedStat;
    B.notifyBlockedRedirect = notifyBlockedRedirect;
    B.recordBlockedScript = recordBlockedScript;
    B.applySettingsObject = applySettingsObject;
    B.broadcastLiveSettingsToTabs = broadcastLiveSettingsToTabs;
    B.handleChunkedConfigMessage = handleChunkedConfigMessage;
    B.cleanTrackingUrl = cleanTrackingUrl;
    B.applyRemoveParamRules = applyRemoveParamRules;
    B.queueRequestLog = queueRequestLog;
    B.requestCnameResolve = requestCnameResolve;
    B.trackMainFrameHop = trackMainFrameHop;
    B.clearTabHop = clearTabHop;
    B.notifyElementCollapse = notifyElementCollapse;
})(globalThis.__uhBg);
