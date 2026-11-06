package app.harvester.companion.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ViewCompositionBehavior
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import app.harvester.companion.CustomizeHomeActivity
import app.harvester.companion.R
import app.harvester.companion.Shortcut
import app.harvester.companion.ShortcutAdapter
import app.harvester.companion.ShortcutStore
import app.harvester.companion.WallpaperStore
import app.harvester.companion.browser.toolbar.HarvesterAddressBar
import app.harvester.companion.databinding.FragmentHomeBinding
import app.harvester.companion.browser.BrowserViewModel
import androidx.fragment.app.activityViewModels

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var shortcutAdapter: ShortcutAdapter
    private var homeVideoPlayer: androidx.media3.exoplayer.ExoPlayer? = null
    private val browserViewModel: BrowserViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupHomeGrid()
        setupAddressBar()
    }

    private fun setupHomeGrid() {
        shortcutAdapter = ShortcutAdapter(
            onOpen = { s -> navigateToBrowser(s.url) },
            onAdd = { startActivity(Intent(requireContext(), CustomizeHomeActivity::class.java)) },
            onLongPress = { }
        )
        binding.shortcutsGrid.layoutManager = GridLayoutManager(requireContext(), 4)
        binding.shortcutsGrid.adapter = shortcutAdapter
        shortcutAdapter.submit(ShortcutStore.list(requireContext()))

        binding.btnEditHome.setOnClickListener {
            startActivity(Intent(requireContext(), CustomizeHomeActivity::class.java))
        }
    }

    private fun setupAddressBar() {
        val isEditing by browserViewModel.isEditing.collectAsState()
        val editText by browserViewModel.editText.collectAsState()
        val suggestions by browserViewModel.suggestions.collectAsState()

        binding.addressBarContainer.setContent {
            app.harvester.companion.theme.UHTheme {
                HarvesterAddressBar(
                    url = "",
                    isEditing = isEditing,
                    tabCount = 1,
                    iconState = 0,
                    isWallpaperActive = true,
                    editText = editText,
                    onEditTextChanged = { browserViewModel.onTextChanged(it) },
                    onSubmit = { url ->
                        val target = browserViewModel.normalize(url)
                        if (target.isNotBlank()) {
                            browserViewModel.exitEditMode()
                            navigateToBrowser(target)
                        }
                    },
                    onEditStart = { browserViewModel.enterEditMode("") },
                    onClear = { browserViewModel.onTextChanged("") },
                    onSearchEngineClick = {},
                    onScriptMenuClick = {},
                    onMenuClick = {},
                    onTabCountClick = {}
                )
            }
        }
    }

    private fun navigateToBrowser(url: String) {
        val action = app.harvester.companion.home.HomeFragmentDirections.actionHomeFragmentToBrowserFragment(url)
        findNavController().navigate(action)
    }

    override fun onResume() {
        super.onResume()
        applyWallpaper()
        shortcutAdapter.submit(ShortcutStore.list(requireContext()))
        homeVideoPlayer?.play()
    }

    private fun applyWallpaper() {
        val wpPath = WallpaperStore.getCurrentWallpaper(requireContext())
        val isVideo = WallpaperStore.isCurrentVideo(requireContext())
        
        if (isVideo && wpPath != null && wpPath != "default") {
            binding.wallpaperBackground.visibility = View.GONE
            binding.wallpaperVideo.visibility = View.VISIBLE
            if (homeVideoPlayer == null) {
                homeVideoPlayer = androidx.media3.exoplayer.ExoPlayer.Builder(requireContext()).build().also {
                    binding.wallpaperVideo.player = it
                    it.volume = 0f
                    it.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                }
            }
            homeVideoPlayer?.let {
                it.setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(java.io.File(wpPath))))
                it.prepare()
                it.play()
            }
            binding.homeScreen.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        } else {
            binding.wallpaperVideo.visibility = View.GONE
            homeVideoPlayer?.pause()
            if (wpPath == null || wpPath == "default") {
                binding.wallpaperBackground.visibility = View.GONE
                binding.homeScreen.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.bg))
            } else {
                val bitmap = android.graphics.BitmapFactory.decodeFile(wpPath)
                if (bitmap != null) {
                    binding.wallpaperBackground.setImageBitmap(bitmap)
                    binding.wallpaperBackground.visibility = View.VISIBLE
                    binding.homeScreen.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        homeVideoPlayer?.pause()
    }

    override fun onDestroyView() {
        binding.wallpaperVideo.player = null
        homeVideoPlayer?.release()
        homeVideoPlayer = null
        _binding = null
        super.onDestroyView()
    }
}
