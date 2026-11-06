package app.harvester.companion.browser

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import app.harvester.companion.DownloadsActivity
import app.harvester.companion.HistoryActivity
import app.harvester.companion.R
import app.harvester.companion.SettingsActivity
import app.harvester.companion.UHApp
import app.harvester.companion.browser.toolbar.HarvesterAddressBar
import app.harvester.companion.databinding.FragmentBrowserBinding
import app.harvester.companion.theme.UHTheme
import com.google.android.material.bottomsheet.BottomSheetDialog
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.feature.session.SessionFeature
import mozilla.components.feature.session.SwipeRefreshFeature
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper

class BrowserFragment : Fragment() {

    private var _binding: FragmentBrowserBinding? = null
    private val binding get() = _binding!!
    private val sessionFeature = ViewBoundFeatureWrapper<SessionFeature>()
    private val swipeRefreshFeature = ViewBoundFeatureWrapper<SwipeRefreshFeature>()
    private val viewModel: BrowserViewModel by activityViewModels()
    private var initialUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialUrl = arguments?.getString("url")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        val components = (context.applicationContext as UHApp).components

        val engineView = components.engine.createView(context, null)
        binding.engineViewContainer.addView(engineView.asView())

        sessionFeature.set(
            feature = SessionFeature(
                store = components.store,
                goBackUseCase = components.useCases.sessionUseCases.goBack,
                goForwardUseCase = components.useCases.sessionUseCases.goForward,
                engineView = engineView,
                tabId = null
            ),
            owner = this,
            view = binding.root
        )

        swipeRefreshFeature.set(
            feature = SwipeRefreshFeature(
                store = components.store,
                reloadUrlUseCase = components.useCases.sessionUseCases.reload,
                swipeRefreshLayout = binding.swipeRefresh
            ),
            owner = this,
            view = binding.root
        )

        if (initialUrl != null) {
            if (components.store.state.tabs.isEmpty()) {
                components.useCases.tabsUseCases.addTab(initialUrl!!)
            } else {
                components.useCases.sessionUseCases.loadUrl(initialUrl!!)
            }
        }

        setupAddressBar(components)
        setupInsets()
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.browserLayout) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(0, top, 0, 0)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.addressBarContainer) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(0, 0, 0, bottom)
            insets
        }
    }

    private fun setupAddressBar(components: app.harvester.companion.components.Components) {
        binding.addressBarContainer.setContent {
            val browserState by components.store.stateFlow.collectAsState()
            val isEditing by viewModel.isEditing.collectAsState()
            val editText by viewModel.editText.collectAsState()
            val selectedTab = browserState.selectedTab

            UHTheme {
                HarvesterAddressBar(
                    url = selectedTab?.content?.url ?: "",
                    isEditing = isEditing,
                    tabCount = browserState.tabs.size,
                    iconState = if (selectedTab?.content?.url?.startsWith("https") == true) 1 else 0,
                    isWallpaperActive = false,
                    editText = editText,
                    onEditTextChanged = { viewModel.onTextChanged(it) },
                    onSubmit = { 
                        val target = viewModel.normalize(it)
                        if (target.isNotBlank()) {
                            viewModel.exitEditMode()
                            components.useCases.sessionUseCases.loadUrl(target)
                        }
                    },
                    onEditStart = { viewModel.enterEditMode(selectedTab?.content?.url ?: "") },
                    onClear = { viewModel.onTextChanged("") },
                    onSearchEngineClick = {},
                    onScriptMenuClick = {},
                    onMenuClick = { showMenu() },
                    onTabCountClick = {}
                )
            }
        }
    }

    private fun showMenu() {
        val sheet = BottomSheetDialog(requireContext(), R.style.Theme_UHCompanion_BottomSheet)
        val v = layoutInflater.inflate(R.layout.dialog_main_menu, null)
        v.setBackgroundResource(R.drawable.bg_bottom_sheet)
        sheet.setContentView(v)

        v.findViewById<View>(R.id.menuSettings)?.setOnClickListener {
            sheet.dismiss()
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        v.findViewById<View>(R.id.menuHistory)?.setOnClickListener {
            sheet.dismiss()
            startActivity(Intent(requireContext(), HistoryActivity::class.java))
        }
        v.findViewById<View>(R.id.menuDownloads)?.setOnClickListener {
            sheet.dismiss()
            startActivity(Intent(requireContext(), DownloadsActivity::class.java))
        }
        sheet.show()
    }

    fun onBackPressed(): Boolean {
        val components = (requireContext().applicationContext as UHApp).components
        val state = components.store.state.selectedTab
        if (state != null && state.content.canGoBack) {
            components.useCases.sessionUseCases.goBack.invoke()
            return true
        }
        return false
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
