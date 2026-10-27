package app.harvester.companion

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import java.util.concurrent.Executors

object StrictModeManager {
    fun enableStrictMode(isDebug: Boolean) {
        if (!isDebug) return
        if (Looper.myLooper() != Looper.getMainLooper()) return

        val manufacturer = Build.MANUFACTURER.lowercase()
        val isOemBypass = manufacturer.contains("huawei") || manufacturer.contains("oneplus") || manufacturer.contains("oppo")

        val threadBuilder = StrictMode.ThreadPolicy.Builder().detectAll()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !isOemBypass) {
            val executor = Executors.newSingleThreadExecutor()
            threadBuilder.penaltyListener(executor) { violation ->
                val trace = violation.stackTrace.joinToString("\n") { "  at $it" }
                UHLog.writeLeakLog("STRICTMODE THREAD VIOLATION:\n$trace")
            }
        } else {
            threadBuilder.penaltyLog()
        }
        StrictMode.setThreadPolicy(threadBuilder.build())

        val vmBuilder = StrictMode.VmPolicy.Builder()
            .detectLeakedSqlLiteObjects()
            .detectLeakedClosableObjects()
            .detectLeakedRegistrationObjects()
            .detectActivityLeaks()
            .detectFileUriExposure()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !isOemBypass) {
            val executor = Executors.newSingleThreadExecutor()
            vmBuilder.penaltyListener(executor) { violation ->
                val trace = violation.stackTrace.joinToString("\n") { "  at $it" }
                UHLog.writeLeakLog("STRICTMODE VM LEAK: ${violation.javaClass.simpleName}\n$trace")
            }
            vmBuilder.detectNonSdkApiUsage()
        } else {
            vmBuilder.penaltyLog()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            vmBuilder.detectUnsafeIntentLaunch()
        }

        StrictMode.setVmPolicy(vmBuilder.build())
    }
}
