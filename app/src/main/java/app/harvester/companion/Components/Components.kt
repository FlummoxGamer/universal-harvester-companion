package app.harvester.companion.components

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.state.engine.EngineMiddleware
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.feature.downloads.DownloadMiddleware
import mozilla.components.feature.session.HistoryDelegate
import org.mozilla.geckoview.GeckoRuntime

class Components(
    val context: Context
) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val geckoRuntime: GeckoRuntime by lazy {
        org.mozilla.geckoview.GeckoRuntime.getDefault(context)
    }

    val engine: GeckoEngine by lazy {
        val settings = DefaultSettings(
            historyTrackingDelegate = HistoryDelegate { null }
        )
        GeckoEngine(context, settings, geckoRuntime)
    }

    val store: BrowserStore by lazy {
        BrowserStore(
            middleware = listOf(
                DownloadMiddleware(context, "app.harvester.companion.DownloadService")
            ) + EngineMiddleware.create(engine)
        )
    }

    val useCases by lazy { UseCases(context, store, engine) }
}
