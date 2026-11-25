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

    function queueBlockedStat(pageHost, isTracker, fallbackHost) {
        const key = pageHost || B.activeMainHost || fallbackHost || "web";
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

    B.queueBlockedStat = queueBlockedStat;
    B.recordBlockedScript = recordBlockedScript;
    B.applySettingsObject = applySettingsObject;
    B.broadcastLiveSettingsToTabs = broadcastLiveSettingsToTabs;
    B.handleChunkedConfigMessage = handleChunkedConfigMessage;
    B.cleanTrackingUrl = cleanTrackingUrl;
    B.notifyElementCollapse = notifyElementCollapse;
})(globalThis.__uhBg);
