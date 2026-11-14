package app.harvester.companion.settings.trustpanel.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.harvester.companion.components.menu.compose.MenuGroup
import app.harvester.companion.components.menu.compose.MenuItem
import app.harvester.companion.components.menu.compose.MenuScaffold
import app.harvester.companion.components.menu.compose.header.SubmenuHeader
import app.harvester.companion.trackingprotection.TrackerBuckets
import app.harvester.companion.trackingprotection.TrackingProtectionCategory

@Composable
internal fun TrackersBlockedPanel(
    title: String,
    url: String,
    numberOfTrackersBlocked: Int,
    numberOfTrackersBlockedThisWeek: Int,
    bucketedTrackers: TrackerBuckets,
    onTrackerCategoryClick: (TrackingProtectionCategory) -> Unit,
    onTrackersBlockedThisWeekClicked: () -> Unit,
    onBackButtonClick: () -> Unit,
) {
    MenuScaffold(
        header = {
            SubmenuHeader(title = title, url = url, onClick = onBackButtonClick)
        }
    ) {
        Column {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "$numberOfTrackersBlocked trackers blocked", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.height(8.dp))
            MenuGroup {
                TrackingProtectionCategory.entries.filter { bucketedTrackers.get(it, true).isNotEmpty() }.forEach { category ->
                    MenuItem(
                        label = "${category.name.lowercase().replaceFirstChar { it.uppercase() }} (${bucketedTrackers.get(category, true).size})",
                        beforeIconPainter = painterResource(id = category.icon),
                        onClick = { onTrackerCategoryClick(category) },
                    )
                }
            }
        }
    }
}
