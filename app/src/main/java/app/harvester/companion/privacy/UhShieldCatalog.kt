package app.harvester.companion.privacy

import app.harvester.companion.R

/**
 * Built-in filter list catalog and unified neutral UH Shield filter categories.
 */
internal object UhShieldCatalog {

    val BUILT_IN_LISTS: List<FilterListDef> = listOf(
        // === Default Multi-Aspect Protection Pack (enabled out of the box) ===
        FilterListDef(
            id = "easylist",
            name = "Primary Web Ad Filters",
            description = "Blocks standard display banners, video ads & ad frames",
            url = "https://easylist.to/easylist/easylist.txt",
            tier = FilterPackTier.CORE,
            defaultEnabled = true,
            isTrackerList = false,
        ),
        FilterListDef(
            id = "ubo_filters",
            name = "Precision Ads & Popunder Rules",
            description = "High-precision ads, popunders, scriptlets & anti-adblock defenses",
            url = "https://ublockorigin.github.io/uAssets/filters/filters.txt",
            tier = FilterPackTier.CORE,
            defaultEnabled = true,
            isTrackerList = false,
        ),
        FilterListDef(
            id = "ubo_unbreak",
            name = "Anti-Breakage & Site Compatibility",
            description = "Prevents broken layouts, login flows & media players across sites",
            url = "https://ublockorigin.github.io/uAssets/filters/unbreak.txt",
            tier = FilterPackTier.CORE,
            defaultEnabled = true,
            isTrackerList = false,
        ),
        FilterListDef(
            id = "adguard_mobile",
            name = "Mobile Banners & Sticky Ads",
            description = "Targets mobile web banners, sticky footers & interstitial ads",
            url = "https://filters.adtidy.org/extension/ublock/filters/11.txt",
            tier = FilterPackTier.CORE,
            defaultEnabled = true,
            isTrackerList = false,
        ),
        FilterListDef(
            id = "easyprivacy",
            name = "Cross-Site Analytics & Beacons",
            description = "Blocks analytics scripts, tracking pixels & cross-site beacons",
            url = "https://easylist.to/easylist/easyprivacy.txt",
            tier = FilterPackTier.CORE,
            defaultEnabled = true,
            isTrackerList = true,
        ),
        FilterListDef(
            id = "ubo_privacy",
            name = "Telemetry & Fingerprinting Scripts",
            description = "Advanced tracker, session-replay & fingerprinting script filters",
            url = "https://ublockorigin.github.io/uAssets/filters/privacy.txt",
            tier = FilterPackTier.CORE,
            defaultEnabled = true,
            isTrackerList = true,
        ),
        FilterListDef(
            id = "peter_lowe",
            name = "Curated Ad & Tracking Hosts",
            description = "Lightweight curated domain blocklist for ad & tracking servers",
            url = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext",
            tier = FilterPackTier.CORE,
            defaultEnabled = true,
            isTrackerList = true,
        ),
        FilterListDef(
            id = "hagezi_pro",
            name = "Multi-Layer CNAME & Telemetry Shield",
            description = "Broad defense against ads, telemetry & CNAME-cloaked trackers",
            url = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/pro.txt",
            tier = FilterPackTier.CORE,
            defaultEnabled = true,
            isTrackerList = true,
        ),
        FilterListDef(
            id = "ubo_badware",
            name = "Fast Site Fixes & Ad-Wall Patches",
            description = "Rapid fixes for ad-walls, redirect loops & broken web elements",
            url = "https://ublockorigin.github.io/uAssets/filters/quick-fixes.txt",
            tier = FilterPackTier.CORE,
            defaultEnabled = true,
            isTrackerList = false,
        ),
        FilterListDef(
            id = "ubo_badware_risks",
            name = "Badware & Malvertising Risks",
            description = "Blocks malvertising networks, fake download traps & scam redirects",
            url = "https://ublockorigin.github.io/uAssets/filters/badware.txt",
            tier = FilterPackTier.CORE,
            defaultEnabled = true,
            isTrackerList = false,
        ),
        FilterListDef(
            id = "urlhaus_malware",
            name = "Live Malware & Phishing Guard",
            description = "Real-time malicious payload, exploit & phishing domain defense",
            url = "https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-online.txt",
            tier = FilterPackTier.CORE,
            defaultEnabled = true,
            isTrackerList = true,
        ),
        FilterListDef(
            id = "fanboy_cookies",
            name = "Cookie Consent & Notice Removal",
            description = "Hides intrusive cookie banners and consent walls automatically",
            url = "https://secure.fanboy.co.nz/fanboy-cookiemonster.txt",
            tier = FilterPackTier.CORE,
            defaultEnabled = true,
            isTrackerList = false,
        ),
        FilterListDef(
            id = "ubo_annoyances",
            name = "App Nags, Modals & Overlays",
            description = "Strips app-install banners, newsletter modals & floating chat popups",
            url = "https://ublockorigin.github.io/uAssets/filters/annoyances-others.txt",
            tier = FilterPackTier.CORE,
            defaultEnabled = true,
            isTrackerList = false,
        ),
        FilterListDef(
            id = "indian_list",
            name = "South Asian & Streaming Popunder Filters",
            description = "Blocks regional streaming popunders, betting overlays & mobile ads",
            url = "https://easylist-downloads.adblockplus.org/indianlist.txt",
            tier = FilterPackTier.REGIONAL,
            defaultEnabled = true,
            isTrackerList = false,
        ),

        // === Optional Extra & High-Intensity Customization Packs ===
        FilterListDef(
            id = "hagezi_ultimate",
            name = "Maximum-Strength Threat Blocklist",
            description = "Maximum-intensity blocking for aggressive trackers & ad networks",
            url = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/ultimate.txt",
            tier = FilterPackTier.NUCLEAR,
            defaultEnabled = false,
            isTrackerList = true,
        ),
        FilterListDef(
            id = "oisd_big",
            name = "Extended Domain Blocklist",
            description = "Large-scale curated domain list targeting ads, phishing & telemetry",
            url = "https://big.oisd.nl/",
            tier = FilterPackTier.NUCLEAR,
            defaultEnabled = false,
            isTrackerList = true,
        ),
        FilterListDef(
            id = "fanboy_social",
            name = "Social Media Widgets & Trackers",
            description = "Blocks embedded share buttons, like widgets & social tracking scripts",
            url = "https://easylist.to/easylist/fanboy-social.txt",
            tier = FilterPackTier.SOCIAL,
            defaultEnabled = false,
            isTrackerList = true,
        ),
        FilterListDef(
            id = "japanese_list",
            name = "East Asian Portals & Media Filters",
            description = "Ad filters for East Asian web portals, manga & media sites",
            url = "https://filters.adtidy.org/extension/ublock/filters/7.txt",
            tier = FilterPackTier.REGIONAL,
            defaultEnabled = false,
            isTrackerList = false,
        ),
    )

    /**
     * Newly promoted default-ON list IDs added in catalog v2 so upgrading installs
     * automatically receive full out-of-the-box coverage without wiping user customizations.
     */
    val CATALOG_V2_NEW_DEFAULTS: Set<String> = setOf(
        "ubo_unbreak",
        "adguard_mobile",
        "ubo_badware_risks",
        "urlhaus_malware",
        "ubo_annoyances",
        "indian_list",
    )

    val FILTER_CATEGORIES: List<FilterCategoryDef> = listOf(
        // === 4 Core Essentials (All Enabled by Default) ===
        FilterCategoryDef(
            id = "uh_core_ads",
            title = "Ad, Mobile & Banner Blocking",
            description = "Blocks web ads, mobile sticky banners, video ads & preserves layouts",
            tier = FilterCategoryTier.CORE,
            iconRes = R.drawable.uh_ic_cat_ads_24,
            listIds = listOf("easylist", "ubo_filters", "ubo_unbreak", "adguard_mobile"),
        ),
        FilterCategoryDef(
            id = "uh_core_trackers",
            title = "Tracker, CNAME & Analytics Shield",
            description = "Stops cross-site trackers, telemetry, beacons & CNAME cloaking",
            tier = FilterCategoryTier.CORE,
            iconRes = R.drawable.uh_ic_cat_trackers_24,
            listIds = listOf("easyprivacy", "ubo_privacy", "peter_lowe", "hagezi_pro"),
        ),
        FilterCategoryDef(
            id = "uh_core_malware_fixes",
            title = "Malware, Badware & Scam Guard",
            description = "Blocks live phishing, malvertising, scam redirects & fixes ad-walls",
            tier = FilterCategoryTier.CORE,
            iconRes = R.drawable.uh_ic_cat_malware_24,
            listIds = listOf("ubo_badware", "ubo_badware_risks", "urlhaus_malware"),
        ),
        FilterCategoryDef(
            id = "uh_core_cookies",
            title = "Cookie Banners, Overlays & Nags",
            description = "Hides consent popups, app-install bars, newsletters & chat bubbles",
            tier = FilterCategoryTier.CORE,
            iconRes = R.drawable.uh_ic_pg_cookie_banner_24,
            listIds = listOf("fanboy_cookies", "ubo_annoyances"),
        ),

        // === 4 Extra Protection Packs ===
        FilterCategoryDef(
            id = "uh_extra_streaming_regional",
            title = "Streaming & Regional Popunders",
            description = "Targets movie/download site popunders, betting ads & regional banners",
            tier = FilterCategoryTier.EXTRA,
            iconRes = R.drawable.uh_ic_cat_regional_24,
            listIds = listOf("indian_list"),
        ),
        FilterCategoryDef(
            id = "uh_extra_nuclear",
            title = "Maximum Threat Defense",
            description = "Heavy-duty ad networks, aggressive trackers & extended domain lists",
            tier = FilterCategoryTier.EXTRA,
            iconRes = R.drawable.uh_ic_cat_nuclear_24,
            listIds = listOf("hagezi_ultimate", "oisd_big"),
        ),
        FilterCategoryDef(
            id = "uh_extra_overlays_social",
            title = "Social Widgets & Share Trackers",
            description = "Blocks embedded share buttons, like widgets & third-party social scripts",
            tier = FilterCategoryTier.EXTRA,
            iconRes = R.drawable.uh_ic_cat_social_24,
            listIds = listOf("fanboy_social"),
        ),
        FilterCategoryDef(
            id = "uh_extra_mobile_regional",
            title = "East Asian Regional Ads",
            description = "Extra ad and banner filters for East Asian portals & media sites",
            tier = FilterCategoryTier.EXTRA,
            iconRes = R.drawable.uh_ic_cat_phishing_24,
            listIds = listOf("japanese_list"),
        ),
    )
}
