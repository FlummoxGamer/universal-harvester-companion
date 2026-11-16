package app.harvester.companion.home

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import app.harvester.companion.R

@Composable
fun ShortcutIcon(iconUrl: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (iconUrl.isBlank()) {
        Icon(
            painter = painterResource(id = R.drawable.ic_globe),
            contentDescription = null,
            modifier = modifier.padding(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(iconUrl)
                .transformations(TransparentCornerTransformation())
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier.padding(6.dp),
            loading = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_globe),
                    contentDescription = null,
                    modifier = Modifier.padding(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            error = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_globe),
                    contentDescription = null,
                    modifier = Modifier.padding(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
    }
}
