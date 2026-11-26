package app.harvester.companion.browser.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import mozilla.components.browser.state.state.TabSessionState

const val GROUP_STRIP_HEIGHT_DP = 38
const val LANDSCAPE_TAB_STRIP_HEIGHT_DP = 38

/**
 * Sleek docked pill Group Tab Strip rendered cleanly above/below the address bar
 * with exact layout reservation in BrowserFragment so it never overlaps webpage content.
 */
@Composable
fun InBrowserGroupTabStrip(
    group: TabGroup,
    members: List<TabSessionState>,
    selectedTabId: String?,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onAddTabInGroup: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (members.isEmpty()) return
    val groupColor = Color(group.colorArgb)
    var isCollapsed by remember(group.id) { mutableStateOf(false) }

    val barBg = if (group.isPrivate) {
        Color(0xFF100C1E)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val onBarColor = if (group.isPrivate) Color(0xFFF3EEFF) else MaterialTheme.colorScheme.onSurface
    val subBarColor = if (group.isPrivate) Color(0xFFA799C9) else MaterialTheme.colorScheme.onSurfaceVariant
    val hairline = if (group.isPrivate) PrivateAccentColor.copy(alpha = 0.20f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f)

    Surface(
        color = barBg,
        modifier = modifier
            .fillMaxWidth()
            .height(GROUP_STRIP_HEIGHT_DP.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HorizontalDivider(thickness = 0.6.dp, color = hairline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Sleek group pill chip (tap to collapse/expand strip)
                Row(
                    modifier = Modifier
                        .height(26.dp)
                        .clip(CircleShape)
                        .background(groupColor.copy(alpha = 0.18f))
                        .border(1.dp, groupColor.copy(alpha = 0.55f), CircleShape)
                        .clickable { isCollapsed = !isCollapsed }
                        .padding(horizontal = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(groupColor)
                    )
                    Text(
                        text = group.name,
                        color = onBarColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 84.dp)
                    )
                    Text(
                        text = "${members.size}",
                        color = groupColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                if (!isCollapsed) {
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        contentPadding = PaddingValues(end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(members, key = { "gstrip_${it.id}" }) { tab ->
                            val active = tab.id == selectedTabId
                            val host = remember(tab.content.url) { extractHostLabel(tab.content.url) }
                            val label = tab.content.title.ifBlank { host.ifBlank { "New Tab" } }
                            val pillBg = when {
                                active && group.isPrivate -> groupColor.copy(alpha = 0.24f)
                                active -> groupColor.copy(alpha = 0.16f)
                                group.isPrivate -> Color(0xFF1A142D)
                                else -> MaterialTheme.colorScheme.surface
                            }

                            Row(
                                modifier = Modifier
                                    .height(27.dp)
                                    .widthIn(min = 58.dp, max = 148.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(pillBg)
                                    .border(
                                        width = if (active) 1.3.dp else 0.7.dp,
                                        color = if (active) groupColor else hairline,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .clickable { onSelectTab(tab.id) }
                                    .padding(start = 7.dp, end = if (active) 3.dp else 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                TabFaviconBox(tab = tab, accentColor = groupColor, sizeDp = 14)
                                Text(
                                    text = label,
                                    color = onBarColor,
                                    fontSize = 11.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (active) {
                                    IconButton(
                                        onClick = { onCloseTab(tab.id) },
                                        modifier = Modifier.size(19.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.fx_ic_close_24),
                                            contentDescription = "Close tab",
                                            tint = subBarColor,
                                            modifier = Modifier.size(10.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                IconButton(
                    onClick = onAddTabInGroup,
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(groupColor.copy(alpha = 0.16f))
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_plus_24),
                        contentDescription = "New tab in group",
                        tint = groupColor,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            HorizontalDivider(thickness = 0.6.dp, color = hairline)
        }
    }
}

/**
 * Clean docked Desktop/Tablet-style top Tab Bar for Landscape mode.
 * Respects status bars & camera cutouts and reserves exact height in BrowserFragment so nothing overlaps.
 */
@Composable
fun LandscapeTopTabStrip(
    tabs: List<TabSessionState>,
    groups: List<TabGroup>,
    pinnedIds: List<String>,
    selectedTabId: String?,
    isPrivate: Boolean,
    applyStatusBarPadding: Boolean,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    val modeTabs = remember(tabs, isPrivate) { tabs.filter { it.content.private == isPrivate } }
    if (modeTabs.isEmpty()) return

    val accent = if (isPrivate) PrivateAccentColor else MaterialTheme.colorScheme.primary
    val barBg = if (isPrivate) Color(0xFF0C0917) else MaterialTheme.colorScheme.background
    val inactiveTabBg = if (isPrivate) Color(0xFF18132B) else MaterialTheme.colorScheme.surfaceContainerLow
    val onBarColor = if (isPrivate) Color(0xFFF3EEFF) else MaterialTheme.colorScheme.onSurface
    val subBarColor = if (isPrivate) Color(0xFFA799C9) else MaterialTheme.colorScheme.onSurfaceVariant
    val hairline = if (isPrivate) PrivateAccentColor.copy(alpha = 0.22f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)

    val pinnedSet = remember(pinnedIds, isPrivate) { if (isPrivate) emptySet() else pinnedIds.toSet() }
    val orderedTabs = remember(modeTabs, pinnedIds, pinnedSet) {
        val byId = modeTabs.associateBy { it.id }
        val pinned = pinnedIds.mapNotNull { byId[it] }
        val rest = modeTabs.filterNot { it.id in pinnedSet }
        pinned + rest
    }
    val groupColorByTabId = remember(groups) {
        buildMap {
            for (g in groups) {
                for (tid in g.tabIds) put(tid, Color(g.colorArgb))
            }
        }
    }

    Surface(
        color = barBg,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (applyStatusBarPadding) Modifier.statusBarsPadding() else Modifier)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .displayCutoutPadding()
                    .height(LANDSCAPE_TAB_STRIP_HEIGHT_DP.dp)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isPrivate) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(PrivateAccentColor.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.uh_ic_private_vibe_24),
                            contentDescription = "Private",
                            tint = PrivateAccentColor,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }

                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(orderedTabs, key = { "lstrip_${it.id}" }) { tab ->
                        val active = tab.id == selectedTabId
                        val isPinned = tab.id in pinnedSet
                        val grpColor = groupColorByTabId[tab.id]
                        val chipAccent = grpColor ?: accent
                        val host = remember(tab.content.url) { extractHostLabel(tab.content.url) }
                        val label = tab.content.title.ifBlank { host.ifBlank { if (isPrivate) "Private" else "New Tab" } }

                        Row(
                            modifier = Modifier
                                .height(29.dp)
                                .then(
                                    if (isPinned) Modifier.widthIn(min = 36.dp, max = 96.dp)
                                    else Modifier.widthIn(min = 104.dp, max = 180.dp)
                                )
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (active) chipAccent.copy(alpha = if (isPrivate) 0.24f else 0.16f)
                                    else inactiveTabBg
                                )
                                .border(
                                    width = if (active) 1.3.dp else 0.7.dp,
                                    color = if (active) chipAccent else hairline,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { onSelectTab(tab.id) }
                                .padding(start = 8.dp, end = if (isPinned) 8.dp else 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            if (grpColor != null) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(grpColor)
                                )
                            }
                            TabFaviconBox(tab = tab, accentColor = chipAccent, sizeDp = 14)
                            if (!isPinned || active) {
                                Text(
                                    text = label,
                                    color = onBarColor,
                                    fontSize = 11.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                            }
                            if (!isPinned) {
                                IconButton(
                                    onClick = { onCloseTab(tab.id) },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.fx_ic_close_24),
                                        contentDescription = "Close tab",
                                        tint = subBarColor,
                                        modifier = Modifier.size(10.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                IconButton(
                    onClick = onNewTab,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.16f))
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_plus_24),
                        contentDescription = "New tab",
                        tint = accent,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            HorizontalDivider(thickness = 0.6.dp, color = hairline)
        }
    }
}

/**
 * Full-screen Biometric/PIN lock gate rendered over active Private browser content
 * when returning from background while Private Lock is enabled.
 */
@Composable
fun InBrowserPrivateLockOverlay(
    onUnlock: () -> Unit,
    onCloseAllPrivate: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xFA090711),
        modifier = modifier
            .fillMaxSize()
            .clickable(enabled = true, onClick = {})
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF7C3AED).copy(alpha = 0.22f),
                            Color.Transparent
                        )
                    )
                )
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(78.dp)
                        .clip(CircleShape)
                        .background(PrivateAccentColor.copy(alpha = 0.18f))
                        .border(1.4.dp, PrivateAccentColor.copy(alpha = 0.52f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_private_vibe_24),
                        contentDescription = "Private tabs locked",
                        tint = PrivateAccentColor,
                        modifier = Modifier.size(36.dp)
                    )
                }
                Text(
                    text = "Private Session Locked",
                    color = Color.White,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Verify your fingerprint, face, or device PIN to resume your private session.",
                    color = Color(0xFFB7A9DA),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = onUnlock,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF7C3AED),
                        contentColor = Color.White
                    ),
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_lock),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Unlock", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onCloseAllPrivate) {
                    Text(
                        text = "Close all private tabs",
                        color = Color(0xFFFCA5A5),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
