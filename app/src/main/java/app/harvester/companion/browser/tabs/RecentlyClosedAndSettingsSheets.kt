package app.harvester.companion.browser.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecentlyClosedSheet(
    records: List<ClosedTabRecord>,
    onRestoreRecord: (ClosedTabRecord) -> Unit,
    onRestoreAll: () -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp)
                .padding(bottom = 22.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recently Closed Tabs",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (records.isNotEmpty()) {
                    TextButton(onClick = {
                        onRestoreAll()
                        onDismiss()
                    }) {
                        Text("Restore all", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = onClearAll) {
                        Text("Clear", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (records.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No recently closed tabs",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(records, key = { it.id }) { rec ->
                        val host = remember(rec.url) { extractHostLabel(rec.url) }
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onRestoreRecord(rec) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ac_ic_arrow_counter_clockwise_24),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = rec.title.ifBlank { host.ifBlank { rec.url } },
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
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
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TabSettingsSheet(
    isListView: Boolean,
    inactiveDays: Int,
    autoCloseDays: Int,
    sleepSeconds: Int,
    jumpToParent: Boolean,
    openNextToCurrent: Boolean,
    groupStripEnabled: Boolean,
    landscapeStripEnabled: Boolean,
    blockPrivateScreenshots: Boolean,
    privateLockEnabled: Boolean,
    canUseBiometricLock: Boolean,
    onViewModeChange: (Boolean) -> Unit,
    onInactiveDaysChange: (Int) -> Unit,
    onAutoCloseDaysChange: (Int) -> Unit,
    onSleepSecondsChange: (Int) -> Unit,
    onJumpToParentChange: (Boolean) -> Unit,
    onOpenNextToCurrentChange: (Boolean) -> Unit,
    onGroupStripChange: (Boolean) -> Unit,
    onLandscapeStripChange: (Boolean) -> Unit,
    onBlockPrivateScreenshotsChange: (Boolean) -> Unit,
    onPrivateLockChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 18.dp)
                .padding(bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Tab Settings",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold
            )

            // Layout mode
            SettingsChipGroupCard(
                title = "Tab Switcher Layout",
                subtitle = "Choose how open tabs are displayed",
                options = listOf(false to "Grid", true to "List"),
                selected = isListView,
                onSelect = onViewModeChange
            )

            // In-Browser Bars & Navigation
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TabSettingToggleRow(
                        title = "Group tab strip",
                        subtitle = "Show quick group switcher strip near address bar",
                        checked = groupStripEnabled,
                        onCheckedChange = onGroupStripChange
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)
                    )
                    TabSettingToggleRow(
                        title = "Landscape horizontal tab strip",
                        subtitle = "Show a compact desktop-style tab bar in landscape",
                        checked = landscapeStripEnabled,
                        onCheckedChange = onLandscapeStripChange
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)
                    )
                    TabSettingToggleRow(
                        title = "Jump to opener tab on close",
                        subtitle = "Return to the parent tab when closing a child tab",
                        checked = jumpToParent,
                        onCheckedChange = onJumpToParentChange
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)
                    )
                    TabSettingToggleRow(
                        title = "Open new tabs next to current",
                        subtitle = if (openNextToCurrent) "Inserted right beside active tab" else "Appended to the end of your tabs",
                        checked = openNextToCurrent,
                        onCheckedChange = onOpenNextToCurrentChange
                    )
                }
            }

            // Lifecycle Thresholds
            SettingsChipGroupCard(
                title = "Inactive Tabs",
                subtitle = "Tuck untouched normal tabs into the collapsible Inactive section",
                options = listOf(0 to "Off", 7 to "7 days", 14 to "14 days", 30 to "30 days"),
                selected = inactiveDays,
                onSelect = onInactiveDaysChange
            )

            SettingsChipGroupCard(
                title = "Auto-Close Old Tabs",
                subtitle = "Automatically archive unpinned, ungrouped tabs to Recently Closed",
                options = listOf(0 to "Never", 1 to "1 day", 7 to "1 week", 30 to "1 month"),
                selected = autoCloseDays,
                onSelect = onAutoCloseDaysChange
            )

            SettingsChipGroupCard(
                title = "Auto-Sleep Background Tabs",
                subtitle = "Suspend background tab engines to save RAM & battery",
                options = listOf(0 to "Never", 30 to "30s", 120 to "2m", 300 to "5m"),
                selected = sleepSeconds,
                onSelect = onSleepSecondsChange
            )

            // Private Browsing Security
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TabSettingToggleRow(
                        title = "Block screenshots in Private mode",
                        subtitle = "Prevent screenshots and hide preview in Android recents",
                        checked = blockPrivateScreenshots,
                        onCheckedChange = onBlockPrivateScreenshotsChange
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)
                    )
                    TabSettingToggleRow(
                        title = "Lock Private tabs when leaving app",
                        subtitle = if (canUseBiometricLock) {
                            "Require fingerprint, face, or device PIN to reopen Private tabs"
                        } else {
                            "Set up a device screen lock in Android Settings to enable"
                        },
                        checked = privateLockEnabled && canUseBiometricLock,
                        enabled = canUseBiometricLock,
                        onCheckedChange = onPrivateLockChange
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun <T> SettingsChipGroupCard(
    title: String,
    subtitle: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                options.forEach { (value, label) ->
                    val isSelected = value == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(11.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                            .clickable { onSelect(value) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabSettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.6f),
                fontSize = 12.sp
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}
