package app.harvester.companion.browser.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R

/**
 * Compact, centered, icon-only frosted section switcher (Normal | Private | Groups).
 * Zero bloat: no top X close button and no redundant text labels.
 */
@Composable
internal fun TabsTrayTopHeader(
    activeSection: TraySection,
    normalCount: Int,
    privateCount: Int,
    groupsCount: Int,
    palette: TabsTrayPalette,
    onSelectSection: (TraySection) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = palette.chromeBg,
            shape = CircleShape,
            shadowElevation = if (palette.isDark) 6.dp else 3.dp,
            modifier = Modifier
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(palette.glassHighlight, palette.subtleBorder)
                    ),
                    shape = CircleShape
                )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompactIconSectionPill(
                    iconRes = R.drawable.uh_ic_tab_modern_24,
                    contentDesc = "Normal tabs",
                    count = normalCount,
                    selected = activeSection == TraySection.NORMAL,
                    accent = MaterialTheme.colorScheme.primary,
                    onAccent = MaterialTheme.colorScheme.onPrimary,
                    inactiveTint = palette.onSurfaceSubtle,
                    onClick = { onSelectSection(TraySection.NORMAL) }
                )
                CompactIconSectionPill(
                    iconRes = R.drawable.uh_ic_private_vibe_24,
                    contentDesc = "Private tabs",
                    count = privateCount,
                    selected = activeSection == TraySection.PRIVATE,
                    accent = PrivateAccentStrongColor,
                    onAccent = Color.White,
                    inactiveTint = palette.onSurfaceSubtle,
                    onClick = { onSelectSection(TraySection.PRIVATE) }
                )
                CompactIconSectionPill(
                    iconRes = R.drawable.uh_ic_tab_groups_24,
                    contentDesc = "Tab groups",
                    count = groupsCount,
                    selected = activeSection == TraySection.GROUPS,
                    accent = MaterialTheme.colorScheme.primary,
                    onAccent = MaterialTheme.colorScheme.onPrimary,
                    inactiveTint = palette.onSurfaceSubtle,
                    onClick = { onSelectSection(TraySection.GROUPS) }
                )
            }
        }
    }
}

@Composable
private fun CompactIconSectionPill(
    iconRes: Int,
    contentDesc: String,
    count: Int,
    selected: Boolean,
    accent: Color,
    onAccent: Color,
    inactiveTint: Color,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        targetValue = if (selected) accent else Color.Transparent,
        label = "compact_pill_bg"
    )
    val fg by animateColorAsState(
        targetValue = if (selected) onAccent else inactiveTint,
        label = "compact_pill_fg"
    )

    Box(
        modifier = Modifier
            .height(36.dp)
            .width(58.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = contentDesc,
            tint = fg,
            modifier = Modifier.size(19.dp)
        )
        if (count > 0) {
            val badgeText = if (count > 99) "99+" else "$count"
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-6).dp, y = 3.dp)
                    .clip(CircleShape)
                    .background(if (selected) Color.Black.copy(alpha = 0.28f) else accent.copy(alpha = 0.18f))
                    .padding(horizontal = 4.dp, vertical = 0.5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badgeText,
                    color = if (selected) onAccent else accent,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = 9.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
internal fun TabsSearchTopBar(
    query: String,
    palette: TabsTrayPalette,
    onQueryChange: (String) -> Unit,
    onCloseSearch: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(42.dp)
                .clip(CircleShape)
                .background(palette.chromeBg)
                .border(1.dp, palette.accent.copy(alpha = 0.50f), CircleShape)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = R.drawable.fx_ic_search_24),
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                cursorBrush = SolidColor(palette.accent),
                textStyle = TextStyle(
                    color = palette.onSurface,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                ),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (query.isEmpty()) {
                            Text(
                                text = "Search open tabs...",
                                color = palette.onSurfaceSubtle,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                maxLines = 1
                            )
                        }
                        inner()
                    }
                }
            )
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.fx_ic_close_24),
                        contentDescription = "Clear search",
                        tint = palette.onSurfaceSubtle,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Surface(
            shape = CircleShape,
            color = palette.chromeBg,
            modifier = Modifier
                .height(42.dp)
                .border(1.dp, palette.subtleBorder, CircleShape)
                .clickable(onClick = onCloseSearch)
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Cancel",
                    color = palette.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
internal fun MultiSelectTopBar(
    selectedCount: Int,
    totalSelectableCount: Int,
    palette: TabsTrayPalette,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDone: () -> Unit
) {
    val allSelected = totalSelectableCount > 0 && selectedCount >= totalSelectableCount

    Surface(
        color = palette.chromeBg,
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (selectedCount == 0) "Select tabs" else "$selectedCount selected",
                    color = palette.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Tap tabs to include or exclude",
                    color = palette.onSurfaceSubtle,
                    fontSize = 11.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Surface(
                shape = CircleShape,
                color = palette.cardBg,
                modifier = Modifier
                    .height(36.dp)
                    .border(1.dp, palette.subtleBorder, CircleShape)
                    .clickable(onClick = if (allSelected) onClearSelection else onSelectAll)
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (allSelected) "Clear" else "Select all",
                        color = palette.onSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Surface(
                shape = CircleShape,
                color = palette.accentStrong,
                modifier = Modifier
                    .height(36.dp)
                    .clickable(onClick = onDone)
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Done",
                        color = palette.onAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

@Composable
internal fun MultiSelectBottomDock(
    selectedCount: Int,
    isPrivate: Boolean,
    palette: TabsTrayPalette,
    onGroupSelected: () -> Unit,
    onBookmarkSelected: () -> Unit,
    onShareSelected: () -> Unit,
    onCloseSelected: () -> Unit
) {
    val enabled = selectedCount > 0
    Surface(
        color = palette.chromeBg,
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(color = palette.subtleBorder)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MultiSelectActionButton(
                    iconRes = R.drawable.uh_ic_tab_groups_24,
                    label = "Group",
                    tint = palette.accent,
                    enabled = enabled,
                    onClick = onGroupSelected,
                    modifier = Modifier.weight(1f)
                )
                if (!isPrivate) {
                    MultiSelectActionButton(
                        iconRes = R.drawable.uh_ic_bookmark_24,
                        label = "Bookmark",
                        tint = palette.onSurface,
                        enabled = enabled,
                        onClick = onBookmarkSelected,
                        modifier = Modifier.weight(1f)
                    )
                }
                MultiSelectActionButton(
                    iconRes = R.drawable.uh_ic_share_24,
                    label = "Share",
                    tint = palette.onSurface,
                    enabled = enabled,
                    onClick = onShareSelected,
                    modifier = Modifier.weight(1f)
                )
                MultiSelectActionButton(
                    iconRes = R.drawable.fx_ic_delete_24,
                    label = "Close",
                    tint = MaterialTheme.colorScheme.error,
                    enabled = enabled,
                    onClick = onCloseSelected,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MultiSelectActionButton(
    iconRes: Int,
    label: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val effectiveTint = if (enabled) tint else tint.copy(alpha = 0.35f)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = if (enabled) 0.06f else 0.02f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = label,
            tint = effectiveTint,
            modifier = Modifier.size(19.dp)
        )
        Text(
            text = label,
            color = effectiveTint,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Floating frosted pill dock: Search (left) | + icon-only (center) | 3-dot Menu (right).
 * Grid/List lives cleanly inside the 3-dot menu with zero dock clutter.
 */
@Composable
internal fun TabsTrayBottomDock(
    activeSection: TraySection,
    isListView: Boolean,
    sortMode: TabSortMode,
    groupStripEnabled: Boolean,
    recentlyClosedCount: Int,
    duplicateCount: Int,
    hasModeTabs: Boolean,
    isPrivateLocked: Boolean,
    showOverflowMenu: Boolean,
    palette: TabsTrayPalette,
    onToggleOverflowMenu: (Boolean) -> Unit,
    onOpenSearch: () -> Unit,
    onToggleViewMode: () -> Unit,
    onPrimaryCta: () -> Unit,
    onStartMultiSelect: () -> Unit,
    onOpenNewGroupDialog: () -> Unit,
    onAutoGroupByDomain: () -> Unit,
    onOpenSortSheet: () -> Unit,
    onToggleGroupStrip: (Boolean) -> Unit,
    onOpenRecentlyClosed: () -> Unit,
    onBookmarkAllModeTabs: () -> Unit,
    onShareAllModeTabs: () -> Unit,
    onCloseDuplicates: () -> Unit,
    onOpenSettings: () -> Unit,
    onCloseAllInSection: () -> Unit
) {
    val menuBg = if (palette.isPrivateTheme) Color(0xFF1A142D) else MaterialTheme.colorScheme.background
    val menuText = palette.onSurface
    val menuSubtle = palette.onSurfaceSubtle

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = palette.chromeBg,
            shape = CircleShape,
            shadowElevation = if (palette.isDark) 14.dp else 8.dp,
            modifier = Modifier
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(palette.glassHighlight, palette.subtleBorder)
                    ),
                    shape = CircleShape
                )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // Left: Search
                IconButton(
                    onClick = onOpenSearch,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.fx_ic_search_24),
                        contentDescription = "Search tabs",
                        tint = palette.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Center: Clean icon-only "+" button
                Surface(
                    shape = CircleShape,
                    color = palette.accentStrong,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onPrimaryCta)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(id = R.drawable.uh_ic_plus_24),
                            contentDescription = when (activeSection) {
                                TraySection.NORMAL -> "New tab"
                                TraySection.PRIVATE -> "New private tab"
                                TraySection.GROUPS -> "New group"
                            },
                            tint = palette.onAccent,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Right: 3-dot Menu
                Box {
                    IconButton(
                        onClick = { onToggleOverflowMenu(true) },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.fx_ic_more_24),
                            contentDescription = "Tab options",
                            tint = palette.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { onToggleOverflowMenu(false) },
                        containerColor = menuBg,
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        val isPrivate = activeSection == TraySection.PRIVATE
                        val canOperateOnTabs = hasModeTabs && !(isPrivate && isPrivateLocked)

                        if (activeSection != TraySection.GROUPS && canOperateOnTabs) {
                            DropdownMenuItem(
                                text = { Text("Select tabs", color = menuText, fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(id = R.drawable.fx_ic_check_24),
                                        contentDescription = null,
                                        tint = menuText,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    onToggleOverflowMenu(false)
                                    onStartMultiSelect()
                                }
                            )
                        }

                        if (!(isPrivate && isPrivateLocked)) {
                            DropdownMenuItem(
                                text = { Text("New tab group", color = menuText, fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(id = R.drawable.uh_ic_tab_groups_24),
                                        contentDescription = null,
                                        tint = menuText,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    onToggleOverflowMenu(false)
                                    onOpenNewGroupDialog()
                                }
                            )
                        }

                        if (activeSection != TraySection.GROUPS && canOperateOnTabs) {
                            DropdownMenuItem(
                                text = { Text("Auto-group by domain", color = menuText, fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(id = R.drawable.uh_ic_tab_groups_24),
                                        contentDescription = null,
                                        tint = palette.accent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    onToggleOverflowMenu(false)
                                    onAutoGroupByDomain()
                                }
                            )
                        }

                        if (activeSection != TraySection.GROUPS) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("Sort tabs", color = menuText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        Text(
                                            text = sortMode.label,
                                            fontSize = 11.sp,
                                            color = menuSubtle
                                        )
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(id = R.drawable.uh_ic_sort_24),
                                        contentDescription = null,
                                        tint = menuText,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    onToggleOverflowMenu(false)
                                    onOpenSortSheet()
                                }
                            )
                        }

                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (isListView) "Grid view" else "List view",
                                    color = menuText,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(
                                        id = if (isListView) R.drawable.uh_ic_tab_groups_24 else R.drawable.uh_ic_list_view_24
                                    ),
                                    contentDescription = null,
                                    tint = menuText,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                onToggleOverflowMenu(false)
                                onToggleViewMode()
                            }
                        )

                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f, fill = false)) {
                                        Text("Group tab strip", color = menuText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        Text(
                                            text = if (groupStripEnabled) "Shown near address bar" else "Hidden",
                                            fontSize = 11.sp,
                                            color = menuSubtle
                                        )
                                    }
                                    Switch(
                                        checked = groupStripEnabled,
                                        onCheckedChange = { onToggleGroupStrip(it) },
                                        modifier = Modifier.scale(0.8f)
                                    )
                                }
                            },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.uh_ic_tab_modern_24),
                                    contentDescription = null,
                                    tint = menuText,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = { onToggleGroupStrip(!groupStripEnabled) }
                        )

                        HorizontalDivider(color = palette.subtleBorder, modifier = Modifier.padding(vertical = 4.dp))

                        if (!isPrivate) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (recentlyClosedCount > 0) "Recently closed ($recentlyClosedCount)" else "Recently closed",
                                        color = menuText,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_history),
                                        contentDescription = null,
                                        tint = menuText,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    onToggleOverflowMenu(false)
                                    onOpenRecentlyClosed()
                                }
                            )
                        }

                        if (activeSection == TraySection.NORMAL && hasModeTabs) {
                            DropdownMenuItem(
                                text = { Text("Bookmark all tabs", color = menuText, fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(id = R.drawable.uh_ic_bookmark_24),
                                        contentDescription = null,
                                        tint = menuText,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    onToggleOverflowMenu(false)
                                    onBookmarkAllModeTabs()
                                }
                            )
                        }

                        if (activeSection != TraySection.GROUPS && canOperateOnTabs) {
                            DropdownMenuItem(
                                text = { Text("Share all tabs", color = menuText, fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(id = R.drawable.uh_ic_share_24),
                                        contentDescription = null,
                                        tint = menuText,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    onToggleOverflowMenu(false)
                                    onShareAllModeTabs()
                                }
                            )
                        }

                        if (activeSection != TraySection.GROUPS && duplicateCount > 0 && canOperateOnTabs) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Close $duplicateCount duplicate ${if (duplicateCount == 1) "tab" else "tabs"}",
                                        color = menuText,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(id = R.drawable.uh_ic_copy_24),
                                        contentDescription = null,
                                        tint = menuText,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    onToggleOverflowMenu(false)
                                    onCloseDuplicates()
                                }
                            )
                        }

                        DropdownMenuItem(
                            text = { Text("Tab settings", color = menuText, fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.fx_ic_settings_24),
                                    contentDescription = null,
                                    tint = menuText,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                onToggleOverflowMenu(false)
                                onOpenSettings()
                            }
                        )

                        if (activeSection != TraySection.GROUPS && hasModeTabs) {
                            HorizontalDivider(color = palette.subtleBorder, modifier = Modifier.padding(vertical = 4.dp))
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (isPrivate) "Close all private tabs" else "Close all tabs",
                                        color = Color(0xFFF87171),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(id = R.drawable.fx_ic_delete_24),
                                        contentDescription = null,
                                        tint = Color(0xFFF87171),
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    onToggleOverflowMenu(false)
                                    onCloseAllInSection()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TabsUndoSnackbar(
    batch: UndoTabBatch?,
    palette: TabsTrayPalette,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = batch != null,
        enter = slideInVertically { it / 2 } + fadeIn(),
        exit = slideOutVertically { it / 2 } + fadeOut(),
        modifier = modifier
    ) {
        if (batch != null) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (palette.isDark) Color(0xFF211B33) else Color(0xFF1F232B),
                shadowElevation = 10.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .border(1.dp, palette.subtleBorder, RoundedCornerShape(18.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = batch.label,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onUndo) {
                        Text(
                            text = "UNDO",
                            color = if (batch.wasPrivate) PrivateAccentColor else Color(0xFF7DD3FC),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(
                            painter = painterResource(id = R.drawable.fx_ic_close_24),
                            contentDescription = "Dismiss",
                            tint = Color.White.copy(alpha = 0.65f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
