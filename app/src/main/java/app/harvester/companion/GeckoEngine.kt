package app.harvester.companion

import android.content.Context
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

object GeckoEngine {
    @Volatile
    private var runtime: GeckoRuntime? = null

    fun getRuntime(ctx: Context): GeckoRuntime? {
        if (runtime == null) {
            synchronized(this) {
                if (runtime == null) {
                    val prefs = ctx.getSharedPreferences("uh_safe_mode", Context.MODE_PRIVATE)
                    val safeMode = prefs.getBoolean("force_safe_mode", false)
                    if (safeMode) {
                        UHLog.e("gecko", "Safe mode enabled. GeckoRuntime will not start.")
                        return null
                    }
                    
                    try {
                        val settings = GeckoRuntimeSettings.Builder()
                            .consoleOutput(true)
                            .debugLogging(true)
                            .build()
                            
                        val rt = GeckoRuntime.create(ctx, settings)
                        rt.delegate = object : GeckoRuntime.Delegate {
                            override fun onShutdown() {
                                UHLog.e("gecko", "GeckoRuntime shut down unexpectedly.")
                            }
                        }
                        runtime = rt
                        
                        rt.webExtensionController.ensureBuiltIn(
                            "resource://android/assets/web_extensions/uh_bridge/",
                            "uh-bridge@harvester.app"
                        ).accept({ ext ->
                            if (ext != null) {
                                rt.webExtensionController.enable(ext, org.mozilla.geckoview.WebExtensionController.EnableSource.APP)
                            }
                        }, { error ->
                            UHLog.e("gecko", "Failed to load UH Bridge extension: ${error?.message}")
                        })
                        
                        rt.webExtensionController.ensureBuiltIn(
                            "resource://android/assets/web_extensions/header_injector/",
                            "header-injector@harvester.app"
                        ).accept({ ext ->
                            if (ext != null) {
                                rt.webExtensionController.enable(ext, org.mozilla.geckoview.WebExtensionController.EnableSource.APP)
                            }
                        }, { error ->
                            UHLog.e("gecko", "Failed to load header injector: ${error?.message}")
                        })
                        
                    } catch (e: Throwable) {
                        UHLog.e("gecko", "GeckoRuntime init failed: ${e.message}")
                        UHLog.throwable("gecko", "GeckoRuntime init failed", e)
                        throw e
                    }
                }
            }
        }
        return runtime
    }

    fun clearBootMarker(ctx: Context) {
        try {
            val bootMarker = java.io.File(ctx.filesDir, "boot_marker.tmp")
            if (bootMarker.exists()) {
                bootMarker.delete()
            }
        } catch (_: Exception) {}
    }
}
