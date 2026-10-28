package app.harvester.companion

import android.os.Looper
import android.os.StrictMode

object StrictModeManager {
    fun enableStrictMode(isDebug: Boolean) {
        if (!isDebug) return
        if (Looper.myLooper() != Looper.getMainLooper()) return

        val vmBuilder = StrictMode.VmPolicy.Builder()
            .detectLeakedSqlLiteObjects()
            .detectLeakedClosableObjects()
            .detectLeakedRegistrationObjects()
            .detectActivityLeaks()
            .penaltyListener(java.util.concurrent.Executors.newSingleThreadExecutor()) { violation ->
                if (violation.javaClass.simpleName == "InstanceCountViolation") return@penaltyListener
                val oldPolicy = StrictMode.allowThreadDiskWrites()
                try {
                    val trace = violation.stackTrace.joinToString("\n") { "  at $it" }
                    UHLog.writeLeakLog("STRICTMODE LEAK: ${violation.javaClass.simpleName}\n$trace")
                } finally {
                    StrictMode.setThreadPolicy(oldPolicy)
                }
            }

        StrictMode.setVmPolicy(vmBuilder.build())
    }
}
