package app.harvester.companion.browser.webapp

import android.app.ActivityManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import app.harvester.companion.HomeActivity
import app.harvester.companion.R
import app.harvester.companion.UHApp
import app.harvester.companion.theme.UHTheme
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineView
import java.io.File
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * Standalone chrome-free activity for installed Progressive Web Apps (PWAs).
 * Uses GeckoEngine directly with manifest themeColor, icon TaskDescription,
 * back navigation, and clean fullscreen/standalone presentation.
 */
class WebAppActivity : AppCompatActivity() {

    companion object {
        const val ACTION_LAUNCH_WEBAPP = "app.harvester.companion.action.LAUNCH_WEBAPP"
        const val EXTRA_WEBAPP_ID = "extra_webapp_id"
        const val EXTRA_START_URL = "extra_start_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_DISPLAY_MODE = "extra_display_mode"
        const val EXTRA_THEME_COLOR = "extra_theme_color"
        const val EXTRA_BG_COLOR = "extra_bg_color"
    }

    private var engineView: EngineView? = null
    private var engineSession: EngineSession? = null

    private var canGoBackState by mutableStateOf(false)
    private var currentUrlState by mutableStateOf("")
    private var currentTitleState by mutableStateOf("")
    private var progressState by mutableIntStateOf(0)
    private var isLoadingState by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val components = (application as UHApp).components
        val webAppId = intent.getStringExtra(EXTRA_WEBAPP_ID)
        val stored = webAppId?.let { WebAppStore.findById(this, it) }

        val startUrl = intent.getStringExtra(EXTRA_START_URL)?.ifBlank { null }
            ?: intent.dataString?.ifBlank { null }
            ?: stored?.startUrl
            ?: run {
                finish()
                return
            }

        val appTitle = intent.getStringExtra(EXTRA_TITLE)?.ifBlank { null }
            ?: stored?.name?.ifBlank { null }
            ?: "Web App"

        val displayMode = intent.getStringExtra(EXTRA_DISPLAY_MODE)?.ifBlank { null }
            ?: stored?.displayMode
            ?: "STANDALONE"

        val themeColorInt: Int? = when {
            intent.hasExtra(EXTRA_THEME_COLOR) -> intent.getIntExtra(EXTRA_THEME_COLOR, Color.BLACK)
            stored?.themeColor != null -> stored.themeColor
            else -> null
        }

        val isMinimalUi = displayMode.equals("MINIMAL_UI", ignoreCase = true)

        currentUrlState = startUrl
        currentTitleState = appTitle

        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        configureTaskDescription(appTitle, stored?.iconPath, themeColorInt)

        val session = components.engine.createSession(private = false)
        val view = components.engine.createView(this, null)
        engineSession = session
        engineView = view

        session.register(object : EngineSession.Observer {
            override fun onLocationChange(url: String, hasUserGesture: Boolean) {
                runOnUiThread { currentUrlState = url }
            }

            override fun onTitleChange(title: String) {
                if (title.isNotBlank()) {
                    runOnUiThread { currentTitleState = title }
                }
            }

            override fun onProgress(progress: Int) {
                runOnUiThread { progressState = progress }
            }

            override fun onLoadingStateChange(loading: Boolean) {
                runOnUiThread { isLoadingState = loading }
            }

            override fun onNavigationStateChange(canGoBack: Boolean?, canGoForward: Boolean?) {
                if (canGoBack != null) {
                    runOnUiThread { canGoBackState = canGoBack }
                }
            }
        })

        view.render(session)
        session.loadUrl(startUrl)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (canGoBackState) {
                    engineSession?.goBack()
                } else {
                    finish()
                }
            }
        })

        val barBgColor = themeColorInt?.let { ComposeColor(it) }
        val lightStatusOverride = barBgColor?.let { it.luminance() > 0.5f }

        setContent {
            UHTheme(lightStatusBarOverride = lightStatusOverride) {
                val resolvedTopBg = barBgColor ?: MaterialTheme.colorScheme.background
                val resolvedTopFg = if (resolvedTopBg.luminance() > 0.5f) {
                    ComposeColor(0xFF12161E)
                } else {
                    ComposeColor(0xFFF3F6FA)
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(resolvedTopBg)
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
                    if (isMinimalUi) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(resolvedTopBg)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    if (canGoBackState) engineSession?.goBack() else finish()
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.uh_ic_back_24),
                                    contentDescription = "Back",
                                    tint = resolvedTopFg,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = currentTitleState,
                                color = resolvedTopFg,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { engineSession?.reload() },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.uh_ic_reload_24),
                                    contentDescription = "Reload",
                                    tint = resolvedTopFg,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = { openInMainBrowser() },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.uh_ic_open_in_app_24),
                                    contentDescription = "Open in UH Browser",
                                    tint = resolvedTopFg,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        AndroidView(
                            factory = { view.asView() },
                            modifier = Modifier.fillMaxSize()
                        )

                        if (isLoadingState && progressState in 1..99) {
                            LinearProgressIndicator(
                                progress = { progressState / 100f },
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = ComposeColor.Transparent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.5.dp)
                                    .align(Alignment.TopCenter)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun openInMainBrowser() {
        val target = currentUrlState.ifBlank { return }
        startActivity(
            Intent(this, HomeActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(target)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    private fun configureTaskDescription(title: String, iconPath: String?, themeColor: Int?) {
        try {
            val iconBitmap = iconPath?.let { path ->
                val f = File(path)
                if (f.isFile) BitmapFactory.decodeFile(path) else null
            }
            val color = themeColor ?: Color.parseColor("#0D1117")
            val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityManager.TaskDescription.Builder()
                    .setLabel(title)
                    .setIcon(R.mipmap.ic_launcher)
                    .setPrimaryColor(color)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                if (iconBitmap != null) {
                    ActivityManager.TaskDescription(title, iconBitmap, color)
                } else {
                    ActivityManager.TaskDescription(title, R.mipmap.ic_launcher, color)
                }
            }
            setTaskDescription(desc)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try {
            engineView?.release()
        } catch (_: Exception) {}
        try {
            engineView?.onDestroy()
        } catch (_: Exception) {}
        try {
            engineSession?.close()
        } catch (_: Exception) {}
        engineView = null
        engineSession = null
        super.onDestroy()
    }
}
