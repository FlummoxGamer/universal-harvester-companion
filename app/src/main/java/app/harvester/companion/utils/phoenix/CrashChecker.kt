package app.harvester.companion.utils

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File

object CrashChecker {
    fun checkNativeCrashes(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            checkApi30(context)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun checkApi30(context: Context) {
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val exitInfoList = activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 1)
            if (exitInfoList.isNotEmpty()) {
                val exitInfo = exitInfoList[0]
                if (exitInfo.reason == ApplicationExitInfo.REASON_CRASH || 
                    exitInfo.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || 
                    exitInfo.reason == ApplicationExitInfo.REASON_ANR || 
                    exitInfo.reason == ApplicationExitInfo.REASON_LOW_MEMORY) {
                    
                    val crashFile = File(context.filesDir, "last_crash.txt")
                    val traceStream = exitInfo.traceInputStream
                    val description = exitInfo.description ?: "No description"
                    
                    val details = if (traceStream != null) {
                        traceStream.bufferedReader().use { it.readText() }
                    } else {
                        "Exit Reason Code: ${exitInfo.reason}\n$description"
                    }

                    crashFile.writeText("ApplicationExitInfo Detected:\nReason: ${exitInfo.reason}\n$description\n\nDetails:\n$details")
                    File(context.filesDir, "safe_mode.flag").createNewFile()
                }
            }
        } catch (_: Exception) {}
    }
}
