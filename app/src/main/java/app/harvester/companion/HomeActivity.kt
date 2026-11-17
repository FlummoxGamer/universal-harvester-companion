package app.harvester.companion

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentContainerView
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import app.harvester.companion.browser.BrowserFragment
import app.harvester.companion.browser.BrowserViewModel
import app.harvester.companion.browser.toolbar.UhGlobalToolbar
import app.harvester.companion.theme.UHTheme

class HomeActivity : AppCompatActivity() {

    private val browserViewModel: BrowserViewModel by viewModels()
    private var activeNavController: NavController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContent {
            UHTheme {
                var navControllerState by remember { mutableStateOf<NavController?>(null) }

                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { context ->
                            FragmentContainerView(context).apply {
                                id = R.id.nav_host_fragment
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = {
                            if (supportFragmentManager.findFragmentById(R.id.nav_host_fragment) == null) {
                                try {
                                    val navHostFragment = NavHostFragment.create(R.navigation.nav_graph)
                                    supportFragmentManager.beginTransaction()
                                        .replace(R.id.nav_host_fragment, navHostFragment)
                                        .setPrimaryNavigationFragment(navHostFragment)
                                        .commitNow()
                                    val controller = navHostFragment.navController
                                    activeNavController = controller
                                    navControllerState = controller
                                    handleIntent(intent)
                                } catch (e: Exception) {
                                    UHLog.e("ui_error", "Failed to initialize NavHostFragment: ${e.message}", e)
                                }
                            }
                        }
                    )

                    val currentNav = navControllerState
                    if (currentNav != null) {
                        UhGlobalToolbar(
                            viewModel = browserViewModel,
                            navController = currentNav,
                            onDismiss = { browserViewModel.exitEditMode() },
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(10f)
                        )
                    } else {
                        LaunchedEffect(Unit) {
                            kotlinx.coroutines.delay(1500)
                            if (navControllerState == null) {
                                UHLog.e("ui_error", "Address Bar failed: NavController timed out.")
                            }
                        }
                    }
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val nav = activeNavController
                if (browserViewModel.isEditing.value) {
                    browserViewModel.exitEditMode()
                } else if (nav != null && nav.currentDestination?.id == R.id.browserFragment) {
                    val currentFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
                        ?.childFragmentManager?.primaryNavigationFragment
                    if (currentFragment is BrowserFragment) {
                        if (!currentFragment.onBackPressed()) {
                            nav.popBackStack()
                        }
                    } else {
                        nav.popBackStack()
                    }
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun handleIntent(intent: Intent?) {
        val url = intent?.data?.toString() ?: return
        val nav = activeNavController
        if (nav == null) {
            UHLog.w("ui_warn", "Intent received before NavController ready: $url")
            return
        }
        if (url.startsWith("http") && nav.currentDestination?.id == R.id.homeFragment) {
            val bundle = Bundle().apply { putString("url", url) }
            nav.navigate(R.id.action_homeFragment_to_browserFragment, bundle)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }
}
