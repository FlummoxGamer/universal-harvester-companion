package app.harvester.companion.browser.menu

import android.app.Dialog
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.activityViewModels
import app.harvester.companion.HomeActivity
import app.harvester.companion.R
import app.harvester.companion.SessionRecorder
import app.harvester.companion.UHApp
import app.harvester.companion.UHLog
import app.harvester.companion.browser.BrowserViewModel
import app.harvester.companion.browser.webapp.WebAppStore
import app.harvester.companion.settings.SettingsActivity
import app.harvester.companion.settings.addons.BUILTIN_INTERNAL_EXTENSION_IDS
import app.harvester.companion.settings.addons.ExtensionsActivity
import app.harvester.companion.settings.addons.ScriptsActivity
import app.harvester.companion.settings.appearance.CustomizeHomeActivity
import app.harvester.companion.theme.UHTheme
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import mozilla.components.browser.state.selector.selectedTab
import com.google.android.material.R as materialR

class MainMenuDialogFragment : BottomSheetDialogFragment() {

    private var bottomSheetBehavior: BottomSheetBehavior<View>? = null
    private val browserViewModel: BrowserViewModel by activityViewModels()

    companion object {
        private const val ARG_IS_HOME = "is_home"
        fun newInstance(isHome: Boolean): MainMenuDialogFragment {
            val args = Bundle().apply { putBoolean(ARG_IS_HOME, isHome) }
            return MainMenuDialogFragment().apply { arguments = args }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return BottomSheetDialog(requireContext(), theme).apply {
            setOnShowListener {
                val bottomSheet = findViewById<View?>(materialR.id.design_bottom_sheet)
                bottomSheet?.let {
                    bottomSheetBehavior = BottomSheetBehavior.from(it).apply {
                        skipCollapsed = true
                        state = BottomSheetBehavior.STATE_EXPANDED
                    }
                }
                bottomSheet?.setBackgroundResource(android.R.color.transparent)
            }
        }
    }

    private fun navigateSafe(destinationId: Int) {
        val hostActivity = activity as? HomeActivity
        dismissAllowingStateLoss()
        if (hostActivity != null) {
            hostActivity.navigateFromMenu(destinationId)
        } else {
            UHLog.e("ui_error", "MainMenu failed to navigate: HomeActivity unavailable.")
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val isHome = arguments?.getBoolean(ARG_IS_HOME) ?: true
        val app = requireActivity().application as UHApp
        val components = app.components

        return ComposeView(requireContext()).apply {
            setContent {
                UHTheme {
                    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                    val isDarkSurface = MaterialTheme.colorScheme.background.luminance() < 0.5f
                    val browserState by components.store.stateFlow.collectAsState()
                    val selectedTab = browserState.selectedTab
                    val currentUrl = selectedTab?.content?.url.orEmpty()
                    val isDesktopMode = selectedTab?.content?.desktopMode == true
                    val canGoBack = selectedTab?.content?.canGoBack == true
                    val canGoForward = selectedTab?.content?.canGoForward == true
                    val isLoading = selectedTab?.content?.loading == true
                    val isPwaInstallable = WebAppStore.isPwaInstallable(selectedTab?.content?.webAppManifest)
                    val isTranslated = selectedTab?.translationsState?.isTranslated == true
                    val activeTransPair = selectedTab?.translationsState?.translationEngineState?.requestedTranslationPair
                    val translateBadge = when {
                        isTranslated && activeTransPair?.toLanguage != null ->
                            activeTransPair.toLanguage!!.uppercase()
                        isTranslated -> "ON"
                        else -> null
                    }
                    val isBookmarked by browserViewModel.isBookmarked.collectAsState()
                    val zoomPercent by browserViewModel.pageZoomPercent.collectAsState()
                    val devToolsActiveTabIds by browserViewModel.devToolsActiveTabIds.collectAsState()
                    val isDevToolsActive = selectedTab?.id != null && selectedTab.id in devToolsActiveTabIds
                    val isCaptureActive = SessionRecorder.isActive()
                    val activeExtensionsCount = browserState.extensions.values.count { ext ->
                        ext.enabled && !ext.isBuiltIn && ext.id !in BUILTIN_INTERNAL_EXTENSION_IDS
                    }

                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Surface(
                            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .widthIn(max = if (isLandscape) 640.dp else 900.dp)
                                .fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                                    .navigationBarsPadding(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp, 4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                if (isHome) {
                                    HomeMenuState(
                                        isCaptureActive = isCaptureActive,
                                        onHistory = { navigateSafe(R.id.historyFragment) },
                                        onDownloads = { navigateSafe(R.id.downloadsFragment) },
                                        onBookmarks = { navigateSafe(R.id.bookmarksFragment) },
                                        onNewPrivateTab = {
                                            dismissAllowingStateLoss()
                                            browserViewModel.openNewPrivateTab()
                                        },
                                        onCaptureTools = {
                                            dismissAllowingStateLoss()
                                            browserViewModel.openCaptureQuickSheet()
                                        },
                                        onSettings = {
                                            dismissAllowingStateLoss()
                                            context.startActivity(Intent(context, SettingsActivity::class.java))
                                        },
                                        onCustomizeHome = {
                                            dismissAllowingStateLoss()
                                            context.startActivity(Intent(context, CustomizeHomeActivity::class.java))
                                        },
                                        onExtensions = {
                                            dismissAllowingStateLoss()
                                            context.startActivity(Intent(context, ExtensionsActivity::class.java))
                                        },
                                        onScripts = {
                                            dismissAllowingStateLoss()
                                            context.startActivity(Intent(context, ScriptsActivity::class.java))
                                        },
                                        onQuit = {
                                            dismissAllowingStateLoss()
                                            activity?.finish()
                                        }
                                    )
                                } else {
                                    BrowserMenuState(
                                        isLandscape = isLandscape,
                                        canGoBack = canGoBack,
                                        canGoForward = canGoForward,
                                        isLoading = isLoading,
                                        isDesktopMode = isDesktopMode,
                                        isBookmarked = isBookmarked,
                                        isPwaInstallable = isPwaInstallable,
                                        translateBadge = translateBadge,
                                        isDevToolsActive = isDevToolsActive,
                                        isCaptureActive = isCaptureActive,
                                        zoomPercent = zoomPercent,
                                        activeExtensionsCount = activeExtensionsCount,
                                        onBack = {
                                            dismissAllowingStateLoss()
                                            selectedTab?.let { components.useCases.sessionUseCases.goBack(it.id) }
                                        },
                                        onForward = {
                                            dismissAllowingStateLoss()
                                            selectedTab?.let { components.useCases.sessionUseCases.goForward(it.id) }
                                        },
                                        onReloadOrStop = {
                                            dismissAllowingStateLoss()
                                            selectedTab?.let {
                                                if (isLoading) {
                                                    components.useCases.sessionUseCases.stopLoading(it.id)
                                                } else {
                                                    components.useCases.sessionUseCases.reload(it.id)
                                                }
                                            }
                                        },
                                        onBookmark = {
                                            val title = selectedTab?.content?.title.orEmpty()
                                            if (currentUrl.isNotBlank() && currentUrl != "about:blank") {
                                                browserViewModel.toggleBookmark(currentUrl, title)
                                            }
                                            dismissAllowingStateLoss()
                                        },
                                        onShare = {
                                            dismissAllowingStateLoss()
                                            if (currentUrl.isNotBlank() && currentUrl != "about:blank") {
                                                val intent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_TEXT, currentUrl)
                                                }
                                                startActivity(Intent.createChooser(intent, "Share"))
                                            }
                                        },
                                        onHistory = { navigateSafe(R.id.historyFragment) },
                                        onDownloads = { navigateSafe(R.id.downloadsFragment) },
                                        onBookmarks = { navigateSafe(R.id.bookmarksFragment) },
                                        onNewPrivateTab = {
                                            dismissAllowingStateLoss()
                                            browserViewModel.openNewPrivateTab()
                                        },
                                        onTranslate = {
                                            dismissAllowingStateLoss()
                                            browserViewModel.openTranslateSheet()
                                        },
                                        onToggleDesktop = {
                                            dismissAllowingStateLoss()
                                            selectedTab?.let {
                                                components.useCases.sessionUseCases.requestDesktopSite(!isDesktopMode, it.id)
                                            }
                                        },
                                        onFindInPage = {
                                            dismissAllowingStateLoss()
                                            browserViewModel.openFindInPage()
                                        },
                                        onZoomClick = {
                                            dismissAllowingStateLoss()
                                            browserViewModel.openZoomBar()
                                        },
                                        onZoomStep = { delta ->
                                            browserViewModel.setPageZoom(zoomPercent + delta)
                                        },
                                        onAddToHome = {
                                            dismissAllowingStateLoss()
                                            browserViewModel.openAddToHomeDialog()
                                        },
                                        onSavePdfPrint = {
                                            dismissAllowingStateLoss()
                                            browserViewModel.openSavePdfSheet()
                                        },
                                        onCopyLink = {
                                            dismissAllowingStateLoss()
                                            browserViewModel.copyLink(currentUrl)
                                        },
                                        onOpenInExternalApp = {
                                            val hostActivity = activity ?: context
                                            dismissAllowingStateLoss()
                                            browserViewModel.openInExternalApp(hostActivity, currentUrl)
                                        },
                                        onExtensions = {
                                            dismissAllowingStateLoss()
                                            browserViewModel.openExtensionsSheet()
                                        },
                                        onScripts = {
                                            dismissAllowingStateLoss()
                                            browserViewModel.openScriptsSheet()
                                        },
                                        onDevTools = {
                                            dismissAllowingStateLoss()
                                            browserViewModel.toggleDevTools(selectedTab?.id, darkTheme = isDarkSurface)
                                        },
                                        onCaptureTools = {
                                            dismissAllowingStateLoss()
                                            browserViewModel.openCaptureQuickSheet()
                                        },
                                        onSettings = {
                                            dismissAllowingStateLoss()
                                            context.startActivity(Intent(context, SettingsActivity::class.java))
                                        }
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun HomeMenuState(
        isCaptureActive: Boolean,
        onHistory: () -> Unit,
        onDownloads: () -> Unit,
        onBookmarks: () -> Unit,
        onNewPrivateTab: () -> Unit,
        onCaptureTools: () -> Unit,
        onSettings: () -> Unit,
        onCustomizeHome: () -> Unit,
        onExtensions: () -> Unit,
        onScripts: () -> Unit,
        onQuit: () -> Unit
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            QuickStripRow(
                onHistory = onHistory,
                onDownloads = onDownloads,
                onBookmarks = onBookmarks,
                onNewPrivateTab = onNewPrivateTab
            )

            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    CompactMenuItem(
                        iconRes = R.drawable.fx_ic_appearance_24,
                        label = "Customize Home",
                        onClick = onCustomizeHome
                    )
                    CompactMenuItem(
                        iconRes = R.drawable.fx_ic_extensions_24,
                        label = "Extensions",
                        onClick = onExtensions
                    )
                    CompactMenuItem(
                        iconRes = R.drawable.fx_ic_scripts_24,
                        label = "Userscripts",
                        onClick = onScripts
                    )
                    CompactMenuItem(
                        iconRes = R.drawable.ic_tools,
                        label = "Capture Tools",
                        badgeText = if (isCaptureActive) "REC" else null,
                        badgeAccent = isCaptureActive,
                        onClick = onCaptureTools
                    )
                    CompactMenuItem(
                        iconRes = R.drawable.fx_ic_settings_24,
                        label = "Settings",
                        onClick = onSettings
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                    )
                    CompactMenuItem(
                        iconRes = R.drawable.ic_power,
                        label = "Quit",
                        tint = MaterialTheme.colorScheme.error,
                        onClick = onQuit
                    )
                }
            }
        }
    }

    @Composable
    private fun BrowserMenuState(
        isLandscape: Boolean,
        canGoBack: Boolean,
        canGoForward: Boolean,
        isLoading: Boolean,
        isDesktopMode: Boolean,
        isBookmarked: Boolean,
        isPwaInstallable: Boolean,
        translateBadge: String?,
        isDevToolsActive: Boolean,
        isCaptureActive: Boolean,
        zoomPercent: Int,
        activeExtensionsCount: Int,
        onBack: () -> Unit,
        onForward: () -> Unit,
        onReloadOrStop: () -> Unit,
        onBookmark: () -> Unit,
        onShare: () -> Unit,
        onHistory: () -> Unit,
        onDownloads: () -> Unit,
        onBookmarks: () -> Unit,
        onNewPrivateTab: () -> Unit,
        onTranslate: () -> Unit,
        onToggleDesktop: () -> Unit,
        onFindInPage: () -> Unit,
        onZoomClick: () -> Unit,
        onZoomStep: (Int) -> Unit,
        onAddToHome: () -> Unit,
        onSavePdfPrint: () -> Unit,
        onCopyLink: () -> Unit,
        onOpenInExternalApp: () -> Unit,
        onExtensions: () -> Unit,
        onScripts: () -> Unit,
        onDevTools: () -> Unit,
        onCaptureTools: () -> Unit,
        onSettings: () -> Unit
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 1. Compact Top Navigation Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, enabled = canGoBack) {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_back_24),
                        contentDescription = "Back",
                        tint = if (canGoBack) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onForward, enabled = canGoForward) {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_forward_24),
                        contentDescription = "Forward",
                        tint = if (canGoForward) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onReloadOrStop) {
                    Icon(
                        painter = painterResource(
                            id = if (isLoading) R.drawable.fx_ic_close_24 else R.drawable.uh_ic_reload_24
                        ),
                        contentDescription = if (isLoading) "Stop" else "Reload",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onBookmark) {
                    Icon(
                        painter = painterResource(
                            id = if (isBookmarked) R.drawable.uh_ic_bookmark_fill_24 else R.drawable.uh_ic_bookmark_24
                        ),
                        contentDescription = if (isBookmarked) "Remove Bookmark" else "Bookmark",
                        tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onShare) {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_share_24),
                        contentDescription = "Share",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 2. Compact 4-Tile Quick Strip (History, Downloads, Bookmarks, Private Tab)
            QuickStripRow(
                onHistory = onHistory,
                onDownloads = onDownloads,
                onBookmarks = onBookmarks,
                onNewPrivateTab = onNewPrivateTab
            )

            // 3. Inline Global Page Zoom Control Row
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onZoomClick() }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_zoom_24),
                        contentDescription = "Page Zoom",
                        tint = if (zoomPercent != 100) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Page Zoom",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ZoomChipButton("−") { onZoomStep(-10) }
                        Text(
                            text = "$zoomPercent%",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        )
                        ZoomChipButton("+") { onZoomStep(10) }
                    }
                }
            }

            // 4. Page Tools Card + Add-ons / DevTools Card
            val desktopIconRes = if (isDesktopMode) R.drawable.uh_ic_device_mobile_24 else R.drawable.uh_ic_device_desktop_24
            val desktopLabel = if (isDesktopMode) "Request Mobile Site" else "Request Desktop Site"
            val addHomeLabel = if (isPwaInstallable) "Install App" else "Add to Home Screen"
            val extensionsBadge = if (activeExtensionsCount > 0) activeExtensionsCount.toString() else null

            if (isLandscape) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            CompactMenuItem(
                                iconRes = R.drawable.uh_ic_translate_24,
                                label = "Translate Page",
                                badgeText = translateBadge,
                                badgeAccent = translateBadge != null,
                                onClick = onTranslate
                            )
                            CompactMenuItem(
                                iconRes = R.drawable.fx_ic_search_24,
                                label = "Find in Page",
                                onClick = onFindInPage
                            )
                            CompactMenuItem(
                                iconRes = desktopIconRes,
                                label = desktopLabel,
                                tint = if (isDesktopMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                onClick = onToggleDesktop
                            )
                            CompactMenuItem(
                                iconRes = R.drawable.uh_ic_plus_24,
                                label = addHomeLabel,
                                badgeText = if (isPwaInstallable) "APP" else null,
                                badgeAccent = isPwaInstallable,
                                onClick = onAddToHome
                            )
                            CompactMenuItem(
                                iconRes = R.drawable.uh_ic_print_24,
                                label = "Save as PDF / Print",
                                onClick = onSavePdfPrint
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            CompactMenuItem(
                                iconRes = R.drawable.fx_ic_extensions_24,
                                label = "Extensions",
                                badgeText = extensionsBadge,
                                onClick = onExtensions
                            )
                            CompactMenuItem(
                                iconRes = R.drawable.fx_ic_scripts_24,
                                label = "Userscripts",
                                onClick = onScripts
                            )
                            CompactMenuItem(
                                iconRes = R.drawable.uh_ic_devtools_24,
                                label = "DevTools",
                                badgeText = if (isDevToolsActive) "ON" else null,
                                badgeAccent = isDevToolsActive,
                                onClick = onDevTools
                            )
                            CompactMenuItem(
                                iconRes = R.drawable.ic_tools,
                                label = "Capture Tools",
                                badgeText = if (isCaptureActive) "REC" else null,
                                badgeAccent = isCaptureActive,
                                onClick = onCaptureTools
                            )
                            CompactMenuItem(
                                iconRes = R.drawable.fx_ic_settings_24,
                                label = "Settings",
                                onClick = onSettings
                            )
                        }
                    }
                }
            } else {
                // Page Actions Group
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        CompactMenuItem(
                            iconRes = R.drawable.uh_ic_translate_24,
                            label = "Translate Page",
                            badgeText = translateBadge,
                            badgeAccent = translateBadge != null,
                            onClick = onTranslate
                        )
                        CompactMenuItem(
                            iconRes = R.drawable.fx_ic_search_24,
                            label = "Find in Page",
                            onClick = onFindInPage
                        )
                        CompactMenuItem(
                            iconRes = desktopIconRes,
                            label = desktopLabel,
                            tint = if (isDesktopMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = onToggleDesktop
                        )
                        CompactMenuItem(
                            iconRes = R.drawable.uh_ic_plus_24,
                            label = addHomeLabel,
                            badgeText = if (isPwaInstallable) "APP" else null,
                            badgeAccent = isPwaInstallable,
                            onClick = onAddToHome
                        )
                        CompactMenuItem(
                            iconRes = R.drawable.uh_ic_print_24,
                            label = "Save as PDF / Print",
                            onClick = onSavePdfPrint
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)
                        )

                        // Dual compact link actions: Copy Link | Open in App
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(onClick = onCopyLink)
                                    .padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.uh_ic_copy_24),
                                    contentDescription = "Copy Link",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Copy Link",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(22.dp)
                                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
                            )
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(onClick = onOpenInExternalApp)
                                    .padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.uh_ic_open_in_app_24),
                                    contentDescription = "Open in App",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Open in App",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                // Extensions, Userscripts, DevTools, Capture Tools & Settings Card
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        CompactMenuItem(
                            iconRes = R.drawable.fx_ic_extensions_24,
                            label = "Extensions",
                            badgeText = extensionsBadge,
                            onClick = onExtensions
                        )
                        CompactMenuItem(
                            iconRes = R.drawable.fx_ic_scripts_24,
                            label = "Userscripts",
                            onClick = onScripts
                        )
                        CompactMenuItem(
                            iconRes = R.drawable.uh_ic_devtools_24,
                            label = "DevTools",
                            badgeText = if (isDevToolsActive) "ACTIVE" else null,
                            badgeAccent = isDevToolsActive,
                            onClick = onDevTools
                        )
                        CompactMenuItem(
                            iconRes = R.drawable.ic_tools,
                            label = "Capture Tools",
                            badgeText = if (isCaptureActive) "REC" else null,
                            badgeAccent = isCaptureActive,
                            onClick = onCaptureTools
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)
                        )
                        CompactMenuItem(
                            iconRes = R.drawable.fx_ic_settings_24,
                            label = "Settings",
                            onClick = onSettings
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun QuickStripRow(
        onHistory: () -> Unit,
        onDownloads: () -> Unit,
        onBookmarks: () -> Unit,
        onNewPrivateTab: () -> Unit,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickActionItem(
                label = "History",
                iconRes = R.drawable.ic_history,
                modifier = Modifier.weight(1f),
                onClick = onHistory
            )
            QuickActionItem(
                label = "Downloads",
                iconRes = R.drawable.fx_ic_downloads_24,
                modifier = Modifier.weight(1f),
                onClick = onDownloads
            )
            QuickActionItem(
                label = "Bookmarks",
                iconRes = R.drawable.uh_ic_bookmark_24,
                modifier = Modifier.weight(1f),
                onClick = onBookmarks
            )
            QuickActionItem(
                label = "Private",
                iconRes = R.drawable.uh_ic_private_mode_24,
                modifier = Modifier.weight(1f),
                onClick = onNewPrivateTab
            )
        }
    }

    @Composable
    private fun ZoomChipButton(text: String, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    @Composable
    private fun QuickActionItem(
        label: String,
        iconRes: Int,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        Column(
            modifier = modifier
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onClick() }
                .padding(vertical = 9.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    @Composable
    private fun CompactMenuItem(
        iconRes: Int,
        label: String,
        tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
        badgeText: String? = null,
        badgeAccent: Boolean = false,
        onClick: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onClick() }
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(19.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                color = if (tint == MaterialTheme.colorScheme.error) tint else MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!badgeText.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            if (badgeAccent) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badgeText,
                        color = if (badgeAccent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
