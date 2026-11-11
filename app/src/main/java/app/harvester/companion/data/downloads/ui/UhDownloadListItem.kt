package app.harvester.companion.data.downloads.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.data.downloads.UhFileItem

@Composable
fun UhDownloadListItem(
    fileItem: UhFileItem,
    isSelected: Boolean,
    areIconsVisible: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onRetryClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isFailed = fileItem.status == UhFileItem.Status.Failed
    val tint = if (isFailed) colorResource(id = R.color.red) else colorResource(id = R.color.subtext)
    val bgColor = if (isSelected) colorResource(id = R.color.surface_3) else colorResource(id = R.color.surface)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(id = fileItem.icon),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileItem.fileName ?: fileItem.url,
                    color = colorResource(id = R.color.text),
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = fileItem.description,
                    color = tint,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
            if (areIconsVisible) {
                when (fileItem.status) {
                    is UhFileItem.Status.Downloading -> {
                        IconButton(onClick = onPauseClick) {
                            Icon(painterResource(R.drawable.ic_uh_media_pause), "Pause", tint = colorResource(id = R.color.text))
                        }
                    }
                    is UhFileItem.Status.Paused -> {
                        IconButton(onClick = onResumeClick) {
                            Icon(painterResource(R.drawable.ic_uh_media_play), "Resume", tint = colorResource(id = R.color.text))
                        }
                    }
                    is UhFileItem.Status.Failed -> {
                        IconButton(onClick = onRetryClick) {
                            Icon(painterResource(R.drawable.ic_uh_retry), "Retry", tint = colorResource(id = R.color.text))
                        }
                    }
                    else -> {}
                }
                IconButton(onClick = onDeleteClick) {
                    Icon(painterResource(R.drawable.ic_close), "Delete", tint = colorResource(id = R.color.subtext))
                }
            }
        }
        
        if (fileItem.status is UhFileItem.Status.Downloading || fileItem.status is UhFileItem.Status.Initiated) {
            Spacer(modifier = Modifier.height(8.dp))
            val progress = (fileItem.status as? UhFileItem.Status.Downloading)?.progress ?: 0f
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = colorResource(id = R.color.accent),
                trackColor = colorResource(id = R.color.surface_2)
            )
        }
    }
}
