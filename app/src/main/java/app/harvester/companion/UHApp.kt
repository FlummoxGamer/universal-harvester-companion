package app.harvester.companion

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import xcrash.ICrashCallback
import xcrash.XCrash

class UHApp : Application() {

    val components by lazy { app.harvester.companion.components.Components(this) }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        initXCrash(base)
    }

    override fun onCreate() {
        super.onCreate()
        
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                e.printStackTrace(pw)
                
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "uh_java_crash.txt")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        output.write("Crash Time: ${System.currentTimeMillis()}\n\n${sw.toString()}".toByteArray())
                    }
                }
            } catch (_: Exception) {}
            
            Process.killProcess(Process.myPid())
            System.exit(1)
        }
        
        ThemePrefs.applyCurrent(this)
    }

    private fun initXCrash(context: Context) {
        try {
            val params = XCrash.InitParameters()
            params.setAppVersion(BuildConfig.VERSION_NAME)
            params.setLogDir("${context.filesDir.absolutePath}/crashes")
            
            val callback = ICrashCallback { logPath, emergency ->
                try {
                    val crashFile = File(context.filesDir, "last_crash.txt")
                    val content = if (!logPath.isNullOrBlank() && File(logPath).exists()) {
                        File(logPath).readText()
                    } else {
                        emergency ?: "Unknown Crash"
                    }
                    crashFile.writeText(content)
                    File(context.filesDir, "safe_mode.flag").createNewFile()
                } catch (_: Exception) {}
            }
            
            params.setJavaCallback(callback)
            params.setNativeCallback(callback)
            params.setAnrCallback(callback)
            
            XCrash.init(context, params)
        } catch (_: Throwable) {}
    }
}
