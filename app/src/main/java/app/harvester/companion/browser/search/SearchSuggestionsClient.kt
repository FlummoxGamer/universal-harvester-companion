package app.harvester.companion.browser.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class SearchSuggestionClient {

    class FetchException : Exception("There was a problem fetching suggestions")
    class ResponseParserException : Exception("There was a problem parsing the suggestion response")

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
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
        conn.connect()
        if (conn.responseCode in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            null
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
                    results.add(suggestions.getString(i))
                }
            }
        } catch (e: Exception) {
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
