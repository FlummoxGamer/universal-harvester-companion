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
        
        AppWatcher.manualInstall(
            application = application,
            retainedDelayMillis = TimeUnit.SECONDS.toMillis(5),
            watchersToInstall = AppWatcher.appDefaultWatchers(application)
        )

        LeakCanary.showLeakDisplayActivityLauncherIcon(false)

        val nm = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancelAll()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channels = nm.notificationChannels
            for (channel in channels) {
                channel.importance = NotificationManager.IMPORTANCE_NONE
                nm.createNotificationChannel(channel)
            }
        }

        LeakCanary.config = LeakCanary.config.copy(
            showNotifications = false,
            onHeapAnalyzedListener = OnHeapAnalyzedListener { heapAnalysis ->
                if (heapAnalysis is HeapAnalysisSuccess) {
                    val realLeaks = heapAnalysis.allLeaks.filter { leak ->
                        val className = leak.leakTraces.firstOrNull()?.leakingObject?.className ?: ""
                        !className.contains("GeckoView") && 
                        !className.contains("androidx.") && 
                        !className.startsWith("android.") && 
                        !className.contains("java.lang.ref")
                    }
                    if (realLeaks.count() > 0) {
                        val trace = realLeaks.first().leakTraces.firstOrNull()
                        val leakingObjectName = trace?.leakingObject?.className ?: "Unknown"
                        val holder = trace?.referencePath?.firstOrNull()?.owningClassName ?: "Unknown"
                        UHLog.writeLeakLog("MEMORY LEAK: $leakingObjectName held by $holder")
                    }
                }
            }
        )
    }
}
