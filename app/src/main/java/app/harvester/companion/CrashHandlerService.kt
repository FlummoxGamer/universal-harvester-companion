package app.harvester.companion

import android.app.Service
import android.content.Intent
import android.os.IBinder
import org.mozilla.geckoview.GeckoRuntime

class CrashHandlerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == GeckoRuntime.ACTION_CRASHED) {
            val visibility = intent.getIntExtra(
                GeckoRuntime.EXTRA_CRASH_PROCESS_VISIBILITY,
                -1
            )
            UHLog.section("GECKO CRASH")
            UHLog.e("gecko", "A Gecko process crashed.")
            UHLog.e("gecko", "Process visibility: $visibility")
            
            // Dump the current UHLog buffer so we can see what the app was doing
            // in its final moments.
            UHLog.e("gecko", UHLog.snapshot())
        }
        stopSelf()
        return START_NOT_STICKY
    }
}
