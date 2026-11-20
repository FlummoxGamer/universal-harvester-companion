package app.harvester.companion.browser.toolbar

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import app.harvester.companion.R
import app.harvester.companion.SessionRecorder
import app.harvester.companion.ShortcutStore
import app.harvester.companion.UHApp
import app.harvester.companion.UHLog
import app.harvester.companion.UserscriptManager
import app.harvester.companion.browser.BrowserViewModel
import app.harvester.companion.browser.menu.CaptureQuickSheet
import app.harvester.companion.browser.menu.MainMenuDialogFragment
import app.harvester.companion.browser.menu.ScriptsSheet
import app.harvester.companion.browser.search.UHSearchEnginePicker
import app.harvester.companion.search.UhSearchViewModel
import app.harvester.companion.search.ui.UhSearchScreen
import app.harvester.companion.settings.trustpanel.TrustPanelFragment
import mozilla.components.browser.state.selector.selectedTab

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
    val showZoomBar by viewModel.showZoomBar.collectAsState()
    val zoomPercent by viewModel.pageZoomPercent.collectAsState()
    val showScriptsSheet by viewModel.showScriptsSheet.collectAsState()
    val showCaptureQuickSheet by viewModel.showCaptureQuickSheet.collectAsState()
    val showAddToHomeDialog by viewModel.showAddToHomeDialog.collectAsState()

    val browserState by components.store.stateFlow.collectAsState()
    val selectedTab = browserState.selectedTab
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
                            onQueryChange = { viewModel.runFindInPage(it, true, selectedTab?.id) },
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
                            onQueryChange = { viewModel.runFindInPage(it, true, selectedTab?.id) },
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

    if (showAddToHomeDialog) {
        var customTitle by remember(displayUrl, pageTitle) {
            mutableStateOf(pageTitle.ifBlank { ShortcutStore.shortNameFor(displayUrl) })
        }
        AlertDialog(
            onDismissRequest = { viewModel.closeAddToHomeDialog() },
            title = { Text("Add to Home Screen", color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = customTitle,
                        onValueChange = { customTitle = it },
                        label = { Text("Label") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            viewModel.addToUhHomeGrid(displayUrl, customTitle)
                            viewModel.closeAddToHomeDialog()
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Add to UH Home Grid")
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.pinToAndroidHomeScreen(context, displayUrl, customTitle)
                            viewModel.closeAddToHomeDialog()
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Pin to Android Launcher")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.closeAddToHomeDialog() }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
private fun FindInPageBar(
    query: String,
    current: Int,
    total: Int,
    onQueryChange: (String) -> Unit,
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

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text("Find in page", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        }
                        inner()
                    }
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (query.isBlank()) "0/0" else "$current/$total",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            IconButton(onClick = onPrev, modifier = Modifier.size(34.dp)) {
                Icon(
                    painter = painterResource(id = R.drawable.uh_ic_back_24),
                    contentDescription = "Previous",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.size(34.dp)) {
                Icon(
                    painter = painterResource(id = R.drawable.uh_ic_forward_24),
                    contentDescription = "Next",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_close_24),
                    contentDescription = "Close Find",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun ZoomAdjustBar(
    zoomPercent: Int,
    onChange: (Int) -> Unit,
    onDone: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "$zoomPercent%",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Slider(
                value = zoomPercent.toFloat(),
                onValueChange = { onChange((it / 5f).toInt() * 5) },
                valueRange = 50f..200f,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "Reset",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onChange(100) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            TextButton(onClick = onDone) {
                Text("Done", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
