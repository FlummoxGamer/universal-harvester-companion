package app.harvester.companion.browser.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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
            return@Box
        }

        val hasAnyContent = pinnedTabs.isNotEmpty() || trayItems.isNotEmpty() || inactiveTabs.isNotEmpty()
        if (!hasAnyContent) {
            EmptyTabsState(
                isPrivate = isPrivate,
                isSearchActive = searchQuery.isNotBlank(),
                palette = palette
            )
            return@Box
        }

        val haptic = LocalHapticFeedback.current
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current
        val screenWidthDp = LocalConfiguration.current.screenWidthDp
        val gridColumns = remember(screenWidthDp) {
            when {
                screenWidthDp >= 840 -> 4
                screenWidthDp >= 600 -> 3
                else -> 2
            }
        }

        var inactiveExpanded by remember { mutableStateOf(false) }
        var contextMenuTabId by remember { mutableStateOf<String?>(null) }
        var contextMenuGroupId by remember { mutableStateOf<String?>(null) }

        val gridState = rememberLazyGridState()
        val latestTrayItems by rememberUpdatedState(trayItems)
        val latestSortMode by rememberUpdatedState(sortMode)

        // Viewport-local drag-to-reorder & drop-to-group engine (100% parity on Normal & Private!)
        var draggingKey by remember { mutableStateOf<String?>(null) }
        var dragPointerViewport by remember { mutableStateOf(Offset.Zero) }
        var dragTouchOffsetInCard by remember { mutableStateOf(Offset.Zero) }
        var dragMovedDistance by remember { mutableFloatStateOf(0f) }
        var didLiveReorder by remember { mutableStateOf(false) }
        var lastReorderAtMs by remember { mutableLongStateOf(0L) }

        var pendingMergeKey by remember { mutableStateOf<String?>(null) }
        var armedMergeTargetKey by remember { mutableStateOf<String?>(null) }
        var dwellJob by remember { mutableStateOf<Job?>(null) }
        var autoScrollSpeed by remember { mutableFloatStateOf(0f) }

        val edgeThresholdPx = with(density) { 74.dp.toPx() }

        // Smooth edge auto-scroll while dragging near top or bottom of grid
        LaunchedEffect(draggingKey, autoScrollSpeed) {
            while (draggingKey != null && autoScrollSpeed != 0f) {
                gridState.scrollBy(autoScrollSpeed)
                delay(16L)
            }
        }

        fun cancelDwell() {
            dwellJob?.cancel()
            dwellJob = null
            pendingMergeKey = null
            armedMergeTargetKey = null
        }

        fun armDwellFor(targetKey: String, isGroupTarget: Boolean) {
            if (armedMergeTargetKey == targetKey) return
            if (pendingMergeKey == targetKey && dwellJob?.isActive == true) return
            dwellJob?.cancel()
            pendingMergeKey = targetKey
            armedMergeTargetKey = null
            // Snappy Chrome-style group-morph response when hovering over a card center
            val dwellMs = if (isGroupTarget) 75L else 105L
            dwellJob = scope.launch {
                delay(dwellMs)
                if (draggingKey != null && pendingMergeKey == targetKey) {
                    armedMergeTargetKey = targetKey
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
        }

        fun performLiveReorder(fromKey: String, toKey: String) {
            val now = System.currentTimeMillis()
            if (now - lastReorderAtMs < 150L) return
            val keys = latestTrayItems.map { it.key }.toMutableList()
            val fromIdx = keys.indexOf(fromKey)
            val toIdx = keys.indexOf(toKey)
            if (fromIdx in keys.indices && toIdx in keys.indices && fromIdx != toIdx) {
                val moved = keys.removeAt(fromIdx)
                keys.add(toIdx, moved)
                if (latestSortMode != TabSortMode.MANUAL) {
                    onResetSortToManual()
                }
                TabGroupStore.setExactTrayOrder(keys, isPrivate)
                lastReorderAtMs = now
                didLiveReorder = true
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }

        fun Modifier.trayCardDragModifier(itemKey: String, canMergeIntoOthers: Boolean): Modifier {
            if (isMultiSelect) return this
            return this.pointerInput(itemKey, isPrivate, canMergeIntoOthers) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { downOffset ->
                        val visibleInfo = gridState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == itemKey }
                        dragTouchOffsetInCard = downOffset
                        dragPointerViewport = if (visibleInfo != null) {
                            Offset(
                                visibleInfo.offset.x.toFloat() + downOffset.x,
                                visibleInfo.offset.y.toFloat() + downOffset.y
                            )
                        } else downOffset

                        draggingKey = itemKey
                        dragMovedDistance = 0f
                        didLiveReorder = false
                        autoScrollSpeed = 0f
                        cancelDwell()
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDragCancel = {
                        draggingKey = null
                        dragMovedDistance = 0f
                        didLiveReorder = false
                        autoScrollSpeed = 0f
                        cancelDwell()
                    },
                    onDragEnd = {
                        val armedTarget = armedMergeTargetKey
                        val moved = dragMovedDistance
                        val reordered = didLiveReorder

                        cancelDwell()
                        draggingKey = null
                        dragMovedDistance = 0f
                        didLiveReorder = false
                        autoScrollSpeed = 0f

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
                            !reordered && moved < 26f -> {
                                if (itemKey.startsWith("tab_")) {
                                    contextMenuTabId = itemKey.removePrefix("tab_")
                                } else if (itemKey.startsWith("grp_")) {
                                    contextMenuGroupId = itemKey.removePrefix("grp_")
                                }
                            }
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragPointerViewport += dragAmount
                        dragMovedDistance += hypot(dragAmount.x, dragAmount.y)

                        val pointerViewport = dragPointerViewport
                        val layoutInfo = gridState.layoutInfo
                        val vpStart = layoutInfo.viewportStartOffset.toFloat()
                        val vpEnd = layoutInfo.viewportEndOffset.toFloat()

                        // Edge auto-scroll
                        autoScrollSpeed = when {
                            pointerViewport.y < vpStart + edgeThresholdPx -> -18f
                            pointerViewport.y > vpEnd - edgeThresholdPx -> 18f
                            else -> 0f
                        }

                        // Hit-test directly against real-time LazyGridState viewport items
                        // (immune to HorizontalPager X-offset -> 100% identical accuracy on Private & Normal!)
                        val hoveredItem = layoutInfo.visibleItemsInfo.firstOrNull { info ->
                            val key = info.key as? String ?: return@firstOrNull false
                            if (key == itemKey) return@firstOrNull false
                            if (!key.startsWith("tab_") && !key.startsWith("grp_")) return@firstOrNull false
                            val left = info.offset.x.toFloat()
                            val top = info.offset.y.toFloat()
                            pointerViewport.x in left..(left + info.size.width) &&
                                pointerViewport.y in top..(top + info.size.height)
                        }

                        if (hoveredItem == null) {
                            cancelDwell()
                            return@detectDragGesturesAfterLongPress
                        }

                        val targetKey = hoveredItem.key as String
                        val left = hoveredItem.offset.x.toFloat()
                        val top = hoveredItem.offset.y.toFloat()
                        val width = hoveredItem.size.width.toFloat().coerceAtLeast(1f)
                        val height = hoveredItem.size.height.toFloat().coerceAtLeast(1f)

                        if (canMergeIntoOthers && targetKey.startsWith("grp_")) {
                            // Generous 72% center sticky magnet on existing Groups; moving to outer edge reorders smoothly
                            val padX = width * 0.14f
                            val padY = height * 0.14f
                            val inGroupCenter = pointerViewport.x in (left + padX)..(left + width - padX) &&
                                pointerViewport.y in (top + padY)..(top + height - padY)
                            if (inGroupCenter) {
                                armDwellFor(targetKey, isGroupTarget = true)
                            } else {
                                cancelDwell()
                                performLiveReorder(itemKey, targetKey)
                            }
                        } else if (canMergeIntoOthers && targetKey.startsWith("tab_")) {
                            // Chrome-style 56% center group-morph zone; pushing past outer edge triggers smooth reorder
                            val padX = width * 0.22f
                            val padY = height * 0.22f
                            val inTabCenter = pointerViewport.x in (left + padX)..(left + width - padX) &&
                                pointerViewport.y in (top + padY)..(top + height - padY)
                            if (inTabCenter) {
                                armDwellFor(targetKey, isGroupTarget = false)
                            } else {
                                cancelDwell()
                                performLiveReorder(itemKey, targetKey)
                            }
                        } else {
                            // Dragging a group card always reorders smoothly
                            cancelDwell()
                            performLiveReorder(itemKey, targetKey)
                        }
                    }
                )
            }
        }

        if (isListView) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (pinnedTabs.isNotEmpty()) {
                    item(key = "pinned_strip") {
                        PinnedTabsStrip(
                            pinnedTabs = pinnedTabs,
                            selectedTabId = selectedTabId,
                            mutedIdSet = mutedIdSet,
                            palette = palette,
                            onSelect = actions.onSelectTab,
                            onUnpin = actions.onTogglePinTab,
                            onClose = actions.onCloseTab
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
                                isDropTarget = false,
                                onOpenGroup = { onOpenGroupDetail(item.group) },
                                onAddNewTabInGroup = { actions.onNewTabInGroup(item.group.id, item.group.isPrivate) },
                                onEditGroup = { onEditGroup(item.group) },
                                onBookmarkGroup = {
                                    if (!item.group.isPrivate) actions.onBookmarkTabs(item.group.tabIds)
                                },
                                onShareGroup = { actions.onShareTabs(item.group.tabIds) },
                                onUngroup = { actions.onUngroup(item.group.id) },
                                onCloseGroup = { actions.onCloseGroupTabs(item.group) }
                            )
                        }
                        is TrayItem.SingleTabItem -> {
                            val tab = item.tab
                            SwipeToCloseTabBox(
                                enabled = !isMultiSelect,
                                onClose = { actions.onCloseTab(tab.id) }
                            ) {
                                TabListRow(
                                    tab = tab,
                                    group = null,
                                    palette = palette,
                                    isSelected = tab.id == selectedTabId,
                                    isPinned = tab.id in pinnedIdSet,
                                    isMuted = tab.id in mutedIdSet,
                                    isSleeping = TabMetaStore.isTabSleeping(tab, selectedTabId),
                                    isMultiSelect = isMultiSelect,
                                    isChecked = tab.id in selectedIds,
                                    isDropTarget = false,
                                    isBeingDragged = false,
                                    showContextMenu = contextMenuTabId == tab.id,
                                    onDismissContextMenu = { contextMenuTabId = null },
                                    onClick = {
                                        if (isMultiSelect) onToggleSelectId(tab.id)
                                        else actions.onSelectTab(tab.id)
                                    },
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

                if (inactiveTabs.isNotEmpty()) {
                    item(key = "inactive_section") {
                        InactiveTabsAccordion(
                            inactiveTabs = inactiveTabs,
                            expanded = inactiveExpanded,
                            palette = palette,
                            onToggleExpand = { inactiveExpanded = !inactiveExpanded },
                            onSelectTab = actions.onSelectTab,
                            onCloseTab = actions.onCloseTab,
                            onCloseAllInactive = onCloseAllInactive
                        )
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(gridColumns),
                userScrollEnabled = draggingKey == null,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 104.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (pinnedTabs.isNotEmpty()) {
                    item(key = "pinned_strip", span = { GridItemSpan(maxLineSpan) }) {
                        PinnedTabsStrip(
                            pinnedTabs = pinnedTabs,
                            selectedTabId = selectedTabId,
                            mutedIdSet = mutedIdSet,
                            palette = palette,
                            onSelect = actions.onSelectTab,
                            onUnpin = actions.onTogglePinTab,
                            onClose = actions.onCloseTab
                        )
                    }
                }

                items(trayItems, key = { it.key }) { item ->
                    val isDraggingThis = draggingKey == item.key
                    val isArmedDropTarget = armedMergeTargetKey == item.key
                    val dragScale by animateFloatAsState(
                        targetValue = if (isDraggingThis) 0.88f else 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        label = "tabDragScale"
                    )

                    when (item) {
                        is TrayItem.GroupCardItem -> {
                            Box(
                                modifier = Modifier
                                    .then(if (!isDraggingThis) Modifier.animateItem() else Modifier)
                                    .zIndex(if (isDraggingThis) 12f else 0f)
                                    .graphicsLayer {
                                        if (isDraggingThis) {
                                            val curInfo = gridState.layoutInfo.visibleItemsInfo
                                                .firstOrNull { it.key == item.key }
                                            if (curInfo != null) {
                                                val desiredTopLeft = dragPointerViewport - dragTouchOffsetInCard
                                                translationX = desiredTopLeft.x - curInfo.offset.x.toFloat()
                                                translationY = desiredTopLeft.y - curInfo.offset.y.toFloat()
                                            }
                                            alpha = 0.94f
                                        }
                                        scaleX = dragScale
                                        scaleY = dragScale
                                    }
                                    .trayCardDragModifier(itemKey = item.key, canMergeIntoOthers = false)
                            ) {
                                TabGroupGridCard(
                                    group = item.group,
                                    members = item.members,
                                    selectedTabId = selectedTabId,
                                    palette = palette,
                                    isDropTarget = isArmedDropTarget,
                                    onOpenGroup = { onOpenGroupDetail(item.group) },
                                    onAddNewTabInGroup = { actions.onNewTabInGroup(item.group.id, item.group.isPrivate) },
                                    onEditGroup = { onEditGroup(item.group) },
                                    onBookmarkGroup = {
                                        if (!item.group.isPrivate) actions.onBookmarkTabs(item.group.tabIds)
                                    },
                                    onShareGroup = { actions.onShareTabs(item.group.tabIds) },
                                    onUngroup = { actions.onUngroup(item.group.id) },
                                    onCloseGroup = { actions.onCloseGroupTabs(item.group) },
                                    isBeingDragged = isDraggingThis,
                                    showContextMenu = contextMenuGroupId == item.group.id,
                                    onDismissContextMenu = { contextMenuGroupId = null }
                                )
                            }
                        }

                        is TrayItem.SingleTabItem -> {
                            val tab = item.tab
                            Box(
                                modifier = Modifier
                                    .then(if (!isDraggingThis) Modifier.animateItem() else Modifier)
                                    .zIndex(if (isDraggingThis) 12f else 0f)
                                    .graphicsLayer {
                                        if (isDraggingThis) {
                                            val curInfo = gridState.layoutInfo.visibleItemsInfo
                                                .firstOrNull { it.key == item.key }
                                            if (curInfo != null) {
                                                val desiredTopLeft = dragPointerViewport - dragTouchOffsetInCard
                                                translationX = desiredTopLeft.x - curInfo.offset.x.toFloat()
                                                translationY = desiredTopLeft.y - curInfo.offset.y.toFloat()
                                            }
                                            alpha = 0.94f
                                        }
                                        scaleX = dragScale
                                        scaleY = dragScale
                                    }
                                    .trayCardDragModifier(itemKey = item.key, canMergeIntoOthers = true)
                            ) {
                                SwipeToCloseTabBox(
                                    enabled = !isMultiSelect && draggingKey == null,
                                    onClose = { actions.onCloseTab(tab.id) }
                                ) {
                                    TabGridCard(
                                        tab = tab,
                                        group = null,
                                        palette = palette,
                                        isSelected = tab.id == selectedTabId,
                                        isPinned = tab.id in pinnedIdSet,
                                        isMuted = tab.id in mutedIdSet,
                                        isSleeping = TabMetaStore.isTabSleeping(tab, selectedTabId),
                                        isMultiSelect = isMultiSelect,
                                        isChecked = tab.id in selectedIds,
                                        isDropTarget = isArmedDropTarget,
                                        isBeingDragged = isDraggingThis,
                                        showContextMenu = contextMenuTabId == tab.id,
                                        onDismissContextMenu = { contextMenuTabId = null },
                                        onClick = {
                                            if (isMultiSelect) onToggleSelectId(tab.id)
                                            else actions.onSelectTab(tab.id)
                                        },
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
                }

                if (inactiveTabs.isNotEmpty()) {
                    item(key = "inactive_section", span = { GridItemSpan(maxLineSpan) }) {
                        InactiveTabsAccordion(
                            inactiveTabs = inactiveTabs,
                            expanded = inactiveExpanded,
                            palette = palette,
                            onToggleExpand = { inactiveExpanded = !inactiveExpanded },
                            onSelectTab = actions.onSelectTab,
                            onCloseTab = actions.onCloseTab,
                            onCloseAllInactive = onCloseAllInactive
                        )
                    }
                }
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(PrivateAccentStrongColor.copy(alpha = 0.22f))
                    .border(1.2.dp, PrivateAccentColor.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.uh_ic_private_vibe_24),
                    contentDescription = null,
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
                    "$privateTabCount private ${if (privateTabCount == 1) "tab is" else "tabs are"} protected. Verify your identity to continue."
                } else {
                    "Verify your identity to access Private Browsing."
                },
                color = palette.onSurfaceSubtle,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Button(
                onClick = onUnlock,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrivateAccentStrongColor,
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp)
            ) {
                Text("Unlock Private Tabs", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            if (privateTabCount > 0) {
                TextButton(onClick = onCloseAllPrivate) {
                    Text(
                        text = "Close all private tabs",
                        color = Color(0xFFF87171),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyTabsState(
    isPrivate: Boolean,
    isSearchActive: Boolean,
    palette: TabsTrayPalette
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(70.dp)
                    .clip(CircleShape)
                    .background(
                        if (isPrivate) PrivateAccentStrongColor.copy(alpha = 0.22f)
                        else palette.accent.copy(alpha = 0.14f)
                    )
                    .border(
                        width = 1.dp,
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
                text = when {
                    isSearchActive -> "No Matching Tabs"
                    isPrivate -> "No Private Tabs"
                    else -> "No Open Tabs"
                },
                color = palette.onSurface,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = when {
                    isSearchActive -> "Try a different search keyword."
                    isPrivate -> "Tap + below to open a private tab. Cookies, cache, and history stay isolated."
                    else -> "Tap + below to open a tab. Hold and drag over another tab or group to organize."
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
private fun PinnedTabsStrip(
    pinnedTabs: List<TabSessionState>,
    selectedTabId: String?,
    mutedIdSet: Set<String>,
    palette: TabsTrayPalette,
    onSelect: (String) -> Unit,
    onUnpin: (TabSessionState) -> Unit,
    onClose: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "PINNED",
            color = palette.onSurfaceSubtle,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.9.sp,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(pinnedTabs, key = { it.id }) { tab ->
                val isSelected = tab.id == selectedTabId
                val host = remember(tab.content.url) { extractHostLabel(tab.content.url) }
                val label = tab.content.title.ifBlank { host.ifBlank { "Tab" } }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) palette.accent.copy(alpha = 0.2f) else palette.cardBg,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .border(
                            width = if (isSelected) 1.5.dp else 0.8.dp,
                            color = if (isSelected) palette.accent else palette.subtleBorder,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { onSelect(tab.id) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        TabFaviconBox(tab = tab, accentColor = palette.accent, sizeDp = 18)
                        Text(
                            text = label,
                            color = palette.onSurface,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        if (tab.id in mutedIdSet) {
                            Icon(
                                painter = painterResource(id = R.drawable.uh_ic_volume_off_24),
                                contentDescription = "Muted",
                                tint = palette.onSurfaceSubtle,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                        Icon(
                            painter = painterResource(id = R.drawable.fx_ic_close_24),
                            contentDescription = "Close pinned tab",
                            tint = palette.onSurfaceSubtle,
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .clickable {
                                    onUnpin(tab)
                                    onClose(tab.id)
                                }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InactiveTabsAccordion(
    inactiveTabs: List<TabSessionState>,
    expanded: Boolean,
    palette: TabsTrayPalette,
    onToggleExpand: () -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onCloseAllInactive: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = palette.cardBg,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .border(0.8.dp, palette.subtleBorder, RoundedCornerShape(18.dp))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.uh_ic_sleep_moon_24),
                    contentDescription = null,
                    tint = palette.onSurfaceSubtle,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Inactive Tabs (${inactiveTabs.size})",
                    color = palette.onSurface,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onCloseAllInactive) {
                    Text(
                        text = "Close all",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    inactiveTabs.forEach { tab ->
                        val host = remember(tab.content.url) { extractHostLabel(tab.content.url) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(palette.previewBg)
                                .clickable { onSelectTab(tab.id) }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TabFaviconBox(tab = tab, accentColor = palette.accent, sizeDp = 22)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tab.content.title.ifBlank { host.ifBlank { "Tab" } },
                                    color = palette.onSurface,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                                if (host.isNotBlank()) {
                                    Text(
                                        text = host,
                                        color = palette.onSurfaceSubtle,
                                        fontSize = 11.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                            Icon(
                                painter = painterResource(id = R.drawable.fx_ic_close_24),
                                contentDescription = "Close inactive tab",
                                tint = palette.onSurfaceSubtle,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .clickable { onCloseTab(tab.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}
