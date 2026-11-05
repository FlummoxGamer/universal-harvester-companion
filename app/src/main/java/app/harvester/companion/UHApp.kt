package app.harvester.companion

import android.app.Application
import android.content.Context
import java.io.File
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
