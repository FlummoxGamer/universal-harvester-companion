package app.harvester.companion

import android.app.Application

class UHApp : Application() {
    override fun onCreate() {
        super.onCreate()
        UHLog.init(this)
    }
}
