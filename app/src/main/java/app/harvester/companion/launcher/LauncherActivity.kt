package app.harvester.companion.launcher

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import app.harvester.companion.MainActivity
import app.harvester.companion.safemode.SafeModeActivity
import java.io.File

class LauncherActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                app.harvester.companion.utils.CrashChecker.checkNativeCrashes(this)
            }
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
