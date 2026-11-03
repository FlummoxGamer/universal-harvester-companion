package app.harvester.companion.components

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.state.store.BrowserStore

class Components(
    val context: Context
) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val engine: GeckoEngine by lazy {
        val runtime = app.harvester.companion.GeckoEngine.getRuntime(context)
            ?: throw IllegalStateException("GeckoRuntime initialization failed")
        GeckoEngine(context, runtime = runtime)
    }

    val store: BrowserStore by lazy {
        BrowserStore()
    }

    val useCases by lazy { UseCases(store, engine) }
}
