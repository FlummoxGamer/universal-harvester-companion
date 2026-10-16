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

    // known keys
    const val K_ENHANCE_DARK = "enhance_dark"
    const val K_AUTOPLAY = "autoplay_videos"
    const val K_QUALITY = "default_quality"
    const val K_TEXT_SCALE = "text_scale"
    const val K_FORCE_DARK_SITES = "force_dark_sites"
    const val K_ASK_DL = "ask_before_download"
    const val K_DL_LOCATION = "download_location"    // 0 vault, 1 public, 2 external

    const val K_PG_ADS = "pg_ads"
    const val K_PG_HTTPS = "pg_https"
    const val K_PG_FINGERPRINT = "pg_fingerprint"
    const val K_PG_SCRIPT = "pg_script"
    const val K_PG_COOKIES = "pg_cookies"            // 0 none, 1 third party
    const val K_PG_REDIRECT = "pg_redirect"          // 0 stop, 1 ask, 2 allow
    const val K_ADV_SAFE_BROWSING = "adv_safe_browsing"
    const val K_ADV_DNT = "adv_dnt"
    const val K_ADV_DEAMP = "adv_deamp"
    const val K_ADV_GPC = "adv_gpc"
    const val K_ADV_SECURE_DNS = "adv_secure_dns"
    const val K_ADDRESS_BAR_POSITION = "address_bar_position"
    const val K_AUTO_HIDE_BAR = "auto_hide_bar"
    const val K_SWIPE_FULLSCREEN = "swipe_fullscreen"
}
