package app.harvester.companion

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

object SessionRecorder {

    private const val TAG = "session"

    val ALL_CATEGORIES = listOf(
        "header",
        "wasm_blob",
        "wasm_memory",
        "crypto",
        "atob",
        "textdecoder",
        "worker_fetch",
        "sw_fetch",
        "wasm_call"
    )

    private val lock = Any()
    private val categories = mutableSetOf<String>().also { it.addAll(ALL_CATEGORIES) }
    private val events = mutableListOf<JSONObject>()
    private var appContext: Context? = null

    @Volatile private var active = false
    private var startedAt: Long = 0L

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    fun isActive(): Boolean = active

    fun isEnabled(category: String): Boolean = synchronized(lock) { category in categories }

    fun setEnabled(category: String, on: Boolean) {
        synchronized(lock) {
            if (on) categories.add(category) else categories.remove(category)
        }
    }

    fun enableAll() { synchronized(lock) { categories.addAll(ALL_CATEGORIES) } }
    fun disableAll() { synchronized(lock) { categories.clear() } }

    fun start(ctx: Context) {
        init(ctx)
        synchronized(lock) {
            if (active) return
            active = true
            startedAt = System.currentTimeMillis()
            events.clear()
            if (categories.isEmpty()) categories.addAll(ALL_CATEGORIES)
        }
        UHLog.d(TAG, "started")
    }

    fun pause() {
        synchronized(lock) { active = false }
        UHLog.d(TAG, "paused, events=${events.size}")
    }

    fun end(ctx: Context) {
        val saved = save(ctx)
        synchronized(lock) {
            active = false
            events.clear()
        }
        UHLog.d(TAG, "ended, saved=$saved")
    }

    fun save(ctx: Context): String? {
        val idSnapshot: String
        val startSnapshot: Long
        val evs: List<JSONObject>
        synchronized(lock) {
            if (events.isEmpty()) return null
            idSnapshot = UHLog.sessionId
            startSnapshot = if (startedAt > 0) startedAt else System.currentTimeMillis()
            evs = events.toList()
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
            val arr = JSONArray()
            for (e in evs) arr.put(e)
            root.put("eventCount", arr.length())
            root.put("events", arr)
        } catch (_: Exception) {}

        val subfolder = "${DownloadHelper.HARVESTER_DIR}/sess_$idSnapshot"
        val ok = DownloadHelper.saveTextToDownloads(ctx, "session.json", root.toString(2), subfolder)
        UHLog.d(TAG, "saved events=${evs.size} ok=$ok")
        return if (ok) "session.json" else null
    }

    fun record(type: String, payload: JSONObject) {
        if (!active) return
        if (type !in categories) return
        synchronized(lock) {
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

    fun currentId(): String = UHLog.sessionId
}
