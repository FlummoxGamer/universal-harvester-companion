package app.harvester.companion

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object UHLog {

    private const val TAG = "UHCompanion"
    private const val MAX_LINES = 3000
    private const val FLUSH_EVERY = 20
    private const val MAX_NET_BYTES = 2_000_000L
    private const val KEEP_SESSIONS = 10
    private const val RESUME_WINDOW_MS = 60L * 1000L

    private const val PREFS = "uh_session"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_ACTIVE = "session_active"
    private const val KEY_LAST_PAUSE = "last_pause_ts"

    private val buffer = ArrayDeque<String>(MAX_LINES)
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    private var writer: BufferedWriter? = null
    private var netWriter: BufferedWriter? = null
    private var pending = 0
    private var appContext: Context? = null
    private var initialised = false
    @Volatile private var verbose = false

    @Volatile var externalPath: String = ""
    @Volatile var netPath: String = ""
    @Volatile var sessionId: String = ""

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
            verbose = DebugPrefs.verboseLogging(ctx)
            installCrashHandler()

            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val prevId = prefs.getString(KEY_SESSION_ID, null)
            val prevActive = prefs.getBoolean(KEY_ACTIVE, false)
            val lastPause = prefs.getLong(KEY_LAST_PAUSE, 0L)
            val now = System.currentTimeMillis()

            val resume = prevActive &&
                    prevId != null &&
                    (now - lastPause) < RESUME_WINDOW_MS

            sessionId = if (resume) prevId!! else newSessionId(now)
            prefs.edit()
                .putString(KEY_SESSION_ID, sessionId)
                .putBoolean(KEY_ACTIVE, true)
                .putLong(KEY_LAST_PAUSE, now)
                .apply()

            try {
                val ext = ctx.getExternalFilesDir(null)
                val dir = ext ?: ctx.filesDir
                dir.mkdirs()

                val diag = File(dir, "uh_log_$sessionId.txt")
                val net = File(dir, "uh_net_$sessionId.txt")

                writer?.close(); writer = null
                netWriter?.close(); netWriter = null
                buffer.clear()

                writer = BufferedWriter(FileWriter(diag, true))
                netWriter = BufferedWriter(FileWriter(net, true))
                externalPath = diag.absolutePath
                netPath = net.absolutePath

                if (resume && diag.exists() && diag.length() > 0L) {
                    diag.readLines().takeLast(MAX_LINES / 2).forEach { buffer.addLast(it) }
                }
            } catch (_: Exception) {}
        }

        section("BOOT")
        d("boot", "Harvester ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
        d("boot", "Android ${android.os.Build.VERSION.RELEASE} · SDK ${android.os.Build.VERSION.SDK_INT}")
        d("boot", "Device ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        d("boot", "session=$sessionId resume=${sessionId.isNotEmpty()}")
    }

    private fun newSessionId(now: Long): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))

    fun endSession(ctx: Context) {
        synchronized(lock) {
            try { writer?.flush() } catch (_: Exception) {}
            try { netWriter?.flush() } catch (_: Exception) {}
            try { writer?.close() } catch (_: Exception) {}
            try { netWriter?.close() } catch (_: Exception) {}
            writer = null
            netWriter = null
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVE, false)
            .apply()
        pruneOldSessions(ctx)
    }

    fun onActivityPause(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_PAUSE, System.currentTimeMillis())
            .apply()
        mirrorToPublic(ctx)
    }

    private fun pruneOldSessions(ctx: Context) {
        val ext = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val all = ext.listFiles { f -> f.name.startsWith("uh_log_") && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() } ?: return
        all.drop(KEEP_SESSIONS).forEach {
            val stamp = it.name.removePrefix("uh_log_").removeSuffix(".txt")
            try { it.delete() } catch (_: Exception) {}
            try { File(ext, "uh_net_$stamp.txt").delete() } catch (_: Exception) {}
        }
    }

    fun mirrorToPublic(ctx: Context) {
        copyToPublic(ctx, externalPath, "uh_log_${sessionId}.txt")
        copyToPublic(ctx, netPath, "uh_net_${sessionId}.txt")
    }

    private fun copyToPublic(ctx: Context, srcPath: String, filename: String) {
        if (srcPath.isBlank()) return
        val src = File(srcPath)
        if (!src.exists() || src.length() == 0L) return
        try {
            val bytes = src.readBytes()
            val resolver = ctx.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val relPath = "Download/Harvester/"
            try {
                resolver.delete(
                    collection,
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(filename, relPath)
                )
            } catch (_: Exception) {}
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(collection, values) ?: return
            resolver.openOutputStream(uri)?.use { os -> os.write(bytes) }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val done = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, done, null, null)
            }
        } catch (_: Exception) {}
    }

    fun addListener(fn: (String) -> Unit) { listeners.add(fn) }
    fun removeListener(fn: (String) -> Unit) { listeners.remove(fn) }

    fun isVerbose(): Boolean = verbose

    fun setVerbose(on: Boolean) {
        verbose = on
        appContext?.let { DebugPrefs.setVerboseLogging(it, on) }
    }

    private fun sanitize(s: String): String {
        var out = s
        out = RX_JWT.replace(out) { "<JWT>" }
        out = RX_BEARER.replace(out) { m -> m.groupValues[1] + "<BEARER>" }
        out = RX_COOKIE.replace(out) { m -> m.groupValues[1] + "<COOKIE>" }
        out = RX_CF.replace(out) { m -> m.groupValues[1] + "<CF>" }
        out = RX_B64_BLOB.replace(out) { "<BLOB(${it.value.length}b)>" }
        return out
    }

    private fun write(tag: String, level: Char, msg: String, isNet: Boolean = false) {
        val clean = sanitize(msg)
        val line = "${timeFormat.format(Date())} $level [$tag] $clean"
        synchronized(lock) {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
            try {
                val target = if (isNet) netWriter else writer
                target?.appendLine(line)
                if (!isNet) {
                    pending++
                    if (pending >= FLUSH_EVERY) { writer?.flush(); pending = 0 }
                }
            } catch (_: Exception) {}
        }
        if (!isNet) {
            for (l in listeners) try { l(line) } catch (_: Exception) {}
        }
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

    fun d(tag: String, msg: String) = write(tag, 'D', msg, false)
    fun v(tag: String, msg: String) { if (verbose) write(tag, 'V', msg, true) }
    fun w(tag: String, msg: String) = write(tag, 'W', msg, false)
    fun e(tag: String, msg: String) = write(tag, 'E', msg, false)
    fun section(title: String) = write("---", 'S', "───── $title ─────", false)

    fun throwable(tag: String, msg: String, t: Throwable) {
        write(tag, 'E', "$msg: ${t.javaClass.simpleName}: ${t.message}", false)
        t.stackTrace.take(25).forEach { write(tag, 'E', "  at $it", false) }
    }

    fun snapshot(): String = synchronized(lock) {
        if (buffer.isEmpty()) "(empty)" else buffer.joinToString("\n")
    }

    fun size(): Int = synchronized(lock) { buffer.size }
    fun fileSize(): Long = try { File(externalPath).length() } catch (_: Exception) { 0L }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            try { writer?.close(); writer = null } catch (_: Exception) {}
            try { netWriter?.close(); netWriter = null } catch (_: Exception) {}
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
                // Mirror immediately so the fatal block survives even if the
                // process is torn down before the next onPause.
                appContext?.let { mirrorToPublic(it) }
            } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
    }
}
