package app.harvester.companion

import android.app.Service
import android.content.Intent
import android.os.IBinder
import org.mozilla.geckoview.GeckoRuntime

class CrashHandlerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == GeckoRuntime.ACTION_CRASHED) {
            UHLog.section("GECKO NATIVE CRASH")
            UHLog.e("gecko", "native crash detected")
        }
        stopSelf()
        return START_NOT_STICKY
    }
}
