package app.harvester.companion

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentContainerView
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.fragment.NavHostFragment
import app.harvester.companion.browser.BrowserFragment
import app.harvester.companion.browser.BrowserViewModel
import app.harvester.companion.browser.toolbar.UhGlobalToolbar
import app.harvester.companion.theme.UHTheme
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.concept.engine.mediasession.MediaSession

class HomeActivity : AppCompatActivity() {

    private val browserViewModel: BrowserViewModel by viewModels()
    private var navControllerState by mutableStateOf<NavController?>(null)
    private var isInPipMode by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )

        setContent {
            val controller = navControllerState
            val backStackEntry = controller?.currentBackStackEntryAsState()?.value
            val currentDestId = backStackEntry?.destination?.id
            val isEditing by browserViewModel.isEditing.collectAsState()
            val wallpaperState by WallpaperStore.wallpaperFlow(this).collectAsState()
            val isOnHome = (currentDestId == R.id.homeFragment || currentDestId == null) && !isEditing
            val lightStatusOverride = if (isOnHome) wallpaperState.isTopDark?.let { !it } else null
            val lightNavOverride = if (isOnHome) wallpaperState.isBottomDark?.let { !it } else null

            UHTheme(
                lightStatusBarOverride = lightStatusOverride,
                lightNavigationBarOverride = lightNavOverride
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { context ->
                            FragmentContainerView(context).apply {
                                id = R.id.nav_host_fragment
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = {
                            try {
                                val existing = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
                                val host = if (existing == null) {
                                    val created = NavHostFragment.create(R.navigation.nav_graph)
                                    supportFragmentManager.beginTransaction()
                                        .replace(R.id.nav_host_fragment, created)
                                        .setPrimaryNavigationFragment(created)
                                        .commitNow()
                                    created
                                } else {
                                    existing
                                }
                                val navCtrl = host.navController
                                if (navControllerState !== navCtrl) {
                                    navControllerState = navCtrl
                                    handleIntent(intent, navCtrl)
                                }
                            } catch (e: Exception) {
                                UHLog.throwable("ui_error", "Failed to initialize NavHostFragment/NavController", e)
                            }
                        }
                    )

                    if (controller != null && !isInPipMode) {
                        UhGlobalToolbar(
                            viewModel = browserViewModel,
                            navController = controller,
                            onDismiss = { browserViewModel.exitEditMode() },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        android.os.Looper.myQueue().addIdleHandler {
            try {
                (application as? UHApp)?.components?.engine
            } catch (_: Exception) {}
            false
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val controller = navControllerState
                if (browserViewModel.isEditing.value) {
                    browserViewModel.exitEditMode()
                    return
                }
                if (browserViewModel.showFindInPage.value) {
                    browserViewModel.closeFindInPage()
                    return
                }
                if (browserViewModel.showZoomBar.value) {
                    browserViewModel.closeZoomBar()
                    return
                }
                val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
                val activeBrowser = navHost?.childFragmentManager?.fragments?.firstOrNull { it is BrowserFragment && it.isVisible } as? BrowserFragment
                if (activeBrowser != null) {
                    if (activeBrowser.onBackPressed()) {
                        return
                    }
                    if (controller != null && controller.currentDestination?.id == R.id.browserFragment) {
                        controller.popBackStack()
                        return
                    }
                }
                if (controller != null && controller.currentDestination?.id != R.id.homeFragment) {
                    if (controller.popBackStack()) {
                        return
                    }
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }

    fun navigateFromMenu(destinationId: Int) {
        window?.decorView?.post {
            val controller = navControllerState
                ?: (supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment)?.navController
                ?: return@post
            try {
                if (controller.currentDestination?.id == destinationId) return@post
                val options = NavOptions.Builder()
                    .setLaunchSingleTop(true)
                    .build()
                controller.navigate(destinationId, null, options)
            } catch (e: Exception) {
                UHLog.throwable("ui_error", "Menu navigation failed for destination $destinationId", e)
            }
        }
    }

    private fun handleIntent(intent: Intent?, controller: NavController? = navControllerState) {
        val url = intent?.data?.toString() ?: return
        val isSupportedScheme = url.startsWith("http://") ||
            url.startsWith("https://") ||
            url.startsWith("moz-extension://")
        if (!isSupportedScheme) {
            UHLog.w("ui_warn", "Ignored unsupported intent URL: $url")
            return
        }
        if (controller == null) {
            UHLog.w("ui_warn", "Cannot handle intent URL before NavController is ready: $url")
            return
        }
        try {
            if (controller.currentDestination?.id == R.id.homeFragment) {
                val bundle = Bundle().apply { putString("url", url) }
                controller.navigate(R.id.action_homeFragment_to_browserFragment, bundle)
            } else {
                (application as? UHApp)?.components?.useCases?.sessionUseCases?.loadUrl(url)
            }
        } catch (e: Exception) {
            UHLog.throwable("ui_error", "Failed to navigate for intent URL: $url", e)
        }
    }

    override fun onResume() {
        super.onResume()
        (application as? UHApp)?.components?.resumePendingNetworkDownloads()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!SettingsPrefs.getBool(this, SettingsPrefs.K_PIP_ENABLED, true)) return
        val controller = navControllerState
        if (controller?.currentDestination?.id != R.id.browserFragment) return
        val tab = (application as? UHApp)?.components?.store?.state?.selectedTab ?: return
        val isPlayingMedia = tab.mediaSessionState?.playbackState == MediaSession.PlaybackState.PLAYING
        if (tab.content.fullScreen || isPlayingMedia) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            } catch (e: Exception) {
                UHLog.w("media", "PiP entry skipped: ${e.message}")
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }
}
