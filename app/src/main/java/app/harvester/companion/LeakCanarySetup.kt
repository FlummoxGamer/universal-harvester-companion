package app.harvester.companion

import android.app.Application
import leakcanary.AppWatcher
import leakcanary.LeakCanary
import shark.LeakTrace
import shark.OnHeapAnalyzedListener

object LeakCanarySetup {
    fun setup(application: Application) {
        if (AppWatcher.isInstalled) return
        AppWatcher.manualInstall(application, AppWatcher.appDefaultWatchers(application))

        LeakCanary.config = LeakCanary.config.copy(
            onHeapAnalyzedListener = OnHeapAnalyzedListener { heapAnalysis ->
                val realLeaks = heapAnalysis.allLeaks.filter { leak ->
                    val cn = leak.className
                    !cn.contains("GeckoView") && 
                    !cn.contains("androidx.") && 
                    !cn.startsWith("android.") && 
                    !cn.contains("java.lang.ref")
                }
                if (realLeaks.isNotEmpty()) {
                    val first = realLeaks.first()
                    val gcRoot = first.leakTraces.firstOrNull()?.leakingObject?.className ?: "Unknown"
                    val holder = first.leakTraces.firstOrNull()?.leakTrace?.referencePath?.firstOrNull()?.ownerClass ?: "Unknown"
                    UHLog.e("leak", "MEMORY LEAK: $gcRoot held by $holder")
                }
            }
        )
    }
}
