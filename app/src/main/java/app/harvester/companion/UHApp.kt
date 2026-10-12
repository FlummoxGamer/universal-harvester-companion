package app.harvester.companion

import android.app.Application
import android.content.Context
import java.io.File

class UHApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()

        if (applicationProcessName() == "$packageName:crash") {
            // Crash handler process: do not init GeckoRuntime here.
            return
        }

        UHLog.init(this)
        GeckoEngine.init(this)
        Thread { exportCrashFiles(this) }.start()
    }

    private fun applicationProcessName(): String {
        return try {
            val pid = android.os.Process.myPid()
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: ""
        } catch (_: Exception) { "" }
    }

    private fun exportCrashFiles(ctx: Context) {
        val dir = ctx.filesDir ?: return
        for (f in dir.listFiles() ?: return) {
            if (!f.isFile) continue
            val name = f.name
            if (!name.startsWith("uh_native_crash") &&
                !name.startsWith("crash_service_invoked") &&
                !name.startsWith("crash_extras") &&
                !name.startsWith("crash_minidump")) continue
            try {
                if (name.endsWith(".dmp")) {
                    DownloadHelper.saveBytesToDownloads(ctx, name, f.readBytes())
                } else {
                    DownloadHelper.saveTextToDownloads(ctx, name, f.readText())
                }
            } catch (_: Exception) {}
        }
    }
}
