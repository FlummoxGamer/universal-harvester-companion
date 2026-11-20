package app.harvester.companion.browser.tabs

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.NavHostFragment
import app.harvester.companion.R
import app.harvester.companion.UHApp
import app.harvester.companion.UHLog
import app.harvester.companion.browser.BrowserViewModel
import app.harvester.companion.theme.UHTheme
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.R as materialR

class TabsTrayDialogFragment : BottomSheetDialogFragment() {

    private var bottomSheetBehavior: BottomSheetBehavior<View>? = null
    private val browserViewModel: BrowserViewModel by activityViewModels()

    companion object {
        const val TAG = "TabsTrayDialogFragment"
        fun newInstance(): TabsTrayDialogFragment = TabsTrayDialogFragment()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, theme)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return BottomSheetDialog(requireContext(), theme).apply {
            window?.let { w ->
                WindowCompat.setDecorFitsSystemWindows(w, false)
                w.statusBarColor = Color.TRANSPARENT
                w.navigationBarColor = Color.TRANSPARENT
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            setOnShowListener {
                val bottomSheet = findViewById<View?>(materialR.id.design_bottom_sheet)
                bottomSheet?.let { sheet ->
                    sheet.setBackgroundResource(android.R.color.transparent)
                    sheet.layoutParams = sheet.layoutParams.apply {
                        width = ViewGroup.LayoutParams.MATCH_PARENT
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                    }
                    bottomSheetBehavior = BottomSheetBehavior.from(sheet).apply {
                        skipCollapsed = true
                        isFitToContents = false
                        maxWidth = ViewGroup.LayoutParams.MATCH_PARENT
                        peekHeight = resources.displayMetrics.heightPixels
                        state = BottomSheetBehavior.STATE_EXPANDED
                        isDraggable = true
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val app = requireActivity().application as UHApp
        val components = app.components
        TabGroupStore.init(requireContext())

        return ComposeView(requireContext()).apply {
            setContent {
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
                            if (targetTab != null && (targetTab.content.url.isBlank() || targetTab.content.url == "about:blank")) {
                                navigateToHome()
                            } else {
                                navigateToBrowserIfNeeded()
                            }
                        },
                        onCloseTab = { tabId ->
                            TabGroupStore.removeTabFromGroups(tabId)
                            components.useCases.tabsUseCases.removeTab(tabId)
                        },
                        onCloseAllTabs = { isPrivate ->
                            val targetTabs = tabs.filter { it.content.private == isPrivate }
                            if (targetTabs.isNotEmpty()) {
                                val ids = targetTabs.map { it.id }
                                ids.forEach { TabGroupStore.removeTabFromGroups(it) }
                                components.useCases.tabsUseCases.removeTabs(ids)
                            }
                        },
                        onNewTab = { isPrivate ->
                            dismissAllowingStateLoss()
                            browserViewModel.exitEditMode()
                            components.useCases.tabsUseCases.addTab("about:blank", selectTab = true, private = isPrivate)
                            if (isPrivate) {
                                browserViewModel.enterEditMode("")
                                navigateToHome()
                            } else {
                                navigateToHome()
                            }
                        },
                        onCloseSelectedTabs = { selectedIds ->
                            if (selectedIds.isNotEmpty()) {
                                selectedIds.forEach { TabGroupStore.removeTabFromGroups(it) }
                                components.useCases.tabsUseCases.removeTabs(selectedIds)
                            }
                        },
                        onShareSelectedTabs = { selectedIds ->
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
                            }
                        },
                        onCreateGroup = { name, colorArgb, tabIds ->
                            TabGroupStore.createGroup(name, colorArgb, tabIds)
                        },
                        onAddToGroup = { groupId, tabId ->
                            TabGroupStore.addTabToGroup(groupId, tabId)
                        },
                        onRenameGroup = { groupId, name, colorArgb ->
                            TabGroupStore.renameAndRecolor(groupId, name, colorArgb)
                        },
                        onToggleGroupCollapsed = { groupId ->
                            TabGroupStore.toggleCollapsed(groupId)
                        },
                        onUngroup = { groupId ->
                            TabGroupStore.ungroup(groupId)
                        },
                        onCloseGroupTabs = { groupId, tabIds ->
                            TabGroupStore.ungroup(groupId)
                            if (tabIds.isNotEmpty()) {
                                components.useCases.tabsUseCases.removeTabs(tabIds)
                            }
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
