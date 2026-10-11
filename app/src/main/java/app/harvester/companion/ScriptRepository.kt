package app.harvester.companion

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class ScriptRepository(private val ctx: Context) {

    private val dir: File get() = File(ctx.filesDir, "scripts").apply { mkdirs() }
    private val scriptFile: File get() = File(dir, "current.js")
    private val metaFile: File get() = File(dir, "meta.json")

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun load(): String? {
        val f = scriptFile
        if (!f.exists() || f.length() == 0L) return null
        return try { f.readText() } catch (_: Exception) { null }
    }

    fun version(): String? {
        if (!metaFile.exists()) return null
        return try {
            JSONObject(metaFile.readText()).optString("version").takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    suspend fun download(url: String): Result<String> = withContext(Dispatchers.IO) {
        UHLog.d("script", "download ${url.take(120)}")
        try {
             val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("HTTP ${resp.code}"))
                val body = resp.body?.string() ?: return@withContext Result.failure(Exception("empty"))
                if (body.length < 100) return@withContext Result.failure(Exception("too small"))
                scriptFile.writeText(body)
                val v = extractVersion(body) ?: System.currentTimeMillis().toString()
                UHLog.d("script", "downloaded v$v (${body.length}b)")
                JSONObject().apply {
                    put("version", v)
                    put("source", url)
                    put("lastChecked", System.currentTimeMillis())
                    put("size", body.length)
                }.also { metaFile.writeText(it.toString()) }
                Result.success(v)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun maybeAutoUpdate() {
        if (!AppPrefs.autoUpdate(ctx)) return
        val url = AppPrefs.scriptUrl(ctx)
        if (url.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - AppPrefs.lastCheck(ctx) < 6L * 60 * 60 * 1000) return
        val result = download(url)
        AppPrefs.setLastCheck(ctx, now)
        result.getOrNull()?.let { AppPrefs.setLastVersion(ctx, it) }
    }

    private fun extractVersion(script: String): String? =
        Regex("""@version\s+([0-9][^\s]*)""").find(script)?.groupValues?.get(1)
}
