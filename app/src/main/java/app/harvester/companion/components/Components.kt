package app.harvester.companion.components

import android.content.Context
import android.content.res.Configuration
import android.os.Environment
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.harvester.companion.BuildConfig
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.ThemePrefs
import app.harvester.companion.browser.storage.sync.GlobalPlacesDependencyProvider
import app.harvester.companion.browser.storage.sync.PlacesBookmarksStorage
import app.harvester.companion.browser.storage.sync.PlacesHistoryStorage
import app.harvester.companion.browser.storage.sync.PlacesHistoryStorageWorker
import app.harvester.companion.core.downloads.UhDownloadMiddleware
import app.harvester.companion.core.downloads.UhDownloadService
import app.harvester.companion.data.downloads.UhDownloadStorage
import app.harvester.companion.feature.session.HistoryDelegate
import app.harvester.companion.service.nimbus.Nimbus
import app.harvester.companion.service.nimbus.NimbusAppInfo
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.engine.gecko.fetch.GeckoViewFetchClient
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.engine.EngineMiddleware
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.mediaquery.PreferredColorScheme
import mozilla.components.concept.fetch.Client
import mozilla.components.feature.contextmenu.ContextMenuUseCases
import mozilla.components.feature.downloads.DefaultFileSizeFormatter
import mozilla.components.feature.downloads.DownloadEstimator
import mozilla.components.feature.downloads.DownloadsUseCases
import mozilla.components.feature.downloads.FileSizeFormatter
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.lib.state.Middleware
import mozilla.components.support.base.android.NotificationsDelegate
import mozilla.components.support.utils.DefaultDateTimeProvider
import mozilla.components.support.utils.DefaultDownloadFileUtils
import mozilla.components.support.utils.DownloadFileUtils
import org.mozilla.geckoview.GeckoRuntime

class Components(val context: Context) {

    val appStore: AppStore by lazy { AppStore() }

    val runtime: GeckoRuntime by lazy {
        GeckoRuntime.create(context.applicationContext)
    }

    val client: Client by lazy {
        GeckoViewFetchClient(context.applicationContext, runtime)
    }

    val geckoSitePermissionsStorage: mozilla.components.feature.sitepermissions.SitePermissionsStorage by lazy {
        mozilla.components.feature.sitepermissions.OnDiskSitePermissionsStorage(context.applicationContext)
    }

    val settings by lazy { app.harvester.companion.utils.Settings(context.applicationContext) }

    val placesHistoryStorage: PlacesHistoryStorage by lazy {
        PlacesHistoryStorage(context.applicationContext)
    }

    val placesBookmarksStorage: PlacesBookmarksStorage by lazy {
        PlacesBookmarksStorage(context.applicationContext)
    }

    val historyDelegate: HistoryDelegate by lazy {
        HistoryDelegate(lazy { placesHistoryStorage })
    }

    val nimbus: Nimbus by lazy {
        Nimbus(
            context = context.applicationContext,
            appInfo = NimbusAppInfo("uh", BuildConfig.VERSION_NAME),
            server = null,
            recordedContext = null
        )
    }

    init {
        GlobalPlacesDependencyProvider.initialize(placesHistoryStorage)
        placesHistoryStorage.registerStorageMaintenanceWorker()
    }

    val fileSizeFormatter: FileSizeFormatter by lazy {
        DefaultFileSizeFormatter(context.applicationContext)
    }

    val downloadEstimator: DownloadEstimator by lazy {
        DownloadEstimator(DefaultDateTimeProvider())
    }

    val notificationsDelegate: NotificationsDelegate by lazy {
        NotificationsDelegate(NotificationManagerCompat.from(context.applicationContext))
    }

    val engine: Engine by lazy {
        GeckoEngine(
            context.applicationContext,
            defaultSettings = buildDefaultSettings(),
            runtime = runtime,
        )
    }

    val icons by lazy {
        mozilla.components.browser.icons.BrowserIcons(context.applicationContext, client)
    }

    val publicSuffixList by lazy {
        mozilla.components.lib.publicsuffixlist.PublicSuffixList(context.applicationContext)
    }

    val downloadFileUtils: DownloadFileUtils by lazy {
        DefaultDownloadFileUtils(context.applicationContext) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
        }
    }

    val downloadStorage: UhDownloadStorage by lazy { UhDownloadStorage(context.applicationContext) }

    val downloadMiddleware: UhDownloadMiddleware by lazy {
        UhDownloadMiddleware(
            applicationContext = context.applicationContext,
            downloadServiceClass = UhDownloadService::class.java,
            deleteFileFromStorage = { 
                app.harvester.companion.SettingsPrefs.getString(context, app.harvester.companion.SettingsPrefs.K_DELETE_BEHAVIOR, "ask") == "device"
            },
            downloadFileUtils = downloadFileUtils,
            downloadStorage = downloadStorage
        )
    }

    val store: BrowserStore by lazy {
        val middlewareList = listOf<Middleware<BrowserState, BrowserAction>>(downloadMiddleware)
        val browserStore = BrowserStore(
            middleware = middlewareList + EngineMiddleware.create(engine, trimMemoryAutomatically = false)
        )
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        try {
            connectivityManager.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    browserStore.state.downloads.values.filter { it.status == mozilla.components.browser.state.state.content.DownloadState.Status.FAILED }.forEach { download ->
                        val intent = android.content.Intent(context, UhDownloadService::class.java)
                        intent.putExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, download.id)
                        intent.action = mozilla.components.feature.downloads.AbstractFetchDownloadService.ACTION_TRY_AGAIN
                        ContextCompat.startForegroundService(context, intent)
                    }
                }
            })
        } catch (e: Exception) {}
        browserStore
    }

    val useCases: UseCases by lazy { UseCases(store, engine, downloadFileUtils) }

    private fun buildDefaultSettings(): DefaultSettings {
        val textScale = SettingsPrefs.getInt(context, SettingsPrefs.K_TEXT_SCALE, 100)
        return DefaultSettings(
            remoteDebuggingEnabled = false,
            testingModeEnabled = false,
            downloadDelegate = mozilla.components.browser.engine.gecko.util.EngineDownloadDelegate(
                context = context.applicationContext,
                downloadLocation = {
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).path
                }
            ),
            preferredColorScheme = buildPreferredColorScheme(),
            automaticFontSizeAdjustment = textScale != 100,
            fontInflationEnabled = textScale != 100,
            suspendMediaWhenInactive = false,
            forceUserScalableContent = true,
            loginAutofillEnabled = false,
            enterpriseRootsEnabled = false,
            globalPrivacyControlEnabled = SettingsPrefs.getBool(context, SettingsPrefs.K_ADV_GPC, false),
            historyTrackingDelegate = historyDelegate,
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
    val searchUseCases by lazy { mozilla.components.feature.search.SearchUseCases(store, tabsUseCases, sessionUseCases) }
    val trackingProtectionUseCases by lazy { mozilla.components.feature.session.TrackingProtectionUseCases(store) }
}
