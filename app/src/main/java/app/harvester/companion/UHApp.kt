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

        // Skip init in the ":crash" process. GeckoRuntime must be created
        // only once, in the main process. Creating it in the crash handler
        // process would crash the service before it can log the crash.
        if (Application.getProcessName().endsWith(":crash")) {
            return
        }

        UHLog.init(this)
        GeckoEngine.init(this)
        Thread { exportCrashFiles(this) }.start()
    }

    private fun exportCrashFiles(ctx: Context) {
        val files = listOf(
            File(ctx.filesDir, "uh_native_crash.txt"),
            File(ctx.filesDir, "tombstones")
        )
        for (src in files) {
            if (src.isFile) {
                try {
                    DownloadHelper.saveTextToDownloads(
                        ctx,
                        "crash_${src.name}",
                        src.readText()
                    )
                } catch (_: Exception) {}
            } else if (src.isDirectory) {
                for (f in src.listFiles() ?: continue) {
                    if (!f.isFile) continue
                    try {
                        DownloadHelper.saveTextToDownloads(
                            ctx,
                            "crash_${f.name}",
                            f.readText()
                        )
                    } catch (_: Exception) {}
                }
            }
        }
    }
}
