package app.harvester.companion.launcher

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.harvester.companion.HomeActivity
import app.harvester.companion.safemode.SafeModeActivity
import java.io.File

class LauncherActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                app.harvester.companion.utils.CrashChecker.checkNativeCrashes(this)
            }
            val safeFlag = File(filesDir, "safe_mode.flag")
            if (safeFlag.exists()) {
                startActivity(Intent(this, SafeModeActivity::class.java))
            } else {
                startActivity(Intent(this, HomeActivity::class.java))
            }
        } catch (e: Throwable) {
            startActivity(Intent(this, HomeActivity::class.java))
        }
        finish()
    }
}
