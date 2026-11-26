package app.harvester.companion.browser.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.harvester.companion.R
import mozilla.components.browser.state.state.TabSessionState
import kotlin.math.hypot

@OptIn(ExperimentalMaterial3Api::class)
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptic = LocalHapticFeedback.current
    val groupColor = Color(group.colorArgb)
    val sheetPalette = rememberTabsTrayPalette(isDark = palette.isDark, isPrivateSection = group.isPrivate)
    val sheetContainerColor = if (group.isPrivate) Color(0xFF120D22) else MaterialTheme.colorScheme.surfaceContainerHigh
    val chipBg = if (group.isPrivate) Color(0xFF1C1633) else MaterialTheme.colorScheme.surface

    var contextMenuTabId by remember { mutableStateOf<String?>(null) }

    // Drag-to-reorder + long-press context menu for member tabs inside the group
    val latestMembers by rememberUpdatedState(members)
    val slotBounds = remember { mutableStateMapOf<String, Rect>() }
    var draggingTabId by remember { mutableStateOf<String?>(null) }
    var dragOriginCenter by remember { mutableStateOf(Offset.Zero) }
    var dragPointerDelta by remember { mutableStateOf(Offset.Zero) }
    var dragMovedDistance by remember { mutableFloatStateOf(0f) }
    var didLiveReorder by remember { mutableStateOf(false) }
    var lastReorderAtMs by remember { mutableLongStateOf(0L) }
    var lastLongPressAtMs by remember { mutableLongStateOf(0L) }

    fun performMemberReorder(dragId: String, targetId: String) {
        val now = System.currentTimeMillis()
        if (now - lastReorderAtMs < 180L) return
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = sheetContainerColor,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
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
                        text = "${members.size} ${if (members.size == 1) "tab" else "tabs"}" +
                            if (group.isPrivate) " · Private group" else "",
                        color = sheetPalette.onSurfaceSubtle,
                        fontSize = 12.sp
                    )
                }

                IconButton(onClick = onEditGroup) {
                    Icon(
                        painter = painterResource(id = R.drawable.fx_ic_edit_24),
                        contentDescription = "Edit group",
                        tint = sheetPalette.onSurface,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = groupColor,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable {
                            onDismiss()
                            actions.onNewTabInGroup(group.id, group.isPrivate)
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.uh_ic_plus_24),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "Add",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Quick Group Action Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!group.isPrivate) {
                    GroupActionChip(
                        iconRes = R.drawable.uh_ic_bookmark_24,
                        label = "Bookmark",
                        tint = sheetPalette.onSurface,
                        containerColor = chipBg,
                        onClick = { actions.onBookmarkTabs(group.tabIds) },
                        modifier = Modifier.weight(1f)
                    )
                }
                GroupActionChip(
                    iconRes = R.drawable.uh_ic_share_24,
                    label = "Share",
                    tint = sheetPalette.onSurface,
                    containerColor = chipBg,
                    onClick = { actions.onShareTabs(group.tabIds) },
                    modifier = Modifier.weight(1f)
                )
                GroupActionChip(
                    iconRes = R.drawable.ic_arrow_out,
                    label = "Ungroup",
                    tint = sheetPalette.onSurface,
                    containerColor = chipBg,
                    onClick = {
                        actions.onUngroup(group.id)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                )
                GroupActionChip(
                    iconRes = R.drawable.fx_ic_delete_24,
                    label = "Close",
                    tint = if (group.isPrivate) Color(0xFFF87171) else MaterialTheme.colorScheme.error,
                    containerColor = chipBg,
                    onClick = {
                        actions.onCloseGroupTabs(group)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider(
                color = sheetPalette.subtleBorder,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            // Clean member tab grid: ZERO colored borders on inactive cards — only the active tab gets a highlight ring
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 480.dp)
            ) {
                items(members, key = { "gmem_${it.id}" }) { tab ->
                    val isMuted = TabMetaStore.isTabMuted(tab) || (tab.id in mutedIdSet)
                    val isDraggingThis = draggingTabId == tab.id

                    val memberDragModifier = Modifier
                        .animateItem()
                        .onGloballyPositioned { coords ->
                            slotBounds[tab.id] = coords.boundsInWindow()
                        }
                        .zIndex(if (isDraggingThis) 12f else 0f)
                        .graphicsLayer {
                            if (isDraggingThis) {
                                val liveCenter = dragOriginCenter + dragPointerDelta
                                val currentSlotCenter = slotBounds[tab.id]?.center ?: dragOriginCenter
                                translationX = liveCenter.x - currentSlotCenter.x
                                translationY = liveCenter.y - currentSlotCenter.y
                                scaleX = 1.045f
                                scaleY = 1.045f
                                alpha = 0.95f
                            }
                        }
                        .pointerInput(tab.id, group.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    draggingTabId = tab.id
                                    dragOriginCenter = slotBounds[tab.id]?.center ?: Offset.Zero
                                    dragPointerDelta = Offset.Zero
                                    dragMovedDistance = 0f
                                    didLiveReorder = false
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragPointerDelta += dragAmount
                                    dragMovedDistance += hypot(dragAmount.x, dragAmount.y)
                                    if (dragMovedDistance > 20f) {
                                        val liveCenter = dragOriginCenter + dragPointerDelta
                                        for ((candidateId, rect) in slotBounds.entries) {
                                            if (candidateId == tab.id) continue
                                            if (rect.contains(liveCenter)) {
                                                performMemberReorder(tab.id, candidateId)
                                                break
                                            }
                                        }
                                    }
                                },
                                onDragEnd = {
                                    lastLongPressAtMs = System.currentTimeMillis()
                                    val moved = dragMovedDistance
                                    val reordered = didLiveReorder
                                    draggingTabId = null
                                    dragPointerDelta = Offset.Zero
                                    dragMovedDistance = 0f
                                    didLiveReorder = false
                                    if (!reordered && moved < 24f) {
                                        contextMenuTabId = tab.id
                                    }
                                },
                                onDragCancel = {
                                    lastLongPressAtMs = System.currentTimeMillis()
                                    draggingTabId = null
                                    dragPointerDelta = Offset.Zero
                                    dragMovedDistance = 0f
                                    didLiveReorder = false
                                }
                            )
                        }

                    TabGridCard(
                        tab = tab,
                        group = group,
                        palette = sheetPalette,
                        isSelected = tab.id == selectedTabId,
                        isPinned = tab.id in pinnedIdSet,
                        isMuted = isMuted,
                        isSleeping = TabMetaStore.isTabSleeping(tab, selectedTabId),
                        isMultiSelect = false,
                        isChecked = false,
                        isDropTarget = false,
                        isBeingDragged = isDraggingThis,
                        showContextMenu = contextMenuTabId == tab.id,
                        onDismissContextMenu = { contextMenuTabId = null },
                        onClick = {
                            if (System.currentTimeMillis() - lastLongPressAtMs >= 350L) {
                                onDismiss()
                                actions.onSelectTab(tab.id)
                            }
                        },
                        onClose = { actions.onCloseTab(tab.id) },
                        onToggleMute = { actions.onToggleMuteTab(tab) },
                        onTogglePin = { actions.onTogglePinTab(tab) },
                        onAddToGroup = { onMoveTabToGroup(tab) },
                        onRemoveFromGroup = { actions.onRemoveTabFromGroup(tab.id) },
                        onBookmark = { actions.onBookmarkTabs(listOf(tab.id)) },
                        onDuplicate = { actions.onDuplicateTab(tab) },
                        onShare = { actions.onShareTabs(listOf(tab.id)) },
                        showGroupAccent = false,
                        modifier = memberDragModifier
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupActionChip(
    iconRes: Int,
    label: String,
    tint: Color,
    containerColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                color = tint,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
