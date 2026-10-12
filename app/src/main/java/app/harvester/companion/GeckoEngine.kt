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
    .crashHandler(object : GeckoRuntime.CrashHandler {
        override fun onCrash() {
            UHLog.section("GECKO NATIVE CRASH")
            UHLog.e("gecko", "native crash detected by GeckoRuntime")
        }
    })
    .build()
            val rt = GeckoRuntime.create(ctx.applicationContext, settings)
            UHLog.d("gecko", "runtime created")
            runtimeRef = rt
            return rt
        }
    }

    fun runtime(): GeckoRuntime =
        runtimeRef ?: error("GeckoEngine not initialised — call init() first")

    /**
     * FIX: restoreState must be called BEFORE open(), otherwise GeckoView throws
     * IllegalStateException("Session is open"). The caller passes state in, and this
     * function sequences restore -> open correctly.
     */
    fun newSession(
        private: Boolean = false,
        restoreState: GeckoSession.SessionState? = null
    ): GeckoSession {
        val settings = GeckoSessionSettings.Builder()
    .usePrivateMode(private)
    .build()
UHLog.d("gecko", "settings built")
val session = GeckoSession(settings)
UHLog.d("gecko", "session constructed")
        if (restoreState != null) {
            try {
                session.restoreState(restoreState)
                UHLog.v("gecko", "session state restored")
            } catch (e: Exception) {
                UHLog.w("gecko", "restoreState failed: ${e.message}")
            }
        }
        UHLog.d("gecko", "calling open")
session.open(runtime())
UHLog.d("gecko", "open returned")
return session
    }
}
