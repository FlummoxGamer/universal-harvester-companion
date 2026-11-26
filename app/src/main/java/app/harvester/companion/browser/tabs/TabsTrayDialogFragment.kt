package app.harvester.companion.browser.tabs

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import app.harvester.companion.R
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.ThemePrefs
import app.harvester.companion.UHApp
import app.harvester.companion.UHLog
import app.harvester.companion.browser.BrowserViewModel
import app.harvester.companion.settings.appearance.TabSettingsActivity
import app.harvester.companion.theme.UHTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.appservices.places.BookmarkRoot
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.state.TabSessionState

/**
 * Full-screen true edge-to-edge Tab Switcher DialogFragment.
 * Draws seamlessly behind the status bar, display cutout, and navigation bar.
 */
class TabsTrayDialogFragment : DialogFragment() {

    private val browserViewModel: BrowserViewModel by activityViewModels()

    companion object {
        const val TAG = "TabsTrayDialogFragment"
        fun newInstance(): TabsTrayDialogFragment = TabsTrayDialogFragment()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, android.R.style.Theme_Translucent_NoTitleBar)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return Dialog(requireContext(), theme).apply {
            window?.let { w ->
                w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                WindowCompat.setDecorFitsSystemWindows(w, false)
                @Suppress("DEPRECATION")
                w.statusBarColor = Color.TRANSPARENT
                @Suppress("DEPRECATION")
                w.navigationBarColor = Color.TRANSPARENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    w.attributes = w.attributes.apply {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    w.isStatusBarContrastEnforced = false
                    w.isNavigationBarContrastEnforced = false
                }
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun applyWindowSystemBarsAndPrivacy(
        isPrivateSection: Boolean,
        isDarkTheme: Boolean,
        blockScreenshots: Boolean
    ) {
        val w = dialog?.window ?: return
        try {
            // Private section always uses Deep Obsidian dark surface, so system bar icons must be light (white).
            val useDarkSystemBarIcons = !isDarkTheme && !isPrivateSection
            val controller = WindowInsetsControllerCompat(w, w.decorView)
            controller.isAppearanceLightStatusBars = useDarkSystemBarIcons
            controller.isAppearanceLightNavigationBars = useDarkSystemBarIcons

            if (isPrivateSection && blockScreenshots) {
                w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        } catch (_: Exception) {}
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val app = requireActivity().application as UHApp
        val components = app.components
        TabGroupStore.init(ctx)
        TabHistoryStore.init(ctx)
        TabMetaStore.init(ctx)

        return ComposeView(ctx).apply {
            setContent {
                val themeMode by ThemePrefs.modeFlow(ctx).collectAsState()
                val systemDark = isSystemInDarkTheme()
                val isDarkTheme = when (themeMode) {
                    ThemePrefs.MODE_DARK -> true
                    ThemePrefs.MODE_LIGHT -> false
                    else -> systemDark
                }

                var isPrivateSectionVisible by remember { mutableStateOf(false) }
                var blockPrivateScreenshots by remember {
                    mutableStateOf(SettingsPrefs.getBool(ctx, SettingsPrefs.K_TAB_PRIVATE_SECURE_SCREEN, true))
                }

                LaunchedEffect(isPrivateSectionVisible, isDarkTheme, blockPrivateScreenshots) {
                    applyWindowSystemBarsAndPrivacy(
                        isPrivateSection = isPrivateSectionVisible,
                        isDarkTheme = isDarkTheme,
                        blockScreenshots = blockPrivateScreenshots
                    )
                }

                UHTheme {
                    val browserState by components.store.stateFlow.collectAsState()
                    val groups by TabGroupStore.groupsFlow.collectAsState()
                    val tabs = browserState.tabs
                    val selectedTabId = browserState.selectedTabId

                    val actions = remember(tabs, selectedTabId) {
                        TabsTrayActions(
                            onSelectTab = { tabId ->
                                dismissAllowingStateLoss()
                                browserViewModel.exitEditMode()
                                components.useCases.tabsUseCases.selectTab(tabId)
                                val targetTab = tabs.firstOrNull { it.id == tabId }
                                if (targetTab != null && !targetTab.content.private) {
                                    TabHistoryStore.recordTabAccess(tabId)
                                }
                                if (targetTab != null && (targetTab.content.url.isBlank() || targetTab.content.url == "about:blank")) {
                                    navigateToHome()
                                } else {
                                    navigateToBrowserIfNeeded()
                                }
                            },
                            onCloseTab = { tabId ->
                                val target = tabs.firstOrNull { it.id == tabId }
                                if (target != null) {
                                    val grp = TabGroupStore.groupForTab(tabId)
                                    val record = ClosedTabRecord(
                                        url = target.content.url.ifBlank { "about:blank" },
                                        title = target.content.title,
                                        isPrivate = target.content.private,
                                        groupSnapshot = grp
                                    )
                                    val remaining = tabs.filter { it.id != tabId }
                                    val wasSelected = tabId == selectedTabId
                                    TabGroupStore.removeTabFromGroups(tabId)
                                    TabMetaStore.unpinTab(tabId)
                                    TabPreviewCache.remove(tabId)
                                    val jumpToParent = SettingsPrefs.getBool(ctx, SettingsPrefs.K_TAB_JUMP_TO_PARENT, true)
                                    components.useCases.tabsUseCases.removeTab(tabId, selectParentIfExists = jumpToParent)
                                    TabHistoryStore.recordClosedBatch(
                                        label = "Tab closed",
                                        records = listOf(record),
                                        wasPrivate = target.content.private,
                                        allowUndo = true
                                    )
                                    handlePostCloseNavigation(
                                        remainingTabs = remaining,
                                        wasSelectedClosed = wasSelected,
                                        wasPrivateClosed = target.content.private
                                    )
                                }
                            },
                            onCloseTabsBatch = { tabIds, label, isPrivate ->
                                if (tabIds.isNotEmpty()) {
                                    val closedSet = tabIds.toSet()
                                    val targetMap = tabs.associateBy { it.id }
                                    val records = tabIds.mapNotNull { id ->
                                        val t = targetMap[id] ?: return@mapNotNull null
                                        ClosedTabRecord(
                                            url = t.content.url.ifBlank { "about:blank" },
                                            title = t.content.title,
                                            isPrivate = t.content.private,
                                            groupSnapshot = TabGroupStore.groupForTab(id)
                                        )
                                    }
                                    val remaining = tabs.filter { it.id !in closedSet }
                                    val wasSelected = selectedTabId != null && selectedTabId in closedSet
                                    tabIds.forEach {
                                        TabGroupStore.removeTabFromGroups(it)
                                        TabMetaStore.unpinTab(it)
                                        TabPreviewCache.remove(it)
                                    }
                                    components.store.dispatch(TabListAction.RemoveTabsAction(tabIds))
                                    TabHistoryStore.recordClosedBatch(
                                        label = label,
                                        records = records,
                                        wasPrivate = isPrivate,
                                        allowUndo = true
                                    )
                                    handlePostCloseNavigation(
                                        remainingTabs = remaining,
                                        wasSelectedClosed = wasSelected,
                                        wasPrivateClosed = isPrivate
                                    )
                                }
                            },
                            onCloseAllTabs = { isPrivate ->
                                if (isPrivate) {
                                    val remaining = tabs.filter { !it.content.private }
                                    val wasSelectedPrivate = tabs.any { it.id == selectedTabId && it.content.private }
                                    PrivateSessionNotificationManager.closeAllPrivateTabs(ctx, components)
                                    handlePostCloseNavigation(
                                        remainingTabs = remaining,
                                        wasSelectedClosed = wasSelectedPrivate,
                                        wasPrivateClosed = true
                                    )
                                } else {
                                    // Shield Pinned tabs from Close All Tabs just like Brave / Edge
                                    val pinnedSet = TabMetaStore.pinnedIdsFlow.value.toSet()
                                    val normalTargets = tabs.filter { !it.content.private && it.id !in pinnedSet }
                                    if (normalTargets.isEmpty()) {
                                        if (tabs.any { !it.content.private && it.id in pinnedSet }) {
                                            Toast.makeText(ctx, "Pinned tabs are protected from Close All", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        val ids = normalTargets.map { it.id }
                                        val closedSet = ids.toSet()
                                        val remaining = tabs.filter { it.id !in closedSet }
                                        val wasSelected = selectedTabId != null && selectedTabId in closedSet
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
                                        TabHistoryStore.recordClosedBatch(
                                            label = "Closed ${ids.size} ${if (ids.size == 1) "tab" else "tabs"}",
                                            records = records,
                                            wasPrivate = false,
                                            allowUndo = true
                                        )
                                        handlePostCloseNavigation(
                                            remainingTabs = remaining,
                                            wasSelectedClosed = wasSelected,
                                            wasPrivateClosed = false
                                        )
                                    }
                                }
                            },
                            onNewTab = { isPrivate ->
                                dismissAllowingStateLoss()
                                browserViewModel.exitEditMode()
                                val anchorId = if (SettingsPrefs.getBool(ctx, SettingsPrefs.K_TAB_OPEN_NEXT_TO_CURRENT, true)) {
                                    selectedTabId
                                } else null
                                val newId = components.useCases.tabsUseCases.addTab(
                                    url = "about:blank",
                                    selectTab = true,
                                    private = isPrivate
                                )
                                if (anchorId != null) {
                                    TabGroupStore.insertTabAfter(newId, anchorId, isPrivate)
                                }
                                if (!isPrivate) TabHistoryStore.recordTabAccess(newId)
                                if (isPrivate) {
                                    browserViewModel.enterEditMode("")
                                }
                                navigateToHome()
                            },
                            onNewTabInGroup = { groupId, isPrivate ->
                                dismissAllowingStateLoss()
                                browserViewModel.exitEditMode()
                                val newId = components.useCases.tabsUseCases.addTab(
                                    url = "about:blank",
                                    selectTab = true,
                                    private = isPrivate
                                )
                                TabGroupStore.addTabToGroup(groupId, newId)
                                if (!isPrivate) TabHistoryStore.recordTabAccess(newId)
                                if (isPrivate) {
                                    browserViewModel.enterEditMode("")
                                }
                                navigateToHome()
                            },
                            onDuplicateTab = { sourceTab ->
                                val targetUrl = sourceTab.content.url.ifBlank { "about:blank" }
                                val dupId = components.useCases.tabsUseCases.addTab(
                                    url = targetUrl,
                                    selectTab = false,
                                    private = sourceTab.content.private
                                )
                                TabGroupStore.insertTabAfter(dupId, sourceTab.id, sourceTab.content.private)
                                TabGroupStore.groupForTab(sourceTab.id)?.let { grp ->
                                    TabGroupStore.addTabToGroup(grp.id, dupId)
                                }
                                if (!sourceTab.content.private) {
                                    TabHistoryStore.recordTabAccess(dupId)
                                }
                                Toast.makeText(ctx, "Tab duplicated", Toast.LENGTH_SHORT).show()
                            },
                            onTogglePinTab = { tab ->
                                if (!tab.content.private) {
                                    val nowPinned = TabMetaStore.togglePin(tab.id)
                                    Toast.makeText(
                                        ctx,
                                        if (nowPinned) "Tab pinned" else "Tab unpinned",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            onToggleMuteTab = { tab ->
                                val nowMuted = TabMetaStore.toggleMute(tab)
                                Toast.makeText(
                                    ctx,
                                    if (nowMuted) "Tab muted" else "Tab unmuted",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onShareTabs = { selectedIds ->
                                val selectedUrls = tabs.filter { it.id in selectedIds }
                                    .map { it.content.url }
                                    .filter { it.isNotBlank() && it != "about:blank" }
                                if (selectedUrls.isNotEmpty()) {
                                    val text = selectedUrls.joinToString("\n")
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, text)
                                    }
                                    startActivity(Intent.createChooser(intent, "Share Tabs"))
                                } else {
                                    Toast.makeText(ctx, "No shareable URLs selected", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onBookmarkTabs = { selectedIds ->
                                val targets = tabs.filter {
                                    it.id in selectedIds && !it.content.private &&
                                        it.content.url.isNotBlank() && it.content.url != "about:blank"
                                }
                                if (targets.isEmpty()) {
                                    Toast.makeText(ctx, "No bookmarkable pages selected", Toast.LENGTH_SHORT).show()
                                } else {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        var saved = 0
                                        for (t in targets) {
                                            try {
                                                val existing = components.placesBookmarksStorage
                                                    .getBookmarksWithUrl(t.content.url).getOrNull().orEmpty()
                                                if (existing.isEmpty()) {
                                                    components.placesBookmarksStorage.addItem(
                                                        BookmarkRoot.Mobile.id,
                                                        t.content.url,
                                                        t.content.title.ifBlank { t.content.url },
                                                        null
                                                    )
                                                    saved++
                                                }
                                            } catch (e: Exception) {
                                                UHLog.w("bookmark", "Batch bookmark skipped: ${e.message}")
                                            }
                                        }
                                        withContext(Dispatchers.Main) {
                                            val msg = if (saved > 0) {
                                                "Bookmarked $saved ${if (saved == 1) "tab" else "tabs"}"
                                            } else {
                                                "Already bookmarked"
                                            }
                                            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            onCreateGroup = { name, colorArgb, tabIds, isPrivate ->
                                val finalIds = if (tabIds.isNotEmpty()) {
                                    tabIds
                                } else {
                                    val createdId = components.useCases.tabsUseCases.addTab(
                                        url = "about:blank",
                                        selectTab = false,
                                        private = isPrivate
                                    )
                                    if (!isPrivate) TabHistoryStore.recordTabAccess(createdId)
                                    listOf(createdId)
                                }
                                TabGroupStore.createGroup(
                                    name = name,
                                    colorArgb = colorArgb,
                                    tabIds = finalIds,
                                    isPrivate = isPrivate
                                )
                            },
                            onAddTabsToGroup = { groupId, tabIds ->
                                tabIds.forEach { TabMetaStore.unpinTab(it) }
                                TabGroupStore.addTabsToGroup(groupId, tabIds)
                            },
                            onRemoveTabFromGroup = { tabId ->
                                TabGroupStore.removeTabFromGroups(tabId)
                            },
                            onRenameGroup = { groupId, name, colorArgb ->
                                TabGroupStore.renameAndRecolor(groupId, name, colorArgb)
                            },
                            onUngroup = { groupId ->
                                TabGroupStore.ungroup(groupId)
                            },
                            onCloseGroupTabs = { group ->
                                val memberTabs = tabs.filter { it.id in group.tabIds }
                                val ids = memberTabs.map { it.id }
                                val closedSet = ids.toSet()
                                val remaining = tabs.filter { it.id !in closedSet }
                                val wasSelected = selectedTabId != null && selectedTabId in closedSet
                                val records = memberTabs.map { t ->
                                    ClosedTabRecord(
                                        url = t.content.url.ifBlank { "about:blank" },
                                        title = t.content.title,
                                        isPrivate = t.content.private,
                                        groupSnapshot = group
                                    )
                                }
                                TabGroupStore.ungroup(group.id)
                                ids.forEach { TabPreviewCache.remove(it) }
                                if (ids.isNotEmpty()) {
                                    components.store.dispatch(TabListAction.RemoveTabsAction(ids))
                                }
                                TabHistoryStore.recordClosedBatch(
                                    label = "Closed group \"${group.name}\"",
                                    records = records,
                                    wasPrivate = group.isPrivate,
                                    allowUndo = true
                                )
                                handlePostCloseNavigation(
                                    remainingTabs = remaining,
                                    wasSelectedClosed = wasSelected,
                                    wasPrivateClosed = group.isPrivate
                                )
                            },
                            onAutoGroupByDomain = { isPrivate ->
                                autoGroupByDomain(tabs, isPrivate)
                            },
                            onReorderGroupMembers = { groupId, orderedTabIds ->
                                TabGroupStore.reorderGroupMembers(groupId, orderedTabIds)
                            },
                            onReorderGroupsDirectory = { orderedGroupIds, isPrivate ->
                                TabGroupStore.reorderGroupsDirectory(orderedGroupIds, isPrivate)
                            },
                            onOpenTabSettings = {
                                startActivity(Intent(ctx, TabSettingsActivity::class.java))
                            },
                            onUndoClose = {
                                TabHistoryStore.consumeUndo()?.let { batch ->
                                    val restoredByGroup = linkedMapOf<String, Pair<TabGroup, MutableList<String>>>()
                                    batch.tabs.forEachIndexed { idx, rec ->
                                        val newId = components.useCases.tabsUseCases.addTab(
                                            url = rec.url.ifBlank { "about:blank" },
                                            selectTab = idx == batch.tabs.lastIndex,
                                            private = rec.isPrivate
                                        )
                                        if (!rec.isPrivate) TabHistoryStore.recordTabAccess(newId)
                                        rec.groupSnapshot?.let { grpSnap ->
                                            val entry = restoredByGroup.getOrPut(grpSnap.id) {
                                                grpSnap to mutableListOf()
                                            }
                                            entry.second.add(newId)
                                        }
                                    }
                                    for ((_, pair) in restoredByGroup) {
                                        TabGroupStore.restoreGroup(pair.first, pair.second)
                                    }
                                }
                            },
                            onRestoreRecentlyClosed = { rec ->
                                TabHistoryStore.removeRecentlyClosed(rec.id)
                                val newId = components.useCases.tabsUseCases.addTab(
                                    url = rec.url,
                                    selectTab = true,
                                    private = false
                                )
                                TabHistoryStore.recordTabAccess(newId)
                                dismissAllowingStateLoss()
                                browserViewModel.exitEditMode()
                                navigateToBrowserIfNeeded()
                            },
                            onRestoreAllRecentlyClosed = {
                                val list = TabHistoryStore.recentlyClosedFlow.value.toList()
                                if (list.isNotEmpty()) {
                                    TabHistoryStore.clearRecentlyClosed()
                                    list.reversed().forEachIndexed { idx, rec ->
                                        val newId = components.useCases.tabsUseCases.addTab(
                                            url = rec.url,
                                            selectTab = idx == list.lastIndex,
                                            private = false
                                        )
                                        TabHistoryStore.recordTabAccess(newId)
                                    }
                                }
                            },
                            onClearRecentlyClosed = {
                                TabHistoryStore.clearRecentlyClosed()
                            },
                            onUnlockPrivate = {
                                PrivateLockManager.promptUnlock(
                                    context = requireActivity(),
                                    onError = { msg ->
                                        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                                    }
                                )
                            },
                            onPrivateSectionVisible = { isPriv ->
                                isPrivateSectionVisible = isPriv
                            },
                            onPrivateSecurePrefChanged = { enabled ->
                                blockPrivateScreenshots = enabled
                            },
                            onDismiss = { dismissAllowingStateLoss() }
                        )
                    }

                    TabsTrayScreen(
                        tabs = tabs,
                        groups = groups,
                        selectedTabId = selectedTabId,
                        actions = actions
                    )
                }
            }
        }
    }

    /**
     * Ensures closing the last tab (especially the last private tab when 0 normal tabs exist)
     * never leaves a stale ghost webpage rendered behind the tray.
     */
    private fun handlePostCloseNavigation(
        remainingTabs: List<TabSessionState>,
        wasSelectedClosed: Boolean,
        wasPrivateClosed: Boolean
    ) {
        if (wasSelectedClosed || remainingTabs.isEmpty()) {
            TabPreviewCache.setLastActiveBitmap(null)
        }
        val remainingPrivateCount = remainingTabs.count { it.content.private }
        if (remainingTabs.isEmpty() || (wasSelectedClosed && wasPrivateClosed && remainingPrivateCount == 0)) {
            browserViewModel.exitEditMode()
            navigateToHome()
        }
    }

    /**
     * Groups ungrouped, unpinned tabs of the active mode that share a host (2+ tabs per domain).
     */
    private fun autoGroupByDomain(tabs: List<TabSessionState>, isPrivate: Boolean) {
        val ctx = context ?: return
        val pinnedSet = if (isPrivate) emptySet() else TabMetaStore.pinnedIdsFlow.value.toSet()
        val alreadyGrouped = TabGroupStore.groupsFlow.value
            .filter { it.isPrivate == isPrivate }
            .flatMap { it.tabIds }
            .toSet()

        val candidates = tabs.filter { t ->
            t.content.private == isPrivate && t.id !in pinnedSet && t.id !in alreadyGrouped
        }

        val byHost = linkedMapOf<String, MutableList<TabSessionState>>()
        for (tab in candidates) {
            val host = extractHostLabel(tab.content.url).lowercase()
            if (host.isBlank() || host == "about:blank") continue
            byHost.getOrPut(host) { mutableListOf() }.add(tab)
        }

        val existingCount = TabGroupStore.groupsFlow.value.count { it.isPrivate == isPrivate }
        var createdGroups = 0
        var groupedTabs = 0

        for ((host, bucket) in byHost) {
            if (bucket.size < 2) continue
            val cleanName = host.substringBeforeLast('.')
                .takeIf { it.length >= 2 && '.' !in it }
                ?.replaceFirstChar { it.uppercase() }
                ?: host.replaceFirstChar { it.uppercase() }
            val color = TabGroupStore.GROUP_COLORS[(existingCount + createdGroups) % TabGroupStore.GROUP_COLORS.size]
            TabGroupStore.createGroup(
                name = cleanName,
                colorArgb = color,
                tabIds = bucket.map { it.id },
                isPrivate = isPrivate
            )
            createdGroups++
            groupedTabs += bucket.size
        }

        val msg = if (createdGroups > 0) {
            "Grouped $groupedTabs tabs into $createdGroups ${if (createdGroups == 1) "group" else "groups"}"
        } else {
            "No ungrouped tabs share a domain"
        }
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    private fun navigateToHome() {
        val act = activity ?: return
        val navHostFragment = act.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        val controller = navHostFragment?.navController ?: return
        if (controller.currentDestination?.id != R.id.homeFragment) {
            try {
                controller.popBackStack(R.id.homeFragment, false)
            } catch (e: Exception) {
                UHLog.throwable("ui_error", "Failed navigating to home from TabsTray", e)
            }
        }
    }

    private fun navigateToBrowserIfNeeded() {
        val act = activity ?: return
        val navHostFragment = act.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        val controller = navHostFragment?.navController ?: return
        if (controller.currentDestination?.id != R.id.browserFragment) {
            try {
                controller.navigate(R.id.action_homeFragment_to_browserFragment)
            } catch (e: Exception) {
                UHLog.throwable("ui_error", "Failed navigating to browser from TabsTray", e)
            }
        }
    }
}
