package app.harvester.companion

import android.content.Context
import java.io.File

object ResourceStore {

    private fun cacheDir(ctx: Context, script: String, name: String): File {
        val safe = script.replace(Regex("""[^a-zA-Z0-9_\-]"""), "_")
        val safeName = name.replace(Regex("""[^a-zA-Z0-9_\-.]"""), "_")
        val dir = File(ctx.filesDir, "res/$safe").apply { mkdirs() }
        return File(dir, safeName)
    }

    fun ensureFetched(ctx: Context, script: String, name: String, url: String): File? {
        val f = cacheDir(ctx, script, name)
        if (f.isFile && f.length() > 0L) return f
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.connect()
            if (conn.responseCode in 200..299) {
                conn.inputStream.use { input -> f.outputStream().use { input.copyTo(it) } }
                f
            } else null
        } catch (_: Exception) { null }
    }

    fun getText(ctx: Context, script: String, name: String): String? {
        val f = cacheDir(ctx, script, name)
        return if (f.isFile) try { f.readText() } catch (_: Exception) { null } else null
    }

    fun getUrl(ctx: Context, script: String, name: String): String? {
        val f = cacheDir(ctx, script, name)
        return if (f.isFile) "file://${f.absolutePath}" else null
    }
}
