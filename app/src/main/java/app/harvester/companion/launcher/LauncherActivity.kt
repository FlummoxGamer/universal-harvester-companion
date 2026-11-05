package app.harvester.companion.launcher

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import app.harvester.companion.MainActivity
import app.harvester.companion.safemode.SafeModeActivity
import java.io.File

class LauncherActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkNativeCrashes()

        val safeFlag = File(filesDir, "safe_mode.flag")
        if (safeFlag.exists()) {
            startActivity(Intent(this, SafeModeActivity::class.java))
        } else {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }

    private fun checkNativeCrashes() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                val exitInfoList = activityManager.getHistoricalProcessExitReasons(packageName, 0, 1)
                if (exitInfoList.isNotEmpty()) {
                    val exitInfo = exitInfoList[0]
                    if (exitInfo.reason == ApplicationExitInfo.REASON_CRASH || 
                        exitInfo.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || 
                        exitInfo.reason == ApplicationExitInfo.REASON_ANR || 
                        exitInfo.reason == ApplicationExitInfo.REASON_LOW_MEMORY) {
                        
                        val crashFile = File(filesDir, "last_crash.txt")
                        val traceStream = exitInfo.traceInputStream
                        val description = exitInfo.description ?: "No description"
                        
                        val details = if (traceStream != null) {
                            traceStream.bufferedReader().use { it.readText() }
                        } else {
                            "Exit Reason Code: ${exitInfo.reason}\n$description"
                        }

                        crashFile.writeText("ApplicationExitInfo Detected:\nReason: ${exitInfo.reason}\n$description\n\nDetails:\n$details")
                        File(filesDir, "safe_mode.flag").createNewFile()
                    }
                }
            } catch (_: Exception) {}
        }
    }
}
