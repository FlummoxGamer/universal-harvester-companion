package app.harvester.companion

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Intent

class UHApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemePrefs.applyCurrent(this)
        UHLog.init(this)
        SessionRecorder.init(this)
        try {
            startService(Intent(this, SessionService::class.java))
        } catch (_: Exception) {}
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val keep = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> 12
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> 8
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> 4
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> 8
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> 4
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> 1
            else -> return
        }
        try {
            TabManager.get(this).evictForMemory(keep)
        } catch (_: Exception) {}
    }
}
