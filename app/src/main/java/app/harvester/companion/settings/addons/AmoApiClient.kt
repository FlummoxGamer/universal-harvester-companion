package app.harvester.companion.settings.addons

import app.harvester.companion.BuildConfig
import app.harvester.companion.UHLog
import app.harvester.companion.core.network.UhHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class AmoAddon(
    val guid: String,
    val slug: String,
    val name: String,
    val summary: String,
    val description: String,
    val version: String,
    val averageRating: Double,
    val reviewCount: Int,
    val weeklyUsers: Int,
    val iconUrl: String,
    val xpiUrl: String,
    val homepageUrl: String,
    val author: String,
    val permissions: List<String>,
    val isRecommended: Boolean = false
)

object AmoApiClient {

    private const val API_V5 = "https://addons.mozilla.org/api/v5/addons"

    fun fetchAddon(slugOrGuid: String): AmoAddon? {
        val encoded = URLEncoder.encode(slugOrGuid, "UTF-8")
        val url = "$API_V5/addon/$encoded/?app=android&lang=en-US"
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", UhHttpClient.DEFAULT_USER_AGENT)
                .build()
            UhHttpClient.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                parseAddon(JSONObject(body))
            }
        } catch (e: Exception) {
            UHLog.w("addons_warn", "AMO fetchAddon($slugOrGuid) failed: ${e.message}")
            null
        }
    }

    fun fetchFeatured(pageSize: Int = 20): List<AmoAddon> {
        val collectionUrl =
            "$API_V5/../accounts/account/${BuildConfig.AMO_COLLECTION_USER}/collections/${BuildConfig.AMO_COLLECTION_NAME}/addons/?page_size=$pageSize&app=android"
        val collectionResults = fetchListFromEndpoint(collectionUrl)
        if (collectionResults.isNotEmpty()) return collectionResults

        val fallbackUrl = "$API_V5/search/?app=android&sort=users&page_size=$pageSize&lang=en-US"
        return fetchListFromEndpoint(fallbackUrl)
    }

    fun searchAddons(query: String, pageSize: Int = 24): List<AmoAddon> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$API_V5/search/?app=android&q=$q&sort=relevance&page_size=$pageSize&lang=en-US"
        return fetchListFromEndpoint(url)
    }

    private fun fetchListFromEndpoint(url: String): List<AmoAddon> {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", UhHttpClient.DEFAULT_USER_AGENT)
                .build()
            UhHttpClient.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val root = JSONObject(body)
                val results = root.optJSONArray("results") ?: return emptyList()
                val out = mutableListOf<AmoAddon>()
                for (i in 0 until results.length()) {
                    val raw = results.optJSONObject(i) ?: continue
                    val addonJson = raw.optJSONObject("addon") ?: raw
                    parseAddon(addonJson)?.let { out.add(it) }
                }
                out
            }
        } catch (e: Exception) {
            UHLog.w("addons_warn", "AMO request failed ($url): ${e.message}")
            emptyList()
        }
    }

    private fun parseAddon(obj: JSONObject): AmoAddon? {
        val guid = obj.optString("guid").trim()
        val slug = obj.optString("slug").trim()
        val name = extractLocalizedString(obj.opt("name"))
        if (guid.isBlank() || name.isBlank()) return null

        val currentVersion = obj.optJSONObject("current_version")
        val version = currentVersion?.optString("version")?.ifBlank { "1.0" } ?: "1.0"
        val fileObj = currentVersion?.optJSONObject("file")
        val xpiUrl = fileObj?.optString("url").orEmpty()
        if (xpiUrl.isBlank()) return null

        val permsList = mutableListOf<String>()
        val permsArr: JSONArray? = fileObj?.optJSONArray("permissions")
        if (permsArr != null) {
            for (i in 0 until permsArr.length()) {
                permsArr.optString(i).takeIf { it.isNotBlank() }?.let { permsList.add(it) }
            }
        }

        val authors = obj.optJSONArray("authors")
        val author = authors?.optJSONObject(0)?.optString("name").orEmpty().ifBlank { "Mozilla Add-ons" }
        val ratings = obj.optJSONObject("ratings")
        val avgRating = ratings?.optDouble("average", 0.0) ?: 0.0
        val countRating = ratings?.optInt("count", 0) ?: 0
        val icons = obj.optJSONObject("icons")
        val iconUrl = icons?.optString("128").orEmpty()
            .ifBlank { icons?.optString("64").orEmpty() }
            .ifBlank { obj.optString("icon_url").orEmpty() }

        val summary = extractLocalizedString(obj.opt("summary"))
        val description = stripHtml(extractLocalizedString(obj.opt("description"))).ifBlank { summary }
        val homepage = extractLocalizedString(obj.opt("homepage"))
            .ifBlank { obj.optString("url").orEmpty() }
        val isRecommended = currentVersion?.optBoolean("is_recommended", false) == true ||
            (obj.optJSONObject("promoted")?.optString("category") == "recommended")

        return AmoAddon(
            guid = guid,
            slug = slug,
            name = name,
            summary = summary,
            description = description,
            version = version,
            averageRating = avgRating,
            reviewCount = countRating,
            weeklyUsers = obj.optInt("average_daily_users", 0),
            iconUrl = iconUrl,
            xpiUrl = xpiUrl,
            homepageUrl = homepage,
            author = author,
            permissions = permsList,
            isRecommended = isRecommended
        )
    }

    private fun extractLocalizedString(raw: Any?): String {
        return when (raw) {
            is String -> raw.trim()
            is JSONObject -> raw.optString("en-US")
                .ifBlank {
                    val keys = raw.keys()
                    if (keys.hasNext()) raw.optString(keys.next()) else ""
                }.trim()
            else -> ""
        }
    }

    private fun stripHtml(input: String): String {
        if (input.isBlank()) return ""
        return input.replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
