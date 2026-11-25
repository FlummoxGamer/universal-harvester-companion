// -----------------------------------------------------------------------------
// UH Bridge Background Module 3: Network Blocker, Popunder/Redirect Killer & Headers
// -----------------------------------------------------------------------------
(function (B) {
    const api = B.api;
    const shieldState = B.shieldState;

    const KNOWN_POPUNDER_PATH_HINTS = [
        "/popunder", "/afu.php", "/click.php?", "/redirect/pop", "/pops/",
        "/interstitial/ad", "/pop.php", "/jump/next.php", "/adclick.php",
        "/directlink", "/smartlink"
    ];

    function isMainFrameAdRedirect(urlStr, sourceHost) {
        if (!urlStr || (!urlStr.startsWith("http://") && !urlStr.startsWith("https://"))) return false;
        if (urlStr.includes("/cdn-cgi/")) return false;
        const reqHost = B.normalizeHost(urlStr);
        if (!reqHost || reqHost === "challenges.cloudflare.com") return false;
        if (shieldState.redirectAllowOnceHosts && shieldState.redirectAllowOnceHosts.has(reqHost)) return false;

        const isThirdParty = !sourceHost || B.getRegistrableBase(reqHost) !== B.getRegistrableBase(sourceHost);
        if (!isThirdParty) return false;

        const rules = B.activeRules;
        if (B.matchesDomainSet(reqHost, rules.exceptionDomains)) return false;

        if (
            B.matchesDomainSet(reqHost, rules.popupDomains) ||
            B.matchesDomainSet(reqHost, rules.adDomains) ||
            B.matchesDomainSet(reqHost, rules.thirdPartyAdDomains)
        ) {
            return true;
        }

        const lower = urlStr.toLowerCase();
        for (let i = 0; i < KNOWN_POPUNDER_PATH_HINTS.length; i++) {
            if (lower.includes(KNOWN_POPUNDER_PATH_HINTS[i])) return true;
        }

        for (let i = 0; i < rules.popupRules.length; i++) {
            if (B.matchesStructuredRule(rules.popupRules[i], lower, sourceHost || "", true, B.TYPE_POPUP)) {
                return true;
            }
        }
        return false;
    }

    function isBlockedPopunderUrl(urlStr, openerHost) {
        if (isMainFrameAdRedirect(urlStr, openerHost)) return true;
        if (!urlStr || (!urlStr.startsWith("http://") && !urlStr.startsWith("https://"))) return false;
        const reqHost = B.normalizeHost(urlStr);
        if (!reqHost) return false;
        if (shieldState.redirectAllowOnceHosts && shieldState.redirectAllowOnceHosts.has(reqHost)) return false;
        const isThirdParty = !openerHost || B.getRegistrableBase(reqHost) !== B.getRegistrableBase(openerHost);
        if (!isThirdParty) return false;
        const rules = B.activeRules;
        if (B.matchesDomainSet(reqHost, rules.exceptionDomains)) return false;
        return B.matchesDomainSet(reqHost, rules.trackerDomains);
    }

    function checkAndClosePopupTab(tabId, urlStr, openerHost, openerTabId) {
        if (!shieldState.blockPopups || shieldState.shieldLevel === 0) return false;
        if (openerHost && shieldState.pausedHosts.has(openerHost)) return false;
        if (!isBlockedPopunderUrl(urlStr, openerHost)) return false;

        B.recentPopupCandidates.delete(tabId);
        const targetHost = B.normalizeHost(urlStr);
        B.queueBlockedStat(openerHost || targetHost, false, targetHost, "popup_tab");
        B.notifyBlockedRedirect(openerHost || B.activeMainHost, urlStr, true);

        const removeTab = () => {
            try {
                api.tabs.remove(tabId).catch(() => {});
            } catch (_e) {}
        };

        // Always re-select the parent/opener tab BEFORE closing the popunder tab
        // so the browser returns cleanly to the parent page instead of jumping to Tab 0.
        if (typeof openerTabId === "number" && openerTabId >= 0 && openerTabId !== tabId && api.tabs && api.tabs.update) {
            try {
                api.tabs.update(openerTabId, { active: true })
                    .then(removeTab)
                    .catch(removeTab);
                return true;
            } catch (_e) {}
        }
        removeTab();
        return true;
    }

    // -------------------------------------------------------------------------
    // Layer 1 Network Blocker + Main-Frame Ad-Redirect Guard + Smart JS + ClearURLs
    // -------------------------------------------------------------------------
    if (api.webRequest && api.webRequest.onBeforeRequest) {
        api.webRequest.onBeforeRequest.addListener(
            (details) => {
                try {
                    if (details.type === "main_frame") {
                        const hasTab = typeof details.tabId === "number" && details.tabId >= 0;
                        const prevTabUrl = hasTab ? (B.tabUrlMap.get(details.tabId) || "") : "";
                        const prevHost = B.normalizeHost(prevTabUrl);
                        const nextHost = B.normalizeHost(details.url);

                        // A) Cancel newly opened popunder tab at network layer before it renders
                        if (hasTab && shieldState.blockPopups && shieldState.shieldLevel !== 0) {
                            const cand = B.recentPopupCandidates.get(details.tabId);
                            if (cand && (Date.now() - cand.createdAt) <= 5000) {
                                if (checkAndClosePopupTab(details.tabId, details.url, cand.openerHost, cand.openerTabId)) {
                                    return { cancel: true };
                                }
                            }
                        }

                        // B) Cancel same-tab full-page ad/interstitial hijack so user stays on parent page
                        if (
                            hasTab &&
                            shieldState.blockPopups &&
                            shieldState.shieldLevel !== 0 &&
                            prevHost &&
                            !shieldState.pausedHosts.has(prevHost) &&
                            isMainFrameAdRedirect(details.url, prevHost)
                        ) {
                            B.queueBlockedStat(prevHost, false, nextHost, "main_frame_redirect");
                            B.notifyBlockedRedirect(prevHost, details.url, false);
                            return { cancel: true };
                        }

                        if (nextHost) B.activeMainHost = nextHost;
                        if (hasTab) {
                            B.trackMainFrameHop(details.tabId, details.url);
                            B.tabUrlMap.set(details.tabId, details.url);
                            B.tabJsBlockedMap.delete(details.tabId);
                            if (nextHost) {
                                api.runtime.sendNativeMessage("uhApp", {
                                    type: "PAGE_NAVIGATED",
                                    host: nextHost
                                }).catch(() => {});
                            }
                        }
                        if (details.method === "GET") {
                            let nextUrl = details.url;
                            const cleaned = B.cleanTrackingUrl(nextUrl, true);
                            if (cleaned) nextUrl = cleaned;
                            if (shieldState.shieldLevel !== 0 && !shieldState.pausedHosts.has(nextHost)) {
                                const rp = B.applyRemoveParamRules(nextUrl, nextHost);
                                if (rp) nextUrl = rp;
                            }
                            if (nextUrl !== details.url) {
                                return { redirectUrl: nextUrl };
                            }
                        }
                        return {};
                    }

                    const tabUrl = (typeof details.tabId === "number" && details.tabId >= 0) ? (B.tabUrlMap.get(details.tabId) || "") : "";
                    const pageUrl = tabUrl || details.documentUrl || details.originUrl || "";
                    const pageHost = B.normalizeHost(pageUrl) || B.activeMainHost;
                    const isPaused = !!(pageHost && shieldState.pausedHosts.has(pageHost));

                    const reqHost = B.normalizeHost(details.url);
                    if (!reqHost) return {};
                    const isThirdParty = !pageHost || B.getRegistrableBase(reqHost) !== B.getRegistrableBase(pageHost);
                    const typeBit = B.mapDetailsTypeToBit(details.type);

                    // Never block Cloudflare Turnstile / challenge assets
                    if (reqHost === "challenges.cloudflare.com" || (details.url.includes("/cdn-cgi/") && !details.url.includes("/cdn-cgi/rum"))) {
                        return {};
                    }

                    // 0) Local Network / Private-IP Port-Scan Guard (public site -> localhost / LAN IP)
                    if (
                        shieldState.lanGuard &&
                        !isPaused &&
                        pageHost &&
                        !B.isPrivateOrLanHost(pageHost) &&
                        B.isPrivateOrLanHost(reqHost)
                    ) {
                        B.queueRequestLog(pageHost, reqHost, details.url, details.type, "BLOCKED_LAN", "Local Network Guard");
                        return { cancel: true };
                    }

                    // 0B) Google One-Tap Sign-In iframe/script killer (Annoyance Pack)
                    if (
                        shieldState.annoyances &&
                        shieldState.shieldLevel !== 0 &&
                        !isPaused &&
                        isThirdParty &&
                        reqHost === "accounts.google.com" &&
                        details.url.includes("/gsi/")
                    ) {
                        B.queueBlockedStat(pageHost, false, reqHost, details.type);
                        B.queueRequestLog(pageHost, reqHost, details.url, details.type, "BLOCKED_AD", "Google One-Tap");
                        B.notifyElementCollapse(details);
                        return { cancel: true };
                    }

                    // 1) Smart JavaScript Blocking
                    if (shieldState.smartBlockJs && typeBit === B.TYPE_SCRIPT && isThirdParty && !isPaused) {
                        const jsAllowed = pageHost && (
                            shieldState.jsAllowOnceHosts.has(pageHost) ||
                            shieldState.jsAllowAlwaysHosts.has(pageHost)
                        );
                        if (!jsAllowed) {
                            B.recordBlockedScript(details.tabId, pageHost, reqHost);
                            B.queueRequestLog(pageHost, reqHost, details.url, details.type, "BLOCKED_TRACKER", "Smart JS Block");
                            return { cancel: true };
                        }
                    }

                    // 2) UH Shield Network Filtering (Ad-First Classification + CNAME Unmasking + 5s Retry Dedup)
                    if (shieldState.shieldLevel !== 0 && !isPaused) {
                        const rules = B.activeRules;
                        const lowerUrl = details.url.toLowerCase();
                        let urlTokens = null;
                        const getTokens = () => {
                            if (!urlTokens) urlTokens = B.tokenizeUrl(lowerUrl);
                            return urlTokens;
                        };

                        // 2A) $important rules
                        if (rules.importantRules.length > 0) {
                            for (let i = 0; i < rules.importantRules.length; i++) {
                                const r = rules.importantRules[i];
                                if (B.matchesStructuredRule(r, lowerUrl, pageHost, isThirdParty, typeBit)) {
                                    B.queueBlockedStat(pageHost, r.isTracker, reqHost, details.type);
                                    B.queueRequestLog(pageHost, reqHost, details.url, details.type, r.isTracker ? "BLOCKED_TRACKER" : "BLOCKED_AD", "Important Rule");
                                    const shim = B.resolveSmartRedirect(r, details, lowerUrl);
                                    if (shim) return { redirectUrl: shim };
                                    B.notifyElementCollapse(details);
                                    return { cancel: true };
                                }
                            }
                        }

                        // 2B) @@ Exception rules
                        let isExempt = B.matchesDomainSet(reqHost, rules.exceptionDomains);
                        if (!isExempt && isThirdParty) {
                            isExempt = B.matchesDomainSet(reqHost, rules.thirdPartyExceptionDomains);
                        }
                        if (!isExempt && (rules.excByDomain.size > 0 || rules.excByToken.size > 0 || rules.excGeneric.length > 0)) {
                            const excMatch = B.findMatchingRuleInIndex(
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

                            // 2C) CNAME-Cloaked First-Party Tracker Blocking + Async Unmasking
                            if (shieldState.cnameUnmask) {
                                const cnameHit = B.cnameBlockedHosts.get(reqHost);
                                if (cnameHit) {
                                    B.queueBlockedStat(pageHost, cnameHit.isTracker !== false, reqHost, details.type);
                                    B.queueRequestLog(
                                        pageHost,
                                        reqHost,
                                        details.url,
                                        details.type,
                                        "BLOCKED_CNAME",
                                        "CNAME → " + (cnameHit.target || "tracker")
                                    );
                                    B.notifyElementCollapse(details);
                                    return { cancel: true };
                                }
                                // Queue zero-lag async DoH CNAME lookup for first-party subdomains (e.g. metrics.site.com)
                                if (!isThirdParty && reqHost !== pageHost && reqHost.split(".").length >= 3) {
                                    B.requestCnameResolve(reqHost);
                                }
                                // Offline first-party tracker proxy paths (/gtm.js, /g/collect, /_vercel/insights, /cdn-cgi/rum)
                                if (allowDomainFirstParty && !isThirdParty && B.FIRST_PARTY_TRACKER_PATH_RE.test(details.url)) {
                                    B.queueBlockedStat(pageHost, true, reqHost, details.type);
                                    B.queueRequestLog(pageHost, reqHost, details.url, details.type, "BLOCKED_TRACKER", "1st-Party Tracker Path");
                                    B.notifyElementCollapse(details);
                                    return { cancel: true };
                                }
                            }

                            // Always classify Ads BEFORE Trackers so ad domains present in multi-purpose lists count as Ads
                            if ((allowDomainFirstParty || isThirdParty) && B.matchesDomainSet(reqHost, rules.adDomains)) {
                                B.queueBlockedStat(pageHost, false, reqHost, details.type);
                                B.queueRequestLog(pageHost, reqHost, details.url, details.type, "BLOCKED_AD", "Ad Domain");
                                const shim = B.resolveSmartRedirect(null, details, lowerUrl);
                                if (shim) return { redirectUrl: shim };
                                B.notifyElementCollapse(details);
                                return { cancel: true };
                            }
                            if ((allowDomainFirstParty || isThirdParty) && B.matchesDomainSet(reqHost, rules.trackerDomains)) {
                                B.queueBlockedStat(pageHost, true, reqHost, details.type);
                                B.queueRequestLog(pageHost, reqHost, details.url, details.type, "BLOCKED_TRACKER", "Tracker Domain");
                                B.notifyElementCollapse(details);
                                return { cancel: true };
                            }
                            if (isThirdParty) {
                                if (B.matchesDomainSet(reqHost, rules.thirdPartyAdDomains)) {
                                    B.queueBlockedStat(pageHost, false, reqHost, details.type);
                                    B.queueRequestLog(pageHost, reqHost, details.url, details.type, "BLOCKED_AD", "3P Ad Domain");
                                    const shim = B.resolveSmartRedirect(null, details, lowerUrl);
                                    if (shim) return { redirectUrl: shim };
                                    B.notifyElementCollapse(details);
                                    return { cancel: true };
                                }
                                if (B.matchesDomainSet(reqHost, rules.thirdPartyTrackerDomains)) {
                                    B.queueBlockedStat(pageHost, true, reqHost, details.type);
                                    B.queueRequestLog(pageHost, reqHost, details.url, details.type, "BLOCKED_TRACKER", "3P Tracker Domain");
                                    B.notifyElementCollapse(details);
                                    return { cancel: true };
                                }
                            }

                            if (rules.blockByDomain.size > 0 || rules.blockByToken.size > 0 || rules.blockGeneric.length > 0) {
                                const matched = B.findMatchingRuleInIndex(
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
                                    const allowInStandard = isThirdParty || matched.firstPartyOnly || (typeBit === B.TYPE_IMAGE) || (typeBit === B.TYPE_SUBDOCUMENT);
                                    if (shieldState.shieldLevel >= 2 || allowInStandard) {
                                        B.queueBlockedStat(pageHost, matched.isTracker, reqHost, details.type);
                                        B.queueRequestLog(
                                            pageHost,
                                            reqHost,
                                            details.url,
                                            details.type,
                                            matched.isTracker ? "BLOCKED_TRACKER" : "BLOCKED_AD",
                                            "Filter Rule"
                                        );
                                        const shim = B.resolveSmartRedirect(matched, details, lowerUrl);
                                        if (shim) return { redirectUrl: shim };
                                        B.notifyElementCollapse(details);
                                        return { cancel: true };
                                    }
                                }
                            }
                        }
                    }

                    // 3) ClearURLs + $removeparam on sub_frame & xmlhttprequest GET
                    if (
                        !isPaused &&
                        details.method === "GET" &&
                        (details.type === "sub_frame" || details.type === "xmlhttprequest" || details.type === "fetch") &&
                        details.url.includes("?")
                    ) {
                        let nextSub = details.url;
                        if (shieldState.clearUrls) {
                            const cleanedSub = B.cleanTrackingUrl(nextSub, false);
                            if (cleanedSub) nextSub = cleanedSub;
                        }
                        if (shieldState.shieldLevel !== 0) {
                            const rpSub = B.applyRemoveParamRules(nextSub, pageHost);
                            if (rpSub) nextSub = rpSub;
                        }
                        if (nextSub !== details.url) {
                            return { redirectUrl: nextSub };
                        }
                    }

                    // Log allowed subresource requests for Live Request Inspector (skip high-volume font/stylesheet noise only if same-origin)
                    if (isThirdParty || details.type === "script" || details.type === "xmlhttprequest" || details.type === "fetch" || details.type === "sub_frame") {
                        B.queueRequestLog(pageHost, reqHost, details.url, details.type, "ALLOWED", isThirdParty ? "3P" : "1P");
                    }
                } catch (_e) {}
                return {};
            },
            { urls: ["http://*/*", "https://*/*"] },
            ["blocking"]
        );
    }

    // -------------------------------------------------------------------------
    // Popunder Ad-Tab Trap Watcher (Parent-Tab-First Closure)
    // -------------------------------------------------------------------------
    if (api.webNavigation && api.webNavigation.onCreatedNavigationTarget) {
        api.webNavigation.onCreatedNavigationTarget.addListener((details) => {
            try {
                if (!shieldState.blockPopups || shieldState.shieldLevel === 0) return;
                const openerUrl = B.tabUrlMap.get(details.sourceTabId) || "";
                const openerHost = B.normalizeHost(openerUrl);
                B.recentPopupCandidates.set(details.tabId, {
                    openerTabId: details.sourceTabId,
                    openerHost,
                    createdAt: Date.now()
                });
                if (details.url) {
                    checkAndClosePopupTab(details.tabId, details.url, openerHost, details.sourceTabId);
                }
            } catch (_e) {}
        });
    }

    if (api.tabs && api.tabs.onCreated) {
        api.tabs.onCreated.addListener((tab) => {
            try {
                if (!shieldState.blockPopups || shieldState.shieldLevel === 0) return;
                if (typeof tab.openerTabId === "number" && tab.openerTabId >= 0) {
                    const openerUrl = B.tabUrlMap.get(tab.openerTabId) || "";
                    const openerHost = B.normalizeHost(openerUrl);
                    B.recentPopupCandidates.set(tab.id, {
                        openerTabId: tab.openerTabId,
                        openerHost,
                        createdAt: Date.now()
                    });
                    const candUrl = tab.pendingUrl || tab.url || "";
                    if (candUrl) {
                        checkAndClosePopupTab(tab.id, candUrl, openerHost, tab.openerTabId);
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
                const cand = B.recentPopupCandidates.get(tabId);
                if (cand) {
                    if (Date.now() - cand.createdAt > 5000) {
                        B.recentPopupCandidates.delete(tabId);
                    } else if (checkAndClosePopupTab(tabId, url, cand.openerHost, cand.openerTabId)) {
                        return;
                    }
                }
                B.tabUrlMap.set(tabId, url);
            } catch (_e) {}
        });
    }

    // -------------------------------------------------------------------------
    // Privacy Request Headers: DNT, Sec-GPC & Cross-Origin Referer Trimming
    // -------------------------------------------------------------------------
    if (api.webRequest && api.webRequest.onBeforeSendHeaders) {
        api.webRequest.onBeforeSendHeaders.addListener(
            (details) => {
                try {
                    if (!shieldState.dnt && !shieldState.gpc && !shieldState.refererTrim) return {};
                    const headers = details.requestHeaders ? details.requestHeaders.slice() : [];
                    let modified = false;

                    if (shieldState.dnt && !headers.some((h) => h.name.toLowerCase() === "dnt")) {
                        headers.push({ name: "DNT", value: "1" });
                        modified = true;
                    }
                    if (shieldState.gpc && !headers.some((h) => h.name.toLowerCase() === "sec-gpc")) {
                        headers.push({ name: "Sec-GPC", value: "1" });
                        modified = true;
                    }

                    // Trim cross-site Referer down to origin-only to prevent path/search leakage
                    if (
                        shieldState.refererTrim &&
                        details.url &&
                        !details.url.includes("/cdn-cgi/") &&
                        !details.url.includes("challenges.cloudflare.com")
                    ) {
                        const reqHost = B.normalizeHost(details.url);
                        const reqBase = reqHost ? B.getRegistrableBase(reqHost) : "";
                        if (reqBase) {
                            for (let i = 0; i < headers.length; i++) {
                                if (headers[i].name.toLowerCase() === "referer" && headers[i].value) {
                                    try {
                                        const refUrl = new URL(headers[i].value);
                                        const refHost = refUrl.hostname.toLowerCase().replace(/^www\./, "");
                                        if (refHost && B.getRegistrableBase(refHost) !== reqBase) {
                                            const trimmed = refUrl.origin + "/";
                                            if (headers[i].value !== trimmed) {
                                                headers[i] = { name: headers[i].name, value: trimmed };
                                                modified = true;
                                            }
                                        }
                                    } catch (_e) {}
                                    break;
                                }
                            }
                        }
                    }

                    return modified ? { requestHeaders: headers } : {};
                } catch (_e) {
                    return {};
                }
            },
            { urls: ["http://*/*", "https://*/*"] },
            ["blocking", "requestHeaders"]
        );
    }

    if (api.tabs && api.tabs.onRemoved) {
        api.tabs.onRemoved.addListener((tabId) => {
            B.tabUrlMap.delete(tabId);
            B.tabJsBlockedMap.delete(tabId);
            B.recentPopupCandidates.delete(tabId);
            B.clearTabHop(tabId);
        });
    }

    // Seed tabUrlMap for any already-open tabs
    if (api.tabs && api.tabs.query) {
        api.tabs.query({}).then((tabs) => {
            if (!tabs) return;
            for (const t of tabs) {
                if (t.url) {
                    B.tabUrlMap.set(t.id, t.url);
                    if (t.active) {
                        const h = B.normalizeHost(t.url);
                        if (h) B.activeMainHost = h;
                    }
                }
            }
        }).catch(() => {});
    }

    B.isBlockedPopunderUrl = isBlockedPopunderUrl;
    B.isMainFrameAdRedirect = isMainFrameAdRedirect;
})(globalThis.__uhBg);
