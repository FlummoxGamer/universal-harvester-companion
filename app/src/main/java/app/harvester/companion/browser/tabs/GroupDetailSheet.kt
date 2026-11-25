package app.harvester.companion.browser.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import mozilla.components.browser.state.state.TabSessionState

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
    val groupColor = Color(group.colorArgb)
    var contextMenuTabId by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
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
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${members.size} ${if (members.size == 1) "tab" else "tabs"}" +
                            if (group.isPrivate) " · Private group" else "",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }

                IconButton(onClick = onEditGroup) {
                    Icon(
                        painter = painterResource(id = R.drawable.fx_ic_edit_24),
                        contentDescription = "Edit group",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = groupColor,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
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
                        tint = MaterialTheme.colorScheme.onSurface,
                        onClick = { actions.onBookmarkTabs(group.tabIds) },
                        modifier = Modifier.weight(1f)
                    )
                }
                GroupActionChip(
                    iconRes = R.drawable.uh_ic_share_24,
                    label = "Share",
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = { actions.onShareTabs(group.tabIds) },
                    modifier = Modifier.weight(1f)
                )
                GroupActionChip(
                    iconRes = R.drawable.ic_arrow_out,
                    label = "Ungroup",
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = {
                        actions.onUngroup(group.id)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                )
                GroupActionChip(
                    iconRes = R.drawable.fx_ic_delete_24,
                    label = "Close",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = {
                        actions.onCloseGroupTabs(group)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f),
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 460.dp)
            ) {
                items(members, key = { "gmem_${it.id}" }) { tab ->
                    val isMuted = TabMetaStore.isTabMuted(tab) || (tab.id in mutedIdSet)
                    TabGridCard(
                        tab = tab,
                        group = group,
                        palette = palette,
                        isSelected = tab.id == selectedTabId,
                        isPinned = tab.id in pinnedIdSet,
                        isMuted = isMuted,
                        isSleeping = TabMetaStore.isTabSleeping(tab, selectedTabId),
                        isMultiSelect = false,
                        isChecked = false,
                        isDropTarget = false,
                        isBeingDragged = false,
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
                        onShare = { actions.onShareTabs(listOf(tab.id)) }
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
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
