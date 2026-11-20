package app.harvester.companion

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import app.harvester.companion.core.lifecycle.AppSleepManager

class SessionService : Service() {

    companion object {
        const val ACTION_UPDATE = "app.harvester.companion.ACTION_SESSION_UPDATE"
        const val ACTION_QUIT = "app.harvester.companion.ACTION_SESSION_QUIT"
        const val EXTRA_SLEEPING = "extra_sleeping"
        const val EXTRA_HAS_TABS = "extra_has_tabs"

        fun startOrUpdate(context: Context, isSleeping: Boolean, hasTabs: Boolean) {
            val intent = Intent(context, SessionService::class.java)
            if (!hasTabs) {
                try {
                    context.stopService(intent)
                } catch (_: Exception) {}
                return
            }
            intent.action = ACTION_UPDATE
            intent.putExtra(EXTRA_SLEEPING, isSleeping)
            intent.putExtra(EXTRA_HAS_TABS, true)
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_QUIT) {
            AppSleepManager.onTaskRemoved()
            stopSelf()
            return START_NOT_STICKY
        }

        val hasTabs = intent?.getBooleanExtra(EXTRA_HAS_TABS, true) ?: true
        if (!hasTabs) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppSleepManager.onTaskRemoved()
        try {
            if (SessionRecorder.isActive()) SessionRecorder.save(this)
        } catch (_: Exception) {}
        try { SessionRecorder.stop() } catch (_: Exception) {}
        UHLog.markCleanExit(this)
        UHLog.endSession(this)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }
}
