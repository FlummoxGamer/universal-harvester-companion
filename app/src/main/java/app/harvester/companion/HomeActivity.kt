package app.harvester.companion

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
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
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.fragment.NavHostFragment
import app.harvester.companion.browser.BrowserFragment
import app.harvester.companion.browser.BrowserViewModel
import app.harvester.companion.browser.toolbar.UhGlobalToolbar
import app.harvester.companion.media.UhMediaController
import app.harvester.companion.theme.UHTheme
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.selectedTab

class HomeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SELECT_TAB_ID = "uh_extra_select_tab_id"

        private const val ACTION_PIP_REPLAY_10 = "app.harvester.companion.pip.REPLAY_10"
        private const val ACTION_PIP_TOGGLE_PLAY = "app.harvester.companion.pip.TOGGLE_PLAY"
        private const val ACTION_PIP_FORWARD_10 = "app.harvester.companion.pip.FORWARD_10"
    }

    private val browserViewModel: BrowserViewModel by viewModels()
    private var navControllerState by mutableStateOf<NavController?>(null)
    private var isInPipMode by mutableStateOf(false)
    private var pipReceiverRegistered = false
    private var autoHideStartX = 0f
    private var autoHideLastY = 0f
    private var autoHideAccumDy = 0f
    private var autoHideTracking = false

    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PIP_REPLAY_10 -> UhMediaController.seekRelative(-10L)
                ACTION_PIP_TOGGLE_PLAY -> UhMediaController.togglePlayPause()
                ACTION_PIP_FORWARD_10 -> UhMediaController.seekRelative(10L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )

        registerPipActionReceiver()
        observeMediaStateForPip()
        observeActiveTabPrivacyAndAccess()
        observeImmersiveFullscreen()

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
                if (browserViewModel.isImmersiveFullscreen.value) {
                    browserViewModel.setImmersiveFullscreen(false)
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

    override fun onDestroy() {
        if (pipReceiverRegistered) {
            try { unregisterReceiver(pipActionReceiver) } catch (_: Exception) {}
            pipReceiverRegistered = false
        }
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        trackAutoHideScrollGesture(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun trackAutoHideScrollGesture(ev: MotionEvent) {
        val onBrowser = navControllerState?.currentDestination?.id == R.id.browserFragment
        val readerActive = app.harvester.companion.browser.reader.ReaderModeManager.state.value.isActive
        if (!onBrowser ||
            !browserViewModel.isAutoHideBarEnabled.value ||
            browserViewModel.isEditing.value ||
            browserViewModel.isImmersiveFullscreen.value ||
            browserViewModel.showFindInPage.value ||
            browserViewModel.showZoomBar.value ||
            readerActive ||
            isInPipMode
        ) {
            autoHideTracking = false
            return
        }

        val density = resources.displayMetrics.density
        val toolbarZonePx = 74f * density
        val screenH = resources.displayMetrics.heightPixels.toFloat()
        val isBottom = browserViewModel.isBarBottom.value

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val y = ev.rawY
                val inToolbarZone = if (isBottom) y > (screenH - toolbarZonePx) else y < toolbarZonePx
                autoHideTracking = !inToolbarZone
                autoHideStartX = ev.rawX
                autoHideLastY = y
                autoHideAccumDy = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                if (!autoHideTracking) return
                val dy = ev.rawY - autoHideLastY
                val dx = kotlin.math.abs(ev.rawX - autoHideStartX)
                autoHideLastY = ev.rawY
                if ((dy < 0f && autoHideAccumDy > 0f) || (dy > 0f && autoHideAccumDy < 0f)) {
                    autoHideAccumDy = dy
                } else {
                    autoHideAccumDy += dy
                }
                val hideThreshold = 38f * density
                val revealThreshold = 20f * density
                if (kotlin.math.abs(autoHideAccumDy) > dx * 0.9f) {
                    if (autoHideAccumDy < -hideThreshold) {
                        browserViewModel.setToolbarAutoHidden(true)
                        autoHideAccumDy = 0f
                    } else if (autoHideAccumDy > revealThreshold) {
                        browserViewModel.setToolbarAutoHidden(false)
                        autoHideAccumDy = 0f
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                autoHideTracking = false
                autoHideAccumDy = 0f
            }
        }
    }

    private fun observeImmersiveFullscreen() {
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(
                browserViewModel.isImmersiveFullscreen,
                app.harvester.companion.browser.reader.ReaderModeManager.state
            ) { swipeFs, readerSt ->
                swipeFs || (readerSt.isActive && readerSt.prefs.isFullscreen)
            }.collect { immersive ->
                applySystemBarsImmersive(immersive)
            }
        }
    }

    private fun applySystemBarsImmersive(immersive: Boolean) {
        try {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            if (immersive) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        } catch (_: Exception) {}
    }

    private fun registerPipActionReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(ACTION_PIP_REPLAY_10)
                addAction(ACTION_PIP_TOGGLE_PLAY)
                addAction(ACTION_PIP_FORWARD_10)
            }
            ContextCompat.registerReceiver(
                this,
                pipActionReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            pipReceiverRegistered = true
        } catch (e: Exception) {
            UHLog.w("media", "Failed registering PiP action receiver: ${e.message}")
        }
    }

    private fun observeMediaStateForPip() {
        lifecycleScope.launch {
            var lastPlaying: Boolean? = null
            UhMediaController.state.collect { snap ->
                if (isInPipMode && lastPlaying != snap.isPlaying) {
                    lastPlaying = snap.isPlaying
                    try {
                        setPictureInPictureParams(buildPipParams(snap.isPlaying))
                    } catch (_: Exception) {}
                } else if (!isInPipMode) {
                    lastPlaying = null
                }
            }
        }
    }

    private fun applyActiveTabSecureFlag(isPrivateTab: Boolean) {
        val blockScreenshots = SettingsPrefs.getBool(
            this,
            SettingsPrefs.K_TAB_PRIVATE_SECURE_SCREEN,
            true
        )
        try {
            if (isPrivateTab && blockScreenshots) {
                window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        } catch (_: Exception) {}
    }

    private fun observeActiveTabPrivacyAndAccess() {
        val store = (application as? UHApp)?.components?.store ?: return
        lifecycleScope.launch {
            store.stateFlow.collect { state ->
                val tab = state.selectedTab
                applyActiveTabSecureFlag(tab?.content?.private == true)

                if (tab != null && !tab.content.private) {
                    app.harvester.companion.browser.tabs.TabHistoryStore.recordTabAccess(tab.id)
                }
            }
        }
    }

    private fun buildPipParams(isPlaying: Boolean): PictureInPictureParams {
        val replayAction = RemoteAction(
            Icon.createWithResource(this, R.drawable.uh_ic_media_replay10_24),
            "Rewind 10s",
            "Rewind 10 seconds",
            pipPendingIntent(ACTION_PIP_REPLAY_10, 201),
        )
        val playPauseAction = RemoteAction(
            Icon.createWithResource(
                this,
                if (isPlaying) R.drawable.uh_ic_media_pause_24 else R.drawable.uh_ic_media_play_24,
            ),
            if (isPlaying) "Pause" else "Play",
            if (isPlaying) "Pause playback" else "Resume playback",
            pipPendingIntent(ACTION_PIP_TOGGLE_PLAY, 202),
        )
        val forwardAction = RemoteAction(
            Icon.createWithResource(this, R.drawable.uh_ic_media_forward10_24),
            "Forward 10s",
            "Forward 10 seconds",
            pipPendingIntent(ACTION_PIP_FORWARD_10, 203),
        )

        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(UhMediaController.resolveActiveVideoAspectRatio())
            .setActions(listOf(replayAction, playPauseAction, forwardAction))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun pipPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(action).apply { setPackage(packageName) }
        return PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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
        if (intent == null) return

        // Direct tap from rich media notification -> select & focus the playing tab
        val targetTabId = intent.getStringExtra(EXTRA_SELECT_TAB_ID)
        if (!targetTabId.isNullOrBlank()) {
            intent.removeExtra(EXTRA_SELECT_TAB_ID)
            val components = (application as? UHApp)?.components
            if (components != null) {
                components.useCases.tabsUseCases.selectTab(targetTabId)
                if (controller != null && controller.currentDestination?.id != R.id.browserFragment) {
                    try {
                        controller.navigate(R.id.action_homeFragment_to_browserFragment)
                    } catch (_: Exception) {}
                }
            }
            return
        }

        val url = intent.data?.toString() ?: return
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
            browserViewModel.setPendingUrl(url)
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
        val components = (application as? UHApp)?.components
        components?.resumePendingNetworkDownloads()
        applyActiveTabSecureFlag(components?.store?.state?.selectedTab?.content?.private == true)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!SettingsPrefs.getBool(this, SettingsPrefs.K_PIP_ENABLED, true)) return
        val controller = navControllerState
        if (controller?.currentDestination?.id != R.id.browserFragment) return
        val tab = (application as? UHApp)?.components?.store?.state?.selectedTab ?: return

        if (UhMediaController.hasActivePlayingVideo(tab)) {
            try {
                // Isolate ONLY the active <video> player and strip browser chrome before PiP transition
                GeckoWebExtensionBridge.setPipVideoIsolation(true)
                browserViewModel.setPipMode(true)
                val params = buildPipParams(isPlaying = true)
                val entered = enterPictureInPictureMode(params)
                if (!entered) {
                    GeckoWebExtensionBridge.setPipVideoIsolation(false)
                    browserViewModel.setPipMode(false)
                }
            } catch (e: Exception) {
                GeckoWebExtensionBridge.setPipVideoIsolation(false)
                browserViewModel.setPipMode(false)
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
        browserViewModel.setPipMode(isInPictureInPictureMode)
        GeckoWebExtensionBridge.setPipVideoIsolation(isInPictureInPictureMode)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }
}
