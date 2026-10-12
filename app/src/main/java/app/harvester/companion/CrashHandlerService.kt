package app.harvester.companion

import android.app.Service
import android.content.Intent
import android.os.IBinder
import org.mozilla.geckoview.GeckoRuntime

class CrashHandlerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == GeckoRuntime.ACTION_CRASHED) {
            val fatal = intent.getBooleanExtra(GeckoRuntime.EXTRA_CRASH_FATAL, false)
            val processVisibility = intent.getIntExtra(
                GeckoRuntime.EXTRA_CRASH_PROCESS_VISIBILITY, -1
            )
            UHLog.section("GECKO CRASH")
            UHLog.e("gecko", "fatal=$fatal visibility=$processVisibility")
        }
        stopSelf()
        return START_NOT_STICKY
    }
}
