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
import mozilla.components.support.utils.DefaultDownloadFileUtils
import org.mozilla.geckoview.GeckoRuntime

class Components(
    val context: Context
) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val geckoRuntime: GeckoRuntime by lazy {
        GeckoRuntime.create(context)
    }

    val engine: GeckoEngine by lazy {
        val settings = DefaultSettings(
            historyTrackingDelegate = null
        )
        GeckoEngine(context, settings, geckoRuntime)
    }

    val store: BrowserStore by lazy {
        BrowserStore(
            middleware = listOf(
                DownloadMiddleware(
                    applicationContext = context,
                    downloadServiceClass = app.harvester.companion.DownloadService::class.java,
                    deleteFileFromStorage = { false },
                    downloadFileUtils = DefaultDownloadFileUtils(context, { null })
                )
            ) + EngineMiddleware.create(engine)
        )
    }

    val useCases by lazy { UseCases(store, engine) }
}
