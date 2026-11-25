package app.harvester.companion.browser.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import mozilla.components.browser.state.state.TabSessionState

@Composable
internal fun GroupsDirectoryPage(
    groups: List<TabGroup>,
    tabsById: Map<String, TabSessionState>,
    selectedTabId: String?,
    isPrivateLocked: Boolean,
    searchQuery: String,
    palette: TabsTrayPalette,
    actions: TabsTrayActions,
    onOpenGroupDetail: (TabGroup) -> Unit,
    onEditGroup: (TabGroup) -> Unit,
    onCreateNewGroup: () -> Unit
) {
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

    if (visibleGroups.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(66.dp)
                        .clip(CircleShape)
                        .background(palette.accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_grid_view),
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Text(
                    text = if (searchQuery.isBlank()) "No Tab Groups Yet" else "No matching groups",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Drag one tab onto another, multi-select tabs, or create a color-coded group right here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                if (searchQuery.isBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = onCreateNewGroup,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.accent,
                            contentColor = palette.onAccent
                        )
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.uh_ic_plus_24),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Create Tab Group", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 92.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (normalGroups.isNotEmpty()) {
            item(key = "hdr_normal_groups") {
                GroupSectionHeader(
                    title = "Tab Groups (${normalGroups.size})",
                    onCreateNew = onCreateNewGroup,
                    palette = palette
                )
            }
            items(normalGroups, key = { "dir_${it.id}" }) { group ->
                val members = group.tabIds.mapNotNull { tabsById[it] }
                TabGroupListCard(
                    group = group,
                    members = members,
                    selectedTabId = selectedTabId,
                    palette = palette,
                    isDropTarget = false,
                    onOpenGroup = { onOpenGroupDetail(group) },
                    onAddNewTabInGroup = { actions.onNewTabInGroup(group.id, false) },
                    onEditGroup = { onEditGroup(group) },
                    onBookmarkGroup = { actions.onBookmarkTabs(group.tabIds) },
                    onShareGroup = { actions.onShareTabs(group.tabIds) },
                    onUngroup = { actions.onUngroup(group.id) },
                    onCloseGroup = { actions.onCloseGroupTabs(group) }
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
            items(privateGroups, key = { "dir_priv_${it.id}" }) { group ->
                val members = group.tabIds.mapNotNull { tabsById[it] }
                TabGroupListCard(
                    group = group,
                    members = members,
                    selectedTabId = selectedTabId,
                    palette = palette,
                    isDropTarget = false,
                    onOpenGroup = { onOpenGroupDetail(group) },
                    onAddNewTabInGroup = { actions.onNewTabInGroup(group.id, true) },
                    onEditGroup = { onEditGroup(group) },
                    onBookmarkGroup = {},
                    onShareGroup = { actions.onShareTabs(group.tabIds) },
                    onUngroup = { actions.onUngroup(group.id) },
                    onCloseGroup = { actions.onCloseGroupTabs(group) }
                )
            }
        }
    }
}

@Composable
private fun GroupSectionHeader(
    title: String,
    onCreateNew: () -> Unit,
    palette: TabsTrayPalette
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title.uppercase(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp
        )
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = palette.accent.copy(alpha = 0.14f),
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onCreateNew)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.uh_ic_plus_24),
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "New group",
                    color = palette.accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
