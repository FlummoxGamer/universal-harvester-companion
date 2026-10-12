package app.harvester.companion

import android.app.Application
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import mozilla.components.lib.crash.CrashReporter
import mozilla.components.support.base.android.NotificationsDelegate
import java.io.File

class UHApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()

        val notificationsDelegate = NotificationsDelegate(
            NotificationManagerCompat.from(this)
        )

        CrashReporter(
            context = this,
            services = listOf(),
            notificationsDelegate = notificationsDelegate
        ).install(this)

        UHLog.init(this)
        GeckoEngine.init(this)

        Thread { exportTombstones(this) }.start()
        Thread { exportCrashReports(this) }.start()
    }

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

    private fun exportCrashReports(ctx: Context) {
        try {
            val src = File(ctx.filesDir, "crash")
            if (!src.exists()) {
                UHLog.d("crash", "no crash dir at ${src.absolutePath}")
                return
            }
            val files = src.listFiles()
            if (files == null || files.isEmpty()) {
                UHLog.d("crash", "crash dir empty")
                return
            }
            var exported = 0
            for (f in files) {
                if (!f.isFile) continue
                try {
                    val content = f.readText()
                    val ok = DownloadHelper.saveTextToDownloads(
                        ctx,
                        "crash_${f.name}",
                        content
                    )
                    if (ok) exported++
                } catch (e: Exception) {
                    UHLog.w("crash", "export failed for ${f.name}: ${e.message}")
                }
            }
            UHLog.d("crash", "exported $exported of ${files.size} file(s) to Downloads")
        } catch (e: Exception) {
            UHLog.throwable("crash", "export crashed", e)
        }
    }
}
