package app.harvester.companion.browser.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.ThemePrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mozilla.components.browser.state.state.TabSessionState

@Composable
fun TabsTrayScreen(
    tabs: List<TabSessionState>,
    groups: List<TabGroup>,
    selectedTabId: String?,
    actions: TabsTrayActions,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val themeMode by ThemePrefs.modeFlow(context).collectAsState()
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemePrefs.MODE_DARK -> true
        ThemePrefs.MODE_LIGHT -> false
        else -> systemDark
    }

    val initialPrivate = remember { tabs.find { it.id == selectedTabId }?.content?.private ?: false }
    val pagerState = rememberPagerState(
        initialPage = if (initialPrivate) TraySection.PRIVATE.ordinal else TraySection.NORMAL.ordinal,
        pageCount = { TraySection.entries.size }
    )
    val activeSection = TraySection.entries[pagerState.currentPage]
    val palette = rememberTabsTrayPalette(
        isDark = isDark,
        isPrivateSection = activeSection == TraySection.PRIVATE
    )

    // Store & Settings flows
    val pinnedIds by TabMetaStore.pinnedIdsFlow.collectAsState()
    val pinnedIdSet = remember(pinnedIds) { pinnedIds.toSet() }
    val sortMode by TabMetaStore.sortModeFlow.collectAsState()
    val mutedIdSet by TabMetaStore.mutedTabIdsFlow.collectAsState()
    val isPrivateLocked by PrivateLockManager.isLockedFlow.collectAsState()

    val normalOrder by TabGroupStore.normalOrderFlow.collectAsState()
    val privateOrder by TabGroupStore.privateOrderFlow.collectAsState()
    val lastAccessedMap by TabHistoryStore.lastAccessedFlow.collectAsState()
    val recentlyClosed by TabHistoryStore.recentlyClosedFlow.collectAsState()
    val activeUndo by TabHistoryStore.activeUndoFlow.collectAsState()

    val groupStripEnabled by SettingsPrefs.tabGroupStripFlow(context).collectAsState()
    val landscapeStripEnabled by SettingsPrefs.tabLandscapeStripFlow(context).collectAsState()
    val privateLockEnabled by SettingsPrefs.tabPrivateLockFlow(context).collectAsState()

    var isListView by remember {
        mutableStateOf(SettingsPrefs.getInt(context, SettingsPrefs.K_TAB_VIEW_MODE, 0) == 1)
    }
    var inactiveDays by remember {
        mutableIntStateOf(SettingsPrefs.getInt(context, SettingsPrefs.K_TAB_INACTIVE_DAYS, 14))
    }
    var autoCloseDays by remember {
        mutableIntStateOf(SettingsPrefs.getInt(context, SettingsPrefs.K_TAB_AUTO_CLOSE_DAYS, 0))
    }
    var sleepSeconds by remember {
        mutableIntStateOf(SettingsPrefs.getInt(context, SettingsPrefs.K_TAB_SLEEP_SECONDS, 90))
    }
    var jumpToParent by remember {
        mutableStateOf(SettingsPrefs.getBool(context, SettingsPrefs.K_TAB_JUMP_TO_PARENT, true))
    }
    var openNextToCurrent by remember {
        mutableStateOf(SettingsPrefs.getBool(context, SettingsPrefs.K_TAB_OPEN_NEXT_TO_CURRENT, false))
    }
    var blockPrivateScreenshots by remember {
        mutableStateOf(SettingsPrefs.getBool(context, SettingsPrefs.K_TAB_PRIVATE_SECURE_SCREEN, true))
    }
    val canUseBiometricLock = remember { PrivateLockManager.isDeviceAuthAvailable(context) }

    // UI overlay & selection states
    var isSearchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isMultiSelect by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showOverflowMenu by remember { mutableStateOf(false) }

    // Modal sheets & dialogs
    var activeGroupDetailId by remember { mutableStateOf<String?>(null) }
    var createGroupDraft by remember { mutableStateOf<CreateGroupDraft?>(null) }
    var addToGroupTabIds by remember { mutableStateOf<Pair<List<String>, Boolean>?>(null) }
    var showSortSheet by remember { mutableStateOf(false) }
    var showRecentlyClosed by remember { mutableStateOf(false) }
    var showTabSettings by remember { mutableStateOf(false) }

    // Sync stores cleanly with current tab list
    val normalTabs = remember(tabs) { tabs.filter { !it.content.private } }
    val privateTabs = remember(tabs) { tabs.filter { it.content.private } }
    val tabsById = remember(tabs) { tabs.associateBy { it.id } }

    LaunchedEffect(normalTabs, privateTabs) {
        val validNormal = normalTabs.map { it.id }.toSet()
        val validPrivate = privateTabs.map { it.id }.toSet()
        TabGroupStore.syncOrderWithTabs(normalTabs.map { it.id }, isPrivate = false)
        TabGroupStore.syncOrderWithTabs(privateTabs.map { it.id }, isPrivate = true)
        TabGroupStore.cleanupOrphanedTabs(validNormal, validPrivate)
        TabMetaStore.prunePinned(validNormal)
        TabMetaStore.pruneMuted(validNormal + validPrivate)
        TabHistoryStore.ensureTracked(validNormal)
        TabHistoryStore.pruneAccessToValid(validNormal)
    }

    LaunchedEffect(activeSection) {
        actions.onPrivateSectionVisible(activeSection == TraySection.PRIVATE)
        if (isMultiSelect) {
            isMultiSelect = false
            selectedIds = emptySet()
        }
    }

    LaunchedEffect(activeUndo?.batchId) {
        val batchId = activeUndo?.batchId
        if (batchId != null) {
            delay(4800L)
            TabHistoryStore.dismissUndo(batchId)
        }
    }

    // Build Normal page collections (Pinned, Active TrayItems, Inactive Tabs)
    val normalData = remember(
        normalTabs, groups, normalOrder, pinnedIds, pinnedIdSet,
        sortMode, lastAccessedMap, inactiveDays, selectedTabId, searchQuery
    ) {
        buildCollectionData(
            modeTabs = normalTabs,
            modeGroups = groups.filter { !it.isPrivate },
            manualOrder = normalOrder,
            pinnedOrder = pinnedIds,
            pinnedSet = pinnedIdSet,
            sortMode = sortMode,
            lastAccessedMap = lastAccessedMap,
            inactiveDays = inactiveDays,
            selectedTabId = selectedTabId,
            searchQuery = searchQuery,
            isPrivate = false
        )
    }

    // Build Private page collections
    val privateData = remember(
        privateTabs, groups, privateOrder, sortMode, lastAccessedMap, selectedTabId, searchQuery
    ) {
        buildCollectionData(
            modeTabs = privateTabs,
            modeGroups = groups.filter { it.isPrivate },
            manualOrder = privateOrder,
            pinnedOrder = emptyList(),
            pinnedSet = emptySet(),
            sortMode = sortMode,
            lastAccessedMap = lastAccessedMap,
            inactiveDays = 0,
            selectedTabId = selectedTabId,
            searchQuery = searchQuery,
            isPrivate = true
        )
    }

    val activeModeTabs = if (activeSection == TraySection.PRIVATE) privateTabs else normalTabs
    val activeDuplicates = remember(activeModeTabs, pinnedIdSet, selectedTabId) {
        findDuplicateTabs(activeModeTabs, pinnedIdSet, selectedTabId)
    }
    val visibleGroupsCount = remember(groups, tabsById, isPrivateLocked) {
        groups.count { g ->
            (!g.isPrivate || !isPrivateLocked) && g.tabIds.any { it in tabsById }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.pageBg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Chrome: Multi-Select / Search / Default Segmented Switcher
            when {
                isMultiSelect -> {
                    val selectableIds = remember(activeSection, normalData, privateData) {
                        val items = if (activeSection == TraySection.PRIVATE) privateData.trayItems else normalData.trayItems
                        items.filterIsInstance<TrayItem.SingleTabItem>().map { it.tab.id }
                    }
                    MultiSelectTopBar(
                        selectedCount = selectedIds.size,
                        totalSelectableCount = selectableIds.size,
                        palette = palette,
                        onSelectAll = { selectedIds = selectableIds.toSet() },
                        onClearSelection = { selectedIds = emptySet() },
                        onDone = {
                            isMultiSelect = false
                            selectedIds = emptySet()
                        }
                    )
                }
                isSearchOpen -> {
                    TabsSearchTopBar(
                        query = searchQuery,
                        palette = palette,
                        onQueryChange = { searchQuery = it },
                        onCloseSearch = {
                            searchQuery = ""
                            isSearchOpen = false
                        }
                    )
                }
                else -> {
                    TabsTrayTopHeader(
                        activeSection = activeSection,
                        normalCount = normalTabs.size,
                        privateCount = privateTabs.size,
                        groupsCount = visibleGroupsCount,
                        palette = palette,
                        onSelectSection = { section ->
                            scope.launch { pagerState.animateScrollToPage(section.ordinal) }
                        },
                        onOpenSearch = { isSearchOpen = true },
                        onCloseTray = actions.onDismiss
                    )
                }
            }

            // Main Swipeable Pager (Normal | Private | Groups)
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !isMultiSelect,
                modifier = Modifier.weight(1f)
            ) { pageIndex ->
                when (TraySection.entries[pageIndex]) {
                    TraySection.NORMAL -> {
                        val normalPalette = rememberTabsTrayPalette(isDark = isDark, isPrivateSection = false)
                        TabCollectionPage(
                            isPrivate = false,
                            isPrivateLocked = false,
                            isListView = isListView,
                            sortMode = sortMode,
                            searchQuery = searchQuery,
                            pinnedTabs = normalData.pinnedTabs,
                            trayItems = normalData.trayItems,
                            inactiveTabs = normalData.inactiveTabs,
                            selectedTabId = selectedTabId,
                            pinnedIdSet = pinnedIdSet,
                            mutedIdSet = mutedIdSet,
                            isMultiSelect = isMultiSelect && activeSection == TraySection.NORMAL,
                            selectedIds = selectedIds,
                            blockPrivateScreenshots = blockPrivateScreenshots,
                            palette = normalPalette,
                            actions = actions,
                            onToggleSelectId = { id ->
                                selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                            },
                            onOpenGroupDetail = { activeGroupDetailId = it.id },
                            onEditGroup = { grp ->
                                createGroupDraft = CreateGroupDraft(
                                    tabIds = grp.tabIds,
                                    isPrivate = grp.isPrivate,
                                    editingGroup = grp
                                )
                            },
                            onRequestAddTabToGroup = { tab ->
                                addToGroupTabIds = listOf(tab.id) to false
                            },
                            onRequestMergeCreateGroup = { ids, isPriv ->
                                createGroupDraft = CreateGroupDraft(tabIds = ids, isPrivate = isPriv)
                            },
                            onCloseAllInactive = {
                                val ids = normalData.inactiveTabs.map { it.id }
                                if (ids.isNotEmpty()) {
                                    actions.onCloseTabsBatch(
                                        ids,
                                        "Closed ${ids.size} inactive ${if (ids.size == 1) "tab" else "tabs"}",
                                        false
                                    )
                                }
                            },
                            onResetSortToManual = {}
                        )
                    }

                    TraySection.PRIVATE -> {
                        val privatePalette = rememberTabsTrayPalette(isDark = isDark, isPrivateSection = true)
                        TabCollectionPage(
                            isPrivate = true,
                            isPrivateLocked = isPrivateLocked,
                            isListView = isListView,
                            sortMode = sortMode,
                            searchQuery = searchQuery,
                            pinnedTabs = emptyList(),
                            trayItems = privateData.trayItems,
                            inactiveTabs = emptyList(),
                            selectedTabId = selectedTabId,
                            pinnedIdSet = emptySet(),
                            mutedIdSet = mutedIdSet,
                            isMultiSelect = isMultiSelect && activeSection == TraySection.PRIVATE,
                            selectedIds = selectedIds,
                            blockPrivateScreenshots = blockPrivateScreenshots,
                            palette = privatePalette,
                            actions = actions,
                            onToggleSelectId = { id ->
                                selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                            },
                            onOpenGroupDetail = { activeGroupDetailId = it.id },
                            onEditGroup = { grp ->
                                createGroupDraft = CreateGroupDraft(
                                    tabIds = grp.tabIds,
                                    isPrivate = grp.isPrivate,
                                    editingGroup = grp
                                )
                            },
                            onRequestAddTabToGroup = { tab ->
                                addToGroupTabIds = listOf(tab.id) to true
                            },
                            onRequestMergeCreateGroup = { ids, isPriv ->
                                createGroupDraft = CreateGroupDraft(tabIds = ids, isPrivate = isPriv)
                            },
                            onCloseAllInactive = {},
                            onResetSortToManual = {}
                        )
                    }

                    TraySection.GROUPS -> {
                        val groupsPalette = rememberTabsTrayPalette(isDark = isDark, isPrivateSection = false)
                        GroupsDirectoryPage(
                            groups = groups,
                            tabsById = tabsById,
                            selectedTabId = selectedTabId,
                            isPrivateLocked = isPrivateLocked,
                            searchQuery = searchQuery,
                            palette = groupsPalette,
                            actions = actions,
                            onOpenGroupDetail = { activeGroupDetailId = it.id },
                            onEditGroup = { grp ->
                                createGroupDraft = CreateGroupDraft(
                                    tabIds = grp.tabIds,
                                    isPrivate = grp.isPrivate,
                                    editingGroup = grp
                                )
                            },
                            onCreateNewGroup = {
                                createGroupDraft = CreateGroupDraft(tabIds = emptyList(), isPrivate = false)
                            }
                        )
                    }
                }
            }

            // Bottom Dock: Multi-Select Actions or Standard Bottom Dock
            if (isMultiSelect) {
                val isPrivateMulti = activeSection == TraySection.PRIVATE
                MultiSelectBottomDock(
                    selectedCount = selectedIds.size,
                    isPrivate = isPrivateMulti,
                    palette = palette,
                    onGroupSelected = {
                        val ids = selectedIds.toList()
                        if (ids.isNotEmpty()) {
                            addToGroupTabIds = ids to isPrivateMulti
                            isMultiSelect = false
                            selectedIds = emptySet()
                        }
                    },
                    onBookmarkSelected = {
                        val ids = selectedIds.toList()
                        if (ids.isNotEmpty()) {
                            actions.onBookmarkTabs(ids)
                            isMultiSelect = false
                            selectedIds = emptySet()
                        }
                    },
                    onShareSelected = {
                        val ids = selectedIds.toList()
                        if (ids.isNotEmpty()) {
                            actions.onShareTabs(ids)
                            isMultiSelect = false
                            selectedIds = emptySet()
                        }
                    },
                    onCloseSelected = {
                        val ids = selectedIds.toList()
                        if (ids.isNotEmpty()) {
                            actions.onCloseTabsBatch(
                                ids,
                                "Closed ${ids.size} ${if (ids.size == 1) "tab" else "tabs"}",
                                isPrivateMulti
                            )
                            isMultiSelect = false
                            selectedIds = emptySet()
                        }
                    }
                )
            } else {
                TabsTrayBottomDock(
                    activeSection = activeSection,
                    isListView = isListView,
                    sortMode = sortMode,
                    groupStripEnabled = groupStripEnabled,
                    recentlyClosedCount = recentlyClosed.size,
                    duplicateCount = activeDuplicates.size,
                    hasModeTabs = activeModeTabs.isNotEmpty(),
                    isPrivateLocked = isPrivateLocked,
                    showOverflowMenu = showOverflowMenu,
                    palette = palette,
                    onToggleOverflowMenu = { showOverflowMenu = it },
                    onToggleViewMode = {
                        isListView = !isListView
                        SettingsPrefs.setInt(context, SettingsPrefs.K_TAB_VIEW_MODE, if (isListView) 1 else 0)
                    },
                    onPrimaryCta = {
                        when (activeSection) {
                            TraySection.NORMAL -> actions.onNewTab(false)
                            TraySection.PRIVATE -> {
                                if (isPrivateLocked) {
                                    actions.onUnlockPrivate()
                                } else {
                                    actions.onNewTab(true)
                                }
                            }
                            TraySection.GROUPS -> {
                                createGroupDraft = CreateGroupDraft(tabIds = emptyList(), isPrivate = false)
                            }
                        }
                    },
                    onStartMultiSelect = {
                        isMultiSelect = true
                        selectedIds = emptySet()
                    },
                    onOpenNewGroupDialog = {
                        createGroupDraft = CreateGroupDraft(
                            tabIds = emptyList(),
                            isPrivate = activeSection == TraySection.PRIVATE
                        )
                    },
                    onOpenSortSheet = { showSortSheet = true },
                    onToggleGroupStrip = { enabled ->
                        SettingsPrefs.setBool(context, SettingsPrefs.K_TAB_GROUP_STRIP_ENABLED, enabled)
                    },
                    onOpenRecentlyClosed = { showRecentlyClosed = true },
                    onBookmarkAllModeTabs = {
                        actions.onBookmarkTabs(normalTabs.map { it.id })
                    },
                    onShareAllModeTabs = {
                        actions.onShareTabs(activeModeTabs.map { it.id })
                    },
                    onCloseDuplicates = {
                        val ids = activeDuplicates.map { it.id }
                        if (ids.isNotEmpty()) {
                            actions.onCloseTabsBatch(
                                ids,
                                "Closed ${ids.size} duplicate ${if (ids.size == 1) "tab" else "tabs"}",
                                activeSection == TraySection.PRIVATE
                            )
                        }
                    },
                    onOpenSettings = { showTabSettings = true },
                    onCloseAllInSection = {
                        actions.onCloseAllTabs(activeSection == TraySection.PRIVATE)
                    }
                )
            }
        }

        // Floating Undo Snackbar anchored above the bottom dock
        TabsUndoSnackbar(
            batch = activeUndo,
            palette = palette,
            onUndo = actions.onUndoClose,
            onDismiss = { TabHistoryStore.dismissUndo() },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    // Expandable Group Detail Sheet
    val detailGroup = activeGroupDetailId?.let { id -> groups.firstOrNull { it.id == id } }
    if (detailGroup != null) {
        val members = detailGroup.tabIds.mapNotNull { tabsById[it] }
        if (members.isEmpty()) {
            activeGroupDetailId = null
        } else {
            val detailPalette = rememberTabsTrayPalette(isDark = isDark, isPrivateSection = detailGroup.isPrivate)
            GroupDetailSheet(
                group = detailGroup,
                members = members,
                selectedTabId = selectedTabId,
                pinnedIdSet = pinnedIdSet,
                mutedIdSet = mutedIdSet,
                palette = detailPalette,
                actions = actions,
                onEditGroup = {
                    createGroupDraft = CreateGroupDraft(
                        tabIds = detailGroup.tabIds,
                        isPrivate = detailGroup.isPrivate,
                        editingGroup = detailGroup
                    )
                },
                onMoveTabToGroup = { tab ->
                    addToGroupTabIds = listOf(tab.id) to detailGroup.isPrivate
                },
                onDismiss = { activeGroupDetailId = null }
            )
        }
    }

    // Add to Existing / New Group Sheet
    addToGroupTabIds?.let { (tabIds, isPriv) ->
        val sameModeGroups = groups.filter { it.isPrivate == isPriv }
        AddToGroupSheet(
            groups = sameModeGroups,
            isPrivate = isPriv,
            tabCount = tabIds.size,
            onSelectExistingGroup = { targetGroup ->
                actions.onAddTabsToGroup(targetGroup.id, tabIds)
                addToGroupTabIds = null
            },
            onCreateNewGroup = {
                addToGroupTabIds = null
                createGroupDraft = CreateGroupDraft(tabIds = tabIds, isPrivate = isPriv)
            },
            onDismiss = { addToGroupTabIds = null }
        )
    }

    // Create / Edit Group Dialog
    createGroupDraft?.let { draft ->
        val editing = draft.editingGroup
        CreateOrEditGroupDialog(
            initialName = editing?.name ?: "",
            initialColorArgb = editing?.colorArgb ?: TabGroupStore.GROUP_COLORS.first(),
            isEditing = editing != null,
            isPrivate = draft.isPrivate,
            onConfirm = { name, colorArgb ->
                if (editing != null) {
                    actions.onRenameGroup(editing.id, name, colorArgb)
                } else {
                    actions.onCreateGroup(name, colorArgb, draft.tabIds, draft.isPrivate)
                }
                createGroupDraft = null
            },
            onDismiss = { createGroupDraft = null }
        )
    }

    // Sort Tabs Sheet
    if (showSortSheet) {
        TabSortSheet(
            currentMode = sortMode,
            onSelectMode = { mode -> TabMetaStore.setSortMode(context, mode) },
            onDismiss = { showSortSheet = false }
        )
    }

    // Recently Closed Sheet
    if (showRecentlyClosed) {
        RecentlyClosedSheet(
            records = recentlyClosed,
            onRestoreRecord = { rec ->
                showRecentlyClosed = false
                actions.onRestoreRecentlyClosed(rec)
            },
            onRestoreAll = actions.onRestoreAllRecentlyClosed,
            onClearAll = actions.onClearRecentlyClosed,
            onDismiss = { showRecentlyClosed = false }
        )
    }

    // Tab Settings Sheet
    if (showTabSettings) {
        TabSettingsSheet(
            isListView = isListView,
            inactiveDays = inactiveDays,
            autoCloseDays = autoCloseDays,
            sleepSeconds = sleepSeconds,
            jumpToParent = jumpToParent,
            openNextToCurrent = openNextToCurrent,
            groupStripEnabled = groupStripEnabled,
            landscapeStripEnabled = landscapeStripEnabled,
            blockPrivateScreenshots = blockPrivateScreenshots,
            privateLockEnabled = privateLockEnabled,
            canUseBiometricLock = canUseBiometricLock,
            onViewModeChange = { list ->
                isListView = list
                SettingsPrefs.setInt(context, SettingsPrefs.K_TAB_VIEW_MODE, if (list) 1 else 0)
            },
            onInactiveDaysChange = { days ->
                inactiveDays = days
                SettingsPrefs.setInt(context, SettingsPrefs.K_TAB_INACTIVE_DAYS, days)
            },
            onAutoCloseDaysChange = { days ->
                autoCloseDays = days
                SettingsPrefs.setInt(context, SettingsPrefs.K_TAB_AUTO_CLOSE_DAYS, days)
            },
            onSleepSecondsChange = { sec ->
                sleepSeconds = sec
                SettingsPrefs.setInt(context, SettingsPrefs.K_TAB_SLEEP_SECONDS, sec)
            },
            onJumpToParentChange = { enabled ->
                jumpToParent = enabled
                SettingsPrefs.setBool(context, SettingsPrefs.K_TAB_JUMP_TO_PARENT, enabled)
            },
            onOpenNextToCurrentChange = { enabled ->
                openNextToCurrent = enabled
                SettingsPrefs.setBool(context, SettingsPrefs.K_TAB_OPEN_NEXT_TO_CURRENT, enabled)
            },
            onGroupStripChange = { enabled ->
                SettingsPrefs.setBool(context, SettingsPrefs.K_TAB_GROUP_STRIP_ENABLED, enabled)
            },
            onLandscapeStripChange = { enabled ->
                SettingsPrefs.setBool(context, SettingsPrefs.K_TAB_LANDSCAPE_STRIP_ENABLED, enabled)
            },
            onBlockPrivateScreenshotsChange = { enabled ->
                blockPrivateScreenshots = enabled
                SettingsPrefs.setBool(context, SettingsPrefs.K_TAB_PRIVATE_SECURE_SCREEN, enabled)
                actions.onPrivateSecurePrefChanged(enabled)
            },
            onPrivateLockChange = { enabled ->
                SettingsPrefs.setBool(context, SettingsPrefs.K_TAB_PRIVATE_LOCK_ENABLED, enabled)
                PrivateLockManager.onLockSettingChanged(enabled)
            },
            onDismiss = { showTabSettings = false }
        )
    }
}

private data class TabCollectionData(
    val pinnedTabs: List<TabSessionState>,
    val trayItems: List<TrayItem>,
    val inactiveTabs: List<TabSessionState>
)

private fun buildCollectionData(
    modeTabs: List<TabSessionState>,
    modeGroups: List<TabGroup>,
    manualOrder: List<String>,
    pinnedOrder: List<String>,
    pinnedSet: Set<String>,
    sortMode: TabSortMode,
    lastAccessedMap: Map<String, Long>,
    inactiveDays: Int,
    selectedTabId: String?,
    searchQuery: String,
    isPrivate: Boolean
): TabCollectionData {
    val tabsById = modeTabs.associateBy { it.id }
    val q = searchQuery.trim().lowercase()

    fun matchesQuery(tab: TabSessionState): Boolean {
        if (q.isEmpty()) return true
        return tab.content.title.lowercase().contains(q) || tab.content.url.lowercase().contains(q)
    }

    // Pinned tabs (Normal only)
    val pinnedTabs = if (isPrivate) {
        emptyList()
    } else {
        pinnedOrder.mapNotNull { tabsById[it] }.filter { matchesQuery(it) }
    }

    val groupedTabIds = modeGroups.flatMap { it.tabIds }.toSet()
    val nowMs = System.currentTimeMillis()
    val inactiveThresholdMs = if (!isPrivate && inactiveDays > 0) inactiveDays * 86_400_000L else Long.MAX_VALUE

    val inactiveTabs = mutableListOf<TabSessionState>()
    val activeUngrouped = mutableListOf<TabSessionState>()

    for (tab in modeTabs) {
        if (tab.id in pinnedSet || tab.id in groupedTabIds) continue
        if (!isPrivate && q.isEmpty() && inactiveDays > 0 && tab.id != selectedTabId) {
            val lastAccess = lastAccessedMap[tab.id] ?: nowMs
            if (nowMs - lastAccess >= inactiveThresholdMs) {
                inactiveTabs.add(tab)
                continue
            }
        }
        if (matchesQuery(tab)) {
            activeUngrouped.add(tab)
        }
    }

    // Sort active ungrouped tabs according to selected TabSortMode
    val orderIndex = manualOrder.withIndex().associate { (i, id) -> id to i }
    val sortedSingles = when (sortMode) {
        TabSortMode.MANUAL -> activeUngrouped.sortedBy { orderIndex[it.id] ?: Int.MAX_VALUE }
        TabSortMode.RECENT -> activeUngrouped.sortedByDescending { lastAccessedMap[it.id] ?: it.createdAt }
        TabSortMode.OLDEST -> activeUngrouped.sortedBy { it.createdAt }
        TabSortMode.TITLE_AZ -> activeUngrouped.sortedBy {
            it.content.title.ifBlank { extractHostLabel(it.content.url) }.lowercase()
        }
        TabSortMode.DOMAIN -> activeUngrouped.sortedBy {
            extractHostLabel(it.content.url).lowercase().ifBlank { "zzz" }
        }
    }

    // Build GroupCardItems
    val groupItems = modeGroups.mapNotNull { grp ->
        val members = grp.tabIds.mapNotNull { tabsById[it] }
        if (members.isEmpty()) return@mapNotNull null
        if (q.isNotEmpty() && !grp.name.lowercase().contains(q) && members.none { matchesQuery(it) }) {
            return@mapNotNull null
        }
        TrayItem.GroupCardItem(group = grp, members = members)
    }

    val singleItems = sortedSingles.map { TrayItem.SingleTabItem(it) }

    return TabCollectionData(
        pinnedTabs = pinnedTabs,
        trayItems = groupItems + singleItems,
        inactiveTabs = inactiveTabs
    )
}

private fun findDuplicateTabs(
    modeTabs: List<TabSessionState>,
    pinnedSet: Set<String>,
    selectedTabId: String?
): List<TabSessionState> {
    val byNormalized = linkedMapOf<String, MutableList<TabSessionState>>()
    for (tab in modeTabs) {
        val norm = normalizeUrlForDedup(tab.content.url)
        if (norm.isBlank()) continue
        byNormalized.getOrPut(norm) { mutableListOf() }.add(tab)
    }
    val duplicates = mutableListOf<TabSessionState>()
    for ((_, list) in byNormalized) {
        if (list.size <= 1) continue
        // Prefer keeping the active tab or any pinned tab, otherwise the newest copy
        val keeper = list.firstOrNull { it.id == selectedTabId }
            ?: list.firstOrNull { it.id in pinnedSet }
            ?: list.last()
        for (t in list) {
            if (t.id != keeper.id && t.id !in pinnedSet) {
                duplicates.add(t)
            }
        }
    }
    return duplicates
}
