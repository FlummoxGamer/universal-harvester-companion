package app.harvester.companion

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

enum class LogLevel(val priority: Int) {
    VERBOSE(0), DEBUG(1), INFO(2), WARN(3), ERROR(4), NONE(5)
}

object UHLog {

    private const val TAG = "UHCompanion"
    private const val MAX_LINES = 3000
    private const val FLUSH_EVERY = 20
    private const val MAX_FILE_BYTES = 2_000_000L
    private const val RETENTION_MS = 7L * 24L * 60L * 60L * 1000L

    private val buffer = ArrayDeque<String>(MAX_LINES)
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    private var writer: BufferedWriter? = null
    private var pending = 0
    private var appContext: Context? = null
    private var initialised = false
    @Volatile private var verbose = true

    @Volatile var externalPath: String = ""

    private val RX_JWT = Regex("""eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+""")
    private val RX_BEARER = Regex("""(?i)(bearer\s+)\S+""")
    private val RX_COOKIE = Regex("""(?i)(cookie["']?\s*[:=]\s*["']?)[^"\r\n]+""")
    private val RX_CF = Regex("""(?i)(cloudfront-(?:policy|signature|key-pair-id)=)[^;&"\s]+""")
    private val RX_B64_BLOB = Regex("""[A-Za-z0-9+/=]{300,}""")

    fun init(ctx: Context) {
        synchronized(lock) {
            if (initialised) return
            initialised = true
            appContext = ctx.applicationContext
            installCrashHandler()
            try { writer?.close() } catch (_: Exception) {}
            writer = null
            buffer.clear()
            try {
                val ext = ctx.getExternalFilesDir(null)
                val dir = ext ?: ctx.filesDir
                dir.mkdirs()
                val current = File(dir, "uh_log.txt")

                // Archive only by size, never on process restart. This preserves
                // crash logs across the OS restarting the app after a fatal error.
                if (current.exists() && current.length() > MAX_FILE_BYTES) {
                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    current.renameTo(File(dir, "uh_log_$ts.txt"))
                }

                // Prune archives older than retention.
                val cutoff = System.currentTimeMillis() - RETENTION_MS
                dir.listFiles { f ->
                    f.name.startsWith("uh_log_") && f.name.endsWith(".txt")
                }?.forEach { f ->
                    if (f.lastModified() < cutoff) {
                        try { f.delete() } catch (_: Exception) {}
                    }
                }

                writer = BufferedWriter(FileWriter(current, true))
                externalPath = current.absolutePath

                // Reload tail of existing file into the in-memory buffer so the
                // live view shows continuity across process restarts.
                if (current.exists() && current.length() > 0L) {
                    current.readLines().takeLast(MAX_LINES / 2).forEach { buffer.addLast(it) }
                }
            } catch (_: Exception) {}
        }
        section("BOOT")
        d("boot", "Harvester ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
        d("boot", "Android ${Build.VERSION.RELEASE} · SDK ${Build.VERSION.SDK_INT}")
        d("boot", "Device ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    fun addListener(fn: (String) -> Unit) { listeners.add(fn) }
    fun removeListener(fn: (String) -> Unit) { listeners.remove(fn) }

    fun isVerbose(): Boolean = verbose

    fun setVerbose(on: Boolean) { verbose = on }

    private fun sanitize(s: String): String {
        var out = s
        out = RX_JWT.replace(out) { "<JWT>" }
        out = RX_BEARER.replace(out) { m -> m.groupValues[1] + "<BEARER>" }
        out = RX_COOKIE.replace(out) { m -> m.groupValues[1] + "<COOKIE>" }
        out = RX_CF.replace(out) { m -> m.groupValues[1] + "<CF>" }
        out = RX_B64_BLOB.replace(out) { "<BLOB(${it.value.length}b)>" }
        return out
    }

    private fun write(tag: String, level: Char, msg: String, force: Boolean = false) {
        if (!force && !verbose && level == 'V') return
        val clean = sanitize(msg)
        val line = "${timeFormat.format(Date())} $level [$tag] $clean"
        synchronized(lock) {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
            try {
                writer?.appendLine(line)
                pending++
                if (pending >= FLUSH_EVERY) { writer?.flush(); pending = 0 }
            } catch (_: Exception) {}
        }
        for (l in listeners) try { l(line) } catch (_: Exception) {}
        try { Log.d(TAG, line) } catch (_: Exception) {}
    }

    private fun writeAlways(tag: String, level: Char, msg: String) {
        val clean = sanitize(msg)
        val line = "${timeFormat.format(Date())} $level [$tag] $clean"
        synchronized(lock) {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
            try {
                writer?.appendLine(line)
                writer?.flush()
            } catch (_: Exception) {}
        }
        try { Log.d(TAG, line) } catch (_: Exception) {}
    }

    fun d(tag: String, msg: String) = write(tag, 'D', msg)
    fun v(tag: String, msg: String) = write(tag, 'V', msg)
    fun w(tag: String, msg: String) = write(tag, 'W', msg)
    fun e(tag: String, msg: String) = write(tag, 'E', msg)
    fun section(title: String) = write("---", 'S', "───── $title ─────")

    fun throwable(tag: String, msg: String, t: Throwable) {
        write(tag, 'E', "$msg: ${t.javaClass.simpleName}: ${t.message}")
        t.stackTrace.take(25).forEach { write(tag, 'E', "  at $it") }
    }

    fun snapshot(): String = synchronized(lock) {
        if (buffer.isEmpty()) "(empty)" else buffer.joinToString("\n")
    }

    fun size(): Int = synchronized(lock) { buffer.size }
    fun fileSize(): Long = try {
        File(externalPath).length()
    } catch (_: Exception) { 0L }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            try { writer?.close(); writer = null } catch (_: Exception) {}
            try {
                val f = File(externalPath)
                f.delete()
                writer = BufferedWriter(FileWriter(f, true))
            } catch (_: Exception) {}
        }
    }

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                writeAlways("---", 'S', "───── FATAL ─────")
                writeAlways("crash", 'E', "Thread ${t.name}")
                writeAlways("crash", 'E', "${e.javaClass.name}: ${e.message}")
                e.stackTrace.take(60).forEach { writeAlways("crash", 'E', "  at $it") }
                var cause = e.cause
                var depth = 0
                while (cause != null && depth++ < 5) {
                    writeAlways("crash", 'E', "caused by ${cause.javaClass.name}: ${cause.message}")
                    cause.stackTrace.take(20).forEach { writeAlways("crash", 'E', "  at $it") }
                    cause = cause.cause
                }
            } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
    }
}
