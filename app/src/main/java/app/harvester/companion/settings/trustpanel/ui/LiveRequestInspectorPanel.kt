package app.harvester.companion.settings.trustpanel.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.privacy.RequestLogAction
import app.harvester.companion.privacy.RequestLogEntry

/**
 * Live network request inspector mounted inside the address-bar Trust Panel.
 * Displays blocked ads, trackers, CNAME-cloaked hosts, LAN scan attempts, and allowed
 * requests for the active tab, with 1-tap Allow / Block custom rule creation.
 */
@Composable
internal fun LiveRequestInspectorPanel(
    siteHost: String,
    entries: List<RequestLogEntry>,
    onAllowHost: (String) -> Unit,
    onBlockHost: (String) -> Unit,
    onReloadClick: () -> Unit,
    onBackButtonClick: () -> Unit,
) {
    // 0 = All, 1 = Blocked, 2 = Allowed
    var selectedTab by remember { mutableIntStateOf(0) }
    var overriddenHosts by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    val blockedCount = remember(entries) { entries.count { it.action.isBlocked } }
    val allowedCount = remember(entries) { entries.size - blockedCount }
    val filteredEntries = remember(entries, selectedTab) {
        when (selectedTab) {
            1 -> entries.filter { it.action.isBlocked }
            2 -> entries.filter { !it.action.isBlocked }
            else -> entries
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 20.dp),
    ) {
        // Header bar with Back + Title + Reload
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBackButtonClick) {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_back_24),
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Live Request Inspector",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (siteHost.isNotBlank()) {
                    Text(
                        text = siteHost,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onReloadClick) {
                Icon(
                    painter = painterResource(id = R.drawable.uh_ic_reload_24),
                    contentDescription = "Reload Page",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Filter selector (All / Blocked / Allowed)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InspectorTabItem(
                label = "All (${entries.size})",
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                modifier = Modifier.weight(1f),
            )
            InspectorTabItem(
                label = "Blocked ($blockedCount)",
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                modifier = Modifier.weight(1f),
            )
            InspectorTabItem(
                label = "Allowed ($allowedCount)",
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (filteredEntries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (entries.isEmpty()) {
                        "No requests recorded yet on this page. Tap Reload to inspect live traffic."
                    } else {
                        "No requests match this filter."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filteredEntries, key = { it.id }) { entry ->
                    val overrideState = overriddenHosts[entry.reqHost]
                    RequestLogRow(
                        entry = entry,
                        overrideAllowed = overrideState,
                        onAllow = {
                            overriddenHosts = overriddenHosts + (entry.reqHost to true)
                            onAllowHost(entry.reqHost)
                        },
                        onBlock = {
                            overriddenHosts = overriddenHosts + (entry.reqHost to false)
                            onBlockHost(entry.reqHost)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun InspectorTabItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RequestLogRow(
    entry: RequestLogEntry,
    overrideAllowed: Boolean?,
    onAllow: () -> Unit,
    onBlock: () -> Unit,
) {
    val (tagBg, tagFg) = when (entry.action) {
        RequestLogAction.BLOCKED_AD -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        RequestLogAction.BLOCKED_TRACKER -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        RequestLogAction.BLOCKED_CNAME -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        RequestLogAction.BLOCKED_LAN -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.onError
        RequestLogAction.ALLOWED -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }

    val metaText = buildString {
        if (entry.resourceType.isNotBlank()) append(entry.resourceType)
        if (entry.pathHint.isNotBlank()) {
            if (isNotEmpty()) append(" • ")
            append(entry.pathHint)
        }
        if (entry.ruleSource.isNotBlank()) {
            if (isNotEmpty()) append(" • ")
            append(entry.ruleSource)
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = 0.75.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(tagBg)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = entry.action.label,
                    color = tagFg,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.reqHost.ifBlank { "unknown" },
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (metaText.isNotBlank()) {
                    Text(
                        text = metaText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
            when {
                overrideAllowed == true -> {
                    Text(
                        text = "Allowed",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
                overrideAllowed == false -> {
                    Text(
                        text = "Blocked",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
                entry.action.isBlocked -> {
                    TextButton(
                        onClick = onAllow,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "Allow",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                else -> {
                    TextButton(
                        onClick = onBlock,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "Block",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
