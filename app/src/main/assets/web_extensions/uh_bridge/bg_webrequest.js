// -----------------------------------------------------------------------------
// UH Bridge Background Module 3: Network Blocker, Popunder Killer & Privacy Headers
// -----------------------------------------------------------------------------
(function (B) {
    const api = B.api;
    const shieldState = B.shieldState;

    const KNOWN_POPUNDER_PATH_HINTS = [
        "/popunder", "/afu.php", "/click.php?", "/redirect/pop", "/pops/", "/interstitial/ad"
    ];

    // -------------------------------------------------------------------------
    // Layer 1 Network Blocker + Smart 3rd-Party JS Blocker + ClearURLs
    // -------------------------------------------------------------------------
    if (api.webRequest && api.webRequest.onBeforeRequest) {
        api.webRequest.onBeforeRequest.addListener(
            (details) => {
                try {
                    if (details.type === "main_frame") {
                        const nextHost = B.normalizeHost(details.url);
                        if (nextHost) B.activeMainHost = nextHost;
                        if (typeof details.tabId === "number" && details.tabId >= 0) {
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
                            const cleaned = B.cleanTrackingUrl(details.url, true);
                            if (cleaned) {
                                return { redirectUrl: cleaned };
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
                    if (reqHost === "challenges.cloudflare.com" || details.url.includes("/cdn-cgi/")) {
                        return {};
                    }

                    // 1) Smart JavaScript Blocking
                    if (shieldState.smartBlockJs && typeBit === B.TYPE_SCRIPT && isThirdParty && !isPaused) {
                        const jsAllowed = pageHost && (
                            shieldState.jsAllowOnceHosts.has(pageHost) ||
                            shieldState.jsAllowAlwaysHosts.has(pageHost)
                        );
                        if (!jsAllowed) {
                            B.recordBlockedScript(details.tabId, pageHost, reqHost);
                            return { cancel: true };
                        }
                    }

                    // 2) UH Shield Network Filtering
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
                                    B.queueBlockedStat(pageHost, r.isTracker, reqHost);
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

                            if ((allowDomainFirstParty || isThirdParty) && B.matchesDomainSet(reqHost, rules.trackerDomains)) {
                                B.queueBlockedStat(pageHost, true, reqHost);
                                B.notifyElementCollapse(details);
                                return { cancel: true };
                            }
                            if ((allowDomainFirstParty || isThirdParty) && B.matchesDomainSet(reqHost, rules.adDomains)) {
                                B.queueBlockedStat(pageHost, false, reqHost);
                                const shim = B.resolveSmartRedirect(null, details, lowerUrl);
                                if (shim) return { redirectUrl: shim };
                                B.notifyElementCollapse(details);
                                return { cancel: true };
                            }
                            if (isThirdParty) {
                                if (B.matchesDomainSet(reqHost, rules.thirdPartyTrackerDomains)) {
                                    B.queueBlockedStat(pageHost, true, reqHost);
                                    B.notifyElementCollapse(details);
                                    return { cancel: true };
                                }
                                if (B.matchesDomainSet(reqHost, rules.thirdPartyAdDomains)) {
                                    B.queueBlockedStat(pageHost, false, reqHost);
                                    const shim = B.resolveSmartRedirect(null, details, lowerUrl);
                                    if (shim) return { redirectUrl: shim };
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
                                        B.queueBlockedStat(pageHost, matched.isTracker, reqHost);
                                        const shim = B.resolveSmartRedirect(matched, details, lowerUrl);
                                        if (shim) return { redirectUrl: shim };
                                        B.notifyElementCollapse(details);
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
                        const cleanedSub = B.cleanTrackingUrl(details.url, false);
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

    // -------------------------------------------------------------------------
    // Popunder Ad-Tab Trap Blocker
    // -------------------------------------------------------------------------
    function isBlockedPopunderUrl(urlStr, openerHost) {
        if (!urlStr || (!urlStr.startsWith("http://") && !urlStr.startsWith("https://"))) return false;
        const reqHost = B.normalizeHost(urlStr);
        if (!reqHost) return false;
        const isThirdParty = !openerHost || B.getRegistrableBase(reqHost) !== B.getRegistrableBase(openerHost);
        if (!isThirdParty) return false;

        const rules = B.activeRules;
        if (B.matchesDomainSet(reqHost, rules.exceptionDomains)) return false;

        if (
            B.matchesDomainSet(reqHost, rules.popupDomains) ||
            B.matchesDomainSet(reqHost, rules.adDomains) ||
            B.matchesDomainSet(reqHost, rules.thirdPartyAdDomains) ||
            B.matchesDomainSet(reqHost, rules.trackerDomains)
        ) {
            return true;
        }

        const lower = urlStr.toLowerCase();
        for (let i = 0; i < KNOWN_POPUNDER_PATH_HINTS.length; i++) {
            if (lower.includes(KNOWN_POPUNDER_PATH_HINTS[i])) return true;
        }

        for (let i = 0; i < rules.popupRules.length; i++) {
            if (B.matchesStructuredRule(rules.popupRules[i], lower, openerHost || "", true, B.TYPE_POPUP)) {
                return true;
            }
        }
        return false;
    }

    function checkAndClosePopupTab(tabId, urlStr, openerHost) {
        if (!shieldState.blockPopups || shieldState.shieldLevel === 0) return;
        if (openerHost && shieldState.pausedHosts.has(openerHost)) return;
        if (isBlockedPopunderUrl(urlStr, openerHost)) {
            B.recentPopupCandidates.delete(tabId);
            B.queueBlockedStat(openerHost || B.normalizeHost(urlStr), false, B.normalizeHost(urlStr));
            try {
                api.tabs.remove(tabId).catch(() => {});
            } catch (_e) {}
        }
    }

    if (api.webNavigation && api.webNavigation.onCreatedNavigationTarget) {
        api.webNavigation.onCreatedNavigationTarget.addListener((details) => {
            try {
                if (!shieldState.blockPopups || shieldState.shieldLevel === 0) return;
                const openerUrl = B.tabUrlMap.get(details.sourceTabId) || "";
                const openerHost = B.normalizeHost(openerUrl);
                B.recentPopupCandidates.set(details.tabId, { openerHost, createdAt: Date.now() });
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
                    const openerUrl = B.tabUrlMap.get(tab.openerTabId) || "";
                    const openerHost = B.normalizeHost(openerUrl);
                    B.recentPopupCandidates.set(tab.id, { openerHost, createdAt: Date.now() });
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
                B.tabUrlMap.set(tabId, url);
                const cand = B.recentPopupCandidates.get(tabId);
                if (cand) {
                    if (Date.now() - cand.createdAt > 4000) {
                        B.recentPopupCandidates.delete(tabId);
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
            B.tabUrlMap.delete(tabId);
            B.tabJsBlockedMap.delete(tabId);
            B.recentPopupCandidates.delete(tabId);
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
})(globalThis.__uhBg);
