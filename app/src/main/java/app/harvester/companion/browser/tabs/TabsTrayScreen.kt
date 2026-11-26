package app.harvester.companion.browser.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    val lifecycleOwner = LocalLifecycleOwner.current
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

    // Store & reactive Settings flows
    val pinnedIds by TabMetaStore.pinnedIdsFlow.collectAsState()
    val pinnedIdSet = remember(pinnedIds) { pinnedIds.toSet() }
    val sortMode by TabMetaStore.sortModeFlow.collectAsState()
    val mutedIdSet by TabMetaStore.mutedTabIdsFlow.collectAsState()
    val isPrivateLocked by PrivateLockManager.isLockedFlow.collectAsState()

    val normalOrder by TabGroupStore.normalOrderFlow.collectAsState()
    val privateOrder by TabGroupStore.privateOrderFlow.collectAsState()
    val normalTrayOrder by TabGroupStore.normalTrayOrderFlow.collectAsState()
    val privateTrayOrder by TabGroupStore.privateTrayOrderFlow.collectAsState()
    val lastAccessedMap by TabHistoryStore.lastAccessedFlow.collectAsState()
    val recentlyClosed by TabHistoryStore.recentlyClosedFlow.collectAsState()
    val activeUndo by TabHistoryStore.activeUndoFlow.collectAsState()

    val tabViewMode by SettingsPrefs.tabViewModeFlow(context).collectAsState()
    val isListView = tabViewMode == 1
    val groupStripEnabled by SettingsPrefs.tabGroupStripFlow(context).collectAsState()
    val recentlyClosedEnabled by SettingsPrefs.tabRecentlyClosedEnabledFlow(context).collectAsState()

    var inactiveDays by remember {
        mutableIntStateOf(SettingsPrefs.getInt(context, SettingsPrefs.K_TAB_INACTIVE_DAYS, 14))
    }
    var blockPrivateScreenshots by remember {
        mutableStateOf(SettingsPrefs.getBool(context, SettingsPrefs.K_TAB_PRIVATE_SECURE_SCREEN, true))
    }

    // Re-sync prefs whenever user returns from unified TabSettingsActivity
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                inactiveDays = SettingsPrefs.getInt(context, SettingsPrefs.K_TAB_INACTIVE_DAYS, 14)
                val secureNow = SettingsPrefs.getBool(context, SettingsPrefs.K_TAB_PRIVATE_SECURE_SCREEN, true)
                if (secureNow != blockPrivateScreenshots) {
                    blockPrivateScreenshots = secureNow
                    actions.onPrivateSecurePrefChanged(secureNow)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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

    // Build Normal page collections (Pinned, naturally ordered mixed TrayItems, Inactive Tabs)
    val normalData = remember(
        normalTabs, groups, normalOrder, normalTrayOrder, pinnedIds, pinnedIdSet,
        sortMode, lastAccessedMap, inactiveDays, selectedTabId, searchQuery
    ) {
        buildCollectionData(
            modeTabs = normalTabs,
            modeGroups = groups.filter { !it.isPrivate },
            manualOrder = normalOrder,
            trayOrder = normalTrayOrder,
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

    // Build Private page collections (naturally ordered mixed TrayItems)
    val privateData = remember(
        privateTabs, groups, privateOrder, privateTrayOrder, sortMode, lastAccessedMap, selectedTabId, searchQuery
    ) {
        buildCollectionData(
            modeTabs = privateTabs,
            modeGroups = groups.filter { it.isPrivate },
            manualOrder = privateOrder,
            trayOrder = privateTrayOrder,
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
            // Top Chrome: Multi-Select / Search / Compact Centered Icon-Only Switcher
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
                        }
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
                            onResetSortToManual = {
                                TabMetaStore.setSortMode(context, TabSortMode.MANUAL)
                            }
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
                            onResetSortToManual = {
                                TabMetaStore.setSortMode(context, TabSortMode.MANUAL)
                            }
                        )
                    }

                    TraySection.GROUPS -> {
                        val groupsPalette = rememberTabsTrayPalette(isDark = isDark, isPrivateSection = false)
                        GroupsDirectoryPage(
                            groups = groups,
                            tabsById = tabsById,
                            selectedTabId = selectedTabId,
                            isListView = isListView,
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
                            }
                        )
                    }
                }
            }
        }

        // Floating Bottom Overlay: Undo Snackbar stacked cleanly above the Floating Frosted Pill Dock
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            TabsUndoSnackbar(
                batch = activeUndo,
                palette = palette,
                onUndo = actions.onUndoClose,
                onDismiss = { TabHistoryStore.dismissUndo() }
            )

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
                    onOpenSearch = { isSearchOpen = true },
                    onToggleViewMode = {
                        SettingsPrefs.setInt(context, SettingsPrefs.K_TAB_VIEW_MODE, if (isListView) 0 else 1)
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
                    onAutoGroupByDomain = {
                        actions.onAutoGroupByDomain(activeSection == TraySection.PRIVATE)
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
                    onOpenSettings = actions.onOpenTabSettings,
                    onCloseAllInSection = {
                        actions.onCloseAllTabs(activeSection == TraySection.PRIVATE)
                    }
                )
            }
        }
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

    // Recently Closed Sheet with inline ON/OFF toggle
    if (showRecentlyClosed) {
        RecentlyClosedSheet(
            records = recentlyClosed,
            isEnabled = recentlyClosedEnabled,
            onToggleEnabled = { enabled ->
                SettingsPrefs.setBool(context, SettingsPrefs.K_TAB_RECENTLY_CLOSED_ENABLED, enabled)
            },
            onRestoreRecord = { rec ->
                showRecentlyClosed = false
                actions.onRestoreRecentlyClosed(rec)
            },
            onRestoreAll = actions.onRestoreAllRecentlyClosed,
            onClearAll = actions.onClearRecentlyClosed,
            onDismiss = { showRecentlyClosed = false }
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
    trayOrder: List<String>,
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

    // Build GroupCardItems
    val groupItems = modeGroups.mapNotNull { grp ->
        val members = grp.tabIds.mapNotNull { tabsById[it] }
        if (members.isEmpty()) return@mapNotNull null
        if (q.isNotEmpty() && !grp.name.lowercase().contains(q) && members.none { matchesQuery(it) }) {
            return@mapNotNull null
        }
        TrayItem.GroupCardItem(group = grp, members = members)
    }

    val orderIndex = manualOrder.withIndex().associate { (i, id) -> id to i }
    val trayOrderIndex = trayOrder.withIndex().associate { (i, key) -> key to i }

    val orderedTrayItems: List<TrayItem> = when (sortMode) {
        TabSortMode.MANUAL -> {
            // Preserve true natural mixed ordering between tabs and groups — groups NEVER jump to #1!
            val singleItems = activeUngrouped.map { TrayItem.SingleTabItem(it) }
            (singleItems + groupItems).sortedWith(
                compareBy<TrayItem>(
                    { trayOrderIndex[it.key] ?: Int.MAX_VALUE },
                    {
                        when (it) {
                            is TrayItem.SingleTabItem -> orderIndex[it.tab.id] ?: Int.MAX_VALUE
                            is TrayItem.GroupCardItem -> Int.MAX_VALUE
                        }
                    }
                )
            )
        }
        TabSortMode.RECENT -> {
            val sortedSingles = activeUngrouped.sortedByDescending { lastAccessedMap[it.id] ?: it.createdAt }
            val sortedGroups = groupItems.sortedByDescending { grp ->
                grp.members.maxOfOrNull { lastAccessedMap[it.id] ?: it.createdAt } ?: 0L
            }
            sortedGroups + sortedSingles.map { TrayItem.SingleTabItem(it) }
        }
        TabSortMode.OLDEST -> {
            val sortedSingles = activeUngrouped.sortedBy { it.createdAt }
            val sortedGroups = groupItems.sortedBy { grp ->
                grp.members.minOfOrNull { it.createdAt } ?: Long.MAX_VALUE
            }
            sortedGroups + sortedSingles.map { TrayItem.SingleTabItem(it) }
        }
        TabSortMode.TITLE_AZ -> {
            val sortedSingles = activeUngrouped.sortedBy {
                it.content.title.ifBlank { extractHostLabel(it.content.url) }.lowercase()
            }
            val sortedGroups = groupItems.sortedBy { it.group.name.lowercase() }
            sortedGroups + sortedSingles.map { TrayItem.SingleTabItem(it) }
        }
        TabSortMode.DOMAIN -> {
            val sortedSingles = activeUngrouped.sortedBy {
                extractHostLabel(it.content.url).lowercase().ifBlank { "zzz" }
            }
            val sortedGroups = groupItems.sortedBy { grp ->
                grp.members.firstOrNull()?.let { extractHostLabel(it.content.url).lowercase() } ?: "zzz"
            }
            sortedGroups + sortedSingles.map { TrayItem.SingleTabItem(it) }
        }
    }

    return TabCollectionData(
        pinnedTabs = pinnedTabs,
        trayItems = orderedTrayItems,
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
