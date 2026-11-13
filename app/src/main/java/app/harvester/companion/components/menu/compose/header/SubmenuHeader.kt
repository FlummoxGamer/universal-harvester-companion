package app.harvester.companion.components.menu.compose.header

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import mozilla.components.support.ktx.kotlin.tryGetHostFromUrl
import app.harvester.companion.R

@Composable
internal fun SubmenuHeader(
    title: String,
    url: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(start = 8.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.IconButton(onClick = onClick) {
            Icon(painter = painterResource(id = R.drawable.ic_back), contentDescription = "Back")
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(text = title.ifEmpty { url.tryGetHostFromUrl() }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (title.isNotEmpty()) {
                Text(text = url.tryGetHostFromUrl(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
