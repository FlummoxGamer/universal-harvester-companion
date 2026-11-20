package app.harvester.companion.browser.search

import app.harvester.companion.core.network.UhHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class SearchSuggestionClient {

    class FetchException : Exception("There was a problem fetching suggestions")
    class ResponseParserException : Exception("There was a problem parsing the suggestion response")

    private val suggestHttpClient by lazy {
        UhHttpClient.client.newBuilder()
            .connectTimeout(1800, TimeUnit.MILLISECONDS)
            .readTimeout(2000, TimeUnit.MILLISECONDS)
            .callTimeout(2500, TimeUnit.MILLISECONDS)
            .build()
    }

    suspend fun getSuggestions(engineId: String, suggestionsUrl: String?, query: String): List<String>? {
        if (suggestionsUrl.isNullOrBlank()) return emptyList()

        val url = suggestionsUrl.replace("%s", URLEncoder.encode(query, "UTF-8"))

        val suggestionResults = try {
            fetch(url)
        } catch (_: IOException) {
            throw FetchException()
        }

        return try {
            parse(suggestionResults)
        } catch (_: JSONException) {
            throw ResponseParserException()
        }
    }

    private suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UhHttpClient.DEFAULT_USER_AGENT)
            .build()
        suggestHttpClient.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    }

    private fun parse(response: String?): List<String>? {
        if (response.isNullOrBlank()) return emptyList()
        val results = mutableListOf<String>()
        try {
            val jsonArray = JSONArray(response)
            if (jsonArray.length() > 1) {
                val suggestions = jsonArray.getJSONArray(1)
                for (i in 0 until suggestions.length()) {
                    val v = suggestions.optString(i).trim()
                    if (v.isNotBlank()) results.add(v)
                }
            }
        } catch (_: Exception) {
            val rx = Regex(""""([^"\\\n]{2,80})"""")
            for (m in rx.findAll(response)) {
                val s = m.groupValues[1].trim()
                if (s.isNotBlank() && !s.startsWith("http")) {
                    results.add(s)
                }
            }
        }
        return results.distinct().take(5)
    }
}
