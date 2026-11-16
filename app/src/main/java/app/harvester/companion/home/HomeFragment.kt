package app.harvester.companion.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import app.harvester.companion.CustomizeHomeActivity
import app.harvester.companion.R
import app.harvester.companion.WallpaperStore
import app.harvester.companion.browser.BrowserViewModel
import app.harvester.companion.theme.UHTheme
import mozilla.components.browser.state.selector.selectedTab

class HomeFragment : Fragment() {

    private val browserViewModel: BrowserViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setContent {
            UHTheme {
                val context = requireContext()
                val wpPath = WallpaperStore.getCurrentWallpaper(context)
                val isVideo = WallpaperStore.isCurrentVideo(context)

                HomeScreen(
                    wallpaperPath = wpPath,
                    isVideo = isVideo,
                    onEditClick = {
                        startActivity(Intent(requireContext(), CustomizeHomeActivity::class.java))
                    },
                    onShortcutClick = { url ->
                        val bundle = Bundle().apply { putString("url", url) }
                        findNavController().navigate(R.id.action_homeFragment_to_browserFragment, bundle)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        browserViewModel.isEditing.value
    }
}
