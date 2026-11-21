package app.harvester.companion.browser.toolbar

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import app.harvester.companion.browser.translate.TranslateSheet
import app.harvester.companion.browser.webapp.InstallWebAppDialog
import app.harvester.companion.search.UhSearchViewModel
import app.harvester.companion.search.ui.UhSearchScreen
import app.harvester.companion.settings.addons.UhExtensionManager
import app.harvester.companion.settings.addons.ui.ExtensionPermissionPromptSheet
import app.harvester.companion.settings.trustpanel.TrustPanelFragment
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
    val activePopupExtension = browserState.extensions.values.firstOrNull { it.popupSession != null }
    val searchViewModel: UhSearchViewModel = viewModel()
    var showSearchEnginePicker by remember { mutableStateOf(false) }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestinationId = backStackEntry?.destination?.id
    val isHome = currentDestinationId == R.id.homeFragment || currentDestinationId == null
    val isBrowser = currentDestinationId == R.id.browserFragment

    LaunchedEffect(isEditing, searchEngine.id) {
        if (isEditing) {
            searchViewModel.onQueryChanged(editText, isBarBottom)
        }
    }

    if (!isHome && !isBrowser && !isEditing) {
        return
    }

    val displayUrl = if (isBrowser) (selectedTab?.content?.url ?: "") else ""
    val pageTitle = if (isBrowser) (selectedTab?.content?.title ?: "") else ""
    val pageProgress = if (isBrowser) (selectedTab?.content?.progress ?: 0) else 0
    val isPageLoading = isBrowser && (selectedTab?.content?.loading == true)
    val isDesktopMode = isBrowser && (selectedTab?.content?.desktopMode == true)
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

    val iconState = if (isBrowser) {
        when {
            displayUrl.startsWith("https://") -> 1
            displayUrl.startsWith("http://") -> 2
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
            try {
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

    Box(modifier = modifier) {
        if (isEditing) {
            UhSearchScreen(
                viewModel = searchViewModel,
                isBarBottom = isBarBottom,
                addressBarContent = {
                    UHAddressBar(
                        url = displayUrl,
                        isEditing = true,
                        tabCount = browserState.tabs.size,
                        iconState = iconState,
                        isWallpaperActive = false,
                        editText = editText,
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
            val barPositionModifier = if (isBarBottom) {
                Modifier.navigationBarsPadding()
            } else {
                Modifier.statusBarsPadding()
            }
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = if (isBarBottom) Alignment.BottomCenter else Alignment.TopCenter
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
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

                    UHAddressBar(
                        url = displayUrl,
                        isEditing = false,
                        tabCount = browserState.tabs.size,
                        iconState = iconState,
                        isWallpaperActive = isHome,
                        editText = editText,
                        progress = pageProgress,
                        isLoading = isPageLoading,
                        scriptMatchCount = scriptMatchCount,
                        isCaptureActive = isCaptureActive,
                        isDesktopMode = isDesktopMode,
                        zoomPercent = zoomPercent,
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
                        onModeBadgeClick = { viewModel.openZoomBar() },
                        onReloadStopClick = onReloadStop,
                        onMenuClick = openMainMenu,
                        onTabCountClick = openTabsTray,
                        searchEngine = searchEngine,
                        modifier = barPositionModifier
                    )

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
                }
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
