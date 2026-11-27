package app.harvester.companion.browser.tabs

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import app.harvester.companion.R
import kotlinx.coroutines.launch
import mozilla.components.browser.state.state.TabSessionState
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Rock-solid Group Detail Sheet rendered inside a dedicated bottom-anchored Dialog
 * so LazyVerticalGrid vertical scrolling NEVER conflicts with Material3 ModalBottomSheet
 * nested-scroll anchor physics (fixes the 5+ tabs upward-swipe bounce bug completely).
 * Supports unlimited tabs, smooth header swipe-down dismiss, long-press context menu,
 * and viewport-live drag-to-reorder.
 */
@Composable
internal fun GroupDetailSheet(
    group: TabGroup,
    members: List<TabSessionState>,
    selectedTabId: String?,
    pinnedIdSet: Set<String>,
    mutedIdSet: Set<String>,
    palette: TabsTrayPalette,
    actions: TabsTrayActions,
    onEditGroup: () -> Unit,
    onMoveTabToGroup: (TabSessionState) -> Unit,
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
    val maxSheetHeight = (screenHeightDp * 0.80f).coerceAtLeast(360.dp)

    val groupColor = Color(group.colorArgb)
    val sheetPalette = rememberTabsTrayPalette(isDark = palette.isDark, isPrivateSection = group.isPrivate)
    val sheetContainerColor = if (group.isPrivate) Color(0xFF120D22) else MaterialTheme.colorScheme.surfaceContainerHigh
    val chipBg = if (group.isPrivate) Color(0xFF1C1633) else MaterialTheme.colorScheme.surface

    val dismissThresholdPx = with(density) { 120.dp.toPx() }
    val sheetOffsetY = remember { Animatable(0f) }

    var contextMenuTabId by remember { mutableStateOf<String?>(null) }

    // Live grid-coordinate drag-to-reorder for member tabs inside the group
    val gridState = rememberLazyGridState()
    val latestMembers by rememberUpdatedState(members)
    var draggingTabId by remember { mutableStateOf<String?>(null) }
    var dragPointerViewport by remember { mutableStateOf(Offset.Zero) }
    var dragTouchOffsetInCard by remember { mutableStateOf(Offset.Zero) }
    var dragMovedDistance by remember { mutableFloatStateOf(0f) }
    var didLiveReorder by remember { mutableStateOf(false) }
    var lastReorderAtMs by remember { mutableLongStateOf(0L) }

    fun performMemberReorder(dragId: String, targetId: String) {
        val now = System.currentTimeMillis()
        if (now - lastReorderAtMs < 150L) return
        val ids = latestMembers.map { it.id }.toMutableList()
        val fromIdx = ids.indexOf(dragId)
        val toIdx = ids.indexOf(targetId)
        if (fromIdx in ids.indices && toIdx in ids.indices && fromIdx != toIdx) {
            val moved = ids.removeAt(fromIdx)
            ids.add(toIdx, moved)
            actions.onReorderGroupMembers(group.id, ids)
            lastReorderAtMs = now
            didLiveReorder = true
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.48f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = sheetContainerColor,
                shadowElevation = 18.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxSheetHeight)
                    .offset { IntOffset(0, sheetOffsetY.value.roundToInt().coerceAtLeast(0)) }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 14.dp)
                ) {
                    // Dedicated swipe-down handle + header (isolated from LazyVerticalGrid scroll!)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                detectVerticalDragGestures(
                                    onDragEnd = {
                                        if (sheetOffsetY.value > dismissThresholdPx) {
                                            onDismiss()
                                        } else {
                                            scope.launch {
                                                sheetOffsetY.animateTo(
                                                    0f,
                                                    spring(stiffness = Spring.StiffnessMediumLow)
                                                )
                                            }
                                        }
                                    },
                                    onDragCancel = {
                                        scope.launch {
                                            sheetOffsetY.animateTo(
                                                0f,
                                                spring(stiffness = Spring.StiffnessMediumLow)
                                            )
                                        }
                                    },
                                    onVerticalDrag = { _, dragAmount ->
                                        val next = (sheetOffsetY.value + dragAmount).coerceAtLeast(0f)
                                        scope.launch { sheetOffsetY.snapTo(next) }
                                    }
                                )
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp, bottom = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 36.dp, height = 4.dp)
                                    .clip(CircleShape)
                                    .background(sheetPalette.onSurfaceSubtle.copy(alpha = 0.42f))
                            )
                        }

                        // Group Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(groupColor)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = group.name,
                                    color = sheetPalette.onSurface,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${members.size} ${if (members.size == 1) "tab" else "tabs"} • Hold & drag to reorder",
                                    color = sheetPalette.onSurfaceSubtle,
                                    fontSize = 12.sp
                                )
                            }
                            IconButton(
                                onClick = {
                                    onDismiss()
                                    actions.onNewTabInGroup(group.id, group.isPrivate)
                                }
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.uh_ic_plus_24),
                                    contentDescription = "New tab in group",
                                    tint = groupColor,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    painter = painterResource(id = R.drawable.fx_ic_close_24),
                                    contentDescription = "Close group sheet",
                                    tint = sheetPalette.onSurfaceSubtle,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // Quick Action Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GroupActionChip(
                            label = "Edit",
                            iconRes = R.drawable.fx_ic_edit_24,
                            tint = sheetPalette.onSurface,
                            bgColor = chipBg,
                            onClick = onEditGroup,
                            modifier = Modifier.weight(1f)
                        )
                        GroupActionChip(
                            label = "Ungroup",
                            iconRes = R.drawable.uh_ic_tab_groups_24,
                            tint = sheetPalette.onSurface,
                            bgColor = chipBg,
                            onClick = {
                                actions.onUngroup(group.id)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f)
                        )
                        GroupActionChip(
                            label = "Close All",
                            iconRes = R.drawable.fx_ic_delete_24,
                            tint = Color(0xFFF87171),
                            bgColor = chipBg,
                            onClick = {
                                actions.onCloseGroupTabs(group)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Clean 2-column member grid — zero scroll conflict, zero bounce loop
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(2),
                        userScrollEnabled = draggingTabId == null,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                    ) {
                        items(members, key = { it.id }) { tab ->
                            val isDraggingThis = draggingTabId == tab.id

                            Box(
                                modifier = Modifier
                                    .zIndex(if (isDraggingThis) 10f else 0f)
                                    .graphicsLayer {
                                        if (isDraggingThis) {
                                            val curInfo = gridState.layoutInfo.visibleItemsInfo
                                                .firstOrNull { it.key == tab.id }
                                            if (curInfo != null) {
                                                val desiredTopLeft = dragPointerViewport - dragTouchOffsetInCard
                                                translationX = desiredTopLeft.x - curInfo.offset.x.toFloat()
                                                translationY = desiredTopLeft.y - curInfo.offset.y.toFloat()
                                            }
                                            scaleX = 1.04f
                                            scaleY = 1.04f
                                            alpha = 0.94f
                                        }
                                    }
                                    .pointerInput(tab.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { downOffset ->
                                                val info = gridState.layoutInfo.visibleItemsInfo
                                                    .firstOrNull { it.key == tab.id }
                                                dragTouchOffsetInCard = downOffset
                                                dragPointerViewport = if (info != null) {
                                                    Offset(
                                                        info.offset.x.toFloat() + downOffset.x,
                                                        info.offset.y.toFloat() + downOffset.y
                                                    )
                                                } else downOffset
                                                draggingTabId = tab.id
                                                dragMovedDistance = 0f
                                                didLiveReorder = false
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            },
                                            onDragCancel = {
                                                draggingTabId = null
                                                dragMovedDistance = 0f
                                                didLiveReorder = false
                                            },
                                            onDragEnd = {
                                                val moved = dragMovedDistance
                                                val reordered = didLiveReorder
                                                draggingTabId = null
                                                dragMovedDistance = 0f
                                                didLiveReorder = false
                                                if (!reordered && moved < 26f) {
                                                    contextMenuTabId = tab.id
                                                }
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dragPointerViewport += dragAmount
                                                dragMovedDistance += hypot(dragAmount.x, dragAmount.y)

                                                val target = gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                                    val key = item.key as? String ?: return@firstOrNull false
                                                    if (key == tab.id) return@firstOrNull false
                                                    val left = item.offset.x.toFloat()
                                                    val top = item.offset.y.toFloat()
                                                    dragPointerViewport.x in left..(left + item.size.width) &&
                                                        dragPointerViewport.y in top..(top + item.size.height)
                                                }
                                                val targetId = target?.key as? String
                                                if (targetId != null) {
                                                    performMemberReorder(tab.id, targetId)
                                                }
                                            }
                                        )
                                    }
                            ) {
                                SwipeToCloseTabBox(
                                    enabled = draggingTabId == null,
                                    onClose = { actions.onCloseTab(tab.id) }
                                ) {
                                    TabGridCard(
                                        tab = tab,
                                        group = group,
                                        palette = sheetPalette,
                                        isSelected = tab.id == selectedTabId,
                                        isPinned = tab.id in pinnedIdSet,
                                        isMuted = tab.id in mutedIdSet,
                                        isSleeping = TabMetaStore.isTabSleeping(tab, selectedTabId),
                                        isMultiSelect = false,
                                        isChecked = false,
                                        isDropTarget = false,
                                        isBeingDragged = isDraggingThis,
                                        showContextMenu = contextMenuTabId == tab.id,
                                        onDismissContextMenu = { contextMenuTabId = null },
                                        onClick = {
                                            onDismiss()
                                            actions.onSelectTab(tab.id)
                                        },
                                        onClose = { actions.onCloseTab(tab.id) },
                                        onToggleMute = { actions.onToggleMuteTab(tab) },
                                        onTogglePin = { actions.onTogglePinTab(tab) },
                                        onAddToGroup = { onMoveTabToGroup(tab) },
                                        onRemoveFromGroup = { actions.onRemoveTabFromGroup(tab.id) },
                                        onBookmark = { actions.onBookmarkTabs(listOf(tab.id)) },
                                        onDuplicate = { actions.onDuplicateTab(tab) },
                                        onShare = { actions.onShareTabs(listOf(tab.id)) },
                                        showGroupAccent = false
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupActionChip(
    label: String,
    iconRes: Int,
    tint: Color,
    bgColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = bgColor,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = tint,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
