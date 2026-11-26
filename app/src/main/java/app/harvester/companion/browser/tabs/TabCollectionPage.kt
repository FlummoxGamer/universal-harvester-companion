package app.harvester.companion.browser.tabs

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.harvester.companion.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mozilla.components.browser.state.state.TabSessionState
import kotlin.math.hypot

@Composable
internal fun TabCollectionPage(
    isPrivate: Boolean,
    isPrivateLocked: Boolean,
    isListView: Boolean,
    sortMode: TabSortMode,
    searchQuery: String,
    pinnedTabs: List<TabSessionState>,
    trayItems: List<TrayItem>,
    inactiveTabs: List<TabSessionState>,
    selectedTabId: String?,
    pinnedIdSet: Set<String>,
    mutedIdSet: Set<String>,
    isMultiSelect: Boolean,
    selectedIds: Set<String>,
    blockPrivateScreenshots: Boolean,
    palette: TabsTrayPalette,
    actions: TabsTrayActions,
    onToggleSelectId: (String) -> Unit,
    onOpenGroupDetail: (TabGroup) -> Unit,
    onEditGroup: (TabGroup) -> Unit,
    onRequestAddTabToGroup: (TabSessionState) -> Unit,
    onRequestMergeCreateGroup: (List<String>, Boolean) -> Unit,
    onCloseAllInactive: () -> Unit,
    onResetSortToManual: () -> Unit
) {
    val backgroundGlowModifier = if (isPrivate) {
        Modifier.background(
            Brush.radialGradient(
                colors = listOf(
                    PrivateAccentStrongColor.copy(alpha = 0.16f),
                    Color.Transparent
                ),
                radius = 900f
            )
        )
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(backgroundGlowModifier)
    ) {
        if (isPrivate && isPrivateLocked) {
            PrivateLockGate(
                privateTabCount = trayItems.sumOf {
                    when (it) {
                        is TrayItem.GroupCardItem -> it.members.size
                        is TrayItem.SingleTabItem -> 1
                    }
                },
                palette = palette,
                onUnlock = actions.onUnlockPrivate,
                onCloseAllPrivate = { actions.onCloseAllTabs(true) }
            )
            return
        }

        val context = LocalContext.current
        val haptic = LocalHapticFeedback.current
        val scope = rememberCoroutineScope()
        val configuration = LocalConfiguration.current
        val columns = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 3 else 2

        val gridState = rememberLazyGridState()
        val listState = rememberLazyListState()

        var inactiveExpanded by remember { mutableStateOf(false) }
        var contextMenuTabId by remember { mutableStateOf<String?>(null) }
        var contextMenuGroupId by remember { mutableStateOf<String?>(null) }

        // Continuous, glitch-free drag-to-reorder & 250ms dwell-to-merge state
        // Works seamlessly for BOTH single tab cards ("tab_<id>") AND group cards ("grp_<id>")
        val slotBounds = remember { mutableStateMapOf<String, Rect>() }
        var draggingKey by remember { mutableStateOf<String?>(null) }
        var dragOriginCenter by remember { mutableStateOf(Offset.Zero) }
        var dragPointerDelta by remember { mutableStateOf(Offset.Zero) }
        var dragMovedDistance by remember { mutableFloatStateOf(0f) }
        var didLiveReorder by remember { mutableStateOf(false) }
        var hoveredMergeTargetKey by remember { mutableStateOf<String?>(null) }
        var dwellCandidateKey by remember { mutableStateOf<String?>(null) }
        var dwellJob by remember { mutableStateOf<Job?>(null) }
        var lastReorderAtMs by remember { mutableLongStateOf(0L) }
        var lastLongPressAtMs by remember { mutableLongStateOf(0L) }

        val latestTrayItems by rememberUpdatedState(trayItems)
        val latestSortMode by rememberUpdatedState(sortMode)

        var didInitialAutoScroll by remember(isPrivate) { mutableStateOf(false) }
        LaunchedEffect(selectedTabId, trayItems.size, isListView) {
            if (!didInitialAutoScroll && selectedTabId != null && searchQuery.isBlank()) {
                val targetIndex = trayItems.indexOfFirst { item ->
                    when (item) {
                        is TrayItem.SingleTabItem -> item.tab.id == selectedTabId
                        is TrayItem.GroupCardItem -> item.members.any { it.id == selectedTabId }
                    }
                }
                if (targetIndex >= 0) {
                    didInitialAutoScroll = true
                    val headerOffset = (if (isPrivate) 1 else 0) + (if (!isPrivate && pinnedTabs.isNotEmpty()) 1 else 0)
                    val scrollIndex = (targetIndex + headerOffset).coerceAtLeast(0)
                    try {
                        if (isListView) listState.scrollToItem(scrollIndex)
                        else gridState.scrollToItem(scrollIndex)
                    } catch (_: Exception) {}
                }
            }
        }

        val isEmptyAll = pinnedTabs.isEmpty() && trayItems.isEmpty() && inactiveTabs.isEmpty()
        if (isEmptyAll) {
            if (searchQuery.isNotBlank()) {
                EmptySearchState(query = searchQuery, palette = palette)
            } else {
                EmptyModeState(
                    isPrivate = isPrivate,
                    palette = palette
                )
            }
            return
        }

        fun performLiveTrayReorder(dragKey: String, targetKey: String) {
            val now = System.currentTimeMillis()
            if (now - lastReorderAtMs < 180L) return
            val keys = latestTrayItems.map { it.key }.toMutableList()
            val fromIdx = keys.indexOf(dragKey)
            val toIdx = keys.indexOf(targetKey)
            if (fromIdx in keys.indices && toIdx in keys.indices && fromIdx != toIdx) {
                val moved = keys.removeAt(fromIdx)
                keys.add(toIdx, moved)
                TabGroupStore.setExactTrayOrder(keys, isPrivate)
                if (latestSortMode != TabSortMode.MANUAL) {
                    TabMetaStore.setSortMode(context, TabSortMode.MANUAL)
                    onResetSortToManual()
                }
                lastReorderAtMs = now
                didLiveReorder = true
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }

        fun cancelDwell() {
            dwellJob?.cancel()
            dwellJob = null
            dwellCandidateKey = null
            hoveredMergeTargetKey = null
        }

        fun armMergeDwell(dragItemKey: String, candidateKey: String) {
            if (dwellCandidateKey == candidateKey && (dwellJob?.isActive == true || hoveredMergeTargetKey == candidateKey)) {
                return
            }
            dwellJob?.cancel()
            dwellCandidateKey = candidateKey
            if (hoveredMergeTargetKey != candidateKey) {
                hoveredMergeTargetKey = null
            }
            dwellJob = scope.launch {
                delay(250L)
                if (draggingKey == dragItemKey && dwellCandidateKey == candidateKey) {
                    hoveredMergeTargetKey = candidateKey
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
        }

        fun unifiedDragModifier(itemKey: String, canMergeIntoOthers: Boolean): Modifier {
            val isDraggingThis = draggingKey == itemKey
            return Modifier
                // Record untranslated layout slot bounds BEFORE applying graphicsLayer translation
                .onGloballyPositioned { coords ->
                    slotBounds[itemKey] = coords.boundsInWindow()
                }
                .zIndex(if (isDraggingThis) 12f else 0f)
                .graphicsLayer {
                    if (isDraggingThis) {
                        val liveCenter = dragOriginCenter + dragPointerDelta
                        val currentSlotCenter = slotBounds[itemKey]?.center ?: dragOriginCenter
                        translationX = liveCenter.x - currentSlotCenter.x
                        translationY = liveCenter.y - currentSlotCenter.y
                        scaleX = 1.045f
                        scaleY = 1.045f
                        alpha = 0.95f
                    }
                }
                .then(
                    if (!isMultiSelect && searchQuery.isBlank()) {
                        Modifier.pointerInput(itemKey, isPrivate) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    draggingKey = itemKey
                                    dragOriginCenter = slotBounds[itemKey]?.center ?: Offset.Zero
                                    dragPointerDelta = Offset.Zero
                                    dragMovedDistance = 0f
                                    didLiveReorder = false
                                    cancelDwell()
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragPointerDelta += dragAmount
                                    dragMovedDistance += hypot(dragAmount.x, dragAmount.y)

                                    val liveCenter = dragOriginCenter + dragPointerDelta
                                    var candidateMergeKey: String? = null
                                    var candidateReorderKey: String? = null

                                    for ((candidateKey, candidateRect) in slotBounds.entries) {
                                        if (candidateKey == itemKey) continue
                                        if (!candidateRect.contains(liveCenter)) continue

                                        if (canMergeIntoOthers && candidateKey.startsWith("grp_")) {
                                            // Center 70% of a group card = dwell 250ms to add into group; outer rim = reorder around group
                                            val insetX = candidateRect.width * 0.15f
                                            val insetY = candidateRect.height * 0.15f
                                            val innerRect = Rect(
                                                left = candidateRect.left + insetX,
                                                top = candidateRect.top + insetY,
                                                right = candidateRect.right - insetX,
                                                bottom = candidateRect.bottom - insetY
                                            )
                                            if (innerRect.contains(liveCenter)) {
                                                candidateMergeKey = candidateKey
                                            } else if (dragMovedDistance > 24f) {
                                                candidateReorderKey = candidateKey
                                            }
                                            break
                                        } else if (canMergeIntoOthers && candidateKey.startsWith("tab_")) {
                                            // Center 54% of another tab card = dwell 250ms to merge; outer rim = edge-cross reorder
                                            val insetX = candidateRect.width * 0.23f
                                            val insetY = candidateRect.height * 0.23f
                                            val innerRect = Rect(
                                                left = candidateRect.left + insetX,
                                                top = candidateRect.top + insetY,
                                                right = candidateRect.right - insetX,
                                                bottom = candidateRect.bottom - insetY
                                            )
                                            if (innerRect.contains(liveCenter)) {
                                                candidateMergeKey = candidateKey
                                            } else if (dragMovedDistance > 24f) {
                                                candidateReorderKey = candidateKey
                                            }
                                            break
                                        } else {
                                            // Dragging a Group card reorders cleanly with all sibling items
                                            if (dragMovedDistance > 24f) {
                                                candidateReorderKey = candidateKey
                                            }
                                            break
                                        }
                                    }

                                    if (candidateMergeKey != null) {
                                        armMergeDwell(itemKey, candidateMergeKey)
                                    } else {
                                        cancelDwell()
                                        if (candidateReorderKey != null) {
                                            performLiveTrayReorder(itemKey, candidateReorderKey)
                                        }
                                    }
                                },
                                onDragEnd = {
                                    lastLongPressAtMs = System.currentTimeMillis()
                                    val armedTarget = hoveredMergeTargetKey
                                    val moved = dragMovedDistance
                                    val reordered = didLiveReorder

                                    cancelDwell()
                                    draggingKey = null
                                    dragPointerDelta = Offset.Zero
                                    dragMovedDistance = 0f
                                    didLiveReorder = false

                                    when {
                                        canMergeIntoOthers && armedTarget != null && armedTarget.startsWith("grp_") -> {
                                            val grpId = armedTarget.removePrefix("grp_")
                                            val tabId = itemKey.removePrefix("tab_")
                                            actions.onAddTabsToGroup(grpId, listOf(tabId))
                                        }
                                        canMergeIntoOthers && armedTarget != null && armedTarget.startsWith("tab_") -> {
                                            val otherTabId = armedTarget.removePrefix("tab_")
                                            val tabId = itemKey.removePrefix("tab_")
                                            onRequestMergeCreateGroup(listOf(otherTabId, tabId), isPrivate)
                                        }
                                        !reordered && moved < 24f -> {
                                            if (itemKey.startsWith("tab_")) {
                                                contextMenuTabId = itemKey.removePrefix("tab_")
                                            } else if (itemKey.startsWith("grp_")) {
                                                contextMenuGroupId = itemKey.removePrefix("grp_")
                                            }
                                        }
                                    }
                                },
                                onDragCancel = {
                                    lastLongPressAtMs = System.currentTimeMillis()
                                    cancelDwell()
                                    draggingKey = null
                                    dragPointerDelta = Offset.Zero
                                    dragMovedDistance = 0f
                                    didLiveReorder = false
                                }
                            )
                        }
                    } else {
                        Modifier
                    }
                )
        }

        fun handleCardClick(tabId: String) {
            if (System.currentTimeMillis() - lastLongPressAtMs < 350L) return
            if (isMultiSelect) {
                onToggleSelectId(tabId)
            } else {
                actions.onSelectTab(tabId)
            }
        }

        if (isListView) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (isPrivate) {
                    item(key = "priv_banner") {
                        PrivateSessionStatusStrip(
                            blockScreenshots = blockPrivateScreenshots,
                            palette = palette
                        )
                    }
                }

                if (!isPrivate && pinnedTabs.isNotEmpty()) {
                    item(key = "pinned_strip") {
                        PinnedTabsStrip(
                            pinnedTabs = pinnedTabs,
                            selectedTabId = selectedTabId,
                            mutedIds = mutedIdSet,
                            palette = palette,
                            onSelectTab = actions.onSelectTab,
                            onUnpinTab = actions.onTogglePinTab,
                            onToggleMuteTab = actions.onToggleMuteTab,
                            onBookmarkTab = { actions.onBookmarkTabs(listOf(it.id)) },
                            onDuplicateTab = actions.onDuplicateTab,
                            onShareTab = { actions.onShareTabs(listOf(it.id)) },
                            onCloseTab = actions.onCloseTab
                        )
                    }
                }

                items(trayItems, key = { it.key }) { item ->
                    when (item) {
                        is TrayItem.GroupCardItem -> {
                            TabGroupListCard(
                                group = item.group,
                                members = item.members,
                                selectedTabId = selectedTabId,
                                palette = palette,
                                isDropTarget = hoveredMergeTargetKey == item.key,
                                isBeingDragged = draggingKey == item.key,
                                showContextMenu = contextMenuGroupId == item.group.id,
                                onDismissContextMenu = { contextMenuGroupId = null },
                                onOpenGroup = {
                                    if (System.currentTimeMillis() - lastLongPressAtMs >= 350L) {
                                        onOpenGroupDetail(item.group)
                                    }
                                },
                                onAddNewTabInGroup = { actions.onNewTabInGroup(item.group.id, item.group.isPrivate) },
                                onEditGroup = { onEditGroup(item.group) },
                                onBookmarkGroup = { actions.onBookmarkTabs(item.group.tabIds) },
                                onShareGroup = { actions.onShareTabs(item.group.tabIds) },
                                onUngroup = { actions.onUngroup(item.group.id) },
                                onCloseGroup = { actions.onCloseGroupTabs(item.group) },
                                modifier = Modifier
                                    .animateItem()
                                    .then(unifiedDragModifier(item.key, canMergeIntoOthers = false))
                            )
                        }
                        is TrayItem.SingleTabItem -> {
                            val tab = item.tab
                            val isMuted = TabMetaStore.isTabMuted(tab) || (tab.id in mutedIdSet)
                            SwipeToCloseTabBox(
                                enabled = !isMultiSelect && draggingKey == null,
                                onClose = { actions.onCloseTab(tab.id) },
                                modifier = Modifier
                                    .animateItem()
                                    .then(unifiedDragModifier(item.key, canMergeIntoOthers = true))
                            ) {
                                TabListRow(
                                    tab = tab,
                                    group = null,
                                    palette = palette,
                                    isSelected = tab.id == selectedTabId,
                                    isPinned = tab.id in pinnedIdSet,
                                    isMuted = isMuted,
                                    isSleeping = TabMetaStore.isTabSleeping(tab, selectedTabId),
                                    isMultiSelect = isMultiSelect,
                                    isChecked = tab.id in selectedIds,
                                    isDropTarget = hoveredMergeTargetKey == item.key,
                                    isBeingDragged = draggingKey == item.key,
                                    showContextMenu = contextMenuTabId == tab.id,
                                    onDismissContextMenu = { contextMenuTabId = null },
                                    onClick = { handleCardClick(tab.id) },
                                    onClose = { actions.onCloseTab(tab.id) },
                                    onToggleMute = { actions.onToggleMuteTab(tab) },
                                    onTogglePin = { actions.onTogglePinTab(tab) },
                                    onAddToGroup = { onRequestAddTabToGroup(tab) },
                                    onRemoveFromGroup = null,
                                    onBookmark = { actions.onBookmarkTabs(listOf(tab.id)) },
                                    onDuplicate = { actions.onDuplicateTab(tab) },
                                    onShare = { actions.onShareTabs(listOf(tab.id)) }
                                )
                            }
                        }
                    }
                }

                if (!isPrivate && inactiveTabs.isNotEmpty() && searchQuery.isBlank()) {
                    item(key = "inactive_section") {
                        Spacer(modifier = Modifier.height(6.dp))
                        InactiveTabsSection(
                            inactiveTabs = inactiveTabs,
                            expanded = inactiveExpanded,
                            palette = palette,
                            onToggleExpanded = { inactiveExpanded = !inactiveExpanded },
                            onSelectTab = actions.onSelectTab,
                            onCloseTab = actions.onCloseTab,
                            onCloseAllInactive = onCloseAllInactive
                        )
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 104.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (isPrivate) {
                    item(key = "priv_banner", span = { GridItemSpan(maxLineSpan) }) {
                        PrivateSessionStatusStrip(
                            blockScreenshots = blockPrivateScreenshots,
                            palette = palette
                        )
                    }
                }

                if (!isPrivate && pinnedTabs.isNotEmpty()) {
                    item(key = "pinned_strip", span = { GridItemSpan(maxLineSpan) }) {
                        PinnedTabsStrip(
                            pinnedTabs = pinnedTabs,
                            selectedTabId = selectedTabId,
                            mutedIds = mutedIdSet,
                            palette = palette,
                            onSelectTab = actions.onSelectTab,
                            onUnpinTab = actions.onTogglePinTab,
                            onToggleMuteTab = actions.onToggleMuteTab,
                            onBookmarkTab = { actions.onBookmarkTabs(listOf(it.id)) },
                            onDuplicateTab = actions.onDuplicateTab,
                            onShareTab = { actions.onShareTabs(listOf(it.id)) },
                            onCloseTab = actions.onCloseTab
                        )
                    }
                }

                items(trayItems, key = { it.key }) { item ->
                    when (item) {
                        is TrayItem.GroupCardItem -> {
                            TabGroupGridCard(
                                group = item.group,
                                members = item.members,
                                selectedTabId = selectedTabId,
                                palette = palette,
                                isDropTarget = hoveredMergeTargetKey == item.key,
                                isBeingDragged = draggingKey == item.key,
                                showContextMenu = contextMenuGroupId == item.group.id,
                                onDismissContextMenu = { contextMenuGroupId = null },
                                onOpenGroup = {
                                    if (System.currentTimeMillis() - lastLongPressAtMs >= 350L) {
                                        onOpenGroupDetail(item.group)
                                    }
                                },
                                onAddNewTabInGroup = { actions.onNewTabInGroup(item.group.id, item.group.isPrivate) },
                                onEditGroup = { onEditGroup(item.group) },
                                onBookmarkGroup = { actions.onBookmarkTabs(item.group.tabIds) },
                                onShareGroup = { actions.onShareTabs(item.group.tabIds) },
                                onUngroup = { actions.onUngroup(item.group.id) },
                                onCloseGroup = { actions.onCloseGroupTabs(item.group) },
                                modifier = Modifier
                                    .animateItem()
                                    .then(unifiedDragModifier(item.key, canMergeIntoOthers = false))
                            )
                        }
                        is TrayItem.SingleTabItem -> {
                            val tab = item.tab
                            val isMuted = TabMetaStore.isTabMuted(tab) || (tab.id in mutedIdSet)
                            SwipeToCloseTabBox(
                                enabled = !isMultiSelect && draggingKey == null,
                                onClose = { actions.onCloseTab(tab.id) },
                                modifier = Modifier
                                    .animateItem()
                                    .then(unifiedDragModifier(item.key, canMergeIntoOthers = true))
                            ) {
                                TabGridCard(
                                    tab = tab,
                                    group = null,
                                    palette = palette,
                                    isSelected = tab.id == selectedTabId,
                                    isPinned = tab.id in pinnedIdSet,
                                    isMuted = isMuted,
                                    isSleeping = TabMetaStore.isTabSleeping(tab, selectedTabId),
                                    isMultiSelect = isMultiSelect,
                                    isChecked = tab.id in selectedIds,
                                    isDropTarget = hoveredMergeTargetKey == item.key,
                                    isBeingDragged = draggingKey == item.key,
                                    showContextMenu = contextMenuTabId == tab.id,
                                    onDismissContextMenu = { contextMenuTabId = null },
                                    onClick = { handleCardClick(tab.id) },
                                    onClose = { actions.onCloseTab(tab.id) },
                                    onToggleMute = { actions.onToggleMuteTab(tab) },
                                    onTogglePin = { actions.onTogglePinTab(tab) },
                                    onAddToGroup = { onRequestAddTabToGroup(tab) },
                                    onRemoveFromGroup = null,
                                    onBookmark = { actions.onBookmarkTabs(listOf(tab.id)) },
                                    onDuplicate = { actions.onDuplicateTab(tab) },
                                    onShare = { actions.onShareTabs(listOf(tab.id)) }
                                )
                            }
                        }
                    }
                }

                if (!isPrivate && inactiveTabs.isNotEmpty() && searchQuery.isBlank()) {
                    item(key = "inactive_section", span = { GridItemSpan(maxLineSpan) }) {
                        InactiveTabsSection(
                            inactiveTabs = inactiveTabs,
                            expanded = inactiveExpanded,
                            palette = palette,
                            onToggleExpanded = { inactiveExpanded = !inactiveExpanded },
                            onSelectTab = actions.onSelectTab,
                            onCloseTab = actions.onCloseTab,
                            onCloseAllInactive = onCloseAllInactive,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivateSessionStatusStrip(
    blockScreenshots: Boolean,
    palette: TabsTrayPalette
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF17112B),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, PrivateAccentColor.copy(alpha = 0.28f), RoundedCornerShape(16.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(PrivateAccentStrongColor.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.uh_ic_private_vibe_24),
                    contentDescription = null,
                    tint = PrivateAccentColor,
                    modifier = Modifier.size(16.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Hardened Stealth Session",
                    color = palette.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (blockScreenshots) {
                        "Strict trackers & 3P cookies blocked · HTTPS-Only · Screenshots blocked"
                    } else {
                        "Strict trackers & 3P cookies blocked · HTTPS-Only · Zero disk trace"
                    },
                    color = palette.onSurfaceSubtle,
                    fontSize = 10.5.sp
                )
            }
        }
    }
}

@Composable
internal fun PrivateLockGate(
    privateTabCount: Int,
    palette: TabsTrayPalette,
    onUnlock: () -> Unit,
    onCloseAllPrivate: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(PrivateAccentStrongColor.copy(alpha = 0.20f))
                    .border(1.5.dp, PrivateAccentColor.copy(alpha = 0.50f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.uh_ic_private_vibe_24),
                    contentDescription = "Locked",
                    tint = PrivateAccentColor,
                    modifier = Modifier.size(34.dp)
                )
            }
            Text(
                text = "Private Tabs Locked",
                color = palette.onSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (privateTabCount > 0) {
                    "Unlock with biometrics or device PIN to view your $privateTabCount private ${if (privateTabCount == 1) "tab" else "tabs"}."
                } else {
                    "Unlock with biometrics or device PIN to access Private Browsing."
                },
                color = palette.onSurfaceSubtle,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = onUnlock,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrivateAccentStrongColor,
                    contentColor = Color.White
                ),
                modifier = Modifier.height(46.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.uh_ic_lock),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Unlock Private Tabs",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            if (privateTabCount > 0) {
                TextButton(onClick = onCloseAllPrivate) {
                    Text(
                        text = "Close all private tabs",
                        color = Color(0xFFF87171),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * Clean minimal empty state with NO redundant mid-screen action button
 * (the floating "+" dock button handles opening tabs cleanly).
 */
@Composable
private fun EmptyModeState(
    isPrivate: Boolean,
    palette: TabsTrayPalette,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 36.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(74.dp)
                    .clip(CircleShape)
                    .background(
                        if (isPrivate) PrivateAccentStrongColor.copy(alpha = 0.18f)
                        else palette.accent.copy(alpha = 0.12f)
                    )
                    .border(
                        width = 1.2.dp,
                        color = if (isPrivate) PrivateAccentColor.copy(alpha = 0.42f) else palette.subtleBorder,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(
                        id = if (isPrivate) R.drawable.uh_ic_private_vibe_24 else R.drawable.uh_ic_tab_modern_24
                    ),
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(32.dp)
                )
            }
            Text(
                text = if (isPrivate) "Stealth Mode Ready" else "No Open Tabs",
                color = palette.onSurface,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (isPrivate) {
                    "Zero history, isolated cookies, strict tracker & fingerprint shield, and instant session wipe on close."
                } else {
                    "Tap + below to open a tab. Hold and dwell one tab over another to create a group."
                },
                color = palette.onSurfaceSubtle,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun EmptySearchState(
    query: String,
    palette: TabsTrayPalette
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.fx_ic_search_24),
                contentDescription = null,
                tint = palette.accent.copy(alpha = 0.7f),
                modifier = Modifier.size(34.dp)
            )
            Text(
                text = "No tabs matching \"$query\"",
                color = palette.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
        }
    }
}
