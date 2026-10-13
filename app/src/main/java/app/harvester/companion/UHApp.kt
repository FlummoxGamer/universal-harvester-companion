package app.harvester.companion

import android.app.Application
import android.content.Intent

class UHApp : Application() {
    override fun onCreate() {
        super.onCreate()
        UHLog.init(this)
        try {
            startService(Intent(this, SessionService::class.java))
        } catch (_: Exception) {}
    }
}
