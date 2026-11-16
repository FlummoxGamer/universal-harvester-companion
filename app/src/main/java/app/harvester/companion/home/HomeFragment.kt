package app.harvester.companion.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import app.harvester.companion.CustomizeHomeActivity
import app.harvester.companion.R
import app.harvester.companion.browser.BrowserViewModel
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
            UHTheme {
                val shortcuts by homeViewModel.shortcuts.collectAsState()
                val wpPath by homeViewModel.wallpaperPath.collectAsState()
                val isVideo by homeViewModel.isVideo.collectAsState()
                val textColor by homeViewModel.textColor.collectAsState()

                HomeScreen(
                    shortcuts = shortcuts,
                    wallpaperPath = wpPath,
                    isVideo = isVideo,
                    textColorInt = textColor,
                    onEditHomeClick = {
                        startActivity(Intent(requireContext(), CustomizeHomeActivity::class.java))
                    },
                    onShortcutOpen = { url ->
                        val bundle = Bundle().apply { putString("url", url) }
                        findNavController().navigate(R.id.action_homeFragment_to_browserFragment, bundle)
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
        browserViewModel.isEditing.value
        homeViewModel.refresh()
    }
}
