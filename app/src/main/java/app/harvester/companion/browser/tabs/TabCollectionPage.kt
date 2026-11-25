package app.harvester.companion.browser.tabs

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
    val configuration = LocalConfiguration.current
    val columns = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 3 else 2

    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()

    var inactiveExpanded by remember { mutableStateOf(false) }
    var contextMenuTabId by remember { mutableStateOf<String?>(null) }

    // Full-card drag-and-drop state
    val itemBounds = remember { mutableStateMapOf<String, Rect>() }
    var draggingTabId by remember { mutableStateOf<String?>(null) }
    var dragStartRect by remember { mutableStateOf<Rect?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragMovedDistance by remember { mutableFloatStateOf(0f) }
    var didLiveReorder by remember { mutableStateOf(false) }
    var hoveredMergeTargetKey by remember { mutableStateOf<String?>(null) }
    var lastLongPressAtMs by remember { mutableLongStateOf(0L) }

    val latestTrayItems by rememberUpdatedState(trayItems)
    val latestSortMode by rememberUpdatedState(sortMode)

    // Auto-scroll to the active tab (or its containing group) once when the Tab Switcher opens
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
                    if (isListView) {
                        listState.scrollToItem(scrollIndex)
                    } else {
                        gridState.scrollToItem(scrollIndex)
                    }
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
                palette = palette,
                onNewTab = { actions.onNewTab(isPrivate) }
            )
        }
        return
    }

    fun performLiveReorder(dragId: String, targetTabId: String) {
        val singleIds = latestTrayItems
            .filterIsInstance<TrayItem.SingleTabItem>()
            .map { it.tab.id }
            .toMutableList()
        val fromIdx = singleIds.indexOf(dragId)
        val toIdx = singleIds.indexOf(targetTabId)
        if (fromIdx in singleIds.indices && toIdx in singleIds.indices && fromIdx != toIdx) {
            val moved = singleIds.removeAt(fromIdx)
            singleIds.add(toIdx, moved)
            // Keep pinned/grouped IDs in store order cleanly while updating ungrouped order
            val existingOrder = if (isPrivate) TabGroupStore.privateOrderFlow.value else TabGroupStore.normalOrderFlow.value
            val others = existingOrder.filterNot { it in singleIds }
            TabGroupStore.setExactOrder(others + singleIds, isPrivate)
            if (latestSortMode != TabSortMode.MANUAL) {
                TabMetaStore.setSortMode(context, TabSortMode.MANUAL)
                onResetSortToManual()
            }
            didLiveReorder = true
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    fun cardDragModifier(tab: TabSessionState, itemKey: String): Modifier {
        val isDraggingThis = draggingTabId == tab.id
        return Modifier
            .onGloballyPositioned { coords ->
                itemBounds[itemKey] = coords.boundsInWindow()
            }
            .zIndex(if (isDraggingThis) 10f else 0f)
            .graphicsLayer {
                if (isDraggingThis) {
                    translationX = dragOffset.x
                    translationY = dragOffset.y
                    scaleX = 1.04f
                    scaleY = 1.04f
                    alpha = 0.94f
                }
            }
            .then(
                if (!isMultiSelect && searchQuery.isBlank()) {
                    Modifier.pointerInput(tab.id, isPrivate) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                draggingTabId = tab.id
                                dragStartRect = itemBounds[itemKey]
                                dragOffset = Offset.Zero
                                dragMovedDistance = 0f
                                didLiveReorder = false
                                hoveredMergeTargetKey = null
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffset += dragAmount
                                dragMovedDistance += hypot(dragAmount.x, dragAmount.y)

                                val baseRect = dragStartRect ?: itemBounds[itemKey]
                                if (baseRect != null) {
                                    val pointerCenter = Offset(
                                        x = baseRect.center.x + dragOffset.x,
                                        y = baseRect.center.y + dragOffset.y
                                    )
                                    var foundMergeKey: String? = null
                                    var reorderTargetTabId: String? = null

                                    for ((candidateKey, candidateRect) in itemBounds.entries) {
                                        if (candidateKey == itemKey) continue
                                        if (!candidateRect.contains(pointerCenter)) continue

                                        if (candidateKey.startsWith("grp_")) {
                                            // Hovering anywhere over a Group card merges into that group
                                            foundMergeKey = candidateKey
                                            break
                                        } else if (candidateKey.startsWith("tab_")) {
                                            // Center 56% of another tab card = merge to create group
                                            // Outer rim = live reorder (just like Chrome / Brave / Firefox)
                                            val insetX = candidateRect.width * 0.22f
                                            val insetY = candidateRect.height * 0.22f
                                            val innerRect = Rect(
                                                left = candidateRect.left + insetX,
                                                top = candidateRect.top + insetY,
                                                right = candidateRect.right - insetX,
                                                bottom = candidateRect.bottom - insetY
                                            )
                                            if (innerRect.contains(pointerCenter)) {
                                                foundMergeKey = candidateKey
                                            } else if (dragMovedDistance > 26f) {
                                                reorderTargetTabId = candidateKey.removePrefix("tab_")
                                            }
                                            break
                                        }
                                    }

                                    if (foundMergeKey != null) {
                                        if (hoveredMergeTargetKey != foundMergeKey) {
                                            hoveredMergeTargetKey = foundMergeKey
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    } else {
                                        hoveredMergeTargetKey = null
                                        if (reorderTargetTabId != null) {
                                            performLiveReorder(tab.id, reorderTargetTabId)
                                            dragStartRect = itemBounds[itemKey] ?: baseRect
                                            dragOffset = Offset.Zero
                                        }
                                    }
                                }
                            },
                            onDragEnd = {
                                lastLongPressAtMs = System.currentTimeMillis()
                                val target = hoveredMergeTargetKey
                                val moved = dragMovedDistance
                                val reordered = didLiveReorder

                                draggingTabId = null
                                dragStartRect = null
                                dragOffset = Offset.Zero
                                hoveredMergeTargetKey = null
                                dragMovedDistance = 0f
                                didLiveReorder = false

                                when {
                                    target != null && target.startsWith("grp_") -> {
                                        val grpId = target.removePrefix("grp_")
                                        actions.onAddTabsToGroup(grpId, listOf(tab.id))
                                    }
                                    target != null && target.startsWith("tab_") -> {
                                        val otherTabId = target.removePrefix("tab_")
                                        onRequestMergeCreateGroup(listOf(otherTabId, tab.id), isPrivate)
                                    }
                                    !reordered && moved < 24f -> {
                                        contextMenuTabId = tab.id
                                    }
                                }
                            },
                            onDragCancel = {
                                lastLongPressAtMs = System.currentTimeMillis()
                                draggingTabId = null
                                dragStartRect = null
                                dragOffset = Offset.Zero
                                hoveredMergeTargetKey = null
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
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 92.dp),
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
                            onOpenGroup = { onOpenGroupDetail(item.group) },
                            onAddNewTabInGroup = { actions.onNewTabInGroup(item.group.id, item.group.isPrivate) },
                            onEditGroup = { onEditGroup(item.group) },
                            onBookmarkGroup = { actions.onBookmarkTabs(item.group.tabIds) },
                            onShareGroup = { actions.onShareTabs(item.group.tabIds) },
                            onUngroup = { actions.onUngroup(item.group.id) },
                            onCloseGroup = { actions.onCloseGroupTabs(item.group) },
                            modifier = Modifier.onGloballyPositioned { coords ->
                                itemBounds[item.key] = coords.boundsInWindow()
                            }
                        )
                    }
                    is TrayItem.SingleTabItem -> {
                        val tab = item.tab
                        val isMuted = TabMetaStore.isTabMuted(tab) || (tab.id in mutedIdSet)
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
                            isBeingDragged = draggingTabId == tab.id,
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
                            onShare = { actions.onShareTabs(listOf(tab.id)) },
                            modifier = cardDragModifier(tab, item.key)
                        )
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
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 92.dp),
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
                            onOpenGroup = { onOpenGroupDetail(item.group) },
                            onAddNewTabInGroup = { actions.onNewTabInGroup(item.group.id, item.group.isPrivate) },
                            onEditGroup = { onEditGroup(item.group) },
                            onBookmarkGroup = { actions.onBookmarkTabs(item.group.tabIds) },
                            onShareGroup = { actions.onShareTabs(item.group.tabIds) },
                            onUngroup = { actions.onUngroup(item.group.id) },
                            onCloseGroup = { actions.onCloseGroupTabs(item.group) },
                            modifier = Modifier.onGloballyPositioned { coords ->
                                itemBounds[item.key] = coords.boundsInWindow()
                            }
                        )
                    }
                    is TrayItem.SingleTabItem -> {
                        val tab = item.tab
                        val isMuted = TabMetaStore.isTabMuted(tab) || (tab.id in mutedIdSet)
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
                            isBeingDragged = draggingTabId == tab.id,
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
                            onShare = { actions.onShareTabs(listOf(tab.id)) },
                            modifier = cardDragModifier(tab, item.key)
                        )
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

@Composable
private fun PrivateSessionStatusStrip(
    blockScreenshots: Boolean,
    palette: TabsTrayPalette
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = PrivateAccentColor.copy(alpha = if (palette.isDark) 0.14f else 0.10f),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, PrivateAccentColor.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.uh_ic_private_vibe_24),
                contentDescription = null,
                tint = PrivateAccentColor,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Private Session Active",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (blockScreenshots) {
                        "History & cookies isolated · Screenshots blocked"
                    } else {
                        "History & cookies isolated in memory only"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
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
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(PrivateAccentColor.copy(alpha = 0.18f))
                    .border(1.5.dp, PrivateAccentColor.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.uh_ic_pg_fingerprint_24),
                    contentDescription = "Locked",
                    tint = PrivateAccentColor,
                    modifier = Modifier.size(34.dp)
                )
            }
            Text(
                text = "Private Tabs Locked",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (privateTabCount > 0) {
                    "Unlock with biometrics or device PIN to view your $privateTabCount private ${if (privateTabCount == 1) "tab" else "tabs"}."
                } else {
                    "Unlock with biometrics or device PIN to access Private Browsing."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = onUnlock,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrivateAccentColor,
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
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyModeState(
    isPrivate: Boolean,
    palette: TabsTrayPalette,
    onNewTab: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(66.dp)
                    .clip(CircleShape)
                    .background(palette.accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(
                        id = if (isPrivate) R.drawable.uh_ic_private_vibe_24 else R.drawable.uh_ic_tab_modern_24
                    ),
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(30.dp)
                )
            }
            Text(
                text = if (isPrivate) "Browse Privately" else "No Open Tabs",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (isPrivate) {
                    "Private tabs never save history, search queries, or site cookies when closed."
                } else {
                    "Open a fresh tab to start browsing or drag tabs together to create a group."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = onNewTab,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accent,
                    contentColor = palette.onAccent
                )
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.uh_ic_plus_24),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isPrivate) "Open Private Tab" else "Open New Tab",
                    fontWeight = FontWeight.Bold
                )
            }
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
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
        }
    }
}
