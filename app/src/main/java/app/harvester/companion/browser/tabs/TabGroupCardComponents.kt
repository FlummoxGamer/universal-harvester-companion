package app.harvester.companion.browser.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import mozilla.components.browser.state.state.TabSessionState

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PinnedTabsStrip(
    pinnedTabs: List<TabSessionState>,
    selectedTabId: String?,
    mutedIds: Set<String>,
    palette: TabsTrayPalette,
    onSelectTab: (String) -> Unit,
    onUnpinTab: (TabSessionState) -> Unit,
    onToggleMuteTab: (TabSessionState) -> Unit,
    onBookmarkTab: (TabSessionState) -> Unit,
    onDuplicateTab: (TabSessionState) -> Unit,
    onShareTab: (TabSessionState) -> Unit,
    onCloseTab: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (pinnedTabs.isEmpty()) return
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.uh_ic_pin_24),
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = "PINNED (${pinnedTabs.size})",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp
            )
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(pinnedTabs, key = { "pin_${it.id}" }) { tab ->
                val isSelected = tab.id == selectedTabId
                val host = remember(tab.content.url) { extractHostLabel(tab.content.url) }
                val label = tab.content.title.ifBlank { host.ifBlank { "Pinned" } }
                val isMuted = TabMetaStore.isTabMuted(tab) || (tab.id in mutedIds)
                val hasMedia = TabMetaStore.hasMediaSession(tab) || isMuted
                var showMenu by remember(tab.id) { mutableStateOf(false) }

                Box {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) palette.accent.copy(alpha = 0.16f) else palette.cardBg,
                        modifier = Modifier
                            .widthIn(min = 92.dp, max = 160.dp)
                            .height(40.dp)
                            .border(
                                width = if (isSelected) 1.8.dp else 1.dp,
                                color = if (isSelected) palette.accent else palette.subtleBorder,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clip(RoundedCornerShape(14.dp))
                            .combinedClickable(
                                onClick = { onSelectTab(tab.id) },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    showMenu = true
                                }
                            )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            TabFaviconBox(tab = tab, accentColor = palette.accent, sizeDp = 18)
                            Text(
                                text = label,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (hasMedia) {
                                Icon(
                                    painter = painterResource(
                                        id = if (isMuted) R.drawable.uh_ic_volume_off_24 else R.drawable.uh_ic_volume_on_24
                                    ),
                                    contentDescription = if (isMuted) "Muted" else "Playing audio",
                                    tint = if (isMuted) MaterialTheme.colorScheme.onSurfaceVariant else palette.accent,
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable { onToggleMuteTab(tab) }
                                )
                            }
                        }
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Unpin tab", fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                            leadingIcon = {
                                Icon(painterResource(id = R.drawable.uh_ic_pin_24), null, modifier = Modifier.size(18.dp))
                            },
                            onClick = {
                                showMenu = false
                                onUnpinTab(tab)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (isMuted) "Unmute tab" else "Mute tab", fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                            leadingIcon = {
                                Icon(
                                    painterResource(id = if (isMuted) R.drawable.uh_ic_volume_on_24 else R.drawable.uh_ic_volume_off_24),
                                    null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                showMenu = false
                                onToggleMuteTab(tab)
                            }
                        )
                        if (tab.content.url.isNotBlank() && tab.content.url != "about:blank") {
                            DropdownMenuItem(
                                text = { Text("Bookmark tab", fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                                leadingIcon = {
                                    Icon(painterResource(id = R.drawable.uh_ic_bookmark_24), null, modifier = Modifier.size(18.dp))
                                },
                                onClick = {
                                    showMenu = false
                                    onBookmarkTab(tab)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Share tab", fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                                leadingIcon = {
                                    Icon(painterResource(id = R.drawable.uh_ic_share_24), null, modifier = Modifier.size(18.dp))
                                },
                                onClick = {
                                    showMenu = false
                                    onShareTab(tab)
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Duplicate tab", fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                            leadingIcon = {
                                Icon(painterResource(id = R.drawable.uh_ic_copy_24), null, modifier = Modifier.size(18.dp))
                            },
                            onClick = {
                                showMenu = false
                                onDuplicateTab(tab)
                            }
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 3.dp))
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Close tab",
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    painterResource(id = R.drawable.fx_ic_delete_24),
                                    null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                showMenu = false
                                onCloseTab(tab.id)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun TabGroupGridCard(
    group: TabGroup,
    members: List<TabSessionState>,
    selectedTabId: String?,
    palette: TabsTrayPalette,
    isDropTarget: Boolean,
    onOpenGroup: () -> Unit,
    onAddNewTabInGroup: () -> Unit,
    onEditGroup: () -> Unit,
    onBookmarkGroup: () -> Unit,
    onShareGroup: () -> Unit,
    onUngroup: () -> Unit,
    onCloseGroup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val groupColor = Color(group.colorArgb)
    val containsSelected = members.any { it.id == selectedTabId }
    var showOverflow by remember(group.id) { mutableStateOf(false) }

    val borderColor = when {
        isDropTarget -> groupColor
        containsSelected -> groupColor
        else -> groupColor.copy(alpha = 0.48f)
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = palette.cardBg,
        shadowElevation = if (isDropTarget || containsSelected) 4.dp else 1.dp,
        modifier = modifier
            .fillMaxWidth()
            .border(if (isDropTarget || containsSelected) 2.2.dp else 1.2.dp, borderColor, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onOpenGroup)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Color accent header bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(groupColor)
            )

            // Header row: Color dot + Group name + Count badge + Overflow
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(groupColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = group.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(groupColor.copy(alpha = 0.18f))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "${members.size}",
                        color = groupColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Box {
                    IconButton(
                        onClick = { showOverflow = true },
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.fx_ic_more_24),
                            contentDescription = "Group options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    GroupCardOverflowMenu(
                        expanded = showOverflow,
                        isPrivate = group.isPrivate,
                        onDismiss = { showOverflow = false },
                        onOpenGroup = onOpenGroup,
                        onAddNewTabInGroup = onAddNewTabInGroup,
                        onEditGroup = onEditGroup,
                        onBookmarkGroup = onBookmarkGroup,
                        onShareGroup = onShareGroup,
                        onUngroup = onUngroup,
                        onCloseGroup = onCloseGroup
                    )
                }
            }

            // 2x2 Mini-Preview Collage
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, end = 6.dp, bottom = 6.dp)
                    .aspectRatio(0.92f)
                    .clip(RoundedCornerShape(13.dp))
                    .background(groupColor.copy(alpha = if (palette.isDark) 0.12f else 0.08f))
                    .padding(5.dp)
            ) {
                val previewSlots = members.take(4)
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        GroupMiniTile(
                            tab = previewSlots.getOrNull(0),
                            groupColor = groupColor,
                            tileBg = palette.previewBg,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                        GroupMiniTile(
                            tab = previewSlots.getOrNull(1),
                            groupColor = groupColor,
                            tileBg = palette.previewBg,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        GroupMiniTile(
                            tab = previewSlots.getOrNull(2),
                            groupColor = groupColor,
                            tileBg = palette.previewBg,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                        if (members.size > 4) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(groupColor.copy(alpha = 0.22f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "+${members.size - 3}",
                                    color = groupColor,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        } else {
                            GroupMiniTile(
                                tab = previewSlots.getOrNull(3),
                                groupColor = groupColor,
                                tileBg = palette.previewBg,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }
                    }
                }

                if (isDropTarget) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(10.dp))
                            .background(groupColor.copy(alpha = 0.28f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = groupColor,
                            shadowElevation = 4.dp
                        ) {
                            Text(
                                text = "Add to group",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupMiniTile(
    tab: TabSessionState?,
    groupColor: Color,
    tileBg: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(tileBg),
        contentAlignment = Alignment.Center
    ) {
        if (tab != null) {
            val bmp = remember(tab.id, tab.content.url) { TabPreviewCache.get(tab.id) }
            if (bmp != null && !bmp.isRecycled) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                TabFaviconBox(tab = tab, accentColor = groupColor, sizeDp = 22)
            }
        }
    }
}

@Composable
internal fun TabGroupListCard(
    group: TabGroup,
    members: List<TabSessionState>,
    selectedTabId: String?,
    palette: TabsTrayPalette,
    isDropTarget: Boolean,
    onOpenGroup: () -> Unit,
    onAddNewTabInGroup: () -> Unit,
    onEditGroup: () -> Unit,
    onBookmarkGroup: () -> Unit,
    onShareGroup: () -> Unit,
    onUngroup: () -> Unit,
    onCloseGroup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val groupColor = Color(group.colorArgb)
    val containsSelected = members.any { it.id == selectedTabId }
    var showOverflow by remember(group.id) { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = palette.cardBg,
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = if (isDropTarget || containsSelected) 1.8.dp else 1.dp,
                color = if (isDropTarget || containsSelected) groupColor else groupColor.copy(alpha = 0.45f),
                shape = RoundedCornerShape(16.dp)
            )
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onOpenGroup)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(groupColor.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_grid_view),
                    contentDescription = null,
                    tint = groupColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = group.name,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (group.isPrivate) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(PrivateAccentColor.copy(alpha = 0.18f))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "Private",
                                color = PrivateAccentColor,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                val previewTitles = members.take(3).joinToString(" · ") { t ->
                    t.content.title.ifBlank { extractHostLabel(t.content.url).ifBlank { "Tab" } }
                }
                Text(
                    text = "${members.size} ${if (members.size == 1) "tab" else "tabs"}" +
                        if (previewTitles.isNotBlank()) " — $previewTitles" else "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                IconButton(onClick = { showOverflow = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        painter = painterResource(id = R.drawable.fx_ic_more_24),
                        contentDescription = "Group options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                GroupCardOverflowMenu(
                    expanded = showOverflow,
                    isPrivate = group.isPrivate,
                    onDismiss = { showOverflow = false },
                    onOpenGroup = onOpenGroup,
                    onAddNewTabInGroup = onAddNewTabInGroup,
                    onEditGroup = onEditGroup,
                    onBookmarkGroup = onBookmarkGroup,
                    onShareGroup = onShareGroup,
                    onUngroup = onUngroup,
                    onCloseGroup = onCloseGroup
                )
            }
        }
    }
}

@Composable
internal fun GroupCardOverflowMenu(
    expanded: Boolean,
    isPrivate: Boolean,
    onDismiss: () -> Unit,
    onOpenGroup: () -> Unit,
    onAddNewTabInGroup: () -> Unit,
    onEditGroup: () -> Unit,
    onBookmarkGroup: () -> Unit,
    onShareGroup: () -> Unit,
    onUngroup: () -> Unit,
    onCloseGroup: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp)
    ) {
        DropdownMenuItem(
            text = { Text("Open group", fontSize = 14.sp, fontWeight = FontWeight.Medium) },
            leadingIcon = { Icon(painterResource(id = R.drawable.ic_grid_view), null, modifier = Modifier.size(18.dp)) },
            onClick = {
                onDismiss()
                onOpenGroup()
            }
        )
        DropdownMenuItem(
            text = { Text("Add new tab", fontSize = 14.sp, fontWeight = FontWeight.Medium) },
            leadingIcon = { Icon(painterResource(id = R.drawable.uh_ic_plus_24), null, modifier = Modifier.size(18.dp)) },
            onClick = {
                onDismiss()
                onAddNewTabInGroup()
            }
        )
        DropdownMenuItem(
            text = { Text("Edit name & color", fontSize = 14.sp, fontWeight = FontWeight.Medium) },
            leadingIcon = { Icon(painterResource(id = R.drawable.fx_ic_edit_24), null, modifier = Modifier.size(18.dp)) },
            onClick = {
                onDismiss()
                onEditGroup()
            }
        )
        if (!isPrivate) {
            DropdownMenuItem(
                text = { Text("Bookmark group", fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                leadingIcon = { Icon(painterResource(id = R.drawable.uh_ic_bookmark_24), null, modifier = Modifier.size(18.dp)) },
                onClick = {
                    onDismiss()
                    onBookmarkGroup()
                }
            )
        }
        DropdownMenuItem(
            text = { Text("Share group", fontSize = 14.sp, fontWeight = FontWeight.Medium) },
            leadingIcon = { Icon(painterResource(id = R.drawable.uh_ic_share_24), null, modifier = Modifier.size(18.dp)) },
            onClick = {
                onDismiss()
                onShareGroup()
            }
        )
        DropdownMenuItem(
            text = { Text("Ungroup tabs", fontSize = 14.sp, fontWeight = FontWeight.Medium) },
            leadingIcon = { Icon(painterResource(id = R.drawable.ic_arrow_out), null, modifier = Modifier.size(18.dp)) },
            onClick = {
                onDismiss()
                onUngroup()
            }
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 3.dp))
        DropdownMenuItem(
            text = {
                Text(
                    "Close group",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            leadingIcon = {
                Icon(
                    painterResource(id = R.drawable.fx_ic_delete_24),
                    null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = {
                onDismiss()
                onCloseGroup()
            }
        )
    }
}

@Composable
internal fun InactiveTabsSection(
    inactiveTabs: List<TabSessionState>,
    expanded: Boolean,
    palette: TabsTrayPalette,
    onToggleExpanded: () -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onCloseAllInactive: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (inactiveTabs.isEmpty()) return

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = palette.cardBg,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, palette.subtleBorder, RoundedCornerShape(18.dp))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_history),
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Inactive tabs (${inactiveTabs.size})",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Not viewed recently",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
                TextButton(onClick = onCloseAllInactive) {
                    Text(
                        text = "Close all",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Icon(
                    painter = painterResource(
                        id = if (expanded) R.drawable.ic_triangle_down else R.drawable.fx_ic_chevron_right_24
                    ),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HorizontalDivider(color = palette.subtleBorder)
                    inactiveTabs.forEach { tab ->
                        val host = remember(tab.content.url) { extractHostLabel(tab.content.url) }
                        val title = tab.content.title.ifBlank { host.ifBlank { "Tab" } }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelectTab(tab.id) }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TabFaviconBox(tab = tab, accentColor = palette.accent, sizeDp = 22)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = title,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (host.isNotBlank()) {
                                    Text(
                                        text = host,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            IconButton(
                                onClick = { onCloseTab(tab.id) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.fx_ic_close_24),
                                    contentDescription = "Close inactive tab",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
