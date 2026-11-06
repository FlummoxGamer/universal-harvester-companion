package app.harvester.companion

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.fragment.NavHostFragment
import app.harvester.companion.databinding.ActivityHomeBinding

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            showComposeSplash()
        } else {
            binding.splashScreen.visibility = View.GONE
        }

        handleIntent(intent)
    }

    private fun showComposeSplash() {
        val composeView = androidx.compose.ui.platform.ComposeView(this).apply {
            setContent {
                app.harvester.companion.splash.ComposeSplashScreen(
                    onSplashFinished = {
                        binding.splashScreen.visibility = View.GONE
                    }
                )
            }
        }
        binding.splashScreen.removeAllViews()
        binding.splashScreen.addView(composeView)
        binding.splashScreen.visibility = View.VISIBLE
    }

    private fun handleIntent(intent: Intent?) {
        val url = intent?.data?.toString() ?: return
        if (url.startsWith("http")) {
            val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            val navController = navHostFragment.navController
            if (navController.currentDestination?.id == R.id.homeFragment) {
                val action = app.harvester.companion.home.HomeFragmentDirections.actionHomeFragmentToBrowserFragment(url)
                navController.navigate(action)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }
    
    override fun onBackPressed() {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val currentFragment = navHostFragment.childFragmentManager.primaryNavigationFragment
        if (currentFragment is app.harvester.companion.browser.BrowserFragment) {
            if (!currentFragment.onBackPressed()) {
                navHostFragment.navController.popBackStack()
            }
        } else {
            super.onBackPressed()
        }
    }
}
