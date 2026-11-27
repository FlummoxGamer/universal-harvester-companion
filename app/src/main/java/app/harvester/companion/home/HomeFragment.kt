package app.harvester.companion.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import app.harvester.companion.R
import app.harvester.companion.UHApp
import app.harvester.companion.UHLog
import app.harvester.companion.browser.BrowserViewModel
import app.harvester.companion.browser.tabs.PrivateSessionNotificationManager
import app.harvester.companion.browser.tabs.TabPreviewCache
import app.harvester.companion.search.PrivateSearchHistoryStore
import app.harvester.companion.theme.UHTheme
import kotlinx.coroutines.delay
import mozilla.components.browser.state.selector.selectedTab

class HomeFragment : Fragment() {
    private val browserViewModel: BrowserViewModel by activityViewModels()
    private val homeViewModel: HomeViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setContent {
            val app = requireActivity().application as UHApp
            val components = app.components
            val browserState by components.store.stateFlow.collectAsState()
            val selectedTab = browserState.selectedTab
            val isPrivateHome = selectedTab?.content?.private == true
            val privateTabCount = remember(browserState.tabs) {
                browserState.tabs.count { it.content.private }
            }
            val tempPrivateQueries by PrivateSearchHistoryStore.queriesFlow.collectAsState()

            val wallpaperState by homeViewModel.wallpaperState.collectAsState()
            val isEditing by browserViewModel.isEditing.collectAsState()
            val shortcuts by homeViewModel.shortcuts.collectAsState()

            val lightStatusOverride = when {
                isEditing -> null
                isPrivateHome -> false
                else -> wallpaperState.isTopDark?.let { !it }
            }
            val lightNavOverride = when {
                isEditing -> null
                isPrivateHome -> false
                else -> wallpaperState.isBottomDark?.let { !it }
            }

            // Track whether HomeFragment is the active top destination so motion video pauses off Home
            var isHomeDestination by remember { mutableStateOf(true) }
            DisposableEffect(this@HomeFragment) {
                val navController = try {
                    findNavController()
                } catch (_: Exception) {
                    null
                }
                val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
                    isHomeDestination = destination.id == R.id.homeFragment
                }
                navController?.let {
                    isHomeDestination = it.currentDestination?.id == R.id.homeFragment
                    it.addOnDestinationChangedListener(listener)
                }
                onDispose {
                    navController?.removeOnDestinationChangedListener(listener)
                }
            }

            // Capture an authentic live Home screen snapshot whenever wallpaper or shortcuts change
            val hostView = LocalView.current
            LaunchedEffect(isHomeDestination, isPrivateHome, isEditing, wallpaperState.path, wallpaperState.textColor, shortcuts) {
                if (isHomeDestination && !isPrivateHome && !isEditing) {
                    delay(550L)
                    captureRealHomeSnapshot(hostView)
                }
            }

            UHTheme(
                lightStatusBarOverride = lightStatusOverride,
                lightNavigationBarOverride = lightNavOverride
            ) {
                if (isPrivateHome) {
                    PrivateHomeScreen(
                        privateTabCount = privateTabCount,
                        hasTempSearches = tempPrivateQueries.isNotEmpty(),
                        onSearchClick = {
                            browserViewModel.enterEditMode("")
                        },
                        onClearPrivateSession = {
                            PrivateSessionNotificationManager.closeAllPrivateTabs(requireContext(), components)
                            Toast.makeText(requireContext(), "Private session cleared", Toast.LENGTH_SHORT).show()
                        }
                    )
                } else {
                    HomeScreen(
                        shortcuts = shortcuts,
                        wallpaperState = wallpaperState,
                        isWallpaperPlaybackActive = isHomeDestination && !isEditing,
                        onShortcutOpen = { url ->
                            try {
                                val bundle = Bundle().apply { putString("url", url) }
                                findNavController().navigate(R.id.action_homeFragment_to_browserFragment, bundle)
                            } catch (e: Exception) {
                                UHLog.throwable("ui_error", "Failed to navigate to BrowserFragment for shortcut: $url", e)
                            }
                        },
                        onMoveShortcut = { from, to -> homeViewModel.moveShortcut(from, to) },
                        onAddShortcut = { homeViewModel.addShortcut(it) },
                        onRemoveShortcut = { homeViewModel.removeShortcut(it) },
                        onUpdateShortcut = { old, new -> homeViewModel.updateShortcut(old, new) }
                    )
                }
            }
        }
    }

    private fun captureRealHomeSnapshot(view: View) {
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0 || !view.isLaidOut) return
        try {
            val scale = (540f / w.toFloat()).coerceAtMost(1f)
            val targetW = (w * scale).toInt().coerceAtLeast(1)
            val targetH = (h * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.scale(scale, scale)
            view.draw(canvas)
            TabPreviewCache.putHomeSnapshot(bmp)
        } catch (_: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        homeViewModel.refresh()
    }
}
