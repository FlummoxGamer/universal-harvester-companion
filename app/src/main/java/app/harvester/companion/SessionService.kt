package app.harvester.companion

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import app.harvester.companion.browser.tabs.PrivateSessionNotificationManager
import app.harvester.companion.core.lifecycle.AppSleepManager

class SessionService : Service() {

    companion object {
        const val ACTION_UPDATE = "app.harvester.companion.ACTION_SESSION_UPDATE"
        const val ACTION_QUIT = "app.harvester.companion.ACTION_SESSION_QUIT"
        const val ACTION_PRIVATE_FOREGROUND = "app.harvester.companion.ACTION_PRIVATE_FOREGROUND"
        const val ACTION_STOP_PRIVATE_FOREGROUND = "app.harvester.companion.ACTION_STOP_PRIVATE_FOREGROUND"
        const val EXTRA_SLEEPING = "extra_sleeping"
        const val EXTRA_HAS_TABS = "extra_has_tabs"
        const val EXTRA_PRIVATE_COUNT = "extra_private_count"

        fun startOrUpdate(context: Context, isSleeping: Boolean, hasTabs: Boolean) {
            val intent = Intent(context, SessionService::class.java)
            if (!hasTabs && !PrivateSessionNotificationManager.hasActivePrivateSession()) {
                try {
                    context.stopService(intent)
                } catch (_: Exception) {}
                return
            }
            intent.action = ACTION_UPDATE
            intent.putExtra(EXTRA_SLEEPING, isSleeping)
            intent.putExtra(EXTRA_HAS_TABS, hasTabs)
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }

        fun updatePrivateForeground(context: Context, privateCount: Int): Boolean {
            if (privateCount <= 0) {
                stopPrivateForeground(context)
                return false
            }
            val intent = Intent(context, SessionService::class.java).apply {
                action = ACTION_PRIVATE_FOREGROUND
                putExtra(EXTRA_PRIVATE_COUNT, privateCount)
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: Exception) {
                UHLog.w("private_notif", "Foreground start deferred, falling back to notification: ${e.message}")
                false
            }
        }

        fun stopPrivateForeground(context: Context) {
            try {
                val intent = Intent(context, SessionService::class.java).apply {
                    action = ACTION_STOP_PRIVATE_FOREGROUND
                }
                context.startService(intent)
            } catch (_: Exception) {
                try {
                    context.stopService(Intent(context, SessionService::class.java))
                } catch (_: Exception) {}
            }
        }
    }

    private var isPrivateForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_QUIT -> {
                detachPrivateForeground()
                AppSleepManager.onTaskRemoved()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_PRIVATE_FOREGROUND -> {
                val count = intent.getIntExtra(EXTRA_PRIVATE_COUNT, 1).coerceAtLeast(1)
                val notification = PrivateSessionNotificationManager.buildNotification(this, count)
                promoteToForeground(notification)
                return START_NOT_STICKY
            }

            ACTION_STOP_PRIVATE_FOREGROUND -> {
                detachPrivateForeground()
                val hasNormalTabs = try {
                    (applicationContext as? UHApp)?.components?.store?.state?.tabs?.any { !it.content.private } == true
                } catch (_: Exception) {
                    false
                }
                if (!hasNormalTabs) {
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }

        val hasTabs = intent?.getBooleanExtra(EXTRA_HAS_TABS, true) ?: true
        if (!hasTabs && !isPrivateForeground && !PrivateSessionNotificationManager.hasActivePrivateSession()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun promoteToForeground(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    PrivateSessionNotificationManager.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(PrivateSessionNotificationManager.NOTIFICATION_ID, notification)
            }
            isPrivateForeground = true
        } catch (e: Exception) {
            isPrivateForeground = false
            UHLog.w("private_notif", "startForeground failed: ${e.message}")
        }
    }

    private fun detachPrivateForeground() {
        if (isPrivateForeground) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (_: Exception) {}
            isPrivateForeground = false
        }
        PrivateSessionNotificationManager.cancel(this)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            if (SessionRecorder.isActive()) SessionRecorder.save(this)
        } catch (_: Exception) {}
        try { SessionRecorder.stop() } catch (_: Exception) {}

        // Keep process & Private tabs alive in foreground until user explicitly closes via notification
        val keepPrivateAlive = isPrivateForeground || PrivateSessionNotificationManager.hasActivePrivateSession()
        if (keepPrivateAlive) {
            val activeCount = try {
                (applicationContext as? UHApp)?.components?.store?.state?.tabs?.count { it.content.private } ?: 0
            } catch (_: Exception) {
                0
            }
            if (activeCount > 0) {
                promoteToForeground(PrivateSessionNotificationManager.buildNotification(this, activeCount))
                super.onTaskRemoved(rootIntent)
                return
            }
            detachPrivateForeground()
        }

        AppSleepManager.onTaskRemoved()
        UHLog.markCleanExit(this)
        UHLog.endSession(this)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }
}
