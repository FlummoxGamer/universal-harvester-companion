package app.harvester.companion.privacy

import app.harvester.companion.UHLog
import app.harvester.companion.core.network.UhHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Non-blocking cached DoH CNAME cloaking resolver.
 * Resolves first-party subdomains asynchronously against DNS-over-HTTPS (JSON API)
 * and caches canonical CNAME targets for 12 hours so zero latency is ever added to page loads.
 */
object UhCnameUnmasker {

    private const val CACHE_TTL_MS = 12L * 60L * 60L * 1000L
    private const val MAX_CACHE_ENTRIES = 1200

    data class CnameCacheEntry(
        val canonicalTarget: String, // empty string means "no CNAME / clean"
        val resolvedAtMs: Long,
    )

    private val cache = ConcurrentHashMap<String, CnameCacheEntry>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun getCachedTarget(host: String): String? {
        val clean = host.trim().lowercase(Locale.ROOT)
        val entry = cache[clean] ?: return null
        if (System.currentTimeMillis() - entry.resolvedAtMs > CACHE_TTL_MS) {
            cache.remove(clean)
            return null
        }
        return entry.canonicalTarget
    }

    /**
     * Resolves a subdomain's canonical CNAME target via DoH JSON API (Cloudflare 1.1.1.1).
     * Returns the canonical target hostname (lowercase, no trailing dot), or "" if none/failed.
     */
    fun resolveCnameBlocking(host: String): String {
        val clean = host.trim().lowercase(Locale.ROOT).removeSuffix(".")
        if (!isEligibleSubdomain(clean)) return ""

        getCachedTarget(clean)?.let { return it }
        if (!inFlight.add(clean)) return ""

        return try {
            val encoded = URLEncoder.encode(clean, "UTF-8")
            val url = "https://cloudflare-dns.com/dns-query?name=$encoded&type=CNAME"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/dns-json")
                .header("User-Agent", UhHttpClient.DEFAULT_USER_AGENT)
                .build()

            var canonical = ""
            UhHttpClient.client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    if (body.isNotBlank()) {
                        val json = JSONObject(body)
                        val answers = json.optJSONArray("Answer")
                        if (answers != null) {
                            // Follow chain to the final CNAME record (type == 5)
                            for (i in 0 until answers.length()) {
                                val rec = answers.optJSONObject(i) ?: continue
                                if (rec.optInt("type", 0) == 5) {
                                    val data = rec.optString("data", "")
                                        .trim()
                                        .lowercase(Locale.ROOT)
                                        .removeSuffix(".")
                                    if (data.contains('.') && data != clean) {
                                        canonical = data
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (cache.size > MAX_CACHE_ENTRIES) {
                val now = System.currentTimeMillis()
                cache.entries.removeIf { now - it.value.resolvedAtMs > CACHE_TTL_MS }
                if (cache.size > MAX_CACHE_ENTRIES) cache.clear()
            }
            cache[clean] = CnameCacheEntry(canonical, System.currentTimeMillis())
            canonical
        } catch (e: Exception) {
            UHLog.w("cname", "DoH CNAME lookup skipped for $clean: ${e.message}")
            ""
        } finally {
            inFlight.remove(clean)
        }
    }

    private fun isEligibleSubdomain(host: String): Boolean {
        if (host.length < 6 || host.length > 220) return false
        val parts = host.split('.')
        // Only subdomains (3+ labels) can be CNAME-cloaked per RFC 1034
        if (parts.size < 3) return false
        if (parts[0] == "www" && parts.size == 3) return false
        // Skip IP literals
        if (host.all { it in '0'..'9' || it == '.' || it == ':' }) return false
        return true
    }
}
