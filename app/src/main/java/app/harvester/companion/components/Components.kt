package app.harvester.companion.components

import android.content.Context
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.state.engine.EngineMiddleware
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.concept.engine.Engine
import org.mozilla.geckoview.GeckoRuntime

class Components(val context: Context) {
    val runtime: GeckoRuntime by lazy { GeckoRuntime.create(context.applicationContext) }
    val engine: Engine by lazy { GeckoEngine(context.applicationContext, runtime = runtime) }
    val store: BrowserStore by lazy {
        BrowserStore(
            middleware = EngineMiddleware.create(engine)
        )
    }
    val useCases by lazy { UseCases(store, engine) }
}

class UseCases(store: BrowserStore, engine: Engine) {
    val sessionUseCases by lazy { SessionUseCases(store) }
    val tabsUseCases by lazy { TabsUseCases(store) }
}
