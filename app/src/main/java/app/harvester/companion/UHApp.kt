package app.harvester.companion

import android.app.Application
import android.content.Context
import java.io.File

class UHApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        xcrash.XCrash.init(this)
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
