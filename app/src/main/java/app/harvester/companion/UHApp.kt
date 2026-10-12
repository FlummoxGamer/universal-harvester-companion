package app.harvester.companion

import android.app.Application
import android.content.Context

class UHApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        xcrash.XCrash.init(this)
    }

    override fun onCreate() {
        super.onCreate()
        UHLog.init(this)
        GeckoEngine.init(this)
    }
}
