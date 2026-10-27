package app.harvester.companion

import android.os.Build
import android.os.Looper
import android.os.StrictMode
import java.util.concurrent.Executors

object StrictModeManager {
    private fun isOurFault(trace: Array<StackTraceElement>): Boolean {
        return trace.any {
            it.className.startsWith("app.harvester.companion") || 
            it.className.startsWith("org.mozilla")
        }
    }

    fun enableStrictMode(isDebug: Boolean) {
        if (!isDebug) return
        if (Looper.myLooper() != Looper.getMainLooper()) return

        val threadBuilder = StrictMode.ThreadPolicy.Builder().detectAll()
        
        val vmBuilder = StrictMode.VmPolicy.Builder()
            .detectLeakedSqlLiteObjects()
            .detectLeakedClosableObjects()
            .detectLeakedRegistrationObjects()
            .detectActivityLeaks()
            .detectFileUriExposure()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val executor = Executors.newSingleThreadExecutor()
            
            threadBuilder.penaltyListener(executor) { violation ->
                if (isOurFault(violation.stackTrace)) {
                    val oldPolicy = StrictMode.allowThreadDiskWrites()
                    try {
                        val trace = violation.stackTrace
                            .filter { it.className.startsWith("app.harvester") || it.className.startsWith("org.mozilla") }
                            .joinToString("\n") { "  at $it" }
                        UHLog.writeLeakLog("STRICTMODE THREAD VIOLATION:\n$trace")
                    } finally {
                        StrictMode.setThreadPolicy(oldPolicy)
                    }
                }
            }

            vmBuilder.penaltyListener(executor) { violation ->
                if (isOurFault(violation.stackTrace)) {
                    val oldPolicy = StrictMode.allowThreadDiskWrites()
                    try {
                        val trace = violation.stackTrace
                            .filter { it.className.startsWith("app.harvester") || it.className.startsWith("org.mozilla") }
                            .joinToString("\n") { "  at $it" }
                        UHLog.writeLeakLog("STRICTMODE VM LEAK: ${violation.javaClass.simpleName}\n$trace")
                    } finally {
                        StrictMode.setThreadPolicy(oldPolicy)
                    }
                }
            }
            vmBuilder.detectNonSdkApiUsage()
        } else {
            threadBuilder.penaltyLog()
            vmBuilder.penaltyLog()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            vmBuilder.detectUnsafeIntentLaunch()
        }

        StrictMode.setThreadPolicy(threadBuilder.build())
        StrictMode.setVmPolicy(vmBuilder.build())
    }
}
