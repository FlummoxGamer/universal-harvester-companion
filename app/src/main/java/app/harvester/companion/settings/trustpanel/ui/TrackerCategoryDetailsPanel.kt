package app.harvester.companion.settings.trustpanel.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import mozilla.components.support.ktx.kotlin.tryGetHostFromUrl
import app.harvester.companion.components.menu.compose.MenuScaffold
import app.harvester.companion.components.menu.compose.header.SubmenuHeader
import app.harvester.companion.trackingprotection.TrackerBuckets
import app.harvester.companion.trackingprotection.TrackingProtectionCategory

@Composable
internal fun TrackerCategoryDetailsPanel(
    title: String,
    url: String,
    detailedTrackerCategory: TrackingProtectionCategory?,
    bucketedTrackers: TrackerBuckets,
    onBackButtonClick: () -> Unit,
) {
    MenuScaffold(header = { SubmenuHeader(title = title, url = url, onClick = onBackButtonClick) }) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            if (detailedTrackerCategory != null) {
                Text(text = stringResource(id = detailedTrackerCategory.title), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = stringResource(id = detailedTrackerCategory.description), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "Blocked", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                bucketedTrackers.get(detailedTrackerCategory, true).forEach { tracker ->
                    Text(text = tracker.url.tryGetHostFromUrl(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
