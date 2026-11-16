package app.harvester.companion

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.zIndex
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.SideEffect
import android.graphics.drawable.ColorDrawable
import android.graphics.Color
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color as ComposeColor
import app.harvester.companion.UHLog
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentContainerView
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import app.harvester.companion.browser.BrowserViewModel
import app.harvester.companion.browser.toolbar.UhGlobalToolbar
import app.harvester.companion.theme.UHTheme

class HomeActivity : AppCompatActivity() {

    private val browserViewModel: BrowserViewModel by viewModels()
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        setContent {
            UHTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { context ->
                            FragmentContainerView(context).apply {
                                id = R.id.nav_host_fragment
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = { view ->
                            if (supportFragmentManager.findFragmentById(R.id.nav_host_fragment) == null) {
                                val navHostFragment = NavHostFragment.create(R.navigation.nav_graph)
                                supportFragmentManager.beginTransaction()
                                    .replace(R.id.nav_host_fragment, navHostFragment)
                                    .setPrimaryNavigationFragment(navHostFragment)
                                    .commitNow()
                                navController = navHostFragment.navController
                                handleIntent(intent)
                            }
                        }
                    )

if (::navController.isInitialized) {
    UHLog.d("ui_debug", "NavController ready. Showing Address Bar Dialog.")
    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        val dialogWindowProvider = LocalView.current.parent as? DialogWindowProvider
        if (dialogWindowProvider == null) {
            UHLog.e("ui_error", "Address Bar failed: DialogWindowProvider is null. UI will be invisible.")
        } else {
            SideEffect {
                dialogWindowProvider.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                dialogWindowProvider.window?.setDimAmount(0f)
            }
        }
        Surface(color = ComposeColor.Transparent, modifier = Modifier.fillMaxSize()) {
            UhGlobalToolbar(
                viewModel = browserViewModel,
                navController = navController,
                onDismiss = { browserViewModel.exitEditMode() },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
} else {
    UHLog.e("ui_error", "Address Bar failed: NavController not initialized yet.")
}
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (browserViewModel.isEditing.value) {
                    browserViewModel.exitEditMode()
                } else if (::navController.isInitialized && navController.currentDestination?.id == R.id.browserFragment) {
                    val currentFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)?.childFragmentManager?.primaryNavigationFragment
                    if (currentFragment is app.harvester.companion.browser.BrowserFragment) {
                        if (!currentFragment.onBackPressed()) {
                            navController.popBackStack()
                        }
                    } else {
                        navController.popBackStack()
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
        if (url.startsWith("http")) {
            if (::navController.isInitialized && navController.currentDestination?.id == R.id.homeFragment) {
                val bundle = Bundle().apply { putString("url", url) }
                navController.navigate(R.id.action_homeFragment_to_browserFragment, bundle)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }
}
