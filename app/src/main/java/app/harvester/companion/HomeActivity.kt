package app.harvester.companion

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.fragment.NavHostFragment
import app.harvester.companion.browser.BrowserViewModel
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

val browserViewModel: BrowserViewModel by viewModels()
onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
    override fun handleOnBackPressed() {
        if (browserViewModel.isEditing.value) {
            browserViewModel.exitEditMode()
        } else {
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
            isEnabled = true
        }
    }
})

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
