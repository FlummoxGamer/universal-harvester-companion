package app.harvester.companion

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import java.util.concurrent.TimeUnit
import leakcanary.AppWatcher
import leakcanary.LeakCanary
import leakcanary.OnHeapAnalyzedListener
import shark.HeapAnalysisSuccess

object LeakCanarySetup {
    fun setup(application: Application) {
        if (AppWatcher.isInstalled) return

        val nm = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancelAll()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            for (channel in nm.notificationChannels) {
                if (channel.id.contains("leak", ignoreCase = true)) {
                    channel.importance = NotificationManager.IMPORTANCE_NONE
                    nm.createNotificationChannel(channel)
                }
            }
        }

        LeakCanary.config = LeakCanary.config.copy(
            dumpHeap = true,
            showNotifications = false,
            onHeapAnalyzedListener = OnHeapAnalyzedListener { heapAnalysis ->
                if (heapAnalysis is HeapAnalysisSuccess) {
                    heapAnalysis.allLeaks.forEach { leak ->
                        leak.leakTraces.forEach { trace ->
                            val path = trace.referencePath.joinToString(" -> ") { it.owningClassName }
                            val leakingObjectName = trace.leakingObject.className
                            UHLog.writeLeakLog("MEMORY LEAK: $leakingObjectName\nPath: $path")
                        }
                    }
                }
            }
        )

        AppWatcher.manualInstall(
            application = application,
            retainedDelayMillis = TimeUnit.SECONDS.toMillis(5),
            watchersToInstall = AppWatcher.appDefaultWatchers(application)
        )

        LeakCanary.showLeakDisplayActivityLauncherIcon(false)
    }
}
