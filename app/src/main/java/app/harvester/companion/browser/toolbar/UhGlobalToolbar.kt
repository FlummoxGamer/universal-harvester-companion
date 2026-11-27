package app.harvester.companion.browser.toolbar

import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import app.harvester.companion.R
import app.harvester.companion.SessionRecorder
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.UHApp
import app.harvester.companion.UHLog
import app.harvester.companion.UserscriptManager
import app.harvester.companion.browser.BrowserViewModel
import app.harvester.companion.browser.menu.BrowserExtensionsSheet
import app.harvester.companion.browser.menu.CaptureQuickSheet
import app.harvester.companion.browser.menu.ExtensionPopupSheet
import app.harvester.companion.browser.menu.MainMenuDialogFragment
import app.harvester.companion.browser.menu.ScriptsSheet
import app.harvester.companion.browser.search.UHSearchEnginePicker
import app.harvester.companion.browser.tabs.ClosedTabRecord
import app.harvester.companion.browser.tabs.InBrowserGroupTabStrip
import app.harvester.companion.browser.tabs.InBrowserPrivateLockOverlay
import app.harvester.companion.browser.tabs.LandscapeTopTabStrip
import app.harvester.companion.browser.tabs.PrivateLockManager
import app.harvester.companion.browser.tabs.PrivateSessionNotificationManager
import app.harvester.companion.browser.tabs.TabGroupStore
import app.harvester.companion.browser.tabs.TabHistoryStore
import app.harvester.companion.browser.tabs.TabMetaStore
import app.harvester.companion.browser.tabs.TabPreviewCache
import app.harvester.companion.browser.translate.TranslatePillPhase
import app.harvester.companion.browser.translate.TranslateSheet
import app.harvester.companion.browser.translate.UhTranslationManager
import app.harvester.companion.browser.webapp.InstallWebAppDialog
import app.harvester.companion.search.UhSearchViewModel
import app.harvester.companion.search.ui.UhSearchScreen
import app.harvester.companion.settings.addons.UhExtensionManager
import app.harvester.companion.settings.addons.ui.ExtensionPermissionPromptSheet
import app.harvester.companion.settings.trustpanel.TrustPanelFragment
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.selector.selectedTab
import java.net.URI
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UhGlobalToolbar(
    viewModel: BrowserViewModel,
    navController: NavController,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val components = (context.applicationContext as UHApp).components
    val isEditing by viewModel.isEditing.collectAsState()
    val isBarBottom by viewModel.isBarBottom.collectAsState()
    val searchEngine by viewModel.searchEngine.collectAsState()
    val editText by viewModel.editText.collectAsState()
    val showFindInPage by viewModel.showFindInPage.collectAsState()
    val findQuery by viewModel.findQuery.collectAsState()
    val findCurrent by viewModel.findCurrent.collectAsState()
    val findTotal by viewModel.findTotal.collectAsState()
    val findMatchCase by viewModel.findMatchCase.collectAsState()
    val findWholeWord by viewModel.findWholeWord.collectAsState()
    val showZoomBar by viewModel.showZoomBar.collectAsState()
    val zoomPercent by viewModel.pageZoomPercent.collectAsState()
    val showScriptsSheet by viewModel.showScriptsSheet.collectAsState()
    val showCaptureQuickSheet by viewModel.showCaptureQuickSheet.collectAsState()
    val showAddToHomeDialog by viewModel.showAddToHomeDialog.collectAsState()
    val showExtensionsSheet by viewModel.showExtensionsSheet.collectAsState()
    val showTranslateSheet by viewModel.showTranslateSheet.collectAsState()
    val showSavePdfSheet by viewModel.showSavePdfSheet.collectAsState()
    val pendingExternalAppPrompt by viewModel.pendingExternalAppPrompt.collectAsState()
    val pendingExtensionPrompt by UhExtensionManager.pendingPrompt.collectAsState()
    val translateState by UhTranslationManager.state.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    var isHostResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            isHostResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val browserState by components.store.stateFlow.collectAsState()
    val selectedTab = browserState.selectedTab
    val allGroups by TabGroupStore.groupsFlow.collectAsState()
    val pinnedTabIds by TabMetaStore.pinnedIdsFlow.collectAsState()
    val mutedTabIds by TabMetaStore.mutedTabIdsFlow.collectAsState()
    val isPrivateLocked by PrivateLockManager.isLockedFlow.collectAsState()
    val groupStripEnabled by SettingsPrefs.tabGroupStripFlow(context).collectAsState()
    val landscapeStripEnabled by SettingsPrefs.tabLandscapeStripFlow(context).collectAsState()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val activePopupExtension = browserState.extensions.values.firstOrNull { it.popupSession != null }
    val searchViewModel: UhSearchViewModel = viewModel()
    var showSearchEnginePicker by remember { mutableStateOf(false) }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestinationId = backStackEntry?.destination?.id
    val isHome = currentDestinationId == R.id.homeFragment || currentDestinationId == null
    val isBrowser = currentDestinationId == R.id.browserFragment

    LaunchedEffect(selectedTab?.id, selectedTab?.content?.url, selectedTab?.translationsState) {
        UhTranslationManager.syncFromGeckoTabState(
            tabId = selectedTab?.id,
            tabUrl = selectedTab?.content?.url,
            tabState = selectedTab?.translationsState,
        )
    }

    LaunchedEffect(isEditing, searchEngine.id) {
        if (isEditing) {
            searchViewModel.onQueryChanged(editText, isBarBottom)
        }
    }

    if (!isHome && !isBrowser && !isEditing) {
        return
    }

    val pendingUrl by viewModel.pendingUrl.collectAsState()
    val rawTabUrl = selectedTab?.content?.url.orEmpty()
    val displayUrl = if (isBrowser) {
        if (rawTabUrl.isNotBlank() && rawTabUrl != "about:blank") rawTabUrl else pendingUrl
    } else ""
    val pageTitle = if (isBrowser) (selectedTab?.content?.title ?: "") else ""
    val pageProgress = if (isBrowser) (selectedTab?.content?.progress ?: 0) else 0
    val isPageLoading = isBrowser && (selectedTab?.content?.loading == true)
    val isDesktopMode = isBrowser && (selectedTab?.content?.desktopMode == true)
    val isPrivateMode = selectedTab?.content?.private == true
    val modeTabCount = browserState.tabs.count { it.content.private == isPrivateMode }
    val isCurrentTabPinned = selectedTab != null && !isPrivateMode && (selectedTab.id in pinnedTabIds)
    val isCurrentTabMuted = selectedTab != null && (TabMetaStore.isTabMuted(selectedTab) || selectedTab.id in mutedTabIds)
    val activeGroup = remember(selectedTab?.id, allGroups) {
        val sid = selectedTab?.id ?: return@remember null
        allGroups.firstOrNull { sid in it.tabIds }
    }
    val activeGroupMembers = remember(activeGroup, browserState.tabs) {
        val grp = activeGroup ?: return@remember emptyList()
        val byId = browserState.tabs.associateBy { it.id }
        grp.tabIds.mapNotNull { byId[it] }
    }
    val showGroupStrip = isBrowser && groupStripEnabled && activeGroup != null && activeGroupMembers.isNotEmpty() && selectedTab?.content?.fullScreen != true
    val showLandscapeStrip = isLandscape && landscapeStripEnabled && browserState.tabs.any { it.content.private == isPrivateMode } && selectedTab?.content?.fullScreen != true

    LaunchedEffect(isBrowser, isBarBottom, showLandscapeStrip, showGroupStrip) {
        if (!isBrowser) {
            viewModel.updateChromeExtraDp(0, 0)
        } else {
            val topExtra = (if (showLandscapeStrip) app.harvester.companion.browser.tabs.LANDSCAPE_TAB_STRIP_HEIGHT_DP else 0) +
                (if (showGroupStrip && !isBarBottom) app.harvester.companion.browser.tabs.GROUP_STRIP_HEIGHT_DP else 0)
            val bottomExtra = if (showGroupStrip && isBarBottom) {
                app.harvester.companion.browser.tabs.GROUP_STRIP_HEIGHT_DP
            } else 0
            viewModel.updateChromeExtraDp(topExtra, bottomExtra)
        }
    }
    val currentHost = remember(displayUrl) {
        try {
            URI(displayUrl).host?.removePrefix("www.").orEmpty()
        } catch (_: Exception) {
            ""
        }
    }
    val scriptMatchCount = remember(displayUrl, showScriptsSheet) {
        if (isBrowser && displayUrl.isNotBlank() && displayUrl != "about:blank") {
            UserscriptManager.matchingEnabledCount(context, displayUrl)
        } else 0
    }
    val isCaptureActive = SessionRecorder.isActive()
    val readerState by app.harvester.companion.browser.reader.ReaderModeManager.state.collectAsState()
    val isTabSecure = selectedTab?.content?.securityInfo?.isSecure == true

    val iconState = if (isBrowser) {
        when {
            displayUrl.startsWith("http://", ignoreCase = true) -> 2
            displayUrl.startsWith("https://", ignoreCase = true) && !isPageLoading && !isTabSecure -> 2
            displayUrl.startsWith("https://", ignoreCase = true) -> 1
            else -> 0
        }
    } else 0

    val openTrustPanel = {
        val activity = context as? AppCompatActivity
        val tab = components.store.state.selectedTab
        if (activity != null && tab != null) {
            try {
                TrustPanelFragment.newInstance(
                    sessionId = tab.id,
                    url = tab.content.url,
                    title = tab.content.title,
                    isSecured = tab.content.securityInfo.isSecure,
                    certificate = tab.content.securityInfo.certificate
                ).show(activity.supportFragmentManager, "TrustPanel")
            } catch (e: Exception) {
                UHLog.throwable("ui_error", "Failed to show TrustPanel", e)
            }
        }
    }

    val openMainMenu = {
        val activity = context as? AppCompatActivity
        if (activity != null) {
            try {
                MainMenuDialogFragment.newInstance(!isBrowser).show(activity.supportFragmentManager, "MainMenu")
            } catch (e: Exception) {
                UHLog.throwable("ui_error", "Failed to show MainMenu", e)
            }
        }
    }

    val openTabsTray = {
        viewModel.requestPreviewCapture()
        val activity = context as? AppCompatActivity
        if (activity != null) {
            try {
                app.harvester.companion.browser.tabs.TabsTrayDialogFragment.newInstance().show(
                    activity.supportFragmentManager,
                    app.harvester.companion.browser.tabs.TabsTrayDialogFragment.TAG
                )
            } catch (e: Exception) {
                UHLog.throwable("ui_error", "Failed to show TabsTray", e)
            }
        }
    }

    val onSearchSubmit = { query: String ->
        val target = viewModel.normalize(query)
        if (target.isNotBlank()) {
            if (isPrivateMode) {
                app.harvester.companion.search.PrivateSearchHistoryStore.record(query)
            }
            try {
                viewModel.setPendingUrl(target)
                if (isBrowser) {
                    components.useCases.sessionUseCases.loadUrl(target)
                } else {
                    val bundle = Bundle().apply { putString("url", target) }
                    navController.navigate(R.id.action_homeFragment_to_browserFragment, bundle)
                }
                viewModel.exitEditMode()
            } catch (e: Exception) {
                UHLog.throwable("ui_error", "Failed to submit address bar query", e)
            }
        }
    }

    val onFillUrl: (String) -> Unit = { url ->
        viewModel.onTextChanged(url)
        searchViewModel.onQueryChanged(url, isBarBottom)
    }

    val onReloadStop: () -> Unit = {
        selectedTab?.let { tab ->
            if (isPageLoading) {
                components.useCases.sessionUseCases.stopLoading(tab.id)
            } else {
                components.useCases.sessionUseCases.reload(tab.id)
            }
        }
    }

    val navigateToHomeSafe: () -> Unit = {
        if (navController.currentDestination?.id != R.id.homeFragment) {
            try {
                navController.popBackStack(R.id.homeFragment, false)
            } catch (_: Exception) {}
        }
    }

    val navigateToBrowserSafe: () -> Unit = {
        if (navController.currentDestination?.id != R.id.browserFragment) {
            try {
                navController.navigate(R.id.action_homeFragment_to_browserFragment)
            } catch (_: Exception) {}
        }
    }

    val openNewModeTab: (Boolean) -> Unit = { priv ->
        viewModel.requestPreviewCapture()
        viewModel.exitEditMode()
        val anchorId = if (SettingsPrefs.getBool(context, SettingsPrefs.K_TAB_OPEN_NEXT_TO_CURRENT, true)) {
            selectedTab?.takeIf { it.content.private == priv }?.id
        } else null
        val newId = components.useCases.tabsUseCases.addTab(
            url = "about:blank",
            selectTab = true,
            private = priv
        )
        if (anchorId != null) TabGroupStore.insertTabAfter(newId, anchorId, priv)
        if (!priv) TabHistoryStore.recordTabAccess(newId)
        if (priv) viewModel.enterEditMode("")
        navigateToHomeSafe()
    }

    val selectStripTab: (String) -> Unit = { tabId ->
        if (tabId != selectedTab?.id) {
            viewModel.requestPreviewCapture()
        }
        val target = browserState.tabs.firstOrNull { it.id == tabId }
        components.useCases.tabsUseCases.selectTab(tabId)
        if (target != null && !target.content.private) {
            TabHistoryStore.recordTabAccess(tabId)
        }
        if (target != null && (target.content.url.isBlank() || target.content.url == "about:blank")) {
            navigateToHomeSafe()
        } else {
            navigateToBrowserSafe()
        }
    }

    val closeStripTab: (String) -> Unit = { tabId ->
        val target = browserState.tabs.firstOrNull { it.id == tabId }
        if (target != null) {
            val rec = ClosedTabRecord(
                url = target.content.url.ifBlank { "about:blank" },
                title = target.content.title,
                isPrivate = target.content.private,
                groupSnapshot = TabGroupStore.groupForTab(tabId)
            )
            TabGroupStore.removeTabFromGroups(tabId)
            TabMetaStore.unpinTab(tabId)
            TabPreviewCache.remove(tabId)
            val jumpToParent = SettingsPrefs.getBool(context, SettingsPrefs.K_TAB_JUMP_TO_PARENT, true)
            components.useCases.tabsUseCases.removeTab(tabId, selectParentIfExists = jumpToParent)
            TabHistoryStore.recordClosedBatch("Tab closed", listOf(rec), target.content.private, allowUndo = true)
            if (browserState.tabs.count { it.content.private == target.content.private } <= 1) {
                navigateToHomeSafe()
            }
        }
    }

    val quickDuplicateCurrentTab: () -> Unit = {
        selectedTab?.let { tab ->
            val dupId = components.useCases.tabsUseCases.addTab(
                url = tab.content.url.ifBlank { "about:blank" },
                selectTab = true,
                private = tab.content.private
            )
            TabGroupStore.insertTabAfter(dupId, tab.id, tab.content.private)
            TabGroupStore.groupForTab(tab.id)?.let { grp ->
                TabGroupStore.addTabToGroup(grp.id, dupId)
            }
            if (!tab.content.private) TabHistoryStore.recordTabAccess(dupId)
            Toast.makeText(context, "Tab duplicated", Toast.LENGTH_SHORT).show()
        }
    }

    val quickTogglePinCurrentTab: () -> Unit = {
        selectedTab?.let { tab ->
            if (!tab.content.private) {
                val nowPinned = TabMetaStore.togglePin(tab.id)
                Toast.makeText(context, if (nowPinned) "Tab pinned" else "Tab unpinned", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val quickToggleMuteCurrentTab: () -> Unit = {
        selectedTab?.let { tab ->
            val nowMuted = TabMetaStore.toggleMute(tab)
            Toast.makeText(context, if (nowMuted) "Tab muted" else "Tab unmuted", Toast.LENGTH_SHORT).show()
        }
    }

    val quickCloseAllModeTabs: () -> Unit = {
        if (isPrivateMode) {
            PrivateSessionNotificationManager.closeAllPrivateTabs(context, components)
            navigateToHomeSafe()
        } else {
            val pinnedSet = TabMetaStore.pinnedIdsFlow.value.toSet()
            val normalTargets = browserState.tabs.filter { !it.content.private && it.id !in pinnedSet }
            if (normalTargets.isEmpty()) {
                Toast.makeText(context, "Pinned tabs are protected", Toast.LENGTH_SHORT).show()
            } else {
                val ids = normalTargets.map { it.id }
                val records = normalTargets.map { t ->
                    ClosedTabRecord(
                        url = t.content.url.ifBlank { "about:blank" },
                        title = t.content.title,
                        isPrivate = false,
                        groupSnapshot = TabGroupStore.groupForTab(t.id)
                    )
                }
                ids.forEach {
                    TabGroupStore.removeTabFromGroups(it)
                    TabPreviewCache.remove(it)
                }
                components.store.dispatch(TabListAction.RemoveTabsAction(ids))
                TabHistoryStore.recordClosedBatch("Closed ${ids.size} tabs", records, false, allowUndo = true)
                if (pinnedSet.isEmpty()) navigateToHomeSafe()
            }
        }
    }

    Box(modifier = modifier) {
        if (isEditing) {
            UhSearchScreen(
                viewModel = searchViewModel,
                isBarBottom = isBarBottom,
                addressBarContent = {
                    UHAddressBar(
                        url = displayUrl,
                        isEditing = true,
                        tabCount = modeTabCount,
                        iconState = iconState,
                        isWallpaperActive = false,
                        editText = editText,
                        isPrivate = isPrivateMode,
                        progress = 0,
                        isLoading = false,
                        scriptMatchCount = scriptMatchCount,
                        isCaptureActive = isCaptureActive,
                        onEditTextChanged = {
                            viewModel.onTextChanged(it)
                            searchViewModel.onQueryChanged(it, isBarBottom)
                        },
                        onSubmit = onSearchSubmit,
                        onEditStart = {},
                        onClear = {
                            viewModel.onTextChanged("")
                            searchViewModel.clearQuery(isBarBottom)
                        },
                        onSearchEngineClick = { showSearchEnginePicker = true },
                        onLockClick = openTrustPanel,
                        onScriptMenuClick = { viewModel.openScriptsSheet() },
                        onMenuClick = openMainMenu,
                        onTabCountClick = openTabsTray,
                        searchEngine = searchEngine
                    )
                },
                onDismiss = onDismiss,
                onSubmit = onSearchSubmit,
                onFillUrl = onFillUrl
            )
        } else {
            val barPositionModifier = when {
                isBarBottom -> Modifier.navigationBarsPadding()
                showLandscapeStrip -> Modifier
                else -> Modifier.statusBarsPadding()
            }
            if (showLandscapeStrip && isBarBottom) {
                LandscapeTopTabStrip(
                    tabs = browserState.tabs,
                    groups = allGroups,
                    pinnedIds = pinnedTabIds,
                    selectedTabId = selectedTab?.id,
                    isPrivate = isPrivateMode,
                    applyStatusBarPadding = true,
                    onSelectTab = selectStripTab,
                    onCloseTab = closeStripTab,
                    onNewTab = { openNewModeTab(isPrivateMode) },
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            } else if (isBrowser && isBarBottom && selectedTab?.content?.fullScreen != true) {
                // Paint continuous status bar backing matching Light / Dark / AMOLED theme
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(MaterialTheme.colorScheme.background)
                        .statusBarsPadding()
                )
            }
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = if (isBarBottom) Alignment.BottomCenter else Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (showLandscapeStrip && !isBarBottom) {
                        LandscapeTopTabStrip(
                            tabs = browserState.tabs,
                            groups = allGroups,
                            pinnedIds = pinnedTabIds,
                            selectedTabId = selectedTab?.id,
                            isPrivate = isPrivateMode,
                            applyStatusBarPadding = true,
                            onSelectTab = selectStripTab,
                            onCloseTab = closeStripTab,
                            onNewTab = { openNewModeTab(isPrivateMode) }
                        )
                    }
                    if (isBrowser && isBarBottom && !showFindInPage && !showZoomBar) {
                        TranslateStatusPill(
                            state = translateState,
                            fromBottom = true,
                            onAcceptOffer = { UhTranslationManager.acceptTranslateOffer() },
                            onRestoreOriginal = { UhTranslationManager.restoreActiveTab() },
                            onClearImageOverlays = { UhTranslationManager.clearImageTranslations() },
                            onRetry = { UhTranslationManager.retryLastTranslation() },
                            onDismiss = { UhTranslationManager.dismissPill() },
                        )
                    }
                    if (isBrowser && showFindInPage && isBarBottom) {
                        FindInPageBar(
                            query = findQuery,
                            current = findCurrent,
                            total = findTotal,
                            matchCase = findMatchCase,
                            wholeWord = findWholeWord,
                            onQueryChange = { viewModel.runFindInPage(it, true, selectedTab?.id) },
                            onToggleMatchCase = { viewModel.toggleFindMatchCase(selectedTab?.id) },
                            onToggleWholeWord = { viewModel.toggleFindWholeWord(selectedTab?.id) },
                            onPrev = { viewModel.runFindInPage(findQuery, false, selectedTab?.id) },
                            onNext = { viewModel.runFindInPage(findQuery, true, selectedTab?.id) },
                            onClose = { viewModel.closeFindInPage(selectedTab?.id) },
                            modifier = Modifier.imePadding()
                        )
                    }
                    if (isBrowser && showZoomBar && isBarBottom) {
                        ZoomAdjustBar(
                            zoomPercent = zoomPercent,
                            onChange = { viewModel.setPageZoom(it) },
                            onDone = { viewModel.closeZoomBar() }
                        )
                    }
                    if (showGroupStrip && isBarBottom && activeGroup != null) {
                        InBrowserGroupTabStrip(
                            group = activeGroup,
                            members = activeGroupMembers,
                            selectedTabId = selectedTab?.id,
                            onSelectTab = selectStripTab,
                            onCloseTab = closeStripTab,
                            onAddTabInGroup = {
                                val newId = components.useCases.tabsUseCases.addTab(
                                    url = "about:blank",
                                    selectTab = true,
                                    private = activeGroup.isPrivate
                                )
                                TabGroupStore.addTabToGroup(activeGroup.id, newId)
                                if (!activeGroup.isPrivate) TabHistoryStore.recordTabAccess(newId)
                                navigateToHomeSafe()
                            }
                        )
                    }

                    UHAddressBar(
                        url = displayUrl,
                        isEditing = false,
                        tabCount = modeTabCount,
                        iconState = iconState,
                        isWallpaperActive = isHome,
                        editText = editText,
                        progress = pageProgress,
                        isLoading = isPageLoading,
                        scriptMatchCount = scriptMatchCount,
                        isCaptureActive = isCaptureActive,
                        isDesktopMode = isDesktopMode,
                        isPrivate = isPrivateMode,
                        isCurrentTabPinned = isCurrentTabPinned,
                        isCurrentTabMuted = isCurrentTabMuted,
                        zoomPercent = zoomPercent,
                        isReaderEligible = isBrowser && readerState.isEligible,
                        isReaderActive = isBrowser && readerState.isActive,
                        onEditTextChanged = { viewModel.onTextChanged(it) },
                        onSubmit = onSearchSubmit,
                        onEditStart = {
                            viewModel.enterEditMode("")
                            searchViewModel.onQueryChanged("", isBarBottom)
                        },
                        onClear = {},
                        onSearchEngineClick = { showSearchEnginePicker = true },
                        onLockClick = openTrustPanel,
                        onScriptMenuClick = { viewModel.openScriptsSheet() },
                        onReaderClick = {
                            app.harvester.companion.browser.reader.ReaderModeManager.toggleReader()
                        },
                        onModeBadgeClick = { viewModel.openZoomBar() },
                        onReloadStopClick = onReloadStop,
                        onMenuClick = openMainMenu,
                        onTabCountClick = openTabsTray,
                        onQuickNewTab = { openNewModeTab(false) },
                        onQuickNewPrivateTab = { openNewModeTab(true) },
                        onQuickDuplicateTab = quickDuplicateCurrentTab,
                        onQuickTogglePinTab = quickTogglePinCurrentTab,
                        onQuickToggleMuteTab = quickToggleMuteCurrentTab,
                        onQuickCloseCurrentTab = { selectedTab?.let { closeStripTab(it.id) } },
                        onQuickCloseAllTabs = quickCloseAllModeTabs,
                        searchEngine = searchEngine,
                        modifier = barPositionModifier
                    )

                    if (showGroupStrip && !isBarBottom && activeGroup != null) {
                        InBrowserGroupTabStrip(
                            group = activeGroup,
                            members = activeGroupMembers,
                            selectedTabId = selectedTab?.id,
                            onSelectTab = selectStripTab,
                            onCloseTab = closeStripTab,
                            onAddTabInGroup = {
                                val newId = components.useCases.tabsUseCases.addTab(
                                    url = "about:blank",
                                    selectTab = true,
                                    private = activeGroup.isPrivate
                                )
                                TabGroupStore.addTabToGroup(activeGroup.id, newId)
                                if (!activeGroup.isPrivate) TabHistoryStore.recordTabAccess(newId)
                                if (activeGroup.isPrivate) viewModel.enterEditMode("")
                                navigateToHomeSafe()
                            }
                        )
                    }
                    if (isBrowser && showFindInPage && !isBarBottom) {
                        FindInPageBar(
                            query = findQuery,
                            current = findCurrent,
                            total = findTotal,
                            matchCase = findMatchCase,
                            wholeWord = findWholeWord,
                            onQueryChange = { viewModel.runFindInPage(it, true, selectedTab?.id) },
                            onToggleMatchCase = { viewModel.toggleFindMatchCase(selectedTab?.id) },
                            onToggleWholeWord = { viewModel.toggleFindWholeWord(selectedTab?.id) },
                            onPrev = { viewModel.runFindInPage(findQuery, false, selectedTab?.id) },
                            onNext = { viewModel.runFindInPage(findQuery, true, selectedTab?.id) },
                            onClose = { viewModel.closeFindInPage(selectedTab?.id) }
                        )
                    }
                    if (isBrowser && showZoomBar && !isBarBottom) {
                        ZoomAdjustBar(
                            zoomPercent = zoomPercent,
                            onChange = { viewModel.setPageZoom(it) },
                            onDone = { viewModel.closeZoomBar() }
                        )
                    }
                    if (isBrowser && !isBarBottom && !showFindInPage && !showZoomBar) {
                        TranslateStatusPill(
                            state = translateState,
                            fromBottom = false,
                            onAcceptOffer = { UhTranslationManager.acceptTranslateOffer() },
                            onRestoreOriginal = { UhTranslationManager.restoreActiveTab() },
                            onClearImageOverlays = { UhTranslationManager.clearImageTranslations() },
                            onRetry = { UhTranslationManager.retryLastTranslation() },
                            onDismiss = { UhTranslationManager.dismissPill() },
                        )
                    }
                }
            }

            if (isPrivateMode && isPrivateLocked) {
                InBrowserPrivateLockOverlay(
                    onUnlock = {
                        val activity = context as? AppCompatActivity
                        if (activity != null) {
                            PrivateLockManager.promptUnlock(
                                context = activity,
                                onError = { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
                            )
                        }
                    },
                    onCloseAllPrivate = {
                        PrivateSessionNotificationManager.closeAllPrivateTabs(context, components)
                        navigateToHomeSafe()
                    }
                )
            }
        }
    }

    if (showSearchEnginePicker) {
        UHSearchEnginePicker(
            onDismiss = { showSearchEnginePicker = false },
            onSelect = { showSearchEnginePicker = false }
        )
    }

    if (showScriptsSheet) {
        ScriptsSheet(
            currentUrl = displayUrl,
            onOpenCaptureSheet = { viewModel.openCaptureQuickSheet() },
            onDismiss = { viewModel.closeScriptsSheet() }
        )
    }

    if (showCaptureQuickSheet) {
        CaptureQuickSheet(
            onDismiss = { viewModel.closeCaptureQuickSheet() }
        )
    }

    if (showTranslateSheet) {
        TranslateSheet(
            tabTranslationsState = selectedTab?.translationsState,
            currentHost = currentHost,
            activeTabId = selectedTab?.id,
            activeTabUrl = displayUrl,
            onDismiss = { viewModel.closeTranslateSheet() }
        )
    }

    if (showAddToHomeDialog && displayUrl.isNotBlank() && displayUrl != "about:blank") {
        InstallWebAppDialog(
            url = displayUrl,
            pageTitle = pageTitle,
            tabIcon = selectedTab?.content?.icon,
            manifest = selectedTab?.content?.webAppManifest,
            onAddToUhHomeGrid = { customTitle ->
                viewModel.addToUhHomeGrid(displayUrl, customTitle)
            },
            onDismiss = { viewModel.closeAddToHomeDialog() }
        )
    }

    if (showSavePdfSheet) {
        SavePdfPrintSheet(
            onSavePdf = {
                viewModel.closeSavePdfSheet()
                viewModel.saveCurrentPageAsPdf(context, pageTitle.ifBlank { currentHost })
            },
            onPrint = {
                viewModel.closeSavePdfSheet()
                viewModel.printCurrentPage(context, pageTitle.ifBlank { currentHost })
            },
            onDismiss = { viewModel.closeSavePdfSheet() }
        )
    }

    pendingExternalAppPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissExternalAppPrompt() },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(22.dp),
            icon = {
                if (prompt.appIcon != null) {
                    Image(
                        bitmap = prompt.appIcon.asImageBitmap(),
                        contentDescription = prompt.appName,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                } else {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_open_in_app_24),
                        contentDescription = prompt.appName,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            },
            title = {
                Text(
                    text = "Open in ${prompt.appName}?",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Leave UH and open ${prompt.host} in ${prompt.appName}?",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmOpenExternalApp(context) },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Open", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissExternalAppPrompt() }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showExtensionsSheet) {
        BrowserExtensionsSheet(
            onOpenUrlInTab = { targetUrl ->
                if (isBrowser) {
                    components.useCases.sessionUseCases.loadUrl(targetUrl)
                } else {
                    val bundle = Bundle().apply { putString("url", targetUrl) }
                    navController.navigate(R.id.action_homeFragment_to_browserFragment, bundle)
                }
            },
            onDismiss = { viewModel.closeExtensionsSheet() }
        )
    }

    val popupExt = activePopupExtension
    val popupSession = popupExt?.popupSession
    if (popupExt != null && popupSession != null) {
        ExtensionPopupSheet(
            extensionId = popupExt.id,
            extensionName = popupExt.name?.ifBlank { popupExt.id } ?: popupExt.id,
            popupSession = popupSession
        )
    }

    val activePrompt = pendingExtensionPrompt
    if (isHostResumed && activePrompt != null) {
        ExtensionPermissionPromptSheet(prompt = activePrompt)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavePdfPrintSheet(
    onSavePdf: () -> Unit,
    onPrint: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Save or Print Page",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onSavePdf)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.fx_ic_downloads_24),
                            contentDescription = "Save as PDF",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(21.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Save Page as PDF",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Exports a full PDF document directly to Downloads",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onPrint)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.uh_ic_print_24),
                            contentDescription = "Print Page",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(21.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Print Page",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Opens system print preview and printer options",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FindInPageBar(
    query: String,
    current: Int,
    total: Int,
    matchCase: Boolean,
    wholeWord: Boolean,
    onQueryChange: (String) -> Unit,
    onToggleMatchCase: () -> Unit,
    onToggleWholeWord: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }
    BackHandler { onClose() }

    val noMatches = query.isNotBlank() && total == 0

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.fx_ic_search_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onNext() }),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        decorationBox = { inner ->
                            if (query.isEmpty()) {
                                Text(
                                    text = "Find in page",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 14.sp
                                )
                            }
                            inner()
                        }
                    )
                    if (query.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            painter = painterResource(id = R.drawable.fx_ic_close_24),
                            contentDescription = "Clear",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onQueryChange("") }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (noMatches) MaterialTheme.colorScheme.error.copy(alpha = 0.16f)
                            else MaterialTheme.colorScheme.surface
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (query.isBlank()) "0/0" else "$current/$total",
                        color = if (noMatches) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                IconButton(onClick = onPrev, enabled = total > 0, modifier = Modifier.size(32.dp)) {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_back_24),
                        contentDescription = "Previous",
                        tint = if (total > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier.size(17.dp)
                    )
                }
                IconButton(onClick = onNext, enabled = total > 0, modifier = Modifier.size(32.dp)) {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_forward_24),
                        contentDescription = "Next",
                        tint = if (total > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier.size(17.dp)
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(
                        painter = painterResource(id = R.drawable.fx_ic_close_24),
                        contentDescription = "Close Find",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            // Filter chips row (Match Case + Whole Word)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FindToggleChip(
                    label = "Aa  Match case",
                    selected = matchCase,
                    onClick = onToggleMatchCase
                )
                FindToggleChip(
                    label = "W  Whole word",
                    selected = wholeWord,
                    onClick = onToggleWholeWord
                )
            }
        }
    }
}

@Composable
private fun FindToggleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    val fg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ZoomAdjustBar(
    zoomPercent: Int,
    onChange: (Int) -> Unit,
    onDone: () -> Unit
) {
    val presets = listOf(75, 100, 125, 150, 200)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.uh_ic_zoom_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Global Page Zoom",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "$zoomPercent%",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (zoomPercent != 100) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Reset",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onChange(100) }
                            .padding(horizontal = 7.dp, vertical = 4.dp)
                    )
                }
                TextButton(onClick = onDone) {
                    Text("Done", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ZoomStepPill("−") { onChange((zoomPercent - 10).coerceIn(50, 300)) }
                Slider(
                    value = zoomPercent.toFloat(),
                    onValueChange = { raw ->
                        val snapped = ((raw / 5f).roundToInt() * 5).coerceIn(50, 300)
                        if (snapped != zoomPercent) onChange(snapped)
                    },
                    valueRange = 50f..300f,
                    modifier = Modifier.weight(1f)
                )
                ZoomStepPill("+") { onChange((zoomPercent + 10).coerceIn(50, 300)) }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                presets.forEach { preset ->
                    val active = zoomPercent == preset
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surface
                            )
                            .clickable { onChange(preset) }
                            .padding(vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$preset%",
                            color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomStepPill(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun TranslateStatusPill(
    state: UhTranslationManager.State,
    fromBottom: Boolean,
    onAcceptOffer: () -> Unit,
    onRestoreOriginal: () -> Unit,
    onClearImageOverlays: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val visible = state.pillPhase != TranslatePillPhase.HIDDEN && state.pillMessage.isNotBlank()
    val isOffer = state.pillPhase == TranslatePillPhase.OFFER
    val isWorking = state.pillPhase == TranslatePillPhase.DOWNLOADING_MODEL ||
        state.pillPhase == TranslatePillPhase.TRANSLATING ||
        state.pillPhase == TranslatePillPhase.TRANSLATING_IMAGES
    val isError = state.pillPhase == TranslatePillPhase.ERROR

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { if (fromBottom) it / 2 else -it / 2 },
        exit = fadeOut() + slideOutVertically { if (fromBottom) it / 2 else -it / 2 },
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .widthIn(max = 368.dp)
                .border(
                    width = 1.dp,
                    color = when {
                        isError -> MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
                        isOffer -> MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
                    },
                    shape = RoundedCornerShape(24.dp)
                )
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isWorking) {
                    if (state.pillProgress > 0.05f && state.pillProgress < 0.99f) {
                        CircularProgressIndicator(
                            progress = { state.pillProgress },
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_translate_24),
                        contentDescription = null,
                        tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Text(
                    text = state.pillMessage,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                if (isOffer) {
                    Text(
                        text = "Translate",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(onClick = onAcceptOffer)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                } else if (state.pillPhase == TranslatePillPhase.COMPLETED && state.pillCanRestore) {
                    Text(
                        text = "Original",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .clickable(onClick = onRestoreOriginal)
                            .padding(horizontal = 9.dp, vertical = 3.dp)
                    )
                } else if (state.pillPhase == TranslatePillPhase.COMPLETED && state.pillCanClearImages) {
                    Text(
                        text = "Clear",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .clickable(onClick = onClearImageOverlays)
                            .padding(horizontal = 9.dp, vertical = 3.dp)
                    )
                } else if (isError && state.pillCanRetry) {
                    Text(
                        text = "Retry",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .clickable(onClick = onRetry)
                            .padding(horizontal = 9.dp, vertical = 3.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.fx_ic_close_24),
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}
