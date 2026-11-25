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
import app.harvester.companion.theme.UHTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.appservices.places.BookmarkRoot

/**
 * Full-screen true edge-to-edge Tab Switcher DialogFragment.
 * Replaces the legacy BottomSheetDialogFragment so the Tab Switcher draws seamlessly
 * behind the status bar, display cutout, and navigation bar just like every other screen.
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

    private fun applyWindowSystemBarsAndPrivacy(isPrivateSection: Boolean, isDarkTheme: Boolean, blockScreenshots: Boolean) {
        val w = dialog?.window ?: return
        try {
            val useLightIcons = !isDarkTheme
            val controller = WindowInsetsControllerCompat(w, w.decorView)
            controller.isAppearanceLightStatusBars = useLightIcons
            controller.isAppearanceLightNavigationBars = useLightIcons

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

                    TabsTrayScreen(
                        tabs = tabs,
                        groups = groups,
                        selectedTabId = selectedTabId,
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
                                TabGroupStore.removeTabFromGroups(tabId)
                                TabPreviewCache.remove(tabId)
                                components.useCases.tabsUseCases.removeTab(tabId)
                                TabHistoryStore.recordClosedBatch(
                                    label = "Tab closed",
                                    records = listOf(record),
                                    wasPrivate = target.content.private,
                                    allowUndo = true
                                )
                            }
                        },
                        onCloseTabsBatch = { tabIds, label, isPrivate ->
                            if (tabIds.isNotEmpty()) {
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
                                tabIds.forEach {
                                    TabGroupStore.removeTabFromGroups(it)
                                    TabPreviewCache.remove(it)
                                }
                                components.useCases.tabsUseCases.removeTabs(tabIds)
                                TabHistoryStore.recordClosedBatch(
                                    label = label,
                                    records = records,
                                    wasPrivate = isPrivate,
                                    allowUndo = true
                                )
                            }
                        },
                        onCloseAllTabs = { isPrivate ->
                            if (isPrivate) {
                                PrivateSessionNotificationManager.closeAllPrivateTabs(ctx, components)
                            } else {
                                val normalTargets = tabs.filter { !it.content.private }
                                if (normalTargets.isNotEmpty()) {
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
                                    components.useCases.tabsUseCases.removeTabs(ids)
                                    TabHistoryStore.recordClosedBatch(
                                        label = "Closed ${ids.size} tabs",
                                        records = records,
                                        wasPrivate = false,
                                        allowUndo = true
                                    )
                                }
                            }
                        },
                        onNewTab = { isPrivate ->
                            dismissAllowingStateLoss()
                            browserViewModel.exitEditMode()
                            val newId = components.useCases.tabsUseCases.addTab(
                                url = "about:blank",
                                selectTab = true,
                                private = isPrivate
                            )
                            if (!isPrivate) TabHistoryStore.recordTabAccess(newId)
                            if (isPrivate) {
                                browserViewModel.enterEditMode("")
                                navigateToHome()
                            } else {
                                navigateToHome()
                            }
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
                                navigateToHome()
                            } else {
                                navigateToHome()
                            }
                        },
                        onDuplicateTab = { sourceTab ->
                            val targetUrl = sourceTab.content.url.ifBlank { "about:blank" }
                            val dupId = components.useCases.tabsUseCases.addTab(
                                url = targetUrl,
                                selectTab = false,
                                private = sourceTab.content.private
                            )
                            TabGroupStore.groupForTab(sourceTab.id)?.let { grp ->
                                TabGroupStore.addTabToGroup(grp.id, dupId)
                            }
                            if (!sourceTab.content.private) {
                                TabHistoryStore.recordTabAccess(dupId)
                            }
                            Toast.makeText(ctx, "Tab duplicated", Toast.LENGTH_SHORT).show()
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
                                        val msg = if (saved > 0) "Bookmarked $saved ${if (saved == 1) "tab" else "tabs"}" else "Already bookmarked"
                                        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        onCreateGroup = { name, colorArgb, tabIds, isPrivate ->
                            val finalIds = if (tabIds.isNotEmpty()) {
                                tabIds
                            } else {
                                // Creating a group with zero pre-selected tabs opens a fresh tab directly inside it!
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
                                components.useCases.tabsUseCases.removeTabs(ids)
                            }
                            TabHistoryStore.recordClosedBatch(
                                label = "Closed group \"${group.name}\"",
                                records = records,
                                wasPrivate = group.isPrivate,
                                allowUndo = true
                            )
                        },
                        onUndoClose = {
                            val batch = TabHistoryStore.consumeUndo() ?: return@TabsTrayScreen
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
                        onPrivateSectionVisible = { isPriv ->
                            isPrivateSectionVisible = isPriv
                        },
                        onPrivateSecurePrefChanged = { enabled ->
                            blockPrivateScreenshots = enabled
                        },
                        onDismiss = { dismissAllowingStateLoss() }
                    )
                }
            }
        }
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
