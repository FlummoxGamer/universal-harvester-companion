package app.harvester.companion.browser.tabs

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.harvester.companion.R
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.ThemePrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mozilla.components.browser.state.state.TabSessionState
import kotlin.math.hypot

private enum class TraySection { NORMAL, PRIVATE, GROUPS }

private sealed interface TrayItem {
    val key: String

    data class GroupCardItem(
        val group: TabGroup,
        val members: List<TabSessionState>
    ) : TrayItem {
        override val key: String get() = "grp_${group.id}"
    }

    data class SingleTabItem(
        val tab: TabSessionState
    ) : TrayItem {
        override val key: String get() = "tab_${tab.id}"
    }
}

@Composable
fun TabsTrayScreen(
    tabs: List<TabSessionState>,
    groups: List<TabGroup>,
    selectedTabId: String?,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onCloseTabsBatch: (tabIds: List<String>, label: String, isPrivate: Boolean) -> Unit,
    onCloseAllTabs: (isPrivate: Boolean) -> Unit,
    onNewTab: (isPrivate: Boolean) -> Unit,
    onNewTabInGroup: (groupId: String, isPrivate: Boolean) -> Unit,
    onDuplicateTab: (TabSessionState) -> Unit,
    onShareTabs: (List<String>) -> Unit,
    onBookmarkTabs: (List<String>) -> Unit,
    onCreateGroup: (name: String, colorArgb: Long, tabIds: List<String>, isPrivate: Boolean) -> Unit,
    onAddTabsToGroup: (groupId: String, tabIds: List<String>) -> Unit,
    onRemoveTabFromGroup: (String) -> Unit,
    onRenameGroup: (groupId: String, name: String, colorArgb: Long) -> Unit,
    onUngroup: (groupId: String) -> Unit,
    onCloseGroupTabs: (group: TabGroup) -> Unit,
    onUndoClose: () -> Unit,
    onRestoreRecentlyClosed: (ClosedTabRecord) -> Unit,
    onRestoreAllRecentlyClosed: () -> Unit,
    onClearRecentlyClosed: () -> Unit,
    onPrivateSectionVisible: (Boolean) -> Unit,
    onPrivateSecurePrefChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val initialPrivate = remember { tabs.find { it.id == selectedTabId }?.content?.private ?: false }
    val pagerState = rememberPagerState(
        initialPage = if (initialPrivate) TraySection.PRIVATE.ordinal else TraySection.NORMAL.ordinal,
        pageCount = { TraySection.entries.size }
    )
    val activeSection = TraySection.entries[pagerState.currentPage]

    // Reactive preferences
    var isListView by remember {
        mutableStateOf(SettingsPrefs.getInt(context, SettingsPrefs.K_TAB_VIEW_MODE, 0) == 1)
    }
    var inactiveDays by remember {
        mutableIntStateOf(SettingsPrefs.getInt(context, SettingsPrefs.K_TAB_INACTIVE_DAYS, 14))
    }
    var blockPrivateScreenshots by remember {
        mutableStateOf(SettingsPrefs.getBool(context, SettingsPrefs.K_TAB_PRIVATE_SECURE_SCREEN, true))
    }
    var sleepSeconds by remember {
        mutableIntStateOf(SettingsPrefs.getInt(context, SettingsPrefs.K_TAB_SLEEP_SECONDS, 120))
    }

    // Stores
    val normalOrder by TabGroupStore.normalOrderFlow.collectAsState()
    val privateOrder by TabGroupStore.privateOrderFlow.collectAsState()
    val recentlyClosed by TabHistoryStore.recentlyClosedFlow.collectAsState()
    val activeUndo by TabHistoryStore.activeUndoFlow.collectAsState()
    val lastAccessedMap by TabHistoryStore.lastAccessedFlow.collectAsState()

    // UI states
    var isSearchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isMultiSelect by remember { mutableStateOf(false) }
    var selectedTabIds by remember { mutableStateOf(setOf<String>()) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var inactiveExpanded by remember { mutableStateOf(false) }

    // Sheet / Dialog states
    var openGroupSheetId by remember { mutableStateOf<String?>(null) }
    var createGroupDraft by remember { mutableStateOf<CreateGroupDraft?>(null) }
    var editingGroup by remember { mutableStateOf<TabGroup?>(null) }
    var addToGroupTabIds by remember { mutableStateOf<Pair<List<String>, Boolean>?>(null) }
    var showRecentlyClosedSheet by remember { mutableStateOf(false) }
    var showTabSettingsSheet by remember { mutableStateOf(false) }

    // Notify window host for FLAG_SECURE and system bar styling
    LaunchedEffect(activeSection) {
        onPrivateSectionVisible(activeSection == TraySection.PRIVATE)
        if (isMultiSelect) {
            isMultiSelect = false
            selectedTabIds = emptySet()
        }
        showOverflowMenu = false
    }

    // Auto-dismiss Undo snackbar after 5.5s
    LaunchedEffect(activeUndo?.batchId) {
        val id = activeUndo?.batchId ?: return@LaunchedEffect
        delay(5500L)
        TabHistoryStore.dismissUndo(id)
    }

    val normalTabs = remember(tabs) { tabs.filter { !it.content.private } }
    val privateTabs = remember(tabs) { tabs.filter { it.content.private } }

    // Keep ordering, orphan groups & tab access records synced
    LaunchedEffect(normalTabs, privateTabs) {
        val normalIds = normalTabs.map { it.id }
        val privateIds = privateTabs.map { it.id }
        TabGroupStore.syncOrderWithTabs(normalIds, isPrivate = false)
        TabGroupStore.syncOrderWithTabs(privateIds, isPrivate = true)
        TabGroupStore.cleanupOrphanedTabs(normalIds.toSet(), privateIds.toSet())
        TabHistoryStore.ensureTracked(normalIds)
        TabHistoryStore.pruneAccessToValid(normalIds.toSet())
    }

    val normalGroups = remember(groups, normalTabs) {
        val valid = normalTabs.map { it.id }.toSet()
        groups.filter { !it.isPrivate && it.tabIds.any { tid -> tid in valid } }
    }
    val privateGroups = remember(groups, privateTabs) {
        val valid = privateTabs.map { it.id }.toSet()
        groups.filter { it.isPrivate && it.tabIds.any { tid -> tid in valid } }
    }
    val allActiveGroups = remember(normalGroups, privateGroups) { normalGroups + privateGroups }

    // Split Normal tabs into Active vs Inactive (strictly untouched >= inactiveDays; never active tab or grouped tabs)
    val nowMs = remember(tabs.size, inactiveDays) { System.currentTimeMillis() }
    val normalGroupedIds = remember(normalGroups) { normalGroups.flatMap { it.tabIds }.toSet() }
    val (activeNormalTabs, inactiveNormalTabs) = remember(normalTabs, normalGroupedIds, lastAccessedMap, inactiveDays, selectedTabId, nowMs) {
        if (inactiveDays <= 0) {
            normalTabs to emptyList()
        } else {
            val cutoffMs = nowMs - inactiveDays * 86_400_000L
            val inactive = mutableListOf<TabSessionState>()
            val active = mutableListOf<TabSessionState>()
            for (t in normalTabs) {
                val last = lastAccessedMap[t.id] ?: nowMs
                val isIdle = t.id != selectedTabId && t.id !in normalGroupedIds && last < cutoffMs
                if (isIdle) inactive.add(t) else active.add(t)
            }
            active to inactive
        }
    }

    // Duplicate detector for the active tab scope (separate 3-dot action, never mixed into Inactive Tabs)
    val currentScopeTabs = if (activeSection == TraySection.PRIVATE) privateTabs else normalTabs
    val duplicateTabIds = remember(currentScopeTabs, selectedTabId) {
        val byNorm = currentScopeTabs
            .filter { normalizeUrlForDedup(it.content.url).isNotBlank() }
            .groupBy { normalizeUrlForDedup(it.content.url) }
        val toClose = mutableListOf<String>()
        for ((_, dupes) in byNorm) {
            if (dupes.size > 1) {
                val keep = dupes.firstOrNull { it.id == selectedTabId } ?: dupes.last()
                dupes.filter { it.id != keep.id }.forEach { toClose.add(it.id) }
            }
        }
        toClose
    }

    // Theme & colors (respects explicit Light/Dark preference as well as Follow System)
    val themeMode by ThemePrefs.modeFlow(context).collectAsState()
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemePrefs.MODE_DARK -> true
        ThemePrefs.MODE_LIGHT -> false
        else -> systemDark
    }
    val surfaceBg by animateColorAsState(
        targetValue = when {
            activeSection == TraySection.PRIVATE && isDark -> Color(0xFF120D1E)
            activeSection == TraySection.PRIVATE && !isDark -> Color(0xFFF4EFFF)
            else -> MaterialTheme.colorScheme.background
        },
        label = "TraySurfaceBg"
    )
    val cardBg by animateColorAsState(
        targetValue = when {
            activeSection == TraySection.PRIVATE && isDark -> Color(0xFF1E1733)
            activeSection == TraySection.PRIVATE && !isDark -> Color(0xFFFFFFFF)
            else -> MaterialTheme.colorScheme.surface
        },
        label = "TrayCardBg"
    )
    val previewBg by animateColorAsState(
        targetValue = when {
            activeSection == TraySection.PRIVATE && isDark -> Color(0xFF161026)
            activeSection == TraySection.PRIVATE && !isDark -> Color(0xFFEDE6FF)
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        label = "TrayPreviewBg"
    )
    val sectionAccent = if (activeSection == TraySection.PRIVATE) {
        if (isDark) Color(0xFFA78BFA) else Color(0xFF7C3AED)
    } else {
        MaterialTheme.colorScheme.primary
    }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val gridColumns = if (isLandscape) 4 else 2

    // Overlays / Sheets / Dialogs
    openGroupSheetId?.let { gid ->
        val grp = groups.firstOrNull { it.id == gid }
        if (grp != null) {
            val scopeMap = (if (grp.isPrivate) privateTabs else normalTabs).associateBy { it.id }
            val members = grp.tabIds.mapNotNull { scopeMap[it] }
            if (members.isEmpty()) {
                openGroupSheetId = null
            } else {
                GroupDetailSheet(
                    group = grp,
                    members = members,
                    selectedTabId = selectedTabId,
                    cardBg = cardBg,
                    previewBg = previewBg,
                    onDismiss = { openGroupSheetId = null },
                    onSelectTab = onSelectTab,
                    onCloseTab = onCloseTab,
                    onRemoveTabFromGroup = onRemoveTabFromGroup,
                    onAddTabInGroup = {
                        openGroupSheetId = null
                        onNewTabInGroup(grp.id, grp.isPrivate)
                    },
                    onEditGroup = { editingGroup = grp },
                    onUngroup = { onUngroup(grp.id) },
                    onCloseGroup = { onCloseGroupTabs(grp) }
                )
            }
        } else {
            openGroupSheetId = null
        }
    }

    createGroupDraft?.let { draft ->
        val scopePool = if (draft.isPrivate) privateTabs else normalTabs
        CreateOrEditGroupDialog(
            title = "New Tab Group",
            initialName = draft.suggestedName,
            initialColorArgb = TabGroupStore.GROUP_COLORS[allActiveGroups.size % TabGroupStore.GROUP_COLORS.size],
            confirmLabel = "Create",
            availableTabsToPick = if (draft.showTabPicker) scopePool else emptyList(),
            preselectedTabIds = draft.preselectedIds.toSet(),
            isPrivateGroup = draft.isPrivate,
            onConfirm = { name, color, pickedIds ->
                val finalIds = if (draft.showTabPicker) pickedIds else draft.preselectedIds
                onCreateGroup(name, color, finalIds, draft.isPrivate)
                createGroupDraft = null
                isMultiSelect = false
                selectedTabIds = emptySet()
            },
            onDismiss = { createGroupDraft = null }
        )
    }

    editingGroup?.let { grp ->
        CreateOrEditGroupDialog(
            title = "Edit Group",
            initialName = grp.name,
            initialColorArgb = grp.colorArgb,
            confirmLabel = "Save",
            isPrivateGroup = grp.isPrivate,
            onConfirm = { name, color, _ ->
                onRenameGroup(grp.id, name, color)
                editingGroup = null
            },
            onDismiss = { editingGroup = null }
        )
    }

    addToGroupTabIds?.let { (tabIds, isPriv) ->
        val eligibleGroups = if (isPriv) privateGroups else normalGroups
        AddToGroupSheet(
            targetTabIds = tabIds,
            existingGroups = eligibleGroups,
            accentColor = if (isPriv) Color(0xFF8B5CF6) else MaterialTheme.colorScheme.primary,
            onCreateNewGroup = {
                createGroupDraft = CreateGroupDraft(
                    suggestedName = "Group ${allActiveGroups.size + 1}",
                    preselectedIds = tabIds,
                    isPrivate = isPriv,
                    showTabPicker = false
                )
            },
            onSelectExistingGroup = { gid ->
                onAddTabsToGroup(gid, tabIds)
                isMultiSelect = false
                selectedTabIds = emptySet()
            },
            onDismiss = { addToGroupTabIds = null }
        )
    }

    if (showRecentlyClosedSheet) {
        RecentlyClosedSheet(
            records = recentlyClosed,
            onRestoreTab = onRestoreRecentlyClosed,
            onRestoreAll = onRestoreAllRecentlyClosed,
            onClearAll = onClearRecentlyClosed,
            onDismiss = { showRecentlyClosedSheet = false }
        )
    }

    if (showTabSettingsSheet) {
        TabSettingsSheet(
            isListView = isListView,
            inactiveDays = inactiveDays,
            blockPrivateScreenshots = blockPrivateScreenshots,
            sleepSeconds = sleepSeconds,
            onViewModeChange = { listMode ->
                isListView = listMode
                SettingsPrefs.setInt(context, SettingsPrefs.K_TAB_VIEW_MODE, if (listMode) 1 else 0)
            },
            onInactiveDaysChange = { days ->
                inactiveDays = days
                SettingsPrefs.setInt(context, SettingsPrefs.K_TAB_INACTIVE_DAYS, days)
            },
            onBlockPrivateScreenshotsChange = { enabled ->
                blockPrivateScreenshots = enabled
                SettingsPrefs.setBool(context, SettingsPrefs.K_TAB_PRIVATE_SECURE_SCREEN, enabled)
                onPrivateSecurePrefChanged(enabled)
            },
            onSleepSecondsChange = { sec ->
                sleepSeconds = sec
                SettingsPrefs.setInt(context, SettingsPrefs.K_TAB_SLEEP_SECONDS, sec)
            },
            onDismiss = { showTabSettingsSheet = false }
        )
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = surfaceBg
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                // Top Chrome: Multi-Select Bar OR Search Bar OR Normal Segmented Switcher
                when {
                    isMultiSelect && activeSection != TraySection.GROUPS -> {
                        val scopeList = if (activeSection == TraySection.PRIVATE) privateTabs else normalTabs
                        MultiSelectTopBar(
                            selectedCount = selectedTabIds.size,
                            totalCount = scopeList.size,
                            accentColor = sectionAccent,
                            onToggleSelectAll = {
                                selectedTabIds = if (selectedTabIds.size == scopeList.size && scopeList.isNotEmpty()) {
                                    emptySet()
                                } else {
                                    scopeList.map { it.id }.toSet()
                                }
                            },
                            onExit = {
                                isMultiSelect = false
                                selectedTabIds = emptySet()
                            }
                        )
                    }

                    isSearchOpen -> {
                        TabsSearchTopBar(
                            query = searchQuery,
                            accentColor = sectionAccent,
                            onQueryChange = { searchQuery = it },
                            onClose = {
                                isSearchOpen = false
                                searchQuery = ""
                            }
                        )
                    }

                    else -> {
                        TabsTrayTopHeader(
                            activeSection = activeSection,
                            normalCount = normalTabs.size,
                            privateCount = privateTabs.size,
                            groupCount = allActiveGroups.size,
                            sectionAccent = sectionAccent,
                            onSelectSection = { target ->
                                scope.launch { pagerState.animateScrollToPage(target.ordinal) }
                            },
                            onOpenSearch = { isSearchOpen = true },
                            onDismiss = onDismiss
                        )
                    }
                }

                // Swipeable HorizontalPager across Normal | Private | Groups
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = !isMultiSelect,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) { pageIndex ->
                    when (TraySection.entries[pageIndex]) {
                        TraySection.NORMAL -> {
                            TabCollectionPage(
                                isPrivate = false,
                                tabs = activeNormalTabs,
                                allScopeTabs = normalTabs,
                                inactiveTabs = inactiveNormalTabs,
                                inactiveDays = inactiveDays,
                                inactiveExpanded = inactiveExpanded,
                                onToggleInactiveExpanded = { inactiveExpanded = !inactiveExpanded },
                                groups = normalGroups,
                                tabOrder = normalOrder,
                                selectedTabId = selectedTabId,
                                searchQuery = if (isSearchOpen) searchQuery else "",
                                isListView = isListView,
                                gridColumns = gridColumns,
                                isMultiSelect = isMultiSelect,
                                selectedTabIds = selectedTabIds,
                                accentColor = MaterialTheme.colorScheme.primary,
                                cardBg = cardBg,
                                previewBg = previewBg,
                                onSelectTab = onSelectTab,
                                onToggleChecked = { tid ->
                                    selectedTabIds = if (tid in selectedTabIds) selectedTabIds - tid else selectedTabIds + tid
                                },
                                onCloseTab = onCloseTab,
                                onCloseInactiveAll = {
                                    if (inactiveNormalTabs.isNotEmpty()) {
                                        onCloseTabsBatch(
                                            inactiveNormalTabs.map { it.id },
                                            "Closed ${inactiveNormalTabs.size} inactive tabs",
                                            false
                                        )
                                    }
                                },
                                onCloseAllPrivate = {},
                                onNewTab = { onNewTab(false) },
                                onOpenGroup = { openGroupSheetId = it.id },
                                onAddTabInGroup = { onNewTabInGroup(it.id, false) },
                                onEditGroup = { editingGroup = it },
                                onUngroup = { onUngroup(it.id) },
                                onCloseGroup = { onCloseGroupTabs(it) },
                                onRequestAddToGroup = { tabId -> addToGroupTabIds = listOf(tabId) to false },
                                onRemoveFromGroup = onRemoveTabFromGroup,
                                onDuplicateTab = onDuplicateTab,
                                onShareTab = { onShareTabs(listOf(it)) },
                                onDragDropCreateGroup = { tabA, tabB ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    createGroupDraft = CreateGroupDraft(
                                        suggestedName = "Group ${allActiveGroups.size + 1}",
                                        preselectedIds = listOf(tabA, tabB),
                                        isPrivate = false,
                                        showTabPicker = false
                                    )
                                },
                                onDragDropAddToGroup = { gid, tabId ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onAddTabsToGroup(gid, listOf(tabId))
                                },
                                onReorderTab = { fromId, toId ->
                                    TabGroupStore.moveTabOrder(fromId, toId, isPrivate = false)
                                }
                            )
                        }

                        TraySection.PRIVATE -> {
                            TabCollectionPage(
                                isPrivate = true,
                                tabs = privateTabs,
                                allScopeTabs = privateTabs,
                                inactiveTabs = emptyList(),
                                inactiveDays = 0,
                                inactiveExpanded = false,
                                onToggleInactiveExpanded = {},
                                groups = privateGroups,
                                tabOrder = privateOrder,
                                selectedTabId = selectedTabId,
                                searchQuery = if (isSearchOpen) searchQuery else "",
                                isListView = isListView,
                                gridColumns = gridColumns,
                                isMultiSelect = isMultiSelect,
                                selectedTabIds = selectedTabIds,
                                accentColor = if (isDark) Color(0xFFA78BFA) else Color(0xFF7C3AED),
                                cardBg = cardBg,
                                previewBg = previewBg,
                                onSelectTab = onSelectTab,
                                onToggleChecked = { tid ->
                                    selectedTabIds = if (tid in selectedTabIds) selectedTabIds - tid else selectedTabIds + tid
                                },
                                onCloseTab = onCloseTab,
                                onCloseInactiveAll = {},
                                onCloseAllPrivate = { onCloseAllTabs(true) },
                                onNewTab = { onNewTab(true) },
                                onOpenGroup = { openGroupSheetId = it.id },
                                onAddTabInGroup = { onNewTabInGroup(it.id, true) },
                                onEditGroup = { editingGroup = it },
                                onUngroup = { onUngroup(it.id) },
                                onCloseGroup = { onCloseGroupTabs(it) },
                                onRequestAddToGroup = { tabId -> addToGroupTabIds = listOf(tabId) to true },
                                onRemoveFromGroup = onRemoveTabFromGroup,
                                onDuplicateTab = onDuplicateTab,
                                onShareTab = { onShareTabs(listOf(it)) },
                                onDragDropCreateGroup = { tabA, tabB ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    createGroupDraft = CreateGroupDraft(
                                        suggestedName = "Private ${privateGroups.size + 1}",
                                        preselectedIds = listOf(tabA, tabB),
                                        isPrivate = true,
                                        showTabPicker = false
                                    )
                                },
                                onDragDropAddToGroup = { gid, tabId ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onAddTabsToGroup(gid, listOf(tabId))
                                },
                                onReorderTab = { fromId, toId ->
                                    TabGroupStore.moveTabOrder(fromId, toId, isPrivate = true)
                                }
                            )
                        }

                        TraySection.GROUPS -> {
                            GroupsDirectoryPage(
                                groups = allActiveGroups,
                                normalTabs = normalTabs,
                                privateTabs = privateTabs,
                                searchQuery = if (isSearchOpen) searchQuery else "",
                                cardBg = cardBg,
                                previewBg = previewBg,
                                onOpenGroup = { openGroupSheetId = it.id },
                                onSelectTab = onSelectTab,
                                onAddTabInGroup = { onNewTabInGroup(it.id, it.isPrivate) },
                                onEditGroup = { editingGroup = it },
                                onUngroup = { onUngroup(it.id) },
                                onCloseGroup = { onCloseGroupTabs(it) },
                                onCreateNewGroup = {
                                    createGroupDraft = CreateGroupDraft(
                                        suggestedName = "Group ${allActiveGroups.size + 1}",
                                        preselectedIds = emptyList(),
                                        isPrivate = false,
                                        showTabPicker = true
                                    )
                                }
                            )
                        }
                    }
                }

                // Bottom Chrome: Spacious Multi-Select Action Dock OR Standard Bottom Bar
                if (isMultiSelect && activeSection != TraySection.GROUPS) {
                    MultiSelectBottomDock(
                        selectedCount = selectedTabIds.size,
                        isPrivate = activeSection == TraySection.PRIVATE,
                        accentColor = sectionAccent,
                        onGroup = {
                            if (selectedTabIds.isNotEmpty()) {
                                addToGroupTabIds = selectedTabIds.toList() to (activeSection == TraySection.PRIVATE)
                            }
                        },
                        onBookmark = {
                            if (selectedTabIds.isNotEmpty()) {
                                onBookmarkTabs(selectedTabIds.toList())
                                isMultiSelect = false
                                selectedTabIds = emptySet()
                            }
                        },
                        onShare = {
                            if (selectedTabIds.isNotEmpty()) {
                                onShareTabs(selectedTabIds.toList())
                            }
                        },
                        onCloseSelected = {
                            if (selectedTabIds.isNotEmpty()) {
                                val ids = selectedTabIds.toList()
                                onCloseTabsBatch(
                                    ids,
                                    "Closed ${ids.size} ${if (ids.size == 1) "tab" else "tabs"}",
                                    activeSection == TraySection.PRIVATE
                                )
                                isMultiSelect = false
                                selectedTabIds = emptySet()
                            }
                        }
                    )
                } else {
                    TabsTrayBottomDock(
                        activeSection = activeSection,
                        hasTabs = currentScopeTabs.isNotEmpty(),
                        isListView = isListView,
                        recentlyClosedCount = recentlyClosed.size,
                        duplicateCount = duplicateTabIds.size,
                        accentColor = sectionAccent,
                        showMenu = showOverflowMenu,
                        onMenuToggle = { showOverflowMenu = it },
                        onToggleViewMode = {
                            isListView = !isListView
                            SettingsPrefs.setInt(context, SettingsPrefs.K_TAB_VIEW_MODE, if (isListView) 1 else 0)
                        },
                        onCreateNewGroup = {
                            showOverflowMenu = false
                            val isPriv = activeSection == TraySection.PRIVATE
                            createGroupDraft = CreateGroupDraft(
                                suggestedName = if (isPriv) "Private ${privateGroups.size + 1}" else "Group ${allActiveGroups.size + 1}",
                                preselectedIds = emptyList(),
                                isPrivate = isPriv,
                                showTabPicker = true
                            )
                        },
                        onStartSelect = {
                            showOverflowMenu = false
                            isMultiSelect = true
                            selectedTabIds = emptySet()
                        },
                        onOpenRecentlyClosed = {
                            showOverflowMenu = false
                            showRecentlyClosedSheet = true
                        },
                        onCloseDuplicates = {
                            showOverflowMenu = false
                            if (duplicateTabIds.isNotEmpty()) {
                                onCloseTabsBatch(
                                    duplicateTabIds,
                                    "Closed ${duplicateTabIds.size} duplicate ${if (duplicateTabIds.size == 1) "tab" else "tabs"}",
                                    activeSection == TraySection.PRIVATE
                                )
                            }
                        },
                        onCloseAll = {
                            showOverflowMenu = false
                            onCloseAllTabs(activeSection == TraySection.PRIVATE)
                        },
                        onOpenTabSettings = {
                            showOverflowMenu = false
                            showTabSettingsSheet = true
                        },
                        onPrimaryFabClick = {
                            when (activeSection) {
                                TraySection.NORMAL -> onNewTab(false)
                                TraySection.PRIVATE -> onNewTab(true)
                                TraySection.GROUPS -> {
                                    createGroupDraft = CreateGroupDraft(
                                        suggestedName = "Group ${allActiveGroups.size + 1}",
                                        preselectedIds = emptyList(),
                                        isPrivate = false,
                                        showTabPicker = true
                                    )
                                }
                            }
                        }
                    )
                }
            }

            // Floating Firefox-Style Undo Snackbar above Bottom Dock
            AnimatedVisibility(
                visible = activeUndo != null,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 74.dp)
            ) {
                activeUndo?.let { batch ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.inverseSurface,
                        shadowElevation = 8.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = batch.label,
                                color = MaterialTheme.colorScheme.inverseOnSurface,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = onUndoClose,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "UNDO",
                                    color = MaterialTheme.colorScheme.inversePrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class CreateGroupDraft(
    val suggestedName: String,
    val preselectedIds: List<String>,
    val isPrivate: Boolean,
    val showTabPicker: Boolean
)

@Composable
private fun TabsTrayTopHeader(
    activeSection: TraySection,
    normalCount: Int,
    privateCount: Int,
    groupCount: Int,
    sectionAccent: Color,
    onSelectSection: (TraySection) -> Unit,
    onOpenSearch: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionPill(
                label = "Tabs",
                count = normalCount,
                selected = activeSection == TraySection.NORMAL,
                accentColor = MaterialTheme.colorScheme.primary,
                iconRes = R.drawable.uh_ic_tab_modern_24,
                onClick = { onSelectSection(TraySection.NORMAL) }
            )
            SectionPill(
                label = "Private",
                count = privateCount,
                selected = activeSection == TraySection.PRIVATE,
                accentColor = if (activeSection == TraySection.PRIVATE) sectionAccent else Color(0xFF9F7AEA),
                iconRes = R.drawable.uh_ic_private_vibe_24,
                onClick = { onSelectSection(TraySection.PRIVATE) }
            )
            SectionPill(
                label = "Groups",
                count = groupCount,
                selected = activeSection == TraySection.GROUPS,
                accentColor = MaterialTheme.colorScheme.primary,
                iconRes = R.drawable.ic_grid_view,
                onClick = { onSelectSection(TraySection.GROUPS) }
            )
        }

        IconButton(onClick = onOpenSearch, modifier = Modifier.size(36.dp)) {
            Icon(
                painter = painterResource(id = R.drawable.fx_ic_search_24),
                contentDescription = "Search open tabs",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
            Icon(
                painter = painterResource(id = R.drawable.fx_ic_close_24),
                contentDescription = "Close tabs switcher",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun SectionPill(
    label: String,
    count: Int,
    selected: Boolean,
    accentColor: Color,
    iconRes: Int,
    onClick: () -> Unit
) {
    val bg = if (selected) accentColor.copy(alpha = 0.16f) else Color.Transparent
    val fg = if (selected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = if (selected) accentColor.copy(alpha = 0.42f) else Color.Transparent,
                shape = RoundedCornerShape(18.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = label,
            tint = fg,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false
        )
        if (count > 0 || selected) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(fg.copy(alpha = 0.16f))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(
                    text = if (count > 99) "99+" else "$count",
                    color = fg,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun TabsSearchTopBar(
    query: String,
    accentColor: Color,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = R.drawable.fx_ic_search_24),
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "Search open tabs & groups…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp
                    ),
                    cursorBrush = SolidColor(accentColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
            }
            if (query.isNotEmpty()) {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_close_24),
                    contentDescription = "Clear search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onQueryChange("") }
                )
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        TextButton(onClick = onClose) {
            Text("Cancel", color = accentColor, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * Clean, spacious Top Multi-Select Bar — zero button crowding, zero wrapped "Done" text.
 */
@Composable
private fun MultiSelectTopBar(
    selectedCount: Int,
    totalCount: Int,
    accentColor: Color,
    onToggleSelectAll: () -> Unit,
    onExit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onExit, modifier = Modifier.size(38.dp)) {
            Icon(
                painter = painterResource(id = R.drawable.fx_ic_close_24),
                contentDescription = "Exit selection",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = if (selectedCount == 0) "Select tabs" else "$selectedCount selected",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onToggleSelectAll) {
            Text(
                text = if (selectedCount == totalCount && totalCount > 0) "Deselect all" else "Select all",
                color = accentColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * Full-width Bottom Multi-Select Action Dock — evenly spaced icon+label controls with tons of breathing room.
 */
@Composable
private fun MultiSelectBottomDock(
    selectedCount: Int,
    isPrivate: Boolean,
    accentColor: Color,
    onGroup: () -> Unit,
    onBookmark: () -> Unit,
    onShare: () -> Unit,
    onCloseSelected: () -> Unit
) {
    val enabled = selectedCount > 0
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MultiSelectActionButton(
                label = "Group",
                iconRes = R.drawable.ic_grid_view,
                tint = if (enabled) accentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                enabled = enabled,
                onClick = onGroup,
                modifier = Modifier.weight(1f)
            )
            if (!isPrivate) {
                MultiSelectActionButton(
                    label = "Bookmark",
                    iconRes = R.drawable.uh_ic_bookmark_24,
                    tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    enabled = enabled,
                    onClick = onBookmark,
                    modifier = Modifier.weight(1f)
                )
            }
            MultiSelectActionButton(
                label = "Share",
                iconRes = R.drawable.uh_ic_share_24,
                tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                enabled = enabled,
                onClick = onShare,
                modifier = Modifier.weight(1f)
            )
            MultiSelectActionButton(
                label = "Close",
                iconRes = R.drawable.fx_ic_delete_24,
                tint = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                enabled = enabled,
                onClick = onCloseSelected,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MultiSelectActionButton(
    label: String,
    iconRes: Int,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun TabCollectionPage(
    isPrivate: Boolean,
    tabs: List<TabSessionState>,
    allScopeTabs: List<TabSessionState>,
    inactiveTabs: List<TabSessionState>,
    inactiveDays: Int,
    inactiveExpanded: Boolean,
    onToggleInactiveExpanded: () -> Unit,
    groups: List<TabGroup>,
    tabOrder: List<String>,
    selectedTabId: String?,
    searchQuery: String,
    isListView: Boolean,
    gridColumns: Int,
    isMultiSelect: Boolean,
    selectedTabIds: Set<String>,
    accentColor: Color,
    cardBg: Color,
    previewBg: Color,
    onSelectTab: (String) -> Unit,
    onToggleChecked: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onCloseInactiveAll: () -> Unit,
    onCloseAllPrivate: () -> Unit,
    onNewTab: () -> Unit,
    onOpenGroup: (TabGroup) -> Unit,
    onAddTabInGroup: (TabGroup) -> Unit,
    onEditGroup: (TabGroup) -> Unit,
    onUngroup: (TabGroup) -> Unit,
    onCloseGroup: (TabGroup) -> Unit,
    onRequestAddToGroup: (String) -> Unit,
    onRemoveFromGroup: (String) -> Unit,
    onDuplicateTab: (TabSessionState) -> Unit,
    onShareTab: (String) -> Unit,
    onDragDropCreateGroup: (String, String) -> Unit,
    onDragDropAddToGroup: (String, String) -> Unit,
    onReorderTab: (String, String) -> Unit
) {
    val q = searchQuery.trim().lowercase()

    // In multi-select or active search, flatten all tabs so every tab can be picked or found directly
    val displayItems: List<TrayItem> = remember(tabs, allScopeTabs, groups, tabOrder, q, isMultiSelect) {
        val tabMap = tabs.associateBy { it.id }
        val allMap = allScopeTabs.associateBy { it.id }
        if (q.isNotBlank()) {
            val matchedGroups = groups.mapNotNull { g ->
                val members = g.tabIds.mapNotNull { allMap[it] }
                val groupMatches = g.name.lowercase().contains(q) || members.any {
                    it.content.title.lowercase().contains(q) || it.content.url.lowercase().contains(q)
                }
                if (groupMatches && members.isNotEmpty()) TrayItem.GroupCardItem(g, members) else null
            }
            val matchedTabs = allScopeTabs.filter {
                it.content.title.lowercase().contains(q) || it.content.url.lowercase().contains(q)
            }.map { TrayItem.SingleTabItem(it) }
            return@remember matchedGroups + matchedTabs
        }

        if (isMultiSelect) {
            return@remember allScopeTabs.map { TrayItem.SingleTabItem(it) }
        }

        val items = mutableListOf<TrayItem>()
        val groupedIds = mutableSetOf<String>()
        for (g in groups) {
            val members = g.tabIds.mapNotNull { tabMap[it] }
            if (members.isNotEmpty()) {
                items.add(TrayItem.GroupCardItem(g, members))
                groupedIds.addAll(members.map { it.id })
            }
        }
        val orderedIds = (tabOrder + tabs.map { it.id }).distinct()
        for (id in orderedIds) {
            if (id !in groupedIds) {
                tabMap[id]?.let { items.add(TrayItem.SingleTabItem(it)) }
            }
        }
        items
    }

    // Live pointer-tracked drag-and-drop state (zero SwipeToDismiss conflicts!)
    val itemBounds = remember { mutableStateMapOf<String, Rect>() }
    var draggingTabId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dropTargetKey by remember { mutableStateOf<String?>(null) }
    var contextMenuTabId by remember { mutableStateOf<String?>(null) }
    var lastLongPressAtMs by remember { mutableStateOf(0L) }
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(displayItems) {
        val activeKeys = displayItems.map { it.key }.toSet()
        itemBounds.keys.retainAll(activeKeys)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (isPrivate && allScopeTabs.isNotEmpty()) {
            PrivateSessionStatusStrip(
                count = allScopeTabs.size,
                accentColor = accentColor,
                onCloseAllPrivate = onCloseAllPrivate
            )
        }

        if (allScopeTabs.isEmpty() && q.isBlank()) {
            EmptyModeState(
                isPrivate = isPrivate,
                accentColor = accentColor,
                onNewTab = onNewTab,
                modifier = Modifier.weight(1f)
            )
        } else if (displayItems.isEmpty() && inactiveTabs.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (q.isNotBlank()) "No tabs match \"$searchQuery\"" else "No tabs open",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        } else if (isListView) {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                items(displayItems, key = { it.key }) { item ->
                    when (item) {
                        is TrayItem.GroupCardItem -> {
                            TabGroupListCard(
                                group = item.group,
                                members = item.members,
                                cardBg = cardBg,
                                previewBg = previewBg,
                                onOpenGroup = { onOpenGroup(item.group) },
                                onTabClick = onSelectTab,
                                onAddTabInGroup = { onAddTabInGroup(item.group) },
                                onEdit = { onEditGroup(item.group) },
                                onUngroup = { onUngroup(item.group) },
                                onCloseGroup = { onCloseGroup(item.group) }
                            )
                        }

                        is TrayItem.SingleTabItem -> {
                            val tab = item.tab
                            val grp = groups.firstOrNull { tab.id in it.tabIds }
                            TabListRow(
                                tab = tab,
                                group = grp,
                                accentColor = accentColor,
                                cardBg = cardBg,
                                previewBg = previewBg,
                                isSelected = tab.id == selectedTabId,
                                isMultiSelect = isMultiSelect,
                                isChecked = tab.id in selectedTabIds,
                                showContextMenu = contextMenuTabId == tab.id,
                                onDismissContextMenu = { contextMenuTabId = null },
                                onClick = {
                                    if (System.currentTimeMillis() - lastLongPressAtMs >= 350L) {
                                        if (isMultiSelect) onToggleChecked(tab.id) else onSelectTab(tab.id)
                                    }
                                },
                                onClose = { onCloseTab(tab.id) },
                                onAddToGroup = { onRequestAddToGroup(tab.id) },
                                onRemoveFromGroup = if (grp != null) ({ onRemoveFromGroup(tab.id) }) else null,
                                onDuplicate = { onDuplicateTab(tab) },
                                onShare = { onShareTab(tab.id) },
                                modifier = Modifier.pointerInput(tab.id, isMultiSelect) {
                                    if (!isMultiSelect) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                lastLongPressAtMs = System.currentTimeMillis()
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                contextMenuTabId = tab.id
                                            },
                                            onDragEnd = {
                                                lastLongPressAtMs = System.currentTimeMillis()
                                            },
                                            onDrag = { _, _ -> }
                                        )
                                    }
                                }
                            )
                        }
                    }
                }

                if (!isPrivate && !isMultiSelect && q.isBlank() && inactiveTabs.isNotEmpty()) {
                    item(key = "inactive_section_list") {
                        InactiveTabsSection(
                            inactiveTabs = inactiveTabs,
                            inactiveDays = inactiveDays,
                            expanded = inactiveExpanded,
                            onToggleExpanded = onToggleInactiveExpanded,
                            onSelectTab = onSelectTab,
                            onCloseTab = onCloseTab,
                            onCloseAllInactive = onCloseInactiveAll,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        } else {
            val gridState = rememberLazyGridState()
            LazyVerticalGrid(
                columns = GridCells.Fixed(gridColumns),
                state = gridState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                items(
                    items = displayItems,
                    key = { it.key }
                ) { item ->
                    when (item) {
                        is TrayItem.GroupCardItem -> {
                            val isHovered = dropTargetKey == item.key
                            TabGroupGridCard(
                                group = item.group,
                                members = item.members,
                                hasActiveTab = selectedTabId != null && selectedTabId in item.group.tabIds,
                                cardBg = cardBg,
                                previewBg = previewBg,
                                isDropTarget = isHovered,
                                onClick = { onOpenGroup(item.group) },
                                onAddTabInGroup = { onAddTabInGroup(item.group) },
                                onEdit = { onEditGroup(item.group) },
                                onUngroup = { onUngroup(item.group) },
                                onCloseGroup = { onCloseGroup(item.group) },
                                modifier = Modifier.onGloballyPositioned { coords ->
                                    itemBounds[item.key] = coords.boundsInWindow()
                                }
                            )
                        }

                        is TrayItem.SingleTabItem -> {
                            val tab = item.tab
                            val grp = groups.firstOrNull { tab.id in it.tabIds }
                            val isDraggingThis = draggingTabId == tab.id
                            val isDropTarget = dropTargetKey == item.key && draggingTabId != tab.id

                            val dragModifier = if (!isMultiSelect && q.isBlank()) {
                                Modifier
                                    .zIndex(if (isDraggingThis) 50f else 0f)
                                    .graphicsLayer {
                                        if (isDraggingThis) {
                                            translationX = dragOffset.x
                                            translationY = dragOffset.y
                                            scaleX = 1.05f
                                            scaleY = 1.05f
                                        }
                                    }
                                    .pointerInput(tab.id, displayItems.size) {
                                        var totalMoved = 0f
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                lastLongPressAtMs = System.currentTimeMillis()
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                draggingTabId = tab.id
                                                dragOffset = Offset.Zero
                                                dropTargetKey = null
                                                totalMoved = 0f
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dragOffset += dragAmount
                                                totalMoved += hypot(dragAmount.x, dragAmount.y)

                                                val myRect = itemBounds[item.key]
                                                if (myRect != null) {
                                                    val centerX = myRect.center.x + dragOffset.x
                                                    val centerY = myRect.center.y + dragOffset.y

                                                    // Hit-test live window bounds of all other visible cards
                                                    var matchedMergeKey: String? = null
                                                    for ((k, rect) in itemBounds) {
                                                        if (k == item.key) continue
                                                        if (centerX in rect.left..rect.right && centerY in rect.top..rect.bottom) {
                                                            // Generous inner 72% drop zone -> Group / Add to Group
                                                            val padX = rect.width * 0.14f
                                                            val padY = rect.height * 0.14f
                                                            val inMergeZone =
                                                                centerX in (rect.left + padX)..(rect.right - padX) &&
                                                                    centerY in (rect.top + padY)..(rect.bottom - padY)
                                                            if (inMergeZone || k.startsWith("grp_")) {
                                                                matchedMergeKey = k
                                                            } else if (k.startsWith("tab_") && totalMoved > 36f) {
                                                                val otherId = k.removePrefix("tab_")
                                                                onReorderTab(tab.id, otherId)
                                                            }
                                                            break
                                                        }
                                                    }
                                                    dropTargetKey = matchedMergeKey
                                                }
                                            },
                                            onDragEnd = {
                                                lastLongPressAtMs = System.currentTimeMillis()
                                                val targetKey = dropTargetKey
                                                draggingTabId = null
                                                dragOffset = Offset.Zero
                                                dropTargetKey = null

                                                when {
                                                    targetKey != null && targetKey.startsWith("grp_") -> {
                                                        val gid = targetKey.removePrefix("grp_")
                                                        onDragDropAddToGroup(gid, tab.id)
                                                    }
                                                    targetKey != null && targetKey.startsWith("tab_") -> {
                                                        val otherId = targetKey.removePrefix("tab_")
                                                        if (otherId != tab.id) {
                                                            onDragDropCreateGroup(otherId, tab.id)
                                                        }
                                                    }
                                                    totalMoved < 20f -> {
                                                        // Held long-press without dragging -> show quick Card Action Menu!
                                                        contextMenuTabId = tab.id
                                                    }
                                                }
                                            },
                                            onDragCancel = {
                                                lastLongPressAtMs = System.currentTimeMillis()
                                                draggingTabId = null
                                                dragOffset = Offset.Zero
                                                dropTargetKey = null
                                            }
                                        )
                                    }
                            } else {
                                Modifier
                            }

                            TabGridCard(
                                tab = tab,
                                group = grp,
                                accentColor = accentColor,
                                cardBg = cardBg,
                                previewBg = previewBg,
                                isSelected = tab.id == selectedTabId,
                                isMultiSelect = isMultiSelect,
                                isChecked = tab.id in selectedTabIds,
                                isDropTarget = isDropTarget,
                                isBeingDragged = isDraggingThis,
                                showContextMenu = contextMenuTabId == tab.id,
                                onDismissContextMenu = { contextMenuTabId = null },
                                onClick = {
                                    if (System.currentTimeMillis() - lastLongPressAtMs >= 350L) {
                                        if (isMultiSelect) onToggleChecked(tab.id) else onSelectTab(tab.id)
                                    }
                                },
                                onClose = { onCloseTab(tab.id) },
                                onAddToGroup = { onRequestAddToGroup(tab.id) },
                                onRemoveFromGroup = if (grp != null) ({ onRemoveFromGroup(tab.id) }) else null,
                                onDuplicate = { onDuplicateTab(tab) },
                                onShare = { onShareTab(tab.id) },
                                modifier = Modifier
                                    .onGloballyPositioned { coords ->
                                        if (!isDraggingThis) {
                                            itemBounds[item.key] = coords.boundsInWindow()
                                        }
                                    }
                                    .then(dragModifier)
                            )
                        }
                    }
                }

                if (!isPrivate && !isMultiSelect && q.isBlank() && inactiveTabs.isNotEmpty()) {
                    item(
                        key = "inactive_section_grid",
                        span = { GridItemSpan(gridColumns) }
                    ) {
                        InactiveTabsSection(
                            inactiveTabs = inactiveTabs,
                            inactiveDays = inactiveDays,
                            expanded = inactiveExpanded,
                            onToggleExpanded = onToggleInactiveExpanded,
                            onSelectTab = onSelectTab,
                            onCloseTab = onCloseTab,
                            onCloseAllInactive = onCloseInactiveAll,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupsDirectoryPage(
    groups: List<TabGroup>,
    normalTabs: List<TabSessionState>,
    privateTabs: List<TabSessionState>,
    searchQuery: String,
    cardBg: Color,
    previewBg: Color,
    onOpenGroup: (TabGroup) -> Unit,
    onSelectTab: (String) -> Unit,
    onAddTabInGroup: (TabGroup) -> Unit,
    onEditGroup: (TabGroup) -> Unit,
    onUngroup: (TabGroup) -> Unit,
    onCloseGroup: (TabGroup) -> Unit,
    onCreateNewGroup: () -> Unit
) {
    val q = searchQuery.trim().lowercase()
    val normalMap = remember(normalTabs) { normalTabs.associateBy { it.id } }
    val privateMap = remember(privateTabs) { privateTabs.associateBy { it.id } }

    val filtered = remember(groups, normalMap, privateMap, q) {
        groups.mapNotNull { g ->
            val src = if (g.isPrivate) privateMap else normalMap
            val members = g.tabIds.mapNotNull { src[it] }
            if (members.isEmpty()) return@mapNotNull null
            val ok = q.isBlank() || g.name.lowercase().contains(q) || members.any {
                it.content.title.lowercase().contains(q) || it.content.url.lowercase().contains(q)
            }
            if (ok) g to members else null
        }
    }

    if (filtered.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_grid_view),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (q.isNotBlank()) "No matching groups" else "Organize tabs into Groups",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (q.isNotBlank()) {
                    "Try a different search keyword."
                } else {
                    "Long-press and drop any tab onto another tab, or tap New Group below to bundle tabs together."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            if (q.isBlank()) {
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = onCreateNewGroup,
                    shape = RoundedCornerShape(22.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_plus_24),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Create Tab Group", fontWeight = FontWeight.SemiBold)
                }
            }
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(filtered, key = { it.first.id }) { (group, members) ->
            TabGroupListCard(
                group = group,
                members = members,
                cardBg = cardBg,
                previewBg = previewBg,
                onOpenGroup = { onOpenGroup(group) },
                onTabClick = onSelectTab,
                onAddTabInGroup = { onAddTabInGroup(group) },
                onEdit = { onEditGroup(group) },
                onUngroup = { onUngroup(group) },
                onCloseGroup = { onCloseGroup(group) }
            )
        }
    }
}

@Composable
private fun PrivateSessionStatusStrip(
    count: Int,
    accentColor: Color,
    onCloseAllPrivate: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(accentColor.copy(alpha = 0.14f))
            .border(1.dp, accentColor.copy(alpha = 0.32f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = R.drawable.uh_ic_private_vibe_24),
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Private session active ($count)",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                text = "No history · Memory-only previews · Tap to wipe",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(
            onClick = onCloseAllPrivate,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                text = "Close all",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun EmptyModeState(
    isPrivate: Boolean,
    accentColor: Color,
    onNewTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(
                    id = if (isPrivate) R.drawable.uh_ic_private_vibe_24 else R.drawable.uh_ic_tab_modern_24
                ),
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(34.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (isPrivate) "Private Browsing" else "No open tabs",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (isPrivate) {
                "Browsing history, cookies, site data, previews, and private groups are never saved to disk and wipe instantly when closed."
            } else {
                "Open a fresh tab to start browsing."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(22.dp))
        Button(
            onClick = onNewTab,
            colors = ButtonDefaults.buttonColors(containerColor = accentColor),
            shape = RoundedCornerShape(22.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.uh_ic_plus_24),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isPrivate) "New Private Tab" else "New Tab",
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun TabsTrayBottomDock(
    activeSection: TraySection,
    hasTabs: Boolean,
    isListView: Boolean,
    recentlyClosedCount: Int,
    duplicateCount: Int,
    accentColor: Color,
    showMenu: Boolean,
    onMenuToggle: (Boolean) -> Unit,
    onToggleViewMode: () -> Unit,
    onCreateNewGroup: () -> Unit,
    onStartSelect: () -> Unit,
    onOpenRecentlyClosed: () -> Unit,
    onCloseDuplicates: () -> Unit,
    onCloseAll: () -> Unit,
    onOpenTabSettings: () -> Unit,
    onPrimaryFabClick: () -> Unit
) {
    val isPrivate = activeSection == TraySection.PRIVATE
    val fabLabel = when (activeSection) {
        TraySection.NORMAL -> "New Tab"
        TraySection.PRIVATE -> "Private Tab"
        TraySection.GROUPS -> "New Group"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (activeSection != TraySection.GROUPS) {
                IconButton(
                    onClick = onToggleViewMode,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(
                            id = if (isListView) R.drawable.ic_grid_view else R.drawable.uh_ic_list_view_24
                        ),
                        contentDescription = if (isListView) "Switch to grid view" else "Switch to list view",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Box {
                IconButton(
                    onClick = { onMenuToggle(true) },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.fx_ic_more_24),
                        contentDescription = "Tab options",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(21.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { onMenuToggle(false) },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    DropdownMenuItem(
                        text = { Text("New tab group", fontSize = 14.sp) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_grid_view),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = onCreateNewGroup
                    )

                    if (activeSection != TraySection.GROUPS && hasTabs) {
                        DropdownMenuItem(
                            text = { Text("Select tabs", fontSize = 14.sp) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.fx_ic_check_24),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = onStartSelect
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (isListView) "Switch to grid view" else "Switch to list view",
                                    fontSize = 14.sp
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(
                                        id = if (isListView) R.drawable.ic_grid_view else R.drawable.uh_ic_list_view_24
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                onMenuToggle(false)
                                onToggleViewMode()
                            }
                        )
                    }

                    if (!isPrivate) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (recentlyClosedCount > 0) {
                                        "Recently closed ($recentlyClosedCount)"
                                    } else {
                                        "Recently closed"
                                    },
                                    fontSize = 14.sp
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ac_ic_arrow_counter_clockwise_24),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = onOpenRecentlyClosed
                        )
                    }

                    if (activeSection != TraySection.GROUPS && duplicateCount > 0) {
                        DropdownMenuItem(
                            text = { Text("Close duplicate tabs ($duplicateCount)", fontSize = 14.sp) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.uh_ic_copy_24),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = onCloseDuplicates
                        )
                    }

                    DropdownMenuItem(
                        text = { Text("Tab settings", fontSize = 14.sp) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(id = R.drawable.fx_ic_settings_24),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = onOpenTabSettings
                    )

                    if (activeSection != TraySection.GROUPS && hasTabs) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (isPrivate) "Close all private tabs" else "Close all tabs",
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 14.sp
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.fx_ic_delete_24),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = onCloseAll
                        )
                    }
                }
            }
        }

        Button(
            onClick = onPrimaryFabClick,
            colors = ButtonDefaults.buttonColors(containerColor = accentColor),
            shape = RoundedCornerShape(22.dp),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.uh_ic_plus_24),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = fabLabel,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
