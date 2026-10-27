package app.harvester.companion

import android.app.Application
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

        LeakCanary.config = LeakCanary.config.copy(
            onHeapAnalyzedListener = OnHeapAnalyzedListener { heapAnalysis ->
                if (heapAnalysis is HeapAnalysisSuccess) {
                    val realLeaks = heapAnalysis.allLeaks.filter { leak ->
                        val className = leak.leakTraces.firstOrNull()?.leakingObject?.className ?: ""
                        !className.contains("GeckoView") && 
                        !className.contains("androidx.") && 
                        !className.startsWith("android.") && 
                        !className.contains("java.lang.ref")
                    }
                    if (realLeaks.isNotEmpty()) {
                        val trace = realLeaks.first().leakTraces.firstOrNull()
                        val leakingObjectName = trace?.leakingObject?.className ?: "Unknown"
                        val holder = trace?.referencePath?.firstOrNull()?.ownerClass ?: "Unknown"
                        UHLog.writeLeakLog("MEMORY LEAK: $leakingObjectName held by $holder")
                    }
                }
            }
        )
    }
}
