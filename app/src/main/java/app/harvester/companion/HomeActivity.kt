package app.harvester.companion

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
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

class HomeActivity : AppCompatActivity() {

    private val browserViewModel: BrowserViewModel by viewModels()
    private var navControllerState by mutableStateOf<NavController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

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

                    if (controller != null) {
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
        if (!url.startsWith("http")) {
            UHLog.w("ui_warn", "Ignored non-http intent URL: $url")
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }
}
