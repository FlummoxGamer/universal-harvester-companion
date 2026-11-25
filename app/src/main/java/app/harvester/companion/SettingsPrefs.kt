package app.harvester.companion

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SettingsPrefs {
    private const val NAME = "uh_settings"
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private val _addressBarBottomFlow = MutableStateFlow<Boolean?>(null)
    private val _customDownloadUriFlow = MutableStateFlow<String?>(null)
    private val _homeStatsEnabledFlow = MutableStateFlow<Boolean?>(null)
    private val _forceDarkWebFlow = MutableStateFlow<Boolean?>(null)
    private val _enhanceDarkFlow = MutableStateFlow<Boolean?>(null)

    fun addressBarBottomFlow(ctx: Context): StateFlow<Boolean> {
        if (_addressBarBottomFlow.value == null) {
            _addressBarBottomFlow.value = getInt(ctx, K_ADDRESS_BAR_POSITION, 1) == 1
        }
        @Suppress("UNCHECKED_CAST")
        return _addressBarBottomFlow.asStateFlow() as StateFlow<Boolean>
    }

    fun customDownloadUriFlow(ctx: Context): StateFlow<String> {
        if (_customDownloadUriFlow.value == null) {
            _customDownloadUriFlow.value = getString(ctx, "custom_download_uri", "")
        }
        @Suppress("UNCHECKED_CAST")
        return _customDownloadUriFlow.asStateFlow() as StateFlow<String>
    }

    fun homeStatsEnabledFlow(ctx: Context): StateFlow<Boolean> {
        if (_homeStatsEnabledFlow.value == null) {
            _homeStatsEnabledFlow.value = getBool(ctx, K_HOME_SHOW_STATS, true)
        }
        @Suppress("UNCHECKED_CAST")
        return _homeStatsEnabledFlow.asStateFlow() as StateFlow<Boolean>
    }

    fun forceDarkWebFlow(ctx: Context): StateFlow<Boolean> {
        if (_forceDarkWebFlow.value == null) {
            _forceDarkWebFlow.value = getBool(ctx, K_FORCE_DARK_SITES, false)
        }
        @Suppress("UNCHECKED_CAST")
        return _forceDarkWebFlow.asStateFlow() as StateFlow<Boolean>
    }

    fun enhanceDarkFlow(ctx: Context): StateFlow<Boolean> {
        if (_enhanceDarkFlow.value == null) {
            _enhanceDarkFlow.value = getBool(ctx, K_ENHANCE_DARK, false)
        }
        @Suppress("UNCHECKED_CAST")
        return _enhanceDarkFlow.asStateFlow() as StateFlow<Boolean>
    }

    fun getBool(ctx: Context, key: String, def: Boolean = false): Boolean =
        p(ctx).getBoolean(key, def)

    fun setBool(ctx: Context, key: String, v: Boolean) {
        p(ctx).edit().putBoolean(key, v).apply()
        when (key) {
            K_HOME_SHOW_STATS -> _homeStatsEnabledFlow.value = v
            K_FORCE_DARK_SITES -> _forceDarkWebFlow.value = v
            K_ENHANCE_DARK -> _enhanceDarkFlow.value = v
        }
    }

    fun getInt(ctx: Context, key: String, def: Int = 0): Int = p(ctx).getInt(key, def)

    fun setInt(ctx: Context, key: String, v: Int) {
        p(ctx).edit().putInt(key, v).apply()
        if (key == K_ADDRESS_BAR_POSITION) {
            _addressBarBottomFlow.value = (v == 1)
        }
    }

    fun getString(ctx: Context, key: String, def: String = ""): String =
        p(ctx).getString(key, def) ?: def

    fun setString(ctx: Context, key: String, v: String) {
        p(ctx).edit().putString(key, v).apply()
        if (key == "custom_download_uri") {
            _customDownloadUriFlow.value = v
        }
    }

    fun getStringSet(ctx: Context, key: String): Set<String> =
        p(ctx).getStringSet(key, emptySet())?.toSet() ?: emptySet()

    fun setStringSet(ctx: Context, key: String, values: Set<String>) {
        p(ctx).edit().putStringSet(key, values.toSet()).apply()
    }

    const val K_ENHANCE_DARK = "enhance_dark"
    const val K_AUTOPLAY = "autoplay_videos"
    const val K_BACKGROUND_PLAY = "media_background_play"
    const val K_PIP_ENABLED = "media_pip_enabled"
    const val K_QUALITY = "default_quality"
    const val K_TEXT_SCALE = "text_scale"
    const val K_FORCE_DARK_SITES = "force_dark_sites"
    const val K_DARK_BYPASS_HOSTS = "force_dark_bypass_hosts"
    const val K_HOME_SHOW_STATS = "home_show_protection_stats"
    const val K_ASK_DL = "ask_before_download"
    const val K_DL_LOCATION = "download_location"
    const val K_DELETE_BEHAVIOR = "delete_download_behavior"

    // Privacy Guard & UH Shield Global Keys
    const val K_PG_ADS = "pg_ads"
    const val K_PG_SHIELD_LEVEL = "pg_shield_level" // 0 = Off, 1 = Standard, 2 = Aggressive (default)
    const val K_PG_HTTPS = "pg_https"               // Boolean legacy + K_PG_HTTPS_MODE: 0=Off, 1=Private, 2=Always
    const val K_PG_HTTPS_MODE = "pg_https_mode"
    const val K_PG_FINGERPRINT = "pg_fingerprint"
    const val K_PG_SCRIPT = "pg_script"             // Global Block JavaScript
    const val K_PG_COOKIES = "pg_cookies"           // 0 = Total Cookie Protection (default), 1 = Block All 3P, 2 = Block All, 3 = Allow All
    const val K_PG_COOKIE_BANNERS = "pg_cookie_banners" // Auto-reject cookie banners
    const val K_PG_REDIRECT = "pg_redirect"         // 0 = Stop redirecting to app, 1 = Ask, 2 = Always allow
    const val K_PG_POPUP_BLOCK = "pg_popup_block"   // Block popups & ad-tab hijacks
    const val K_PG_CLEAR_URLS = "pg_clear_urls"     // Strip tracking URL parameters

    // Advanced Privacy Keys
    const val K_ADV_SAFE_BROWSING = "adv_safe_browsing"
    const val K_ADV_DNT = "adv_dnt"
    const val K_ADV_DEAMP = "adv_deamp"
    const val K_ADV_GPC = "adv_gpc"
    const val K_ADV_SECURE_DNS = "adv_secure_dns"
    const val K_ADV_DOH_PROVIDER = "adv_doh_provider" // 0=Off, 1=Cloudflare, 2=Quad9, 3=AdGuard, 4=NextDNS, 5=Custom
    const val K_ADV_DOH_CUSTOM_URL = "adv_doh_custom_url"
    const val K_ADV_WEBRTC_POLICY = "adv_webrtc_policy" // 0=Default, 1=Disable Non-Proxied UDP, 2=Relay Only, 3=Block WebRTC

    const val K_ADDRESS_BAR_POSITION = "address_bar_position"
    const val K_AUTO_HIDE_BAR = "auto_hide_bar"
    const val K_SWIPE_FULLSCREEN = "swipe_fullscreen"

    const val K_SEARCH_SUGGESTIONS = "search_suggestions"
    const val K_SEARCH_SUGGESTIONS_PRIVATE = "search_suggestions_private"
    const val K_SEARCH_HISTORY = "search_history"
    const val K_SEARCH_BOOKMARKS = "search_bookmarks"
    const val K_SEARCH_CLIPBOARD = "search_clipboard"
    const val K_SEARCH_VOICE = "search_voice"
    const val K_SEARCH_AUTOCOMPLETE = "search_autocomplete"
    const val K_SEARCH_REMOTE = "search_remote"
    const val K_SHOW_FULL_URL = "show_full_url"

    const val K_TAB_SLEEP_SECONDS = "tab_sleep_seconds"
    const val K_TAB_CONFIRM_SWIPE = "tab_confirm_swipe"
    const val K_TAB_AUTO_COLLAPSE_GROUPS = "tab_auto_collapse_groups"
}
