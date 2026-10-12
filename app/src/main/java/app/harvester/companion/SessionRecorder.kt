package app.harvester.companion

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

object SessionRecorder {

    private const val TAG = "session"
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    @Volatile private var active = false
    private var sessionId: String = ""
    private var startedAt: Long = 0L
    private val events = mutableListOf<JSONObject>()

    fun isActive(): Boolean = active

    fun start(): Boolean {
        synchronized(lock) {
            if (active) return false
            active = true
            sessionId = "sess_${timeFormat.format(Date())}_${Random.nextInt(100000, 999999)}"
            startedAt = System.currentTimeMillis()
            events.clear()
        }
        UHLog.d(TAG, "started $sessionId")
        return true
    }

    fun stop(ctx: Context): String? {
        val idSnapshot: String
        val startSnapshot: Long
        synchronized(lock) {
            if (!active) return null
            active = false
            idSnapshot = sessionId
            startSnapshot = startedAt
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
            synchronized(lock) {
                for (e in events) arr.put(e)
            }
            root.put("eventCount", arr.length())
            root.put("events", arr)
        } catch (_: Exception) {}
        val filename = "$idSnapshot.json"
        val ok = DownloadHelper.saveTextToDownloads(
            ctx, filename, root.toString(2), DownloadHelper.HARVESTER_DIR
        )
        UHLog.d(TAG, "stopped $idSnapshot, ${events.size} events, saved=$ok")
        return if (ok) filename else null
    }

    fun record(type: String, payload: JSONObject) {
        if (!active) return
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
        val o = JSONObject()
        try { o.block() } catch (_: Exception) {}
        record(type, o)
    }

    fun count(): Int = synchronized(lock) { events.size }

    fun currentId(): String = sessionId
}
