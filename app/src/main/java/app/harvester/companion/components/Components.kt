package app.harvester.companion.components

import android.content.Context
import android.content.res.Configuration
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.ThemePrefs
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.state.engine.EngineMiddleware
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.mediaquery.PreferredColorScheme
import mozilla.components.feature.contextmenu.ContextMenuUseCases
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.tabs.TabsUseCases
import org.mozilla.geckoview.GeckoRuntime

class Components(val context: Context) {

    val appStore: AppStore by lazy { AppStore() }

    val runtime: GeckoRuntime by lazy {
        GeckoRuntime.create(context.applicationContext)
    }

    val engine: Engine by lazy {
        GeckoEngine(
            context.applicationContext,
            defaultSettings = buildDefaultSettings(),
            runtime = runtime,
        )
    }

    val store: BrowserStore by lazy {
        BrowserStore(
            middleware = EngineMiddleware.create(engine, trimMemoryAutomatically = false)
        )
    }

    val useCases: UseCases by lazy { UseCases(store, engine) }

    private fun buildDefaultSettings(): DefaultSettings {
        val textScale = SettingsPrefs.getInt(context, SettingsPrefs.K_TEXT_SCALE, 100)
        return DefaultSettings(
            remoteDebuggingEnabled = false,
            testingModeEnabled = false,
            preferredColorScheme = buildPreferredColorScheme(),
            automaticFontSizeAdjustment = textScale != 100,
            fontInflationEnabled = textScale != 100,
            suspendMediaWhenInactive = false,
            forceUserScalableContent = true,
            loginAutofillEnabled = false,
            enterpriseRootsEnabled = false,
            globalPrivacyControlEnabled = SettingsPrefs.getBool(context, SettingsPrefs.K_ADV_GPC, false),
        )
    }

    private fun buildPreferredColorScheme(): PreferredColorScheme {
        return when (ThemePrefs.getMode(context)) {
            ThemePrefs.MODE_LIGHT -> PreferredColorScheme.Light
            ThemePrefs.MODE_DARK -> PreferredColorScheme.Dark
            else -> {
                val night = (context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                if (night) PreferredColorScheme.Dark else PreferredColorScheme.Light
            }
        }
    }
}

class UseCases(store: BrowserStore, engine: Engine) {
    val sessionUseCases by lazy { SessionUseCases(store) }
    val tabsUseCases by lazy { TabsUseCases(store) }
    val contextMenuUseCases by lazy { ContextMenuUseCases(store) }
}
