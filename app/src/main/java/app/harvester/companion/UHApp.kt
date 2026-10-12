package app.harvester.companion

import android.app.Application
import android.content.Context
import mozilla.components.lib.crash.CrashReporter
import java.io.File

class UHApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // xCrash REMOVED — conflicts with lib-crash per official README:
        // "To avoid conflicting setups do not use any other crash reporting
        // libraries/services independently from this library."
    }

    override fun onCreate() {
        super.onCreate()

        // Install lib-crash BEFORE GeckoEngine.init().
        // Empty services list: capture locally, do not upload anywhere.
        CrashReporter(
            services = listOf()
        ).install(this)

        UHLog.init(this)
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
            if (!src.exists()) {
                UHLog.d("tombstone", "no tombstones dir at ${src.absolutePath}")
                return
            }
            val files = src.listFiles()
            if (files == null || files.isEmpty()) {
                UHLog.d("tombstone", "tombstones dir empty")
                return
            }
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
            UHLog.d("tombstone", "exported $exported of ${files.size} file(s) to Downloads")
        } catch (e: Exception) {
            UHLog.throwable("tombstone", "export crashed", e)
        }
    }
}
