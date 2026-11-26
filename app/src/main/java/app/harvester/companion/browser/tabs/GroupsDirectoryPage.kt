package app.harvester.companion.browser.tabs

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
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

/**
 * Tab Groups Directory following the user's Grid/List view setting (Grid default with 2x2 collages),
 * featuring long-press context menu and smooth hold-and-drag reorder.
 */
@Composable
internal fun GroupsDirectoryPage(
    groups: List<TabGroup>,
    tabsById: Map<String, TabSessionState>,
    selectedTabId: String?,
    isListView: Boolean,
    isPrivateLocked: Boolean,
    searchQuery: String,
    palette: TabsTrayPalette,
    actions: TabsTrayActions,
    onOpenGroupDetail: (TabGroup) -> Unit,
    onEditGroup: (TabGroup) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val configuration = LocalConfiguration.current
    val columns = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 3 else 2

    val visibleGroups = groups.filter { g ->
        if (g.isPrivate && isPrivateLocked) return@filter false
        val members = g.tabIds.mapNotNull { tabsById[it] }
        if (members.isEmpty()) return@filter false
        if (searchQuery.isBlank()) return@filter true
        val q = searchQuery.trim().lowercase()
        g.name.lowercase().contains(q) || members.any {
            it.content.title.lowercase().contains(q) || it.content.url.lowercase().contains(q)
        }
    }

    val normalGroups = visibleGroups.filter { !it.isPrivate }
    val privateGroups = visibleGroups.filter { it.isPrivate }

    val latestNormalGroups by rememberUpdatedState(normalGroups)
    val latestPrivateGroups by rememberUpdatedState(privateGroups)

    val slotBounds = remember { mutableStateMapOf<String, Rect>() }
    var draggingGroupId by remember { mutableStateOf<String?>(null) }
    var dragOriginCenter by remember { mutableStateOf(Offset.Zero) }
    var dragPointerDelta by remember { mutableStateOf(Offset.Zero) }
    var dragMovedDistance by remember { mutableFloatStateOf(0f) }
    var didLiveReorder by remember { mutableStateOf(false) }
    var lastReorderAtMs by remember { mutableLongStateOf(0L) }
    var lastLongPressAtMs by remember { mutableLongStateOf(0L) }
    var contextMenuGroupId by remember { mutableStateOf<String?>(null) }

    if (visibleGroups.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 36.dp),
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
                        .background(palette.accent.copy(alpha = 0.12f))
                        .border(1.2.dp, palette.subtleBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_tab_groups_24),
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Text(
                    text = if (searchQuery.isBlank()) "No Tab Groups Yet" else "No matching groups",
                    color = palette.onSurface,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Tap + below to create a group, or drag and hold one tab over another.",
                    color = palette.onSurfaceSubtle,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    fun performGroupReorder(dragId: String, targetId: String, isPrivate: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastReorderAtMs < 180L) return
        val list = (if (isPrivate) latestPrivateGroups else latestNormalGroups).map { it.id }.toMutableList()
        val fromIdx = list.indexOf(dragId)
        val toIdx = list.indexOf(targetId)
        if (fromIdx in list.indices && toIdx in list.indices && fromIdx != toIdx) {
            val moved = list.removeAt(fromIdx)
            list.add(toIdx, moved)
            actions.onReorderGroupsDirectory(list, isPrivate)
            lastReorderAtMs = now
            didLiveReorder = true
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    fun groupDragModifier(group: TabGroup): Modifier {
        val isDraggingThis = draggingGroupId == group.id
        return Modifier
            .onGloballyPositioned { coords ->
                slotBounds[group.id] = coords.boundsInWindow()
            }
            .zIndex(if (isDraggingThis) 12f else 0f)
            .graphicsLayer {
                if (isDraggingThis) {
                    val liveCenter = dragOriginCenter + dragPointerDelta
                    val currentSlotCenter = slotBounds[group.id]?.center ?: dragOriginCenter
                    translationX = liveCenter.x - currentSlotCenter.x
                    translationY = liveCenter.y - currentSlotCenter.y
                    scaleX = 1.045f
                    scaleY = 1.045f
                    alpha = 0.95f
                }
            }
            .then(
                if (searchQuery.isBlank()) {
                    Modifier.pointerInput(group.id, group.isPrivate) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                draggingGroupId = group.id
                                dragOriginCenter = slotBounds[group.id]?.center ?: Offset.Zero
                                dragPointerDelta = Offset.Zero
                                dragMovedDistance = 0f
                                didLiveReorder = false
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragPointerDelta += dragAmount
                                dragMovedDistance += hypot(dragAmount.x, dragAmount.y)
                                if (dragMovedDistance > 22f) {
                                    val liveCenter = dragOriginCenter + dragPointerDelta
                                    val sameModeIds = (if (group.isPrivate) latestPrivateGroups else latestNormalGroups)
                                        .map { it.id }
                                        .toSet()
                                    for ((candidateId, rect) in slotBounds.entries) {
                                        if (candidateId == group.id || candidateId !in sameModeIds) continue
                                        if (rect.contains(liveCenter)) {
                                            performGroupReorder(group.id, candidateId, group.isPrivate)
                                            break
                                        }
                                    }
                                }
                            },
                            onDragEnd = {
                                lastLongPressAtMs = System.currentTimeMillis()
                                val moved = dragMovedDistance
                                val reordered = didLiveReorder
                                draggingGroupId = null
                                dragPointerDelta = Offset.Zero
                                dragMovedDistance = 0f
                                didLiveReorder = false
                                if (!reordered && moved < 24f) {
                                    contextMenuGroupId = group.id
                                }
                            },
                            onDragCancel = {
                                lastLongPressAtMs = System.currentTimeMillis()
                                draggingGroupId = null
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

    if (isListView) {
        LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            if (normalGroups.isNotEmpty()) {
                item(key = "hdr_normal_groups") {
                    Text(
                        text = "TAB GROUPS (${normalGroups.size})",
                        color = palette.onSurfaceSubtle,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
                items(normalGroups, key = { "dir_l_${it.id}" }) { group ->
                    val members = group.tabIds.mapNotNull { tabsById[it] }
                    TabGroupListCard(
                        group = group,
                        members = members,
                        selectedTabId = selectedTabId,
                        palette = palette,
                        isDropTarget = false,
                        isBeingDragged = draggingGroupId == group.id,
                        showContextMenu = contextMenuGroupId == group.id,
                        onDismissContextMenu = { contextMenuGroupId = null },
                        onOpenGroup = {
                            if (System.currentTimeMillis() - lastLongPressAtMs >= 350L) {
                                onOpenGroupDetail(group)
                            }
                        },
                        onAddNewTabInGroup = { actions.onNewTabInGroup(group.id, false) },
                        onEditGroup = { onEditGroup(group) },
                        onBookmarkGroup = { actions.onBookmarkTabs(group.tabIds) },
                        onShareGroup = { actions.onShareTabs(group.tabIds) },
                        onUngroup = { actions.onUngroup(group.id) },
                        onCloseGroup = { actions.onCloseGroupTabs(group) },
                        modifier = Modifier
                            .animateItem()
                            .then(groupDragModifier(group))
                    )
                }
            }

            if (privateGroups.isNotEmpty()) {
                item(key = "hdr_private_groups") {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "PRIVATE GROUPS (${privateGroups.size})",
                        color = PrivateAccentColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
                items(privateGroups, key = { "dir_lp_${it.id}" }) { group ->
                    val members = group.tabIds.mapNotNull { tabsById[it] }
                    TabGroupListCard(
                        group = group,
                        members = members,
                        selectedTabId = selectedTabId,
                        palette = palette,
                        isDropTarget = false,
                        isBeingDragged = draggingGroupId == group.id,
                        showContextMenu = contextMenuGroupId == group.id,
                        onDismissContextMenu = { contextMenuGroupId = null },
                        onOpenGroup = {
                            if (System.currentTimeMillis() - lastLongPressAtMs >= 350L) {
                                onOpenGroupDetail(group)
                            }
                        },
                        onAddNewTabInGroup = { actions.onNewTabInGroup(group.id, true) },
                        onEditGroup = { onEditGroup(group) },
                        onBookmarkGroup = {},
                        onShareGroup = { actions.onShareTabs(group.tabIds) },
                        onUngroup = { actions.onUngroup(group.id) },
                        onCloseGroup = { actions.onCloseGroupTabs(group) },
                        modifier = Modifier
                            .animateItem()
                            .then(groupDragModifier(group))
                    )
                }
            }
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 104.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            if (normalGroups.isNotEmpty()) {
                item(key = "hdr_normal_groups", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "TAB GROUPS (${normalGroups.size})",
                        color = palette.onSurfaceSubtle,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
                items(normalGroups, key = { "dir_g_${it.id}" }) { group ->
                    val members = group.tabIds.mapNotNull { tabsById[it] }
                    TabGroupGridCard(
                        group = group,
                        members = members,
                        selectedTabId = selectedTabId,
                        palette = palette,
                        isDropTarget = false,
                        isBeingDragged = draggingGroupId == group.id,
                        showContextMenu = contextMenuGroupId == group.id,
                        onDismissContextMenu = { contextMenuGroupId = null },
                        onOpenGroup = {
                            if (System.currentTimeMillis() - lastLongPressAtMs >= 350L) {
                                onOpenGroupDetail(group)
                            }
                        },
                        onAddNewTabInGroup = { actions.onNewTabInGroup(group.id, false) },
                        onEditGroup = { onEditGroup(group) },
                        onBookmarkGroup = { actions.onBookmarkTabs(group.tabIds) },
                        onShareGroup = { actions.onShareTabs(group.tabIds) },
                        onUngroup = { actions.onUngroup(group.id) },
                        onCloseGroup = { actions.onCloseGroupTabs(group) },
                        modifier = Modifier
                            .animateItem()
                            .then(groupDragModifier(group))
                    )
                }
            }

            if (privateGroups.isNotEmpty()) {
                item(key = "hdr_private_groups", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "PRIVATE GROUPS (${privateGroups.size})",
                        color = PrivateAccentColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 2.dp)
                    )
                }
                items(privateGroups, key = { "dir_gp_${it.id}" }) { group ->
                    val members = group.tabIds.mapNotNull { tabsById[it] }
                    TabGroupGridCard(
                        group = group,
                        members = members,
                        selectedTabId = selectedTabId,
                        palette = palette,
                        isDropTarget = false,
                        isBeingDragged = draggingGroupId == group.id,
                        showContextMenu = contextMenuGroupId == group.id,
                        onDismissContextMenu = { contextMenuGroupId = null },
                        onOpenGroup = {
                            if (System.currentTimeMillis() - lastLongPressAtMs >= 350L) {
                                onOpenGroupDetail(group)
                            }
                        },
                        onAddNewTabInGroup = { actions.onNewTabInGroup(group.id, true) },
                        onEditGroup = { onEditGroup(group) },
                        onBookmarkGroup = {},
                        onShareGroup = { actions.onShareTabs(group.tabIds) },
                        onUngroup = { actions.onUngroup(group.id) },
                        onCloseGroup = { actions.onCloseGroupTabs(group) },
                        modifier = Modifier
                            .animateItem()
                            .then(groupDragModifier(group))
                    )
                }
            }
        }
    }
}
