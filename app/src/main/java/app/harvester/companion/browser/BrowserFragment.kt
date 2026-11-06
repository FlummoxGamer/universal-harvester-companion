package app.harvester.companion.browser

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import app.harvester.companion.UHApp
import app.harvester.companion.browser.toolbar.HarvesterAddressBar
import app.harvester.companion.databinding.FragmentBrowserBinding
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.feature.session.SessionFeature
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper

class BrowserFragment : Fragment() {

    private var _binding: FragmentBrowserBinding? = null
    private val binding get() = _binding!!
    private val sessionFeature = ViewBoundFeatureWrapper<SessionFeature>()
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

        if (initialUrl != null && components.store.state.tabs.isEmpty()) {
            components.useCases.tabsUseCases.addTab(initialUrl!!)
        }

        setupAddressBar(components)
    }

    private fun setupAddressBar(components: app.harvester.companion.components.Components) {
        binding.addressBarContainer.setContent {
            val browserState by components.store.stateFlow.collectAsState()
            val isEditing by viewModel.isEditing.collectAsState()
            val editText by viewModel.editText.collectAsState()
            val suggestions by viewModel.suggestions.collectAsState()
            val selectedTab = browserState.selectedTab

            app.harvester.companion.theme.UHTheme {
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
                    onMenuClick = {},
                    onTabCountClick = {}
                )
            }
        }
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
