package app.harvester.companion.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import app.harvester.companion.MainActivity
import app.harvester.companion.safemode.SafeModeActivity
import app.harvester.companion.utils.CrashChecker
import java.io.File

class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            CrashChecker.checkNativeCrashes(this)
            
            val safeFlag = File(filesDir, "safe_mode.flag")
            if (safeFlag.exists()) {
                startActivity(Intent(this, SafeModeActivity::class.java))
            } else {
                startActivity(Intent(this, MainActivity::class.java))
            }
        } catch (e: Throwable) {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }
}
