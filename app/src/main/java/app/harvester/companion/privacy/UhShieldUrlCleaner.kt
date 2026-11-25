package app.harvester.companion.privacy

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/**
 * Cloudflare-safe ClearURLs parameter stripper, redirect unwrapper,
 * Auto De-AMP resolver, and domain/host matching utilities for UH Shield.
 */
internal object UhShieldUrlCleaner {

    private val TRACKING_PARAMS: Set<String> = setOf(
        "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "twclid",
        "yclid", "ymclid", "igshid", "igsh", "si", "ref_src", "ref_url",
        "oly_anon_id", "oly_enc_id", "vero_id", "wickedid",
        "_hsenc", "_hsmi", "hsctatracking", "mkt_tok", "trk", "trkid",
        "spm", "scm", "aff_id", "aff_sub", "click_id", "clickid", "irclickid",
        "s_cid", "s_kwcid", "ef_id", "icid", "ncid", "sr_share", "share_id",
        "guccounter", "guce_referrer", "guce_referrer_sig",
        "nb", "ei", "ved", "uact", "oq", "gs_lcp", "sclient",
        "is_from_webapp", "sender_device", "sender_web_id",
        "rb_clickid", "scid", "sscid", "ttclid", "li_fat_id",
        "epik", "ndclid", "oborigurl", "dicbo", "ad_id", "adset_id", "campaign_id",
        "ref_", "qid", "sr", "dib", "dib_tag", "ascsubtag", "linkcode", "linkid",
        "cxt", "cn", "ref_campaign", "ref_source", "share_source",
    )

    fun cleanTrackingUrl(rawUrl: String, stripTrackers: Boolean = true, deAmp: Boolean = true): String {
        if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) return rawUrl
        var current = rawUrl.trim()
        // Never mutate Cloudflare / WAF challenge URLs (preserves Turnstile & __cf_chl_* redirects)
        if (current.contains("/cdn-cgi/") || current.contains("__cf_chl")) return current

        if (stripTrackers) {
            current = unwrapRedirectUrl(current)
        }
        if (deAmp) {
            current = unwrapAmpUrl(current)
        }
        if (!stripTrackers) return current

        return try {
            val qIdx = current.indexOf('?')
            if (qIdx < 0) return current
            val hashIdx = current.indexOf('#', qIdx)
            val base = current.substring(0, qIdx)
            val query = if (hashIdx >= 0) current.substring(qIdx + 1, hashIdx) else current.substring(qIdx + 1)
            val frag = if (hashIdx >= 0) current.substring(hashIdx) else ""
            if (query.isBlank()) return current

            val kept = query.split('&').filter { pair ->
                if (pair.isBlank()) return@filter false
                val key = pair.substringBefore('=').lowercase(Locale.ROOT)
                !isTrackingQueryParam(key)
            }
            if (kept.isEmpty()) base + frag else "$base?${kept.joinToString("&")}$frag"
        } catch (_: Exception) {
            current
        }
    }

    fun normalizeHost(urlOrHost: String): String {
        val raw = urlOrHost.trim()
        if (raw.isBlank()) return ""
        return try {
            val host = if (raw.contains("://")) URI(raw).host else raw.substringBefore('/').substringBefore(':')
            host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
        } catch (_: Exception) {
            raw.lowercase(Locale.ROOT).removePrefix("www.").substringBefore('/')
        }
    }

    fun matchesDomainSet(host: String, set: Set<String>): Boolean {
        if (set.isEmpty()) return false
        var candidate = host
        while (true) {
            if (candidate in set) return true
            val dot = candidate.indexOf('.')
            if (dot < 0 || dot >= candidate.length - 1) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    private fun unwrapRedirectUrl(url: String): String {
        return try {
            val uri = URI(url)
            val host = (uri.host ?: return url).lowercase(Locale.ROOT)
            val path = (uri.path ?: "").lowercase(Locale.ROOT)
            val rawQuery = uri.rawQuery ?: return url

            val targetParam = when {
                (host.contains("google.") && path == "/url") -> extractQueryParam(rawQuery, "q")
                    ?: extractQueryParam(rawQuery, "url")
                (host == "l.facebook.com" || host == "lm.facebook.com" || host == "l.messenger.com") && path.startsWith("/l.php") ->
                    extractQueryParam(rawQuery, "u")
                host == "l.instagram.com" -> extractQueryParam(rawQuery, "u")
                host == "out.reddit.com" -> extractQueryParam(rawQuery, "url")
                host == "away.vk.com" && path.startsWith("/away.php") -> extractQueryParam(rawQuery, "to")
                host == "steamcommunity.com" && path.startsWith("/linkfilter") ->
                    extractQueryParam(rawQuery, "u") ?: extractQueryParam(rawQuery, "url")
                host == "exit.sc" -> extractQueryParam(rawQuery, "url")
                host == "href.li" && rawQuery.startsWith("http") -> rawQuery
                else -> null
            } ?: return url

            val decoded = URLDecoder.decode(targetParam, "UTF-8").trim()
            if (decoded.startsWith("https://") || decoded.startsWith("http://")) decoded else url
        } catch (_: Exception) {
            url
        }
    }

    private fun extractQueryParam(rawQuery: String, key: String): String? {
        for (part in rawQuery.split('&')) {
            val eq = part.indexOf('=')
            if (eq > 0 && part.substring(0, eq).equals(key, ignoreCase = true)) {
                return part.substring(eq + 1)
            }
        }
        return null
    }

    private fun unwrapAmpUrl(url: String): String {
        return try {
            val lower = url.lowercase(Locale.ROOT)
            val ampSIdx = lower.indexOf("/amp/s/")
            if (ampSIdx > 0 && (lower.contains(".google.") || lower.contains("ampproject.org"))) {
                val target = url.substring(ampSIdx + 7)
                if (target.contains('.')) {
                    return "https://${URLDecoder.decode(target, "UTF-8").removePrefix("https://").removePrefix("http://")}"
                }
            }
            val ampIdx = lower.indexOf("/amp/")
            if (ampIdx > 0 && lower.contains(".google.")) {
                val target = url.substring(ampIdx + 5)
                if (target.contains('.') && !target.startsWith("s/")) {
                    return "https://${URLDecoder.decode(target, "UTF-8").removePrefix("https://").removePrefix("http://")}"
                }
            }
            url
        } catch (_: Exception) {
            url
        }
    }

    private fun isTrackingQueryParam(key: String): Boolean {
        if (key.startsWith("utm_") ||
            key.startsWith("hsa_") ||
            key.startsWith("mtm_") ||
            key.startsWith("pk_") ||
            key.startsWith("pd_rd_") ||
            key.startsWith("pf_rd_") ||
            key.startsWith("ga_") ||
            key.startsWith("mc_")
        ) {
            return true
        }
        return key in TRACKING_PARAMS
    }
}
