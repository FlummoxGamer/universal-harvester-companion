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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.activityViewModels
import app.harvester.companion.HomeActivity
import app.harvester.companion.R
import app.harvester.companion.SessionRecorder
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.UHApp
import app.harvester.companion.UHLog
import app.harvester.companion.browser.BrowserViewModel
import app.harvester.companion.browser.webapp.WebAppStore
import app.harvester.companion.privacy.UhShieldEngine
import app.harvester.companion.settings.SettingsActivity
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

                    val isBookmarked by browserViewModel.isBookmarked.collectAsState()
                    val zoomPercent by browserViewModel.pageZoomPercent.collectAsState()
                    val devToolsActiveTabIds by browserViewModel.devToolsActiveTabIds.collectAsState()
                    val isDevToolsActive = selectedTab?.id != null && selectedTab.id in devToolsActiveTabIds
                    val isCaptureActive = SessionRecorder.isActive()

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
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                                    .navigationBarsPadding(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp, 4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
                                )

                                Spacer(modifier = Modifier.height(8.dp))

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
                                        canGoBack = canGoBack,
                                        canGoForward = canGoForward,
                                        isLoading = isLoading,
                                        isDesktopMode = isDesktopMode,
                                        isBookmarked = isBookmarked,
                                        isPwaInstallable = isPwaInstallable,
                                        isTranslated = isTranslated,
                                        isDevToolsActive = isDevToolsActive,
                                        isCaptureActive = isCaptureActive,
                                        zoomPercent = zoomPercent,
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
                                                val ctx = requireContext()
                                                val cleanUrl = UhShieldEngine.cleanTrackingUrl(
                                                    rawUrl = currentUrl,
                                                    stripTrackers = SettingsPrefs.getBool(
                                                        ctx,
                                                        SettingsPrefs.K_PG_CLEAR_URLS,
                                                        true,
                                                    ),
                                                    deAmp = SettingsPrefs.getBool(
                                                        ctx,
                                                        SettingsPrefs.K_ADV_DEAMP,
                                                        true,
                                                    ),
                                                )
                                                val intent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_TEXT, cleanUrl)
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

                                Spacer(modifier = Modifier.height(4.dp))
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
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickStripRow(
                onHistory = onHistory,
                onDownloads = onDownloads,
                onBookmarks = onBookmarks,
                onNewPrivateTab = onNewPrivateTab
            )

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    CompactGridTile(
                        iconRes = R.drawable.fx_ic_appearance_24,
                        label = "Customize",
                        modifier = Modifier.weight(1f),
                        onClick = onCustomizeHome
                    )
                    CompactGridTile(
                        iconRes = R.drawable.fx_ic_extensions_24,
                        label = "Extensions",
                        modifier = Modifier.weight(1f),
                        onClick = onExtensions
                    )
                    CompactGridTile(
                        iconRes = R.drawable.fx_ic_scripts_24,
                        label = "Scripts",
                        modifier = Modifier.weight(1f),
                        onClick = onScripts
                    )
                    CompactGridTile(
                        iconRes = R.drawable.ic_tools,
                        label = "Capture",
                        isActive = isCaptureActive,
                        isErrorTint = isCaptureActive,
                        modifier = Modifier.weight(1f),
                        onClick = onCaptureTools
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FooterActionButton(
                        iconRes = R.drawable.fx_ic_settings_24,
                        label = "Settings",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        onClick = onSettings
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(22.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
                    )
                    FooterActionButton(
                        iconRes = R.drawable.ic_power,
                        label = "Quit",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                        onClick = onQuit
                    )
                }
            }
        }
    }

    @Composable
    private fun BrowserMenuState(
        canGoBack: Boolean,
        canGoForward: Boolean,
        isLoading: Boolean,
        isDesktopMode: Boolean,
        isBookmarked: Boolean,
        isPwaInstallable: Boolean,
        isTranslated: Boolean,
        isDevToolsActive: Boolean,
        isCaptureActive: Boolean,
        zoomPercent: Int,
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
        val desktopIconRes = if (isDesktopMode) R.drawable.uh_ic_device_mobile_24 else R.drawable.uh_ic_device_desktop_24
        val desktopLabel = if (isDesktopMode) "Mobile" else "Desktop"

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Row 1: Compact Nav + Inline Zoom Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavIconBtn(
                    iconRes = R.drawable.uh_ic_back_24,
                    contentDesc = "Back",
                    enabled = canGoBack,
                    onClick = onBack,
                    modifier = Modifier.weight(1f)
                )
                NavIconBtn(
                    iconRes = R.drawable.uh_ic_forward_24,
                    contentDesc = "Forward",
                    enabled = canGoForward,
                    onClick = onForward,
                    modifier = Modifier.weight(1f)
                )
                NavIconBtn(
                    iconRes = if (isLoading) R.drawable.fx_ic_close_24 else R.drawable.uh_ic_reload_24,
                    contentDesc = if (isLoading) "Stop" else "Reload",
                    enabled = true,
                    onClick = onReloadOrStop,
                    modifier = Modifier.weight(1f)
                )
                NavIconBtn(
                    iconRes = if (isBookmarked) R.drawable.uh_ic_bookmark_fill_24 else R.drawable.uh_ic_bookmark_24,
                    contentDesc = if (isBookmarked) "Remove Bookmark" else "Bookmark",
                    enabled = true,
                    tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    onClick = onBookmark,
                    modifier = Modifier.weight(1f)
                )
                NavIconBtn(
                    iconRes = R.drawable.uh_ic_share_24,
                    contentDesc = "Share",
                    enabled = true,
                    onClick = onShare,
                    modifier = Modifier.weight(1f)
                )

                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .width(1.dp)
                        .height(22.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                )

                // Inline compact zoom stepper (- 100% +)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 3.dp, vertical = 2.dp)
                ) {
                    InlineZoomButton("−") { onZoomStep(-10) }
                    Text(
                        text = "$zoomPercent%",
                        color = if (zoomPercent != 100) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onZoomClick)
                            .padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                    InlineZoomButton("+") { onZoomStep(10) }
                }
            }

            // Row 2: 4-Tile Quick Library Strip (History, Downloads, Bookmarks, Private)
            QuickStripRow(
                onHistory = onHistory,
                onDownloads = onDownloads,
                onBookmarks = onBookmarks,
                onNewPrivateTab = onNewPrivateTab
            )

            // Row 3: Compact 4×2 Action Grid (clean icons, zero overlay badges, subtle tint for active states)
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        CompactGridTile(
                            iconRes = R.drawable.uh_ic_translate_24,
                            label = "Translate",
                            isActive = isTranslated,
                            modifier = Modifier.weight(1f),
                            onClick = onTranslate
                        )
                        CompactGridTile(
                            iconRes = R.drawable.fx_ic_search_24,
                            label = "Find",
                            modifier = Modifier.weight(1f),
                            onClick = onFindInPage
                        )
                        CompactGridTile(
                            iconRes = desktopIconRes,
                            label = desktopLabel,
                            isActive = isDesktopMode,
                            modifier = Modifier.weight(1f),
                            onClick = onToggleDesktop
                        )
                        CompactGridTile(
                            iconRes = R.drawable.uh_ic_plus_24,
                            label = "Add to Home",
                            isActive = isPwaInstallable,
                            modifier = Modifier.weight(1f),
                            onClick = onAddToHome
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        CompactGridTile(
                            iconRes = R.drawable.fx_ic_extensions_24,
                            label = "Extensions",
                            modifier = Modifier.weight(1f),
                            onClick = onExtensions
                        )
                        CompactGridTile(
                            iconRes = R.drawable.fx_ic_scripts_24,
                            label = "Scripts",
                            modifier = Modifier.weight(1f),
                            onClick = onScripts
                        )
                        CompactGridTile(
                            iconRes = R.drawable.uh_ic_devtools_24,
                            label = "DevTools",
                            isActive = isDevToolsActive,
                            modifier = Modifier.weight(1f),
                            onClick = onDevTools
                        )
                        CompactGridTile(
                            iconRes = R.drawable.ic_tools,
                            label = "Capture",
                            isActive = isCaptureActive,
                            isErrorTint = isCaptureActive,
                            modifier = Modifier.weight(1f),
                            onClick = onCaptureTools
                        )
                    }
                }
            }

            // Row 4: Slim Utility & Settings Bar at bottom thumb edge
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FooterActionButton(
                        iconRes = R.drawable.uh_ic_print_24,
                        label = "Print / PDF",
                        modifier = Modifier.weight(1f),
                        onClick = onSavePdfPrint
                    )
                    FooterDivider()
                    FooterActionButton(
                        iconRes = R.drawable.uh_ic_copy_24,
                        label = "Copy Link",
                        modifier = Modifier.weight(1f),
                        onClick = onCopyLink
                    )
                    FooterDivider()
                    FooterActionButton(
                        iconRes = R.drawable.uh_ic_open_in_app_24,
                        label = "Open App",
                        modifier = Modifier.weight(1f),
                        onClick = onOpenInExternalApp
                    )
                    FooterDivider()
                    FooterActionButton(
                        iconRes = R.drawable.fx_ic_settings_24,
                        label = "Settings",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        onClick = onSettings
                    )
                }
            }
        }
    }

    @Composable
    private fun NavIconBtn(
        iconRes: Int,
        contentDesc: String,
        enabled: Boolean,
        tint: Color = MaterialTheme.colorScheme.onSurface,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(36.dp)
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = contentDesc,
                tint = if (enabled) tint else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
                modifier = Modifier.size(19.dp)
            )
        }
    }

    @Composable
    private fun InlineZoomButton(symbol: String, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = symbol,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
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
                .padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.height(3.dp))
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
    private fun CompactGridTile(
        iconRes: Int,
        label: String,
        isActive: Boolean = false,
        isErrorTint: Boolean = false,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        val iconTint = when {
            isErrorTint -> MaterialTheme.colorScheme.error
            isActive -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        }
        val iconBg = when {
            isErrorTint -> MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
            isActive -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        }

        Column(
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 7.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = label,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = label,
                color = when {
                    isErrorTint -> MaterialTheme.colorScheme.error
                    isActive -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
                fontSize = 11.sp,
                fontWeight = if (isActive || isErrorTint) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }

    @Composable
    private fun FooterDivider() {
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(22.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
        )
    }

    @Composable
    private fun FooterActionButton(
        iconRes: Int,
        label: String,
        tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        Column(
            modifier = modifier
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(17.dp)
            )
            Text(
                text = label,
                color = if (tint == MaterialTheme.colorScheme.error) tint else MaterialTheme.colorScheme.onSurface,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
