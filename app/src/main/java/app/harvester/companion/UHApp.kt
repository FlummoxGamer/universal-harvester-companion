package app.harvester.companion

import android.app.Application
import android.content.Context
import java.io.File

class UHApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // xCrash MUST be initialized in attachBaseContext so its native signal
        // handler is installed before any other code runs.
        xcrash.XCrash.init(this)
    }

    override fun onCreate() {
        super.onCreate()
        UHLog.init(this)
        UHLog.d("xcrash", "initialized=${xcrash.XCrash.initialized}")
        GeckoEngine.init(this)
        // Fire-and-forget: dump any pending tombstones to Downloads.
        Thread { exportTombstones(this) }.start()
    }

    /**
     * Copies all tombstone files from filesDir/tombstones to Downloads/uh_tombstones/.
     * Runs on every app launch. Safe to call repeatedly — existing files are overwritten.
     */
    private fun exportTombstones(ctx: Context) {
        try {
            val src = File(ctx.filesDir, "tombstones")
            if (!src.exists()) return
            val files = src.listFiles() ?: return
            if (files.isEmpty()) return
            var exported = 0
            for (f in files) {
                if (!f.isFile) continue
                try {
                    val content = f.readText()
                    val ok = DownloadHelper.saveTextToDownloads(
                        ctx,
                        "tombstone_${f.name}",
                        content
                    )
                    if (ok) exported++
                } catch (e: Exception) {
                    UHLog.w("tombstone", "export failed for ${f.name}: ${e.message}")
                }
            }
            if (exported > 0) {
                UHLog.d("tombstone", "exported $exported tombstone(s) to Downloads/uh_tombstones")
            }
        } catch (e: Exception) {
            UHLog.throwable("tombstone", "export crashed", e)
        }
    }
}
