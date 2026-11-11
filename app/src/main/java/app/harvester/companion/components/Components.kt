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
import mozilla.components.feature.downloads.DownloadsUseCases
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.support.utils.DefaultDownloadFileUtils
import mozilla.components.support.utils.DownloadFileUtils
import android.os.Environment
import app.harvester.companion.core.downloads.UhDownloadMiddleware
import app.harvester.companion.core.downloads.UhDownloadService
import app.harvester.companion.data.downloads.UhDownloadStorage
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

val downloadFileUtils: DownloadFileUtils by lazy {
    DefaultDownloadFileUtils(context) {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
    }
}

val downloadStorage: UhDownloadStorage by lazy { UhDownloadStorage(context) }

val downloadMiddleware: UhDownloadMiddleware by lazy {
    UhDownloadMiddleware(
        applicationContext = context,
        downloadServiceClass = UhDownloadService::class.java,
        deleteFileFromStorage = { true },
        downloadFileUtils = downloadFileUtils,
        downloadStorage = downloadStorage
    )
}

val client by lazy { org.mozilla.components.browser.engine.gecko.fetch.GeckoViewFetchClient(context, runtime) }
val fileSizeFormatter by lazy { mozilla.components.feature.downloads.DefaultFileSizeFormatter(context) }
val downloadEstimator by lazy { mozilla.components.feature.downloads.DownloadEstimator(mozilla.components.support.utils.DefaultDateTimeProvider()) }
val notificationsDelegate by lazy { 
    object : mozilla.components.support.base.android.NotificationsDelegate {
        override fun notify(notificationId: Int, notification: android.app.Notification) {
            androidx.core.app.NotificationManagerCompat.from(context).notify(notificationId, notification)
        }
    }
}

val store: BrowserStore by lazy {
    val middlewares = mutableListOf<mozilla.components.lib.state.Middleware<mozilla.components.browser.state.state.BrowserState, mozilla.components.browser.state.action.BrowserAction>>()
    middlewares.addAll(EngineMiddleware.create(engine, trimMemoryAutomatically = false))
    middlewares.add(downloadMiddleware)
    BrowserStore(middleware = middlewares)
}

val useCases: UseCases by lazy { UseCases(store, engine, downloadFileUtils) }

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

class UseCases(store: BrowserStore, engine: Engine, downloadFileUtils: DownloadFileUtils) {
    val sessionUseCases by lazy { SessionUseCases(store) }
    val tabsUseCases by lazy { TabsUseCases(store) }
    val contextMenuUseCases by lazy { ContextMenuUseCases(store) }
    val downloadsUseCases by lazy { DownloadsUseCases(store, downloadFileUtils) }
}
