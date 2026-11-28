package app.harvester.companion.browser.tabs

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.UHBrand
import kotlinx.coroutines.launch
import mozilla.components.browser.state.state.TabSessionState
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Elevated glassmorphic tab card with full-bleed reactive live preview,
 * frosted top header overlay, Home mini-preview for blank tabs, and clean selection ring.
 */
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
    showGroupAccent: Boolean = false,
    modifier: Modifier = Modifier
) {
    val groupColor = if (showGroupAccent) group?.colorArgb?.let { Color(it) } else null
    val borderColor = when {
        isDropTarget -> palette.accent
        isChecked -> palette.accent
        isSelected -> palette.accent
        groupColor != null -> groupColor.copy(alpha = 0.55f)
        else -> palette.subtleBorder
    }
    val borderWidth = if (isDropTarget || isSelected || isChecked) 2.dp else 0.9.dp

    val host = remember(tab.content.url) { extractHostLabel(tab.content.url) }
    val isBlankTab = tab.content.url.isBlank() || tab.content.url == "about:blank"
    val titleText = tab.content.title.ifBlank {
        host.ifBlank { if (tab.content.private) "Private Tab" else "New Tab" }
    }

    val previewVersion by TabPreviewCache.versionFlow.collectAsState()
    val previewBitmap = remember(tab.id, tab.content.url, isBlankTab, previewVersion) {
        when {
            isBlankTab && !tab.content.private -> TabPreviewCache.getHomeSnapshotAsync()
            isBlankTab -> null
            else -> TabPreviewCache.getOrLoadAsync(tab.id, isPrivate = tab.content.private)
        }
    }

    val hasMedia = TabMetaStore.hasMediaSession(tab) || isMuted
    val isPlaying = TabMetaStore.isTabPlayingAudio(tab)
    val isLoading = tab.content.loading && tab.content.progress in 1..99
    val cardShape = RoundedCornerShape(20.dp)

    val morphProgress by animateFloatAsState(
        targetValue = if (isDropTarget) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "tabGroupMorph"
    )

    Box(modifier = modifier.fillMaxWidth()) {
        Surface(
            shape = cardShape,
            color = palette.cardBg,
            shadowElevation = if (isBeingDragged) 18.dp else if (isDropTarget || isSelected) 6.dp else 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.78f)
                .graphicsLayer {
                    val targetScale = 1f - (morphProgress * 0.03f)
                    scaleX = targetScale
                    scaleY = targetScale
                }
                .border(borderWidth, borderColor, cardShape)
                .clip(cardShape)
                .clickable(onClick = onClick)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 1. Solid Opaque Top Header Bar stacked cleanly ABOVE preview (zero overlap/bleed)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.headerFrostBg)
                ) {
                    if (groupColor != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.5.dp)
                                .background(groupColor)
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 9.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TabFaviconBox(
                            tab = tab,
                            accentColor = palette.accent,
                            sizeDp = 17
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isDropTarget) "New Group" else titleText,
                            color = if (isDropTarget) palette.accent else palette.onSurface,
                            fontSize = 11.5.sp,
                            fontWeight = if (isSelected || isDropTarget) FontWeight.Bold else FontWeight.SemiBold,
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
                                    uncheckedColor = palette.onSurfaceSubtle
                                ),
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            IconButton(
                                onClick = onClose,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.fx_ic_close_24),
                                    contentDescription = "Close tab",
                                    tint = palette.onSurfaceSubtle,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                    HorizontalDivider(
                        thickness = 0.6.dp,
                        color = palette.subtleBorder.copy(alpha = 0.65f)
                    )
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

                // 2. Clean Preview Canvas sitting strictly below the solid header
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(palette.previewBg)
                ) {
                    if (isDropTarget) {
                        // Chrome-style 2x2 Group Morph Transition preview frame
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(palette.accent.copy(alpha = if (palette.isDark) 0.14f else 0.08f))
                                .padding(7.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Slot 1: Current tab mini-card preview
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(palette.cardBg)
                                        .border(1.dp, palette.subtleBorder, RoundedCornerShape(10.dp))
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
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            TabFaviconBox(tab = tab, accentColor = palette.accent, sizeDp = 18)
                                        }
                                    }
                                }
                                // Slot 2: Glowing target slot ready for dragged tab
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(palette.accent.copy(alpha = 0.22f))
                                        .border(1.5.dp, palette.accent, RoundedCornerShape(10.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.uh_ic_plus_24),
                                        contentDescription = null,
                                        tint = palette.accent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(palette.cardBg.copy(alpha = 0.45f))
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(palette.cardBg.copy(alpha = 0.45f))
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 8.dp)
                                .clip(CircleShape)
                                .background(palette.accentStrong)
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Drop to Group",
                                color = palette.onAccent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        if (previewBitmap != null && !previewBitmap.isRecycled) {
                            Image(
                                bitmap = previewBitmap.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                alignment = Alignment.TopCenter,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else if (isBlankTab) {
                            BlankTabMiniPreview(
                                isPrivate = tab.content.private,
                                palette = palette,
                                compact = false,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            UnrenderedSitePreview(
                                tab = tab,
                                host = host,
                                palette = palette,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        // Subtle bottom glass vignette for elevated depth
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(26.dp)
                                .align(Alignment.BottomCenter)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.Transparent,
                                            Color.Black.copy(alpha = if (palette.isDark) 0.28f else 0.10f)
                                        )
                                    )
                                )
                        )
                    }

                // 3. Live Audio / Mute one-tap interactive pill (bottom-end)
                if (hasMedia && !isMultiSelect) {
                    val audioBg = if (isMuted) Color(0xCC27272A) else palette.accentStrong.copy(alpha = 0.92f)
                    val audioIcon = if (isMuted) R.drawable.uh_ic_volume_off_24 else R.drawable.uh_ic_volume_on_24
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .clip(CircleShape)
                            .background(audioBg)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onToggleMute
                            )
                            .padding(horizontal = 7.dp, vertical = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = audioIcon),
                                contentDescription = if (isMuted) "Unmute tab" else "Mute tab",
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
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

                // 4. Sleeping tab moon badge (bottom-start)
                if (isSleeping && !isDropTarget) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xC2141222))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.uh_ic_sleep_moon_24),
                                contentDescription = "Sleeping tab",
                                tint = Color(0xFF93C5FD),
                                modifier = Modifier.size(10.dp)
                            )
                            Text(
                                text = "Sleep",
                                color = Color.White.copy(alpha = 0.92f),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold
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
            hasUrl = !isBlankTab,
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

/**
 * Bespoke Home Mini-Preview rendered inside blank/new tab cards and 2x2 group collage tiles
 * instead of a dull fallback globe icon.
 */
@Composable
internal fun BlankTabMiniPreview(
    isPrivate: Boolean,
    palette: TabsTrayPalette,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val bgBrush = if (isPrivate) {
        Brush.radialGradient(
            colors = listOf(
                Color(0xFF2D1B54),
                Color(0xFF140D26),
                Color(0xFF0A0714)
            )
        )
    } else if (palette.isDark) {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF1A1E2B),
                Color(0xFF11141E)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFFF3F6FC),
                Color(0xFFE6ECF7)
            )
        )
    }

    Box(
        modifier = modifier
            .background(bgBrush)
            .then(
                if (isPrivate) {
                    Modifier.background(
                        Brush.radialGradient(
                            colors = listOf(
                                PrivateAccentStrongColor.copy(alpha = 0.24f),
                                Color.Transparent
                            ),
                            radius = 280f
                        )
                    )
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        if (compact) {
            if (isPrivate) {
                Icon(
                    painter = painterResource(id = R.drawable.uh_ic_private_vibe_24),
                    contentDescription = null,
                    tint = PrivateAccentColor.copy(alpha = 0.85f),
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Text(
                    text = UHBrand.NAME,
                    color = palette.accent.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 42.dp, bottom = 14.dp, start = 14.dp, end = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (isPrivate) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(PrivateAccentColor.copy(alpha = 0.16f))
                            .border(1.dp, PrivateAccentColor.copy(alpha = 0.42f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.uh_ic_private_vibe_24),
                            contentDescription = null,
                            tint = PrivateAccentColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Private Browsing",
                        color = Color(0xFFE9E2FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.76f)
                            .height(10.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .border(0.6.dp, PrivateAccentColor.copy(alpha = 0.30f), CircleShape)
                    )
                } else {
                    Text(
                        text = UHBrand.NAME,
                        color = palette.onSurface.copy(alpha = 0.88f),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.8.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .width(22.dp)
                            .height(2.dp)
                            .clip(CircleShape)
                            .background(palette.accent.copy(alpha = 0.72f))
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    // Mini 4-tile shortcut preview grid
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(4) { idx ->
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(
                                        if (idx == 0) palette.accent.copy(alpha = 0.25f)
                                        else palette.onSurface.copy(alpha = if (palette.isDark) 0.10f else 0.08f)
                                    )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    // Mini pill address bar silhouette
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.78f)
                            .height(12.dp)
                            .clip(CircleShape)
                            .background(palette.onSurface.copy(alpha = if (palette.isDark) 0.10f else 0.08f))
                            .border(0.6.dp, palette.subtleBorder, CircleShape)
                    )
                }
            }
        }
    }
}

@Composable
private fun UnrenderedSitePreview(
    tab: TabSessionState,
    host: String,
    palette: TabsTrayPalette,
    modifier: Modifier = Modifier
) {
    val bgBrush = if (tab.content.private) {
        Brush.verticalGradient(
            listOf(Color(0xFF1A1230), Color(0xFF0E0A1B))
        )
    } else if (palette.isDark) {
        Brush.verticalGradient(
            listOf(Color(0xFF1C202C), Color(0xFF131620))
        )
    } else {
        Brush.verticalGradient(
            listOf(Color(0xFFF6F8FC), Color(0xFFEAEEF6))
        )
    }

    Column(
        modifier = modifier
            .background(bgBrush)
            .padding(top = 42.dp, start = 12.dp, end = 12.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(palette.accent.copy(alpha = 0.14f))
                .border(0.8.dp, palette.subtleBorder, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            TabFaviconBox(
                tab = tab,
                accentColor = palette.accent,
                sizeDp = 28
            )
        }
        if (host.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(palette.onSurface.copy(alpha = 0.06f))
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            ) {
                Text(
                    text = host,
                    color = palette.onSurfaceSubtle,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Smooth horizontal swipe-to-close wrapper with spring bounce-back physics.
 * Automatically disabled during multi-select or active drag-to-reorder.
 */
@Composable
internal fun SwipeToCloseTabBox(
    enabled: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 108.dp.toPx() }
    val offsetX = remember { Animatable(0f) }

    Box(
        modifier = modifier
            .offset { IntOffset(offsetX.value.roundToInt(), 0) }
            .graphicsLayer {
                val progress = (abs(offsetX.value) / (dismissThresholdPx * 1.4f)).coerceIn(0f, 1f)
                alpha = 1f - (progress * 0.55f)
            }
            .then(
                if (enabled) {
                    Modifier.pointerInput(onClose) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                scope.launch {
                                    if (abs(offsetX.value) >= dismissThresholdPx) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        val target = if (offsetX.value > 0f) dismissThresholdPx * 2.5f else -dismissThresholdPx * 2.5f
                                        offsetX.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow))
                                        onClose()
                                    } else {
                                        offsetX.animateTo(
                                            0f,
                                            spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessMedium
                                            )
                                        )
                                    }
                                }
                            },
                            onDragCancel = {
                                scope.launch {
                                    offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                                }
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                scope.launch {
                                    offsetX.snapTo(offsetX.value + dragAmount)
                                }
                            }
                        )
                    }
                } else {
                    Modifier
                }
            )
    ) {
        content()
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
    showGroupAccent: Boolean = false,
    modifier: Modifier = Modifier
) {
    val host = remember(tab.content.url) { extractHostLabel(tab.content.url) }
    val isBlankTab = tab.content.url.isBlank() || tab.content.url == "about:blank"
    val titleText = tab.content.title.ifBlank {
        host.ifBlank { if (tab.content.private) "Private Tab" else "New Tab" }
    }
    val hasMedia = TabMetaStore.hasMediaSession(tab) || isMuted
    val isLoading = tab.content.loading && tab.content.progress in 1..99
    val groupColor = if (showGroupAccent) group?.colorArgb?.let { Color(it) } else null
    val borderColor = when {
        isDropTarget -> palette.accent
        isChecked || isSelected -> palette.accent
        groupColor != null -> groupColor.copy(alpha = 0.55f)
        else -> palette.subtleBorder
    }

    Box(modifier = modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = palette.cardBg,
            shadowElevation = if (isBeingDragged) 12.dp else if (isSelected) 3.dp else 1.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(if (isSelected || isChecked || isDropTarget) 1.8.dp else 0.9.dp, borderColor, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
                .clickable(onClick = onClick)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TabFaviconBox(tab = tab, accentColor = palette.accent, sizeDp = 28)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = titleText,
                                color = palette.onSurface,
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
                                color = palette.onSurfaceSubtle,
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
                                tint = if (isMuted) palette.onSurfaceSubtle else palette.accent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    if (isMultiSelect) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { onClick() },
                            colors = CheckboxDefaults.colors(
                                checkedColor = palette.accent,
                                uncheckedColor = palette.onSurfaceSubtle
                            )
                        )
                    } else {
                        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                            Icon(
                                painter = painterResource(id = R.drawable.fx_ic_close_24),
                                contentDescription = "Close tab",
                                tint = palette.onSurfaceSubtle,
                                modifier = Modifier.size(15.dp)
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
            hasUrl = !isBlankTab,
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
    val menuBg = if (isPrivate) Color(0xFF1A142D) else MaterialTheme.colorScheme.background
    val itemColor = if (isPrivate) Color(0xFFF3EEFF) else MaterialTheme.colorScheme.onSurface
    val dangerColor = if (isPrivate) Color(0xFFF87171) else MaterialTheme.colorScheme.error

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = menuBg,
        shape = RoundedCornerShape(18.dp)
    ) {
        if (!isPrivate) {
            DropdownMenuItem(
                text = { Text(if (isPinned) "Unpin tab" else "Pin tab", color = itemColor, fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_pin_24),
                        contentDescription = null,
                        tint = itemColor,
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
            text = { Text(if (isMuted) "Unmute tab" else "Mute tab", color = itemColor, fontSize = 14.sp, fontWeight = FontWeight.Medium) },
            leadingIcon = {
                Icon(
                    painter = painterResource(
                        id = if (isMuted) R.drawable.uh_ic_volume_on_24 else R.drawable.uh_ic_volume_off_24
                    ),
                    contentDescription = null,
                    tint = itemColor,
                    modifier = Modifier.size(18.dp)
                )
            },
            onClick = {
                onDismiss()
                onToggleMute()
            }
        )

        DropdownMenuItem(
            text = { Text(if (isGrouped) "Move to another group" else "Add to group", color = itemColor, fontSize = 14.sp, fontWeight = FontWeight.Medium) },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.uh_ic_tab_groups_24),
                    contentDescription = null,
                    tint = itemColor,
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
                text = { Text("Remove from group", color = itemColor, fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_arrow_out),
                        contentDescription = null,
                        tint = itemColor,
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
                text = { Text("Bookmark tab", color = itemColor, fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_bookmark_24),
                        contentDescription = null,
                        tint = itemColor,
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
            text = { Text("Duplicate tab", color = itemColor, fontSize = 14.sp, fontWeight = FontWeight.Medium) },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.uh_ic_copy_24),
                    contentDescription = null,
                    tint = itemColor,
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
                text = { Text("Share tab", color = itemColor, fontSize = 14.sp, fontWeight = FontWeight.Medium) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_share_24),
                        contentDescription = null,
                        tint = itemColor,
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
                    color = dangerColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_delete_24),
                    contentDescription = null,
                    tint = dangerColor,
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
                    id = if (tab.content.private) R.drawable.uh_ic_private_vibe_24 else R.drawable.uh_ic_tab_modern_24
                ),
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size((sizeDp * 0.65f).dp)
            )
        }
    }
}
