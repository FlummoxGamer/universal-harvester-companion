package app.harvester.companion

import android.content.Context
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings

object GeckoEngine {

    @Volatile private var runtimeRef: GeckoRuntime? = null

    fun init(ctx: Context): GeckoRuntime {
        runtimeRef?.let { return it }
        synchronized(this) {
            runtimeRef?.let { return it }
            val settings = GeckoRuntimeSettings.Builder()
                .aboutConfigEnabled(false)
                .remoteDebuggingEnabled(false)
                .build()
            val rt = GeckoRuntime.create(ctx.applicationContext, settings)
            UHLog.d("gecko", "runtime created")
            runtimeRef = rt
            return rt
        }
    }

    fun runtime(): GeckoRuntime =
        runtimeRef ?: error("GeckoEngine not initialised — call init() first")

    fun newSession(private: Boolean = false): GeckoSession {
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(private)
            .useTrackingProtection(true)
            .build()
        val session = GeckoSession(settings)
        session.open(runtime())
        UHLog.v("gecko", "session opened private=$private")
        return session
    }
}
