package app.harvester.companion

import android.content.Context

object SettingsPrefs {
    private const val NAME = "uh_settings"
    private fun p(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getBool(ctx: Context, key: String, def: Boolean = false): Boolean =
        p(ctx).getBoolean(key, def)
    fun setBool(ctx: Context, key: String, v: Boolean) =
        p(ctx).edit().putBoolean(key, v).apply()

    fun getInt(ctx: Context, key: String, def: Int = 0): Int = p(ctx).getInt(key, def)
    fun setInt(ctx: Context, key: String, v: Int) = p(ctx).edit().putInt(key, v).apply()

    fun getString(ctx: Context, key: String, def: String = ""): String =
        p(ctx).getString(key, def) ?: def
    fun setString(ctx: Context, key: String, v: String) =
        p(ctx).edit().putString(key, v).apply()

 
    const val K_ENHANCE_DARK = "enhance_dark"
    const val K_AUTOPLAY = "autoplay_videos"
    const val K_QUALITY = "default_quality"
    const val K_TEXT_SCALE = "text_scale"
    const val K_FORCE_DARK_SITES = "force_dark_sites"
    const val K_ASK_DL = "ask_before_download"
    const val K_DL_LOCATION = "download_location"    

    const val K_PG_ADS = "pg_ads"
    const val K_PG_HTTPS = "pg_https"
    const val K_PG_FINGERPRINT = "pg_fingerprint"
    const val K_PG_SCRIPT = "pg_script"
    const val K_PG_COOKIES = "pg_cookies"            
    const val K_PG_REDIRECT = "pg_redirect"          
    const val K_ADV_SAFE_BROWSING = "adv_safe_browsing"
    const val K_ADV_DNT = "adv_dnt"
    const val K_ADV_DEAMP = "adv_deamp"
    const val K_ADV_GPC = "adv_gpc"
    const val K_ADV_SECURE_DNS = "adv_secure_dns"
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
}
