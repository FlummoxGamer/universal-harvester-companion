package app.harvester.companion

import android.app.Application
import android.content.Context
import java.io.File

class UHApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)

        // xCrash must be initialized here, before any other code runs.
        // It installs a native signal handler inside this process so that
        // SIGSEGV/SIGABRT in libxul.so or libEGL.so are captured before
        // the process dies. ColorOS cannot block this — it runs in-process.
        val crashDir = File(base.filesDir, "tombstones")
        crashDir.mkdirs()

        xcrash.XCrash.init(
            this,
            xcrash.InitParameters().apply {
                setLogDir(crashDir.absolutePath)
                setJavaCallback { _, _, _ -> }
                setNativeCallback { _, _ -> }
                setAnrCallback { _, _ -> }
                setLogFile(null) // do not write a separate xCrash log file
            }
        )
    }

    override fun onCreate() {
        super.onCreate()
        UHLog.init(this)
        GeckoEngine.init(this)
        Thread { exportTombstones(this) }.start()
    }

    private fun exportTombstones(ctx: Context) {
        val src = File(ctx.filesDir, "tombstones")
        if (!src.isDirectory) return

        for (f in src.listFiles() ?: return) {
            if (!f.isFile) continue
            try {
                DownloadHelper.saveTextToDownloads(
                    ctx,
                    "tombstone_${f.name}",
                    f.readText()
                )
            } catch (_: Exception) {}
        }
    }
}
