package app.harvester.companion

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object SessionRecorder {

    private const val TAG = "session"

    val ALL_CATEGORIES = listOf(
        "header", "wasm_blob", "wasm_memory", "crypto",
        "atob", "textdecoder", "worker_fetch", "sw_fetch", "wasm_call"
    )

    private val lock = Any()
    private val categories = mutableSetOf<String>().also { it.addAll(ALL_CATEGORIES) }
    private val events = mutableListOf<JSONObject>()
    private val seenHashes = mutableMapOf<String, MutableSet<String>>()
    private var appContext: Context? = null

    @Volatile private var active = false
    @Volatile private var rawMode = false
    private var startedAt: Long = 0L
    private var filteredCount = 0
    private var duplicateCount = 0

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    fun isActive(): Boolean = active
    fun isRawMode(): Boolean = rawMode
    fun setRawMode(on: Boolean) {
        rawMode = on
        UHLog.d(TAG, "raw mode = $on")
    }

    fun isEnabled(category: String): Boolean = synchronized(lock) { category in categories }

    fun setEnabled(category: String, on: Boolean) {
        synchronized(lock) {
            if (on) categories.add(category) else categories.remove(category)
        }
    }

    fun start() {
        synchronized(lock) {
            if (active) return
            active = true
            startedAt = System.currentTimeMillis()
        }
        UHLog.d(TAG, "started")
    }

    fun stop() {
        synchronized(lock) { active = false }
        UHLog.d(TAG, "stopped events=${events.size}")
    }

    fun reset() {
        synchronized(lock) {
            events.clear()
            seenHashes.clear()
            filteredCount = 0
            duplicateCount = 0
        }
        UHLog.d(TAG, "reset")
    }

    fun save(ctx: Context): String? {
        val idSnapshot = UHLog.sessionId
        val startSnapshot: Long
        val evs: List<JSONObject>
        val filtered: Int
        val duped: Int
        synchronized(lock) {
            if (events.isEmpty()) return null
            startSnapshot = if (startedAt > 0) startedAt else System.currentTimeMillis()
            evs = events.toList()
            filtered = filteredCount
            duped = duplicateCount
        }
        val root = JSONObject()
        try {
            root.put("app", "Harvester")
            root.put("version", BuildConfig.VERSION_NAME)
            root.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            root.put("android", Build.VERSION.RELEASE)
            root.put("sdk", Build.VERSION.SDK_INT)
            root.put("sessionId", idSnapshot)
            root.put("startedAt", startSnapshot)
            root.put("endedAt", System.currentTimeMillis())
            root.put("rawMode", rawMode)
            root.put("eventCount", evs.size)
            root.put("filteredCount", filtered)
            root.put("duplicateCount", duped)
            val arr = JSONArray()
            for (e in evs) arr.put(e)
            root.put("events", arr)
        } catch (_: Exception) {}

        val subfolder = "${DownloadHelper.HARVESTER_DIR}/sess_$idSnapshot"
        val ok = DownloadHelper.saveTextToDownloads(ctx, "session.json", root.toString(2), subfolder)
        UHLog.d(TAG, "saved events=${evs.size} filtered=$filtered duped=$duped ok=$ok")
        return if (ok) "session.json" else null
    }

    fun record(type: String, payload: JSONObject) {
        if (!active) return
        if (type !in categories) return
        val bodyKey = payload.optString("data", payload.toString())
        val sha = sha256(bodyKey)
        val size = bodyKey.length

        synchronized(lock) {
            val seen = seenHashes.getOrPut(type) { mutableSetOf() }
            if (sha in seen) {
                duplicateCount++
                events.add(stubEvent(type, sha, size, "duplicate"))
                return
            }
            seen.add(sha)

            val reason = if (rawMode) null else filterReason(type, payload, bodyKey)
            if (reason != null) {
                filteredCount++
                events.add(stubEvent(type, sha, size, reason))
                return
            }

            val e = JSONObject()
            try {
                e.put("t", System.currentTimeMillis() - startedAt)
                e.put("type", type)
                e.put("data", payload)
            } catch (_: Exception) {}
            events.add(e)
        }
    }

    fun record(type: String, block: JSONObject.() -> Unit) {
        if (!active) return
        if (type !in categories) return
        val o = JSONObject()
        try { o.block() } catch (_: Exception) {}
        record(type, o)
    }

    fun count(): Int = synchronized(lock) { events.size }
    fun filteredCount(): Int = synchronized(lock) { filteredCount }
    fun duplicateCount(): Int = synchronized(lock) { duplicateCount }
    fun currentId(): String = UHLog.sessionId

    private fun stubEvent(type: String, sha: String, size: Int, reason: String): JSONObject {
        val e = JSONObject()
        try {
            e.put("t", System.currentTimeMillis() - startedAt)
            e.put("type", type)
            e.put("sha", sha)
            e.put("size", size)
            e.put(reason, true)
            if (reason != "duplicate") e.put("filtered", reason)
            else e.put("duplicate", true)
        } catch (_: Exception) {}
        return e
    }

    private fun filterReason(type: String, payload: JSONObject, body: String): String? {
        when (type) {
            "textdecoder" -> {
                if (body.isEmpty()) return "empty"
                if (body.length < 32 && body.all { it.isWhitespace() || it in ".,;:!?-_" }) return "whitespace"
                if (isHlsThumbnailMap(body)) return "hls-thumbnail-map"
            }
            "atob" -> {
                if (body.isEmpty()) return "empty"
                if (body.length < 16 && body.all { it.isLetterOrDigit() || it in "+/=" }) return "base64-noise"
            }
            "wasm_blob" -> {
                if (body.isEmpty()) return "empty"
            }
            "crypto" -> {
                if (payload.optString("algo").isEmpty()) return "empty"
            }
            "header" -> {
                // dedup only
            }
        }
        return null
    }

    private fun isHlsThumbnailMap(body: String): Boolean {
        if (!body.startsWith("WEBVTT")) return false
        var tsCount = 0
        for (line in body.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t == "WEBVTT") continue
            if (t.contains("-->")) { tsCount++; continue }
            if (!t.startsWith("storyboard.webp#xywh=")) return false
        }
        return tsCount > 0
    }

    private fun sha256(s: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val b = md.digest(s.toByteArray(Charsets.UTF_8))
            b.take(8).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { s.hashCode().toString(16) }
    }
}
