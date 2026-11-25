package app.harvester.companion.privacy

/**
 * Bundled high-impact Day-0 offline bootstrap rules & always-on popunder/betting redirector
 * defenses so protection is instantaneous even before external lists finish syncing.
 */
internal object UhShieldBootstrapRules {

    /**
     * Confirmed popunder, click-hijack & gambling/betting affiliate redirector domains.
     * Merged into popupDomains whenever UH Shield is active so streaming/download redirect
     * traps (such as mlsbd -> jaya9bangla.com) are neutralized on Day 0 and forever.
     */
    val ALWAYS_POPUP_DOMAINS: Set<String> = setOf(
        // Gambling / betting affiliate popunder redirectors (South Asian & global streaming traps)
        "jaya9bangla.com", "jaya9.com", "jaya9.live", "jaya9.bet", "jaya9bd.com", "jaya9win.com",
        "babu88.com", "babu88.net", "babu88.live", "babu88bd.com",
        "krikya.com", "krikya88.com", "krikya11.com", "krikyabd.com",
        "baji.live", "baji999.live", "bj88.com", "bj888.live", "bajilive.net",
        "crickex.com", "crickex.net", "crickex.in", "crickexbd.com",
        "jeebet.com", "jeetbuzz.com", "jeetwin.com", "mcw19.com", "casinomcw.com", "nagad88.com",
        "melbet.com", "1xbet.com", "1xbit.com", "mostbet.com", "betwinner.com",
        "parimatch.com", "linebet.com", "megapari.com", "22bet.com", "4rabet.com", "1win.pro", "1win.com",
        // Dedicated popunder & malvertising redirector networks
        "popads.net", "popcash.net", "propellerads.com", "propellerclick.com",
        "adsterra.com", "monetag.com", "clickadu.com", "hilltopads.net", "hilltopads.com",
        "exoclick.com", "exosrv.com", "realsrv.com", "magsrv.com", "pemsrv.com",
        "juicyads.com", "trafficjunky.net", "trafficstars.com", "tsyndicate.com",
        "acscdn.com", "highperformanceformat.com", "profitablecpmrate.com",
        "effectivegatecpm.com", "topcreativeformat.com", "onclickalgo.com",
        "onclickperformance.com", "onclkds.com", "admaven.com", "galaksion.com",
        "evadav.com", "richads.com", "pushame.com", "wpadmngr.com", "popunder.net",
        "doublepimp.com", "go2affise.com", "trackwilltrk.com", "offergate.pro",
    )

    val BOOTSTRAP_AD_DOMAINS: Set<String> = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "pagead2.googlesyndication.com", "tpc.googlesyndication.com", "ade.googlesyndication.com",
        "adnxs.com", "adsrvr.org", "advertising.com", "adform.net", "adcolony.com", "admob.com",
        "amazon-adsystem.com", "aax.amazon-adsystem.com", "criteo.com", "criteo.net",
        "taboola.com", "outbrain.com", "mgid.com", "revcontent.com", "zergnet.com",
        "pubmatic.com", "rubiconproject.com", "openx.net", "casalemedia.com", "indexww.com",
        "smartadserver.com", "bidswitch.net", "triplelift.com", "sharethrough.com", "33across.com",
        "media.net", "yieldmo.com", "teads.tv", "spotxchange.com", "springserve.com",
        "popads.net", "popcash.net", "propellerads.com", "exoclick.com", "exosrv.com",
        "juicyads.com", "trafficjunky.net", "trafficstars.com", "adsterra.com", "a-ads.com",
        "adcash.com", "hilltopads.net", "clickadu.com", "monetag.com", "pushame.com",
        "zedo.com", "undertone.com", "gumgum.com", "sovrn.com", "lijit.com", "kargo.com",
        "moatads.com", "adsafeprotected.com", "doubleverify.com", "serving-sys.com",
        "flashtalking.com", "innovid.com", "tremorhub.com", "vungle.com", "unityads.unity3d.com",
        "applovin.com", "chartboost.com", "inmobi.com", "ironsrc.com", "startappservice.com",
        "ads.yahoo.com", "ads.linkedin.com", "ads.twitter.com", "ads-api.twitter.com",
        "ads.tiktok.com", "ads.reddit.com", "ads.pinterest.com", "an.facebook.com",
        "static.ads-twitter.com", "pixel.advertising.com", "banners.ebay.com",
    )

    val BOOTSTRAP_TRACKER_DOMAINS: Set<String> = setOf(
        "google-analytics.com", "googletagmanager.com", "googletagservices.com",
        "analytics.google.com", "stats.g.doubleclick.net", "app-measurement.com",
        "connect.facebook.net", "pixel.facebook.com", "tr.snapchat.com", "sc-static.net",
        "analytics.tiktok.com", "analytics.twitter.com", "t.co", "bat.bing.com",
        "clarity.ms", "hotjar.com", "static.hotjar.com", "script.hotjar.com",
        "fullstory.com", "logrocket.com", "mouseflow.com", "crazyegg.com", "luckyorange.com",
        "mixpanel.com", "api.mixpanel.com", "segment.io", "api.segment.io", "cdn.segment.com",
        "amplitude.com", "api.amplitude.com", "heapanalytics.com", "kissmetrics.com",
        "scorecardresearch.com", "quantserve.com", "quantcount.com", "chartbeat.com", "chartbeat.net",
        "omtrdc.net", "2o7.net", "demdex.net", "everesttech.net", "bluekai.com", "krxd.net",
        "exelator.com", "rlcdn.com", "tapad.com", "liveramp.com", "pippio.com", "id5-sync.com",
        "crwdcntrl.net", "agkn.com", "narrative.io", "lotame.com", "eyeota.net",
        "newrelic.com", "bam.nr-data.net", "js-agent.newrelic.com", "bugsnag.com",
        "sentry.io", "browser-intake-datadoghq.com", "branch.io", "app.link", "adjust.com",
        "appsflyer.com", "kochava.com", "singular.net", "braze.com", "onesignal.com",
        "clevertap.com", "webengage.com", "mc.yandex.ru", "metrika.yandex.ru", "hm.baidu.com",
        "coinhive.com", "coin-hive.com", "authedmine.com", "cryptoloot.pro",
    )

    val BOOTSTRAP_URL_PATTERNS: List<String> = listOf(
        "/pagead/js/", "/pagead/ads", "/adserver/", "/adservice/", "/ads/banner",
        "/advert/", "/ad_banner", "/sponsored_ads", "/prebid.js", "/gpt.js",
        "/pubads_impl", "/popunder", "/track/pixel", "/beacon.js", "/telemetry/v1",
        "/ad_300x250", "/ad_728x90", "/ads/static/", "/banner_ad",
    )

    val BOOTSTRAP_GLOBAL_COSMETICS: List<String> = listOf(
        "ins.adsbygoogle",
        "[id^='google_ads_iframe']",
        "[id^='div-gpt-ad']",
        ".adsbygoogle",
        ".ad-container",
        ".ad-wrapper",
        ".ad-banner",
        ".ad-slot",
        ".sponsored-content",
        ".taboola-container",
        "#taboola-below-article-thumbnails",
        ".OUTBRAIN",
        "[data-ad-slot]",
        "[data-ad-unit]",
        "[data-google-query-id]",
        "iframe[src*='doubleclick.net']",
        "iframe[src*='googlesyndication.com']",
    )

    val BOOTSTRAP_DOMAIN_COSMETICS: Map<String, List<String>> = mapOf(
        "youtube.com" to listOf(
            "ytd-ad-slot-renderer",
            "ytd-banner-promo-renderer",
            "ytd-promoted-sparkles-web-renderer",
            "ytm-promoted-sparkles-web-renderer",
            "ytm-companion-ad-renderer",
            "#player-ads",
            ".ytp-ad-overlay-container",
            ".ytp-ad-module",
            "ad-slot-renderer",
        ),
        "m.youtube.com" to listOf(
            "ytm-promoted-sparkles-web-renderer",
            "ytm-companion-ad-renderer",
            "ad-slot-renderer",
            ".ad-showing .video-ads",
        ),
        "reddit.com" to listOf(
            "shreddit-ad-post",
            "[data-testid='promoted-post']",
            ".promotedlink",
        ),
    )

    val fallbackRuleCount: Int
        get() = BOOTSTRAP_AD_DOMAINS.size +
            BOOTSTRAP_TRACKER_DOMAINS.size +
            ALWAYS_POPUP_DOMAINS.size +
            BOOTSTRAP_URL_PATTERNS.size +
            BOOTSTRAP_GLOBAL_COSMETICS.size

    fun buildBootstrapFilterSet(): UhAdblockRuleCompiler.CompiledFilterSet {
        val builder = UhAdblockRuleCompiler.Builder()
        builder.adDomains.addAll(BOOTSTRAP_AD_DOMAINS)
        builder.trackerDomains.addAll(BOOTSTRAP_TRACKER_DOMAINS)
        builder.popupDomains.addAll(ALWAYS_POPUP_DOMAINS)
        BOOTSTRAP_URL_PATTERNS.forEach { pat ->
            builder.addNetworkRule(
                UhAdblockRuleCompiler.CompiledNetworkRule(
                    pattern = pat,
                    isTracker = false,
                )
            )
        }
        builder.globalCosmetics.addAll(BOOTSTRAP_GLOBAL_COSMETICS)
        BOOTSTRAP_DOMAIN_COSMETICS.forEach { (dom, sels) ->
            builder.domainCosmetics[dom] = sels.toMutableList()
        }
        return builder.build()
    }
}
