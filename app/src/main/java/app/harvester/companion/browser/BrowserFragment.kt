package app.harvester.companion.browser

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import app.harvester.companion.UHApp
import app.harvester.companion.UHLog
import app.harvester.companion.browser.menu.ContextMenuDialog
import app.harvester.companion.browser.menu.MainMenuSheet
import app.harvester.companion.browser.toolbar.HarvesterAddressBar
import app.harvester.companion.databinding.FragmentBrowserBinding
import app.harvester.companion.theme.UHTheme
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.feature.app.links.AppLinksFeature
import mozilla.components.feature.prompts.PromptFeature
import mozilla.components.feature.prompts.file.AndroidPhotoPicker
import mozilla.components.feature.prompts.share.ShareDelegate
import mozilla.components.concept.engine.prompt.ShareData
import mozilla.components.feature.session.SessionFeature
import mozilla.components.feature.session.SwipeRefreshFeature
import mozilla.components.feature.sitepermissions.SitePermissionsFeature
import mozilla.components.feature.sitepermissions.SitePermissionsRules
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import android.content.Context

class BrowserFragment : Fragment() {

    private var _binding: FragmentBrowserBinding? = null
    private val binding get() = _binding!!
    private val sessionFeature = ViewBoundFeatureWrapper<SessionFeature>()
    private val swipeRefreshFeature = ViewBoundFeatureWrapper<SwipeRefreshFeature>()
    private val appLinksFeature = ViewBoundFeatureWrapper<AppLinksFeature>()
    private val promptsFeature = ViewBoundFeatureWrapper<PromptFeature>()
    private val sitePermissionsFeature = ViewBoundFeatureWrapper<SitePermissionsFeature>()
    
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

        val initStart = System.currentTimeMillis()
        val engineView = components.engine.createView(context, null)
        binding.swipeRefresh.addView(engineView.asView())
        UHLog.d("perf", "Browser engine view created: ${System.currentTimeMillis() - initStart}ms")

        setupFeatures(components, engineView)

        if (initialUrl != null) {
            if (components.store.state.tabs.isEmpty()) {
                components.useCases.tabsUseCases.addTab(initialUrl!!)
            } else {
                components.useCases.sessionUseCases.loadUrl(initialUrl!!)
            }
        }

        setupAddressBar(components)
        setupInsets()
        setupLogging(components)
    }

    private fun setupFeatures(components: app.harvester.companion.components.Components, engineView: mozilla.components.concept.engine.EngineView) {
        sessionFeature.set(
            feature = SessionFeature(
                components.store,
                components.useCases.sessionUseCases.goBack,
                components.useCases.sessionUseCases.goForward,
                engineView,
                null
            ),
            owner = this,
            view = binding.root
        )

        binding.swipeRefresh.setColorSchemeResources(app.harvester.companion.R.color.accent)
        binding.swipeRefresh.setProgressBackgroundColorSchemeResource(app.harvester.companion.R.color.surface_2)

        swipeRefreshFeature.set(
            feature = SwipeRefreshFeature(
                components.store,
                components.useCases.sessionUseCases.reload,
                binding.swipeRefresh,
                null,
                null
            ),
            owner = this,
            view = binding.root
        )

        appLinksFeature.set(
            feature = AppLinksFeature(
                requireContext(),
                components.store,
                fragmentManager = parentFragmentManager,
                sessionId = null,
                loadUrlUseCase = components.useCases.sessionUseCases.loadUrl,
                shouldPrompt = { true },
                launchInApp = { false }
            ),
            owner = this,
            view = binding.root
        )

        promptsFeature.set(
            feature = PromptFeature(
                activity = requireActivity(),
                store = components.store,
                customTabId = null,
                fragmentManager = parentFragmentManager,
                fileUploadsDirCleaner = mozilla.components.feature.prompts.file.FileUploadsDirCleaner { requireContext().cacheDir },
                tabsUseCases = components.useCases.tabsUseCases,
                shareDelegate = object : ShareDelegate {
                    override fun showShareSheet(
                        context: Context,
                        shareData: ShareData,
                        onDismiss: () -> Unit,
                        onSuccess: () -> Unit
                    ) {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareData.url)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share"))
                        onSuccess()
                    }
                },
                isEmailMaskFeatureEnabled = { false },
                isSuggestEmailMaskEnabled = { false },
                androidPhotoPicker = AndroidPhotoPicker(
                    requireContext(),
                    AndroidPhotoPicker.singleMediaPicker({ this }) { promptsFeature.get() },
                    AndroidPhotoPicker.multipleMediaPicker({ this }) { promptsFeature.get() }
                ),
                onNeedToRequestPermissions = { requestPermissions(it, 1) }
            ),
            owner = this,
            view = binding.root
        )

        val sitePermissionsStorage = mozilla.components.feature.sitepermissions.OnDiskSitePermissionsStorage(requireContext())
        sitePermissionsFeature.set(
            feature = SitePermissionsFeature(
                context = requireContext(),
                storage = sitePermissionsStorage,
                sessionId = null,
                fragmentManager = parentFragmentManager,
                onNeedToRequestPermissions = { requestPermissions(it, 2) },
                onShouldShowRequestPermissionRationale = { shouldShowRequestPermissionRationale(it) },
                store = components.store,
                sitePermissionsRules = SitePermissionsRules(
                    autoplayAudible = SitePermissionsRules.AutoplayAction.BLOCKED,
                    autoplayInaudible = SitePermissionsRules.AutoplayAction.BLOCKED,
                    camera = SitePermissionsRules.Action.ASK_TO_ALLOW,
                    location = SitePermissionsRules.Action.ASK_TO_ALLOW,
                    notification = SitePermissionsRules.Action.ASK_TO_ALLOW,
                    microphone = SitePermissionsRules.Action.ASK_TO_ALLOW,
                    persistentStorage = SitePermissionsRules.Action.ASK_TO_ALLOW,
                    mediaKeySystemAccess = SitePermissionsRules.Action.ASK_TO_ALLOW,
                    crossOriginStorageAccess = SitePermissionsRules.Action.ASK_TO_ALLOW,
                    localDeviceAccess = SitePermissionsRules.Action.ASK_TO_ALLOW,
                    localNetworkAccess = SitePermissionsRules.Action.ASK_TO_ALLOW
                )
            ),
            owner = this,
            view = binding.root
        )
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

    private fun setupLogging(components: app.harvester.companion.components.Components) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                components.store.stateFlow.collect { state ->
                    state.selectedTab?.content?.let { content ->
                        if (content.url != "about:blank" && content.url.isNotBlank()) {
                            UHLog.netNav("GET", content.url, null, false, false)
                        }
                        if (content.loading && content.progress == 100) {
                             UHLog.d("web", "Page loaded: ${content.url}")
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
private fun setupAddressBar(components: app.harvester.companion.components.Components) {
    binding.addressBarContainer.setContent {
        val browserState by components.store.stateFlow.collectAsState()
        val isEditing by viewModel.isEditing.collectAsState()
        val editText by viewModel.editText.collectAsState()
        val selectedTab = browserState.selectedTab
        val showMenu by viewModel.showMainMenu.collectAsState()
        
        val hitResult = selectedTab?.content?.hitResult
        if (hitResult != null) {
            ContextMenuDialog(
                hitResult = hitResult,
                tabsUseCases = components.useCases.tabsUseCases,
                onDismiss = { 
                    selectedTab.id.let { components.useCases.contextMenuUseCases.consumeHitResult(it) }
                }
            )
        }

        if (showMenu) {
            MainMenuSheet(onDismiss = { viewModel.setShowMainMenu(false) })
        }
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
                    onMenuClick = { viewModel.setShowMainMenu(true) },
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
