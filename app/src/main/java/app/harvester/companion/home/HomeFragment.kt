package app.harvester.companion.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import app.harvester.companion.CustomizeHomeActivity
import app.harvester.companion.R
import app.harvester.companion.ShortcutAdapter
import app.harvester.companion.ShortcutStore
import app.harvester.companion.UHApp
import app.harvester.companion.WallpaperStore
import app.harvester.companion.browser.BrowserViewModel
import app.harvester.companion.browser.menu.MainMenuDialogFragment
import app.harvester.companion.browser.search.UHSearchEnginePicker
import app.harvester.companion.browser.toolbar.UHAddressBar
import app.harvester.companion.browser.toolbar.UHSearchOverlay
import app.harvester.companion.databinding.FragmentHomeBinding
import app.harvester.companion.theme.UHTheme

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
        setupInsets()
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.homeScreen) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(0, top, 0, 0)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.addressBarContainer) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(0, 0, 0, bottom)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.btnEditHome) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val lp = v.layoutParams as ViewGroup.MarginLayoutParams
            lp.topMargin = top + (16 * resources.displayMetrics.density).toInt()
            v.layoutParams = lp
            insets
        }
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
        binding.addressBarContainer.setContent {
            val isEditing by browserViewModel.isEditing.collectAsState()
            val searchEngine by browserViewModel.searchEngine.collectAsState()
            val editText by browserViewModel.editText.collectAsState()
            val components = (requireContext().applicationContext as UHApp).components
            
            var showSearchEnginePicker by remember { mutableStateOf(false) }

            if (showSearchEnginePicker) {
                UHSearchEnginePicker(
                    onDismiss = { showSearchEnginePicker = false },
                    onSelect = { browserViewModel.enterEditMode("") }
                )
            }

            UHTheme {
                if (isEditing) {
                    UHSearchOverlay(
                        viewModel = browserViewModel,
                        fragment = this@HomeFragment,
                        onDismissRequest = { browserViewModel.exitEditMode() },
                        onSearchEngineClick = { showSearchEnginePicker = true },
                        onLockClick = {},
                        onMenuClick = {},
                        onTabCountClick = {}
                    )
                } else {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        UHAddressBar(
                            url = "",
                            isEditing = isEditing,
                            tabCount = 1,
                            iconState = 0,
                            isWallpaperActive = true,
                            editText = editText,
                            onEditTextChanged = { browserViewModel.onTextChanged(it) },
                            onSubmit = { },
                            onEditStart = { browserViewModel.enterEditMode("") },
                            onClear = { },
                            onSearchEngineClick = { showSearchEnginePicker = true },
                            onLockClick = {},
                            onScriptMenuClick = {},
                            onMenuClick = { MainMenuDialogFragment.newInstance(true).show(parentFragmentManager, "MainMenu") },
                            onTabCountClick = {},
                            searchEngine = searchEngine
                        )
                    }
                }
            }
        }
    }

    private fun navigateToBrowser(url: String) {
        val bundle = Bundle().apply { putString("url", url) }
        findNavController().navigate(R.id.action_homeFragment_to_browserFragment, bundle)
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
