package app.harvester.companion.search.awesomebar.internal

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.harvester.companion.R
import mozilla.components.support.ktx.util.URLStringUtils

@Composable
internal fun CurrentTabDetails(
    currentTabData: CurrentTabData,
    onInteraction: (CurrentTabDetailsInteractions) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(28.dp),
            )
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable { onInteraction(CurrentTabDetailsInteractions.DetailsClicked) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val icon = currentTabData.icon
            Image(
                painter = if (icon == null) painterResource(R.drawable.ic_globe) else remember(icon) { BitmapPainter(icon.asImageBitmap()) },
                contentDescription = null,
                colorFilter = if (icon == null) ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant) else null,
                modifier = Modifier.padding(8.dp).size(24.dp),
            )
            Column {
                if (currentTabData.title.isNotBlank()) {
                    Text(
                        text = currentTabData.title,
                        style = MaterialTheme.typography.bodyLarge,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = URLStringUtils.toDisplayUrl(currentTabData.url).toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        IconButton(onClick = { onInteraction(CurrentTabDetailsInteractions.ShareClicked) }) {
            Icon(
                painter = painterResource(R.drawable.ic_ext_on),
                contentDescription = "Share",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(onClick = { onInteraction(CurrentTabDetailsInteractions.CopyClicked) }) {
            Icon(
                painter = painterResource(R.drawable.mozac_ic_copy_24),
                contentDescription = "Copy",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(onClick = { onInteraction(CurrentTabDetailsInteractions.EditClicked) }) {
            Icon(
                painter = painterResource(R.drawable.mozac_ic_edit_24),
                contentDescription = "Edit",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

data class CurrentTabData(
    val title: String,
    val url: String,
    val icon: Bitmap?,
)

sealed class CurrentTabDetailsInteractions {
    data object DetailsClicked : CurrentTabDetailsInteractions()
    data object ShareClicked : CurrentTabDetailsInteractions()
    data object CopyClicked : CurrentTabDetailsInteractions()
    data object EditClicked : CurrentTabDetailsInteractions()
}
