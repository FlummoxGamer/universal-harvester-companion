package app.harvester.companion

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object UHLog {
    private const val TAG = "UHCompanion"
    private const val MAX_FILE_SIZE = 64L * 1024L
    private const val PREFS = "uh_session"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_ACTIVE = "session_active"
    private const val KEY_LAST_PAUSE = "last_pause_ts"
    private const val KEY_CRASHED = "was_crashed"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileMutex = Mutex()

    private var appContext: Context? = null
    private var customTreeUri: Uri? = null
    
    private var internalLogFile: File? = null
    private var internalNetFile: File? = null
    private var customLogDoc: DocumentFile? = null
    private var customNetDoc: DocumentFile? = null

    private var logIndex = 0
    private var netIndex = 0
    private var internalLogSize = 0L
    private var internalNetSize = 0L

    @Volatile var sessionId: String = ""
        private set
    @Volatile private var verbose = false

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileNameFormat = SimpleDateFormat("HH-mm", Locale.US)

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        verbose = DebugPrefs.verboseLogging(ctx)
        
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wasCrashed = prefs.getBoolean(KEY_CRASHED, false)
        
        if (wasCrashed) {
            renameCrashFiles(ctx, prefs.getString(KEY_SESSION_ID, "") ?: "")
            prefs.edit().putBoolean(KEY_CRASHED, false).apply()
        }

        val prevId = prefs.getString(KEY_SESSION_ID, null)
        val prevActive = prefs.getBoolean(KEY_ACTIVE, false)
        val lastPause = prefs.getLong(KEY_LAST_PAUSE, 0L)
        val now = System.currentTimeMillis()

        val resume = prevActive && prevId != null && (now - lastPause) < 60000L
        sessionId = if (resume) prevId!! else newSessionId(now)
        
        prefs.edit()
            .putString(KEY_SESSION_ID, sessionId)
            .putBoolean(KEY_ACTIVE, true)
            .putLong(KEY_LAST_PAUSE, now)
            .apply()

        setupFiles(ctx)

        d("boot", "Harvester ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
        d("boot", "Android ${android.os.Build.VERSION.RELEASE} · SDK ${android.os.Build.VERSION.SDK_INT}")
        d("boot", "Device ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        d("boot", "session=$sessionId")
    }

    private fun setupFiles(ctx: Context) {
        val customUriStr = SettingsPrefs.getString(ctx, "custom_download_uri", "")
        customTreeUri = if (customUriStr.isNotBlank()) Uri.parse(customUriStr) else null

        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val sessionDir = File(dir, "sess_$sessionId").apply { mkdirs() }

        internalLogFile = File(sessionDir, "log.txt")
        internalNetFile = File(sessionDir, "net.txt")
        
        internalLogSize = if (internalLogFile!!.exists()) internalLogFile!!.length() else 0L
        internalNetSize = if (internalNetFile!!.exists()) internalNetFile!!.length() else 0L
        
        logIndex = 0
        netIndex = 0

    if (customTreeUri != null) {
    var customLogCreated = false
    try {
        val parent = DocumentFile.fromTreeUri(ctx, customTreeUri!!)
        if (parent != null) {
            var harvesterDir = parent.findFile("Harvester")
            if (harvesterDir == null || !harvesterDir.isDirectory) {
                harvesterDir = parent.createDirectory("Harvester")
            }
            if (harvesterDir != null) {
                var sDir = harvesterDir.findFile("sess_$sessionId")
                if (sDir == null || !sDir.isDirectory) {
                    sDir = harvesterDir.createDirectory("sess_$sessionId")
                }
                if (sDir != null) {
                    customLogDoc = sDir.findFile("log.txt") ?: sDir.createFile("text/plain", "log.txt")
                    customNetDoc = sDir.findFile("net.txt") ?: sDir.createFile("text/plain", "net.txt")
                    if (customLogDoc != null && customNetDoc != null) {
                        customLogCreated = true
                    }
                }
            }
        }
    } catch (e: Exception) {
        internalLogFile?.appendText("[diag-log] Custom location setup exception: ${e.message}\n")
    }
    if (!customLogCreated) {
        internalLogFile?.appendText("[diag-log] Custom location setup failed: Could not create session or file in selected tree URI.\n")
        customTreeUri = null
    }
    }
    }

    private fun renameCrashFiles(ctx: Context, prevSessionId: String) {
        if (prevSessionId.isBlank()) return
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val sessionDir = File(dir, "sess_$prevSessionId")
        if (!sessionDir.exists()) return
        val time = fileNameFormat.format(Date())
        File(sessionDir, "log.txt").takeIf { it.exists() }?.renameTo(File(sessionDir, "crash_log_$time.txt"))
        File(sessionDir, "net.txt").takeIf { it.exists() }?.renameTo(File(sessionDir, "crash_net_$time.txt"))
    }

    private fun rotateFile(ctx: Context, isNet: Boolean) {
        val sessionDir = internalLogFile?.parentFile ?: return
        if (isNet) {
            netIndex++
            internalNetFile = File(sessionDir, "net($netIndex).txt")
            internalNetSize = 0L
            if (customTreeUri != null) {
                val parent = DocumentFile.fromTreeUri(ctx, customTreeUri!!)?.findFile("Harvester")?.findFile("sess_$sessionId")
                customNetDoc = parent?.createFile("text/plain", "net($netIndex).txt")
            }
        } else {
            logIndex++
            internalLogFile = File(sessionDir, "log($logIndex).txt")
            internalLogSize = 0L
            if (customTreeUri != null) {
                val parent = DocumentFile.fromTreeUri(ctx, customTreeUri!!)?.findFile("Harvester")?.findFile("sess_$sessionId")
                customLogDoc = parent?.createFile("text/plain", "log($logIndex).txt")
            }
        }
    }

    fun endSession(ctx: Context) {
        scope.launch {
            fileMutex.withLock {
                renameSessionFiles(ctx)
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ACTIVE, false)
                .putBoolean(KEY_CRASHED, false)
                .apply()
            pruneOldSessions(ctx)
        }
    }

    fun onActivityPause(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_PAUSE, System.currentTimeMillis())
            .putBoolean(KEY_CRASHED, true)
            .apply()
    }

    fun markCleanExit(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CRASHED, false)
            .apply()
    }

    private fun renameSessionFiles(ctx: Context) {
        val time = fileNameFormat.format(Date())
        val sessionDir = internalLogFile?.parentFile ?: return
        val version = BuildConfig.VERSION_NAME

        internalLogFile?.takeIf { it.exists() }?.renameTo(File(sessionDir, "log_v${version}_$time.txt"))
        internalNetFile?.takeIf { it.exists() }?.renameTo(File(sessionDir, "net_v${version}_$time.txt"))
        
        if (internalLogSize > 0 && internalLogFile?.length() == 0L) {
            for (i in 1..logIndex) {
                File(sessionDir, "log($i).txt").takeIf { it.exists() }?.renameTo(File(sessionDir, "log_v${version}_${time}_($i).txt"))
            }
        }
        if (internalNetSize > 0 && internalNetFile?.length() == 0L) {
            for (i in 1..netIndex) {
                File(sessionDir, "net($i).txt").takeIf { it.exists() }?.renameTo(File(sessionDir, "net_v${version}_${time}_($i).txt"))
            }
        }
    }

    private fun pruneOldSessions(ctx: Context) {
        val ext = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val all = ext.listFiles { f -> f.isDirectory && f.name.startsWith("sess_") }
            ?.sortedByDescending { it.lastModified() } ?: return
        all.drop(4).forEach { it.deleteRecursively() }
    }

    private fun newSessionId(now: Long): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))

    fun addListener(fn: (String) -> Unit) { listeners.add(fn) }
    fun removeListener(fn: (String) -> Unit) { listeners.remove(fn) }

    fun isVerbose(): Boolean = verbose

    fun setVerbose(on: Boolean) {
        verbose = on
        appContext?.let { DebugPrefs.setVerboseLogging(it, on) }
    }

    fun netNav(method: String, url: String, referer: String?, hasCookie: Boolean, hasAuth: Boolean) {
        if (!verbose) return
        write("net", "NAV $method $url")
    }

    fun netError(url: String, code: Int) {
        write("net", "ERROR $code $url")
    }

    private fun write(tag: String, msg: String) {
        val line = "${timeFormat.format(Date())} [$tag] $msg\n"
        
        for (l in listeners) {
            try { l(line) } catch (_: Exception) {}
        }
        try { Log.d(TAG, line) } catch (_: Exception) {}

        scope.launch {
            fileMutex.withLock {
                try {
                    if (tag == "net") {
                        internalNetFile?.appendText(line)
                        internalNetSize += line.length
                        if (internalNetSize >= MAX_FILE_SIZE) {
                            appContext?.let { rotateFile(it, true) }
                        }
                    } else {
                        internalLogFile?.appendText(line)
                        internalLogSize += line.length
                        if (internalLogSize >= MAX_FILE_SIZE) {
                            appContext?.let { rotateFile(it, false) }
                        }
                    }
                } catch (e: Exception) {}
                mirrorToPublic(line)
            }
        }
    }

    private fun mirrorToPublic(text: String) {
        val ctx = appContext ?: return
        if (customTreeUri == null) return
        
        try {
            val targetDoc = if (text.contains("[net]")) customNetDoc else customLogDoc
            targetDoc?.uri?.let { uri ->
                ctx.contentResolver.openOutputStream(uri, "wa")?.use { 
                    it.write(text.toByteArray()) 
                }
            }
        } catch (e: Exception) {
            try {
                internalLogFile?.appendText("[diag-log] Custom location write failed: ${e.message}\n")
            } catch (_: Exception) {}
        }
    }

    fun d(tag: String, msg: String) = write(tag, msg)
    fun e(tag: String, msg: String) = write(tag, "ERROR: $msg")
    fun w(tag: String, msg: String) = write(tag, "WARN: $msg")
    fun section(title: String) = write("---", "───── $title ─────")

    fun throwable(tag: String, msg: String, t: Throwable) {
        write(tag, "ERROR: $msg: ${t.javaClass.simpleName}: ${t.message}")
        t.stackTrace.take(25).forEach { write(tag, "  at $it") }
    }

    fun snapshot(): String {
        return try { internalLogFile?.readText() ?: "" } catch (_: Exception) { "" }
    }
    
    fun size(): Int = snapshot().lines().size
    fun fileSize(): Long = internalLogFile?.length() ?: 0L

    fun clear() {
        scope.launch {
            fileMutex.withLock {
                try {
                    internalLogFile?.delete()
                    internalNetFile?.delete()
                    appContext?.let { setupFiles(it) }
                } catch (_: Exception) {}
            }
        }
    }

    fun writeLeakLog(msg: String) {
        val clean = msg.replace("\n", " ")
        write("leak", "ERROR: $clean")
    }
}
