package app.harvester.companion.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import app.harvester.companion.R
import app.harvester.companion.UHLog
import app.harvester.companion.browser.BrowserViewModel
import app.harvester.companion.settings.appearance.CustomizeHomeActivity
import app.harvester.companion.theme.UHTheme

class HomeFragment : Fragment() {

    private val browserViewModel: BrowserViewModel by activityViewModels()
    private val homeViewModel: HomeViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setContent {
            val wallpaperState by homeViewModel.wallpaperState.collectAsState()
            val isEditing by browserViewModel.isEditing.collectAsState()
            val lightStatusOverride = if (!isEditing) wallpaperState.isTopDark?.let { !it } else null
            val lightNavOverride = if (!isEditing) wallpaperState.isBottomDark?.let { !it } else null

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

            UHTheme(
                lightStatusBarOverride = lightStatusOverride,
                lightNavigationBarOverride = lightNavOverride
            ) {
                val shortcuts by homeViewModel.shortcuts.collectAsState()

                HomeScreen(
                    shortcuts = shortcuts,
                    wallpaperState = wallpaperState,
                    isWallpaperPlaybackActive = isHomeDestination && !isEditing,
                    onEditHomeClick = {
                        try {
                            startActivity(Intent(requireContext(), CustomizeHomeActivity::class.java))
                        } catch (e: Exception) {
                            UHLog.throwable("ui_error", "Failed to open CustomizeHomeActivity", e)
                        }
                    },
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

    override fun onResume() {
        super.onResume()
        homeViewModel.refresh()
    }
}
