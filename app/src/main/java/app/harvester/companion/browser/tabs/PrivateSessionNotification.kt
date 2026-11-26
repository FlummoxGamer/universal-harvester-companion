package app.harvester.companion.browser.tabs

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.harvester.companion.R
import app.harvester.companion.UHApp
import app.harvester.companion.UHLog
import app.harvester.companion.components.Components
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.store.BrowserStore

/**
 * Displays a silent ongoing Android notification whenever >= 1 Private tab is open
 * (matching mainstream mobile browsers). Tapping the notification or its action
 * immediately closes all Private tabs, wipes RAM-only private previews & private groups,
 * and dismisses the notification.
 */
object PrivateSessionNotificationManager {
    const val ACTION_CLOSE_ALL_PRIVATE = "app.harvester.companion.action.CLOSE_ALL_PRIVATE_TABS"
    private const val CHANNEL_ID = "uh_private_browsing_channel"
    private const val NOTIFICATION_ID = 4109

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var started = false

    fun start(appContext: Context, store: BrowserStore) {
        if (started) return
        started = true
        ensureChannel(appContext)

        scope.launch {
            var hadPrivateTabs = false
            store.stateFlow
                .map { state -> state.tabs.count { it.content.private } }
                .distinctUntilChanged()
                .collect { privateCount ->
                    if (privateCount > 0) {
                        hadPrivateTabs = true
                        postOrUpdate(appContext, privateCount)
                    } else {
                        TabGroupStore.clearPrivateGroups()
                        TabPreviewCache.clearPrivate()
                        TabHistoryStore.clearPrivateUndo()
                        PrivateLockManager.onAllPrivateClosed()
                        if (hadPrivateTabs) {
                            hadPrivateTabs = false
                            app.harvester.companion.privacy.UhPrivacyController.purgePrivateSessionData(appContext)
                        }
                        cancel(appContext)
                    }
                }
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Private Browsing",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows when private tabs are open so you can close them in one tap"
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                    setSound(null, null)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun postOrUpdate(context: Context, privateCount: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        try {
            ensureChannel(context)
            val closeIntent = Intent(context, ClosePrivateTabsReceiver::class.java).apply {
                action = ACTION_CLOSE_ALL_PRIVATE
            }
            val closePendingIntent = PendingIntent.getBroadcast(
                context,
                NOTIFICATION_ID,
                closeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = if (privateCount == 1) {
                "Private browsing active"
            } else {
                "Private browsing active ($privateCount tabs)"
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.uh_ic_private_vibe_24)
                .setContentTitle(title)
                .setContentText("Tap to close all private tabs & wipe session")
                .setColor(0xFF7C3AED.toInt())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setAutoCancel(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(closePendingIntent)
                .build()

            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            UHLog.w("private_notif", "Unable to post private session notification: ${e.message}")
        }
    }

    fun cancel(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
    }

    /**
     * Airtight purge of all active Private tabs, Private groups, RAM previews, and private undo state.
     */
    fun closeAllPrivateTabs(context: Context, components: Components) {
        val state = components.store.state
        val privateTabs = state.tabs.filter { it.content.private }
        val privateIds = privateTabs.map { it.id }

        if (privateIds.isNotEmpty()) {
            components.store.dispatch(TabListAction.RemoveAllPrivateTabsAction)
        }
        TabGroupStore.clearPrivateGroups()
        TabPreviewCache.clearPrivate(privateIds)
        if (state.selectedTabId in privateIds) {
            TabPreviewCache.setLastActiveBitmap(null)
        }
        TabHistoryStore.clearPrivateUndo()
        PrivateLockManager.onAllPrivateClosed()
        app.harvester.companion.privacy.UhPrivacyController.purgePrivateSessionData(context)
        cancel(context)

        // If no tab remains selected or the active tab was private, select the latest normal tab if available
        val normalFallback = state.tabs.lastOrNull { !it.content.private }
        if (normalFallback != null && (state.selectedTabId == null || state.selectedTabId in privateIds)) {
            components.useCases.tabsUseCases.selectTab(normalFallback.id)
        }
    }
}

class ClosePrivateTabsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != PrivateSessionNotificationManager.ACTION_CLOSE_ALL_PRIVATE) return
        val app = context.applicationContext as? UHApp ?: return
        try {
            PrivateSessionNotificationManager.closeAllPrivateTabs(app, app.components)
        } catch (e: Exception) {
            UHLog.throwable("private_notif", "Failed closing private tabs from notification", e)
        }
    }
}
