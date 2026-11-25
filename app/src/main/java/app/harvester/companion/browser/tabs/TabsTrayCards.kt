package app.harvester.companion.browser.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import mozilla.components.browser.state.state.TabSessionState
import java.net.URI

internal fun extractHostLabel(rawUrl: String): String {
    if (rawUrl.isBlank() || rawUrl == "about:blank") return ""
    return try {
        URI(rawUrl).host?.removePrefix("www.") ?: rawUrl
    } catch (_: Exception) {
        rawUrl
    }
}

internal fun normalizeUrlForDedup(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank() || trimmed == "about:blank") return ""
    return try {
        val u = URI(trimmed)
        val scheme = (u.scheme ?: "https").lowercase()
        val host = (u.host ?: "").lowercase().removePrefix("www.")
        val path = (u.path ?: "").trimEnd('/')
        val query = if (u.query.isNullOrBlank()) "" else "?${u.query}"
        "$scheme://$host$path$query"
    } catch (_: Exception) {
        trimmed.substringBefore("#").trimEnd('/').lowercase()
    }
}

@Composable
internal fun TabGridCard(
    tab: TabSessionState,
    group: TabGroup?,
    accentColor: Color,
    cardBg: Color,
    previewBg: Color,
    isSelected: Boolean,
    isMultiSelect: Boolean,
    isChecked: Boolean,
    isDropTarget: Boolean,
    isBeingDragged: Boolean,
    showContextMenu: Boolean,
    onDismissContextMenu: () -> Unit,
    onClick: () -> Unit,
    onClose: () -> Unit,
    onAddToGroup: () -> Unit,
    onRemoveFromGroup: (() -> Unit)?,
    onDuplicate: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    val groupColor = group?.colorArgb?.let { Color(it) }
    val borderColor = when {
        isDropTarget -> accentColor
        isChecked -> accentColor
        isSelected -> accentColor
        groupColor != null -> groupColor.copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    }
    val borderWidth = if (isDropTarget || isSelected || isChecked) 2.2.dp else 1.dp

    val host = remember(tab.content.url) { extractHostLabel(tab.content.url) }
    val titleText = tab.content.title.ifBlank { host.ifBlank { if (tab.content.private) "Private Tab" else "New Tab" } }
    val previewBitmap = remember(tab.id, tab.content.url) { TabPreviewCache.get(tab.id) }

    Box(modifier = modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = cardBg,
            tonalElevation = if (isSelected) 4.dp else 1.dp,
            shadowElevation = if (isBeingDragged) 14.dp else 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(borderWidth, borderColor, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (groupColor != null) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(groupColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    if (tab.content.icon != null) {
                        Image(
                            bitmap = tab.content.icon!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(15.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                    } else {
                        Icon(
                            painter = painterResource(
                                id = if (tab.content.private) R.drawable.uh_ic_private_vibe_24 else R.drawable.ic_globe
                            ),
                            contentDescription = null,
                            tint = if (tab.content.private) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = titleText,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (isMultiSelect) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { onClick() },
                            colors = CheckboxDefaults.colors(checkedColor = accentColor),
                            modifier = Modifier.size(28.dp)
                        )
                    } else {
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.fx_ic_close_24),
                                contentDescription = "Close tab",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                // Thumbnail Preview Area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.22f)
                        .padding(horizontal = 6.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(previewBg),
                    contentAlignment = Alignment.Center
                ) {
                    if (previewBitmap != null && !previewBitmap.isRecycled) {
                        Image(
                            bitmap = previewBitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (tab.content.private && host.isBlank()) {
                                Icon(
                                    painter = painterResource(id = R.drawable.uh_ic_private_vibe_24),
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            } else {
                                Text(
                                    text = (host.firstOrNull() ?: if (tab.content.private) 'P' else 'U').uppercase(),
                                    color = accentColor,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Drop-to-Group overlay feedback
                    if (isDropTarget) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(accentColor.copy(alpha = 0.24f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(accentColor)
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = "Release to group",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                // Footer domain strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = host.ifBlank { if (tab.content.private) "Private session" else "New Tab" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(accentColor)
                        )
                    }
                }
            }
        }

        // Long-press quick action context menu
        TabCardContextMenu(
            expanded = showContextMenu,
            hasGroup = group != null,
            canShare = tab.content.url.isNotBlank() && tab.content.url != "about:blank",
            onDismiss = onDismissContextMenu,
            onAddToGroup = onAddToGroup,
            onRemoveFromGroup = onRemoveFromGroup,
            onDuplicate = onDuplicate,
            onShare = onShare,
            onClose = onClose
        )
    }
}

@Composable
internal fun TabListRow(
    tab: TabSessionState,
    group: TabGroup?,
    accentColor: Color,
    cardBg: Color,
    previewBg: Color,
    isSelected: Boolean,
    isMultiSelect: Boolean,
    isChecked: Boolean,
    showContextMenu: Boolean,
    onDismissContextMenu: () -> Unit,
    onClick: () -> Unit,
    onClose: () -> Unit,
    onAddToGroup: () -> Unit,
    onRemoveFromGroup: (() -> Unit)?,
    onDuplicate: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    val host = remember(tab.content.url) { extractHostLabel(tab.content.url) }
    val titleText = tab.content.title.ifBlank { host.ifBlank { if (tab.content.private) "Private Tab" else "New Tab" } }
    val previewBitmap = remember(tab.id, tab.content.url) { TabPreviewCache.get(tab.id) }
    val borderColor = when {
        isSelected || isChecked -> accentColor
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
    }

    Box(modifier = modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = cardBg,
            modifier = Modifier
                .fillMaxWidth()
                .border(if (isSelected || isChecked) 1.8.dp else 1.dp, borderColor, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onClick)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 48.dp, height = 38.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(previewBg),
                    contentAlignment = Alignment.Center
                ) {
                    if (previewBitmap != null && !previewBitmap.isRecycled) {
                        Image(
                            bitmap = previewBitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (tab.content.icon != null) {
                        Image(
                            bitmap = tab.content.icon!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Icon(
                            painter = painterResource(
                                id = if (tab.content.private) R.drawable.uh_ic_private_vibe_24 else R.drawable.ic_globe
                            ),
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (group != null) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(Color(group.colorArgb))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = titleText,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = host.ifBlank { if (tab.content.private) "Private session" else "New Tab" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (isMultiSelect) {
                    Checkbox(
                        checked = isChecked,
                        onCheckedChange = { onClick() },
                        colors = CheckboxDefaults.colors(checkedColor = accentColor),
                        modifier = Modifier.size(32.dp)
                    )
                } else {
                    IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                        Icon(
                            painter = painterResource(id = R.drawable.fx_ic_close_24),
                            contentDescription = "Close tab",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        TabCardContextMenu(
            expanded = showContextMenu,
            hasGroup = group != null,
            canShare = tab.content.url.isNotBlank() && tab.content.url != "about:blank",
            onDismiss = onDismissContextMenu,
            onAddToGroup = onAddToGroup,
            onRemoveFromGroup = onRemoveFromGroup,
            onDuplicate = onDuplicate,
            onShare = onShare,
            onClose = onClose
        )
    }
}

@Composable
private fun TabCardContextMenu(
    expanded: Boolean,
    hasGroup: Boolean,
    canShare: Boolean,
    onDismiss: () -> Unit,
    onAddToGroup: () -> Unit,
    onRemoveFromGroup: (() -> Unit)?,
    onDuplicate: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        DropdownMenuItem(
            text = { Text(if (hasGroup) "Move to group" else "Add to group", fontSize = 14.sp) },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_grid_view),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = { onDismiss(); onAddToGroup() }
        )
        if (hasGroup && onRemoveFromGroup != null) {
            DropdownMenuItem(
                text = { Text("Remove from group", fontSize = 14.sp) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_arrow_out),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = { onDismiss(); onRemoveFromGroup() }
            )
        }
        DropdownMenuItem(
            text = { Text("Duplicate tab", fontSize = 14.sp) },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.uh_ic_copy_24),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = { onDismiss(); onDuplicate() }
        )
        if (canShare) {
            DropdownMenuItem(
                text = { Text("Share tab", fontSize = 14.sp) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_share_24),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = { onDismiss(); onShare() }
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
        DropdownMenuItem(
            text = { Text("Close tab", color = MaterialTheme.colorScheme.error, fontSize = 14.sp) },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_delete_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = { onDismiss(); onClose() }
        )
    }
}

/**
 * Compact 2x2 Mini-Preview Tab Group Card (Firefox / Chrome modern grid folder card).
 */
@Composable
internal fun TabGroupGridCard(
    group: TabGroup,
    members: List<TabSessionState>,
    hasActiveTab: Boolean,
    cardBg: Color,
    previewBg: Color,
    isDropTarget: Boolean,
    onClick: () -> Unit,
    onAddTabInGroup: () -> Unit,
    onEdit: () -> Unit,
    onUngroup: () -> Unit,
    onCloseGroup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val groupColor = Color(group.colorArgb)
    var showMenu by remember { mutableStateOf(false) }

    val borderColor = when {
        isDropTarget -> groupColor
        hasActiveTab -> groupColor
        else -> groupColor.copy(alpha = 0.55f)
    }
    val borderWidth = if (isDropTarget || hasActiveTab) 2.2.dp else 1.4.dp

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = cardBg,
        tonalElevation = if (hasActiveTab) 4.dp else 2.dp,
        modifier = modifier
            .fillMaxWidth()
            .border(borderWidth, borderColor, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Colored header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(groupColor.copy(alpha = 0.15f))
                    .padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(groupColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = group.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(groupColor.copy(alpha = 0.22f))
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
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(26.dp)) {
                        Icon(
                            painter = painterResource(id = R.drawable.fx_ic_more_24),
                            contentDescription = "Group options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    GroupCardOverflowMenu(
                        expanded = showMenu,
                        onDismiss = { showMenu = false },
                        onAddTabInGroup = onAddTabInGroup,
                        onEdit = onEdit,
                        onUngroup = onUngroup,
                        onCloseGroup = onCloseGroup
                    )
                }
            }

            // 2x2 Mini-Preview Collage
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.22f)
                    .padding(6.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        MiniGroupPreviewTile(
                            tab = members.getOrNull(0),
                            accent = groupColor,
                            bg = previewBg,
                            overflowCount = 0,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                        MiniGroupPreviewTile(
                            tab = members.getOrNull(1),
                            accent = groupColor,
                            bg = previewBg,
                            overflowCount = 0,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        MiniGroupPreviewTile(
                            tab = members.getOrNull(2),
                            accent = groupColor,
                            bg = previewBg,
                            overflowCount = 0,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                        val overflow = (members.size - 3).coerceAtLeast(0)
                        MiniGroupPreviewTile(
                            tab = if (overflow > 1) null else members.getOrNull(3),
                            accent = groupColor,
                            bg = previewBg,
                            overflowCount = if (overflow > 1) overflow else 0,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
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
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(groupColor)
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "Add to group",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Footer summary
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (group.isPrivate) "Private group · ${members.size} tabs" else "${members.size} tabs",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_chevron_right_24),
                    contentDescription = null,
                    tint = groupColor,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

@Composable
private fun MiniGroupPreviewTile(
    tab: TabSessionState?,
    accent: Color,
    bg: Color,
    overflowCount: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (tab != null || overflowCount > 0) bg else bg.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center
    ) {
        when {
            overflowCount > 0 -> {
                Text(
                    text = "+$overflowCount",
                    color = accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            tab != null -> {
                val bmp = remember(tab.id, tab.content.url) { TabPreviewCache.get(tab.id) }
                if (bmp != null && !bmp.isRecycled) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (tab.content.icon != null) {
                    Image(
                        bitmap = tab.content.icon!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    val h = extractHostLabel(tab.content.url)
                    Text(
                        text = (h.firstOrNull() ?: 'T').uppercase(),
                        color = accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
internal fun TabGroupListCard(
    group: TabGroup,
    members: List<TabSessionState>,
    cardBg: Color,
    previewBg: Color,
    onOpenGroup: () -> Unit,
    onTabClick: (String) -> Unit,
    onAddTabInGroup: () -> Unit,
    onEdit: () -> Unit,
    onUngroup: () -> Unit,
    onCloseGroup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val groupColor = Color(group.colorArgb)
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = cardBg,
        modifier = modifier
            .fillMaxWidth()
            .border(1.2.dp, groupColor.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onOpenGroup)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(groupColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = group.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (group.isPrivate) {
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF8B5CF6).copy(alpha = 0.18f))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Private",
                            color = Color(0xFFA78BFA),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Text(
                    text = "${members.size} ${if (members.size == 1) "tab" else "tabs"}",
                    color = groupColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(4.dp))
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(30.dp)) {
                        Icon(
                            painter = painterResource(id = R.drawable.fx_ic_more_24),
                            contentDescription = "Group options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    GroupCardOverflowMenu(
                        expanded = showMenu,
                        onDismiss = { showMenu = false },
                        onAddTabInGroup = onAddTabInGroup,
                        onEdit = onEdit,
                        onUngroup = onUngroup,
                        onCloseGroup = onCloseGroup
                    )
                }
            }

            if (members.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    members.take(3).forEach { tab ->
                        val host = extractHostLabel(tab.content.url)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(previewBg)
                                .clickable { onTabClick(tab.id) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (tab.content.icon != null) {
                                Image(
                                    bitmap = tab.content.icon!!.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                            } else {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_globe),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = tab.content.title.ifBlank { host.ifBlank { "New Tab" } },
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (host.isNotBlank()) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = host,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 10.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    if (members.size > 3) {
                        Text(
                            text = "+${members.size - 3} more tabs — tap to expand",
                            color = groupColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 2.dp, start = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupCardOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onAddTabInGroup: () -> Unit,
    onEdit: () -> Unit,
    onUngroup: () -> Unit,
    onCloseGroup: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        DropdownMenuItem(
            text = { Text("New tab in group", fontSize = 14.sp) },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.uh_ic_plus_24),
                    contentDescription = null,
                    modifier = Modifier.size(17.dp)
                )
            },
            onClick = { onDismiss(); onAddTabInGroup() }
        )
        DropdownMenuItem(
            text = { Text("Rename & color", fontSize = 14.sp) },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_edit_24),
                    contentDescription = null,
                    modifier = Modifier.size(17.dp)
                )
            },
            onClick = { onDismiss(); onEdit() }
        )
        DropdownMenuItem(
            text = { Text("Ungroup tabs", fontSize = 14.sp) },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_arrow_out),
                    contentDescription = null,
                    modifier = Modifier.size(17.dp)
                )
            },
            onClick = { onDismiss(); onUngroup() }
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
        DropdownMenuItem(
            text = { Text("Close group", color = MaterialTheme.colorScheme.error, fontSize = 14.sp) },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_delete_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(17.dp)
                )
            },
            onClick = { onDismiss(); onCloseGroup() }
        )
    }
}

/**
 * Authentic Firefox-style collapsible Inactive Tabs section at the bottom of Normal Tabs.
 */
@Composable
internal fun InactiveTabsSection(
    inactiveTabs: List<TabSessionState>,
    inactiveDays: Int,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onCloseAllInactive: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (inactiveTabs.isEmpty()) return

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
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
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Inactive tabs (${inactiveTabs.size})",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Untouched for $inactiveDays+ days",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
                IconButton(onClick = onCloseAllInactive, modifier = Modifier.size(32.dp)) {
                    Icon(
                        painter = painterResource(id = R.drawable.fx_ic_delete_24),
                        contentDescription = "Close all inactive tabs",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Icon(
                    painter = painterResource(id = R.drawable.ic_triangle_down),
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(14.dp)
                        .scale(scaleX = 1f, scaleY = if (expanded) -1f else 1f)
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
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))
                    inactiveTabs.forEach { tab ->
                        val host = extractHostLabel(tab.content.url)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .clickable { onSelectTab(tab.id) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (tab.content.icon != null) {
                                Image(
                                    bitmap = tab.content.icon!!.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_globe),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tab.content.title.ifBlank { host.ifBlank { "Tab" } },
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 13.sp,
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
                            IconButton(onClick = { onCloseTab(tab.id) }, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    painter = painterResource(id = R.drawable.fx_ic_close_24),
                                    contentDescription = "Close inactive tab",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(
                            onClick = onCloseAllInactive,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Close all inactive tabs",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}
