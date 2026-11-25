package app.harvester.companion.browser.tabs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

@Composable
internal fun TabGridCard(
    tab: TabSessionState,
    group: TabGroup?,
    palette: TabsTrayPalette,
    isSelected: Boolean,
    isPinned: Boolean,
    isMuted: Boolean,
    isSleeping: Boolean,
    isMultiSelect: Boolean,
    isChecked: Boolean,
    isDropTarget: Boolean,
    isBeingDragged: Boolean,
    showContextMenu: Boolean,
    onDismissContextMenu: () -> Unit,
    onClick: () -> Unit,
    onClose: () -> Unit,
    onToggleMute: () -> Unit,
    onTogglePin: () -> Unit,
    onAddToGroup: () -> Unit,
    onRemoveFromGroup: (() -> Unit)?,
    onBookmark: () -> Unit,
    onDuplicate: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    val groupColor = group?.colorArgb?.let { Color(it) }
    val borderColor = when {
        isDropTarget -> palette.accent
        isChecked -> palette.accent
        isSelected -> palette.accent
        groupColor != null -> groupColor.copy(alpha = 0.62f)
        else -> palette.subtleBorder
    }
    val borderWidth = if (isDropTarget || isSelected || isChecked) 2.2.dp else 1.dp

    val host = remember(tab.content.url) { extractHostLabel(tab.content.url) }
    val titleText = tab.content.title.ifBlank {
        host.ifBlank { if (tab.content.private) "Private Tab" else "New Tab" }
    }
    val previewBitmap = remember(tab.id, tab.content.url, tab.content.title) { TabPreviewCache.get(tab.id) }
    val hasMedia = TabMetaStore.hasMediaSession(tab) || isMuted
    val isPlaying = TabMetaStore.isTabPlayingAudio(tab)
    val isLoading = tab.content.loading && tab.content.progress in 1..99

    Box(modifier = modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = palette.cardBg,
            shadowElevation = if (isBeingDragged) 14.dp else if (isSelected) 3.dp else 1.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(borderWidth, borderColor, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
                .clickable(onClick = onClick)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Top color accent strip if member of a group
                if (groupColor != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(groupColor)
                    )
                }

                // Card Header: Favicon + Title + Close/Checkbox
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TabFaviconBox(
                        tab = tab,
                        accentColor = palette.accent,
                        sizeDp = 18
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = titleText,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isMultiSelect) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { onClick() },
                            colors = CheckboxDefaults.colors(
                                checkedColor = palette.accent,
                                uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.size(26.dp)
                        )
                    } else {
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.size(26.dp)
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

                // Preview Canvas
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, end = 6.dp, bottom = 6.dp)
                        .aspectRatio(0.92f)
                        .clip(RoundedCornerShape(13.dp))
                        .background(palette.previewBg)
                ) {
                    if (previewBitmap != null && !previewBitmap.isRecycled) {
                        Image(
                            bitmap = previewBitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.TopCenter,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            TabFaviconBox(
                                tab = tab,
                                accentColor = palette.accent,
                                sizeDp = 34
                            )
                            if (host.isNotBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = host,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // Mini loading progress bar along top edge of preview
                    if (isLoading) {
                        LinearProgressIndicator(
                            progress = { (tab.content.progress / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .height(2.5.dp),
                            color = palette.accent,
                            trackColor = Color.Transparent
                        )
                    }

                    // Top-right Live Audio / Mute one-tap interactive pill
                    if (hasMedia && !isMultiSelect) {
                        val audioBg = if (isMuted) Color(0xCC27272A) else palette.accent.copy(alpha = 0.92f)
                        val audioIcon = if (isMuted) R.drawable.uh_ic_volume_off_24 else R.drawable.uh_ic_volume_on_24
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(audioBg)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onToggleMute
                                )
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = audioIcon),
                                    contentDescription = if (isMuted) "Unmute tab" else "Mute tab",
                                    tint = Color.White,
                                    modifier = Modifier.size(13.dp)
                                )
                                if (isPlaying && !isMuted) {
                                    Text(
                                        text = "LIVE",
                                        color = Color.White,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }
                    }

                    // Bottom-start Sleeping tab moon badge (suspended background session)
                    if (isSleeping && !isDropTarget) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(6.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(Color(0xB818181B))
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.uh_ic_sleep_moon_24),
                                    contentDescription = "Sleeping tab",
                                    tint = Color(0xFF93C5FD),
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = "Sleep",
                                    color = Color.White.copy(alpha = 0.90f),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    // Magnetic Drop-to-Group overlay when dragging another card over center
                    if (isDropTarget) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(palette.accent.copy(alpha = 0.24f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = palette.accent,
                                shadowElevation = 4.dp
                            ) {
                                Text(
                                    text = "Drop to group",
                                    color = palette.onAccent,
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

        TabCardContextMenu(
            expanded = showContextMenu,
            isPrivate = tab.content.private,
            isPinned = isPinned,
            isMuted = isMuted,
            isGrouped = group != null,
            hasUrl = tab.content.url.isNotBlank() && tab.content.url != "about:blank",
            onDismiss = onDismissContextMenu,
            onTogglePin = onTogglePin,
            onToggleMute = onToggleMute,
            onAddToGroup = onAddToGroup,
            onRemoveFromGroup = onRemoveFromGroup,
            onBookmark = onBookmark,
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
    palette: TabsTrayPalette,
    isSelected: Boolean,
    isPinned: Boolean,
    isMuted: Boolean,
    isSleeping: Boolean,
    isMultiSelect: Boolean,
    isChecked: Boolean,
    isDropTarget: Boolean,
    isBeingDragged: Boolean,
    showContextMenu: Boolean,
    onDismissContextMenu: () -> Unit,
    onClick: () -> Unit,
    onClose: () -> Unit,
    onToggleMute: () -> Unit,
    onTogglePin: () -> Unit,
    onAddToGroup: () -> Unit,
    onRemoveFromGroup: (() -> Unit)?,
    onBookmark: () -> Unit,
    onDuplicate: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    val host = remember(tab.content.url) { extractHostLabel(tab.content.url) }
    val titleText = tab.content.title.ifBlank {
        host.ifBlank { if (tab.content.private) "Private Tab" else "New Tab" }
    }
    val hasMedia = TabMetaStore.hasMediaSession(tab) || isMuted
    val isLoading = tab.content.loading && tab.content.progress in 1..99
    val borderColor = when {
        isDropTarget -> palette.accent
        isChecked || isSelected -> palette.accent
        group != null -> Color(group.colorArgb).copy(alpha = 0.55f)
        else -> palette.subtleBorder
    }

    Box(modifier = modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = palette.cardBg,
            shadowElevation = if (isBeingDragged) 10.dp else 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(if (isSelected || isChecked || isDropTarget) 1.8.dp else 1.dp, borderColor, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onClick)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TabFaviconBox(tab = tab, accentColor = palette.accent, sizeDp = 26)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = titleText,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (isSleeping) {
                                Icon(
                                    painter = painterResource(id = R.drawable.uh_ic_sleep_moon_24),
                                    contentDescription = "Sleeping",
                                    tint = Color(0xFF60A5FA),
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                        if (host.isNotBlank()) {
                            Text(
                                text = host,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (hasMedia && !isMultiSelect) {
                        IconButton(
                            onClick = onToggleMute,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                painter = painterResource(
                                    id = if (isMuted) R.drawable.uh_ic_volume_off_24 else R.drawable.uh_ic_volume_on_24
                                ),
                                contentDescription = if (isMuted) "Unmute" else "Mute",
                                tint = if (isMuted) MaterialTheme.colorScheme.onSurfaceVariant else palette.accent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    if (isMultiSelect) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { onClick() },
                            colors = CheckboxDefaults.colors(checkedColor = palette.accent)
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

                if (isLoading) {
                    LinearProgressIndicator(
                        progress = { (tab.content.progress / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = palette.accent,
                        trackColor = Color.Transparent
                    )
                }
            }
        }

        TabCardContextMenu(
            expanded = showContextMenu,
            isPrivate = tab.content.private,
            isPinned = isPinned,
            isMuted = isMuted,
            isGrouped = group != null,
            hasUrl = tab.content.url.isNotBlank() && tab.content.url != "about:blank",
            onDismiss = onDismissContextMenu,
            onTogglePin = onTogglePin,
            onToggleMute = onToggleMute,
            onAddToGroup = onAddToGroup,
            onRemoveFromGroup = onRemoveFromGroup,
            onBookmark = onBookmark,
            onDuplicate = onDuplicate,
            onShare = onShare,
            onClose = onClose
        )
    }
}

@Composable
internal fun TabCardContextMenu(
    expanded: Boolean,
    isPrivate: Boolean,
    isPinned: Boolean,
    isMuted: Boolean,
    isGrouped: Boolean,
    hasUrl: Boolean,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleMute: () -> Unit,
    onAddToGroup: () -> Unit,
    onRemoveFromGroup: (() -> Unit)?,
    onBookmark: () -> Unit,
    onDuplicate: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp)
    ) {
        if (!isPrivate) {
            DropdownMenuItem(
                text = { Text(if (isPinned) "Unpin tab" else "Pin tab", fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_pin_24),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    onDismiss()
                    onTogglePin()
                }
            )
        }

        DropdownMenuItem(
            text = { Text(if (isMuted) "Unmute tab" else "Mute tab", fontSize = 14.sp, fontWeight = FontWeight.Medium) },
            leadingIcon = {
                Icon(
                    painter = painterResource(
                        id = if (isMuted) R.drawable.uh_ic_volume_on_24 else R.drawable.uh_ic_volume_off_24
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = {
                onDismiss()
                onToggleMute()
            }
        )

        DropdownMenuItem(
            text = { Text(if (isGrouped) "Move to another group" else "Add to group", fontSize = 14.sp, fontWeight = FontWeight.Medium) },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_grid_view),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = {
                onDismiss()
                onAddToGroup()
            }
        )

        if (isGrouped && onRemoveFromGroup != null) {
            DropdownMenuItem(
                text = { Text("Remove from group", fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_arrow_out),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    onDismiss()
                    onRemoveFromGroup()
                }
            )
        }

        if (!isPrivate && hasUrl) {
            DropdownMenuItem(
                text = { Text("Bookmark tab", fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_bookmark_24),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    onDismiss()
                    onBookmark()
                }
            )
        }

        DropdownMenuItem(
            text = { Text("Duplicate tab", fontSize = 14.sp, fontWeight = FontWeight.Medium) },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.uh_ic_copy_24),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = {
                onDismiss()
                onDuplicate()
            }
        )

        if (hasUrl) {
            DropdownMenuItem(
                text = { Text("Share tab", fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_share_24),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    onDismiss()
                    onShare()
                }
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 3.dp))

        DropdownMenuItem(
            text = {
                Text(
                    text = "Close tab",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_delete_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = {
                onDismiss()
                onClose()
            }
        )
    }
}

@Composable
internal fun TabFaviconBox(
    tab: TabSessionState,
    accentColor: Color,
    sizeDp: Int
) {
    val iconBitmap = tab.content.icon
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape((sizeDp / 3).dp))
            .background(accentColor.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        if (iconBitmap != null && !iconBitmap.isRecycled) {
            Image(
                bitmap = iconBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                painter = painterResource(
                    id = if (tab.content.private) R.drawable.uh_ic_private_vibe_24 else R.drawable.ic_globe
                ),
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size((sizeDp * 0.65f).dp)
            )
        }
    }
}
