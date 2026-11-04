package app.harvester.companion

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentContainerView
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.harvester.companion.browser.BrowserFragment
import app.harvester.companion.home.HomeFragment
import app.harvester.companion.ui.StartupFragment

class HomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = "startup") {
                        composable("startup") { StartupFragment(navController) }
                        composable("home") { HomeFragment() }
                        composable("browser") {
                            AndroidView(
                                factory = { ctx ->
                                    FragmentContainerView(ctx).apply {
                                        id = 98765
                                    }
                                },
                                update = { view ->
                                    val fm = supportFragmentManager
                                    val existing = fm.findFragmentById(view.id)
                                    if (existing == null) {
                                        fm.beginTransaction()
                                            .replace(view.id, BrowserFragment())
                                            .commitAllowingStateLoss()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
