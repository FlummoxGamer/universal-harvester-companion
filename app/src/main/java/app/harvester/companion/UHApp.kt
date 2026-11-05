package app.harvester.companion

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import java.io.PrintWriter
import java.io.StringWriter

class UHApp : Application() {

    val components by lazy { app.harvester.companion.components.Components(this) }

    override fun onCreate() {
        super.onCreate()
        
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, e ->
                try {
                    val sw = StringWriter()
                    val pw = PrintWriter(sw)
                    e.printStackTrace(pw)
                    
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "uh_crash_log.txt")
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
        } catch (e: Throwable) {
            android.util.Log.e("UHApp", "UHApp onCreate failed", e)
        }
    }
}
