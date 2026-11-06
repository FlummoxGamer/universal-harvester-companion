package app.harvester.companion

import android.content.Context
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoWebExecutor

@Deprecated("Legacy shim. Use Components.runtime and Components.engine. Deleted in Batch 10.")
object GeckoEngine {

    @Volatile
    private var executor: GeckoWebExecutor? = null

    fun getRuntime(ctx: Context): GeckoRuntime? {
        val app = ctx.applicationContext as? UHApp ?: return null
        val runtime = app.components.runtime
        if (executor == null) {
            synchronized(this) {
                if (executor == null) {
                    executor = GeckoWebExecutor(runtime)
                }
            }
        }
        return runtime
    }

    fun speculativeConnect(url: String) {
        executor?.speculativeConnect(url)
    }

    fun clearBootMarker(ctx: Context) {
        try {
            java.io.File(ctx.filesDir, "boot_marker.tmp").takeIf { it.exists() }?.delete()
        } catch (_: Exception) {}
    }
}
