package app.harvester.companion

import android.os.Build
import android.os.Looper
import android.os.StrictMode

object StrictModeManager {
    fun enableStrictMode(isDebug: Boolean) {
        if (!isDebug) return
        if (Looper.myLooper() != Looper.getMainLooper()) return

        val manufacturer = Build.MANUFACTURER.lowercase()
        val isOemBypass = manufacturer.contains("huawei") || manufacturer.contains("oneplus") || manufacturer.contains("oppo") || manufacturer.contains("realme")

        if (isOemBypass) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX)
            StrictMode.setVmPolicy(StrictMode.VmPolicy.LAX)
            return
        }

        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build()
        )

        val vmBuilder = StrictMode.VmPolicy.Builder()
            .detectLeakedSqlLiteObjects()
            .detectLeakedClosableObjects()
            .detectLeakedRegistrationObjects()
            .detectActivityLeaks()
            .detectFileUriExposure()
            .penaltyLog()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            vmBuilder.detectNonSdkApiUsage()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            vmBuilder.detectUnsafeIntentLaunch()
        }

        StrictMode.setVmPolicy(vmBuilder.build())
    }
}
