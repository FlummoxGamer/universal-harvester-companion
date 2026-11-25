package app.harvester.companion.privacy

import app.harvester.companion.R

/**
 * Built-in filter list catalog and rebranded UH Shield filter categories.
 */
internal object UhShieldCatalog {

    val BUILT_IN_LISTS: List<FilterListDef> = listOf(
        // === Full Power Default Pack (enabled out of the box) ===
        FilterListDef(
            id = "easylist",
            name = "EasyList",
            description = "Primary web advertisement filter list",
            url = "https://easylist.to/easylist/easylist.txt",
            tier = FilterPackTier.CORE,
            defaultEnabled = true,
            isTrackerList = false,
        ),
        FilterListDef(
            id = "easyprivacy",
            name = "EasyPrivacy",
            description = "Comprehensive analytics, beacons & cross-site trackers",
            url = "https://easylist.to/easylist/easyprivacy.txt",
            tier = FilterPackTier.CORE,
            defaultEnabled = true,
            isTrackerList = true,
        ),
        FilterListDef(
            id = "ubo_filters",
            name = "uBlock Core Filters",
            description = "High-precision ads, popunders & anti-adblock fixes",
            url = "https://ublockorigin.github.io/uAssets/filters/filters.txt",
            tier = FilterPackTier.CORE,
            defaultEnabled = true,
            isTrackerList = false,
        ),
        FilterListDef(
            id = "ubo_privacy",
            name = "uBlock Privacy",
            description = "Advanced tracker & telemetry network filters",
            url = "https://ublockorigin.github.io/uAssets/filters/privacy.txt",
            tier = FilterPackTier.CORE,
            defaultEnabled = true,
            isTrackerList = true,
        ),
        FilterListDef(
            id = "ubo_badware",
            name = "uBlock Badware & Quick Fixes",
            description = "Malvertising, scam redirects & fast site break fixes",
            url = "https://ublockorigin.github.io/uAssets/filters/quick-fixes.txt",
            tier = FilterPackTier.CORE,
            defaultEnabled = true,
            isTrackerList = false,
        ),
        FilterListDef(
            id = "peter_lowe",
            name = "Peter Lowe's Ad & Tracking Server List",
            description = "Ultra-clean curated ad & tracker domain blocklist",
            url = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext",
            tier = FilterPackTier.CORE,
            defaultEnabled = true,
            isTrackerList = true,
        ),
        FilterListDef(
            id = "hagezi_pro",
            name = "HaGeZi Multi PRO",
            description = "Industry-leading ads, trackers, telemetry & CNAME cloaking",
            url = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/pro.txt",
            tier = FilterPackTier.CORE,
            defaultEnabled = true,
            isTrackerList = true,
        ),
        FilterListDef(
            id = "fanboy_cookies",
            name = "Fanboy Cookie Notices & Annoyances",
            description = "Removes cookie popups, newsletter modals & floating nags",
            url = "https://secure.fanboy.co.nz/fanboy-cookiemonster.txt",
            tier = FilterPackTier.CORE,
            defaultEnabled = true,
            isTrackerList = false,
        ),

        // === Optional Extra & Nuclear Customization Packs ===
        FilterListDef(
            id = "hagezi_ultimate",
            name = "HaGeZi Multi ULTIMATE (Nuclear)",
            description = "Maximum-strength blocking for aggressive trackers & ad networks",
            url = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/ultimate.txt",
            tier = FilterPackTier.NUCLEAR,
            defaultEnabled = false,
            isTrackerList = true,
        ),
        FilterListDef(
            id = "oisd_big",
            name = "OISD Big (Nuclear)",
            description = "Massive curated blocklist targeting ads, phishing & telemetry",
            url = "https://big.oisd.nl/",
            tier = FilterPackTier.NUCLEAR,
            defaultEnabled = false,
            isTrackerList = true,
        ),
        FilterListDef(
            id = "urlhaus_malware",
            name = "URLHaus Malware & Phishing",
            description = "Real-time malicious payload & phishing domain defense",
            url = "https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-online.txt",
            tier = FilterPackTier.NUCLEAR,
            defaultEnabled = false,
            isTrackerList = true,
        ),
        FilterListDef(
            id = "ubo_annoyances",
            name = "uBlock Annoyances & Overlays",
            description = "Strips app-install banners, paywall nags & chat popups",
            url = "https://ublockorigin.github.io/uAssets/filters/annoyances-others.txt",
            tier = FilterPackTier.NUCLEAR,
            defaultEnabled = false,
            isTrackerList = false,
        ),
        FilterListDef(
            id = "fanboy_social",
            name = "Fanboy Social Media Blocking",
            description = "Blocks embedded share buttons, like widgets & social scripts",
            url = "https://easylist.to/easylist/fanboy-social.txt",
            tier = FilterPackTier.SOCIAL,
            defaultEnabled = false,
            isTrackerList = true,
        ),
        FilterListDef(
            id = "adguard_mobile",
            name = "AdGuard Mobile Ads",
            description = "Specialized mobile web banners, sticky ads & app prompts",
            url = "https://filters.adtidy.org/extension/ublock/filters/11.txt",
            tier = FilterPackTier.REGIONAL,
            defaultEnabled = false,
            isTrackerList = false,
        ),
        FilterListDef(
            id = "indian_list",
            name = "IndianList (Regional)",
            description = "Ad & popup filters for Indian news, media & regional sites",
            url = "https://easylist-downloads.adblockplus.org/indianlist.txt",
            tier = FilterPackTier.REGIONAL,
            defaultEnabled = false,
            isTrackerList = false,
        ),
        FilterListDef(
            id = "japanese_list",
            name = "AdGuard Japanese (Regional)",
            description = "Ad filters for Japanese web portals, manga & media sites",
            url = "https://filters.adtidy.org/extension/ublock/filters/7.txt",
            tier = FilterPackTier.REGIONAL,
            defaultEnabled = false,
            isTrackerList = false,
        ),
    )

    val FILTER_CATEGORIES: List<FilterCategoryDef> = listOf(
        // === 4 Core Essentials ===
        FilterCategoryDef(
            id = "uh_core_ads",
            title = "Ad & Banner Blocking",
            description = "Blocks web ads, popups, banners, and video ads",
            tier = FilterCategoryTier.CORE,
            iconRes = R.drawable.uh_ic_cat_ads_24,
            listIds = listOf("easylist", "ubo_filters"),
        ),
        FilterCategoryDef(
            id = "uh_core_trackers",
            title = "Tracker & Analytics Shield",
            description = "Stops cross-site trackers, telemetry, and CNAME cloaking",
            tier = FilterCategoryTier.CORE,
            iconRes = R.drawable.uh_ic_cat_trackers_24,
            listIds = listOf("easyprivacy", "ubo_privacy", "peter_lowe", "hagezi_pro"),
        ),
        FilterCategoryDef(
            id = "uh_core_malware_fixes",
            title = "Malware & Anti-Breakage",
            description = "Blocks malvertising, scam redirects, and fixes broken layouts",
            tier = FilterCategoryTier.CORE,
            iconRes = R.drawable.uh_ic_cat_malware_24,
            listIds = listOf("ubo_badware"),
        ),
        FilterCategoryDef(
            id = "uh_core_cookies",
            title = "Cookie Consent & Nags",
            description = "Hides intrusive cookie banners and newsletter popups",
            tier = FilterCategoryTier.CORE,
            iconRes = R.drawable.uh_ic_pg_cookie_banner_24,
            listIds = listOf("fanboy_cookies"),
        ),

        // === 4 Extra Protection Packs ===
        FilterCategoryDef(
            id = "uh_extra_nuclear",
            title = "Maximum Threat Defense",
            description = "Heavy-duty ad networks, aggressive trackers, and telemetry",
            tier = FilterCategoryTier.EXTRA,
            iconRes = R.drawable.uh_ic_cat_nuclear_24,
            listIds = listOf("hagezi_ultimate", "oisd_big"),
        ),
        FilterCategoryDef(
            id = "uh_extra_phishing",
            title = "Anti-Phishing & Scam Guard",
            description = "Real-time malicious payload and phishing domain blocking",
            tier = FilterCategoryTier.EXTRA,
            iconRes = R.drawable.uh_ic_cat_phishing_24,
            listIds = listOf("urlhaus_malware"),
        ),
        FilterCategoryDef(
            id = "uh_extra_overlays_social",
            title = "Social Widgets & Overlays",
            description = "Strips app-install banners, paywall nags, share buttons, and chat popups",
            tier = FilterCategoryTier.EXTRA,
            iconRes = R.drawable.uh_ic_cat_social_24,
            listIds = listOf("ubo_annoyances", "fanboy_social"),
        ),
        FilterCategoryDef(
            id = "uh_extra_mobile_regional",
            title = "Mobile & Regional Ads",
            description = "Extra mobile sticky banners and international site ad filters",
            tier = FilterCategoryTier.EXTRA,
            iconRes = R.drawable.uh_ic_cat_regional_24,
            listIds = listOf("adguard_mobile", "indian_list", "japanese_list"),
        ),
    )
}
