package app.harvester.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object SearchPredictions {

    private fun endpointFor(engineId: String): String? = when (engineId) {
        SearchEnginePrefs.ENGINE_BRAVE -> "https://search.brave.com/api/suggest?q="
        SearchEnginePrefs.ENGINE_GOOGLE -> "https://suggestqueries.google.com/complete/search?client=firefox&q="
        SearchEnginePrefs.ENGINE_BING -> "https://api.bing.com/osjson.aspx?query="
        SearchEnginePrefs.ENGINE_DUCKDUCKGO -> "https://duckduckgo.com/ac/?type=list&q="
        SearchEnginePrefs.ENGINE_YANDEX -> "https://suggest.yandex.com/suggest-ff.cgi?part="
        else -> null
    }

    suspend fun fetch(engineId: String, query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val base = endpointFor(engineId) ?: return emptyList()
        return try {
            withContext(Dispatchers.IO) {
                val url = base + URLEncoder.encode(query, "UTF-8")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
                conn.connect()
                if (conn.responseCode !in 200..299) return@withContext emptyList<String>()
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                parse(body, query)
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun parse(body: String, original: String): List<String> {
        val out = mutableListOf<String>()
        val rx = Regex(""""([^"\\\n]{2,80})"""")
        for (m in rx.findAll(body)) {
            val s = m.groupValues[1].trim()
            if (s.isBlank()) continue
            if (s.startsWith("http")) continue
            if (s.equals(original, ignoreCase = true)) continue
            if (s.length < 2) continue
            out.add(s)
        }
        return out.distinct().take(8)
    }
                       }
