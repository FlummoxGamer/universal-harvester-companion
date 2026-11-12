package app.harvester.companion.data.downloads.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import app.harvester.companion.R
import app.harvester.companion.data.downloads.UhFileItem
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun UhDownloadListItem(
    fileItem: UhFileItem,
    isSelected: Boolean,
    areIconsVisible: Boolean,
    swipingEnabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onRetryClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onShareUrlClick: () -> Unit,
    onShareFileClick: () -> Unit,
    onRenameClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val isFailed = fileItem.status == UhFileItem.Status.Failed
    val tint = if (isFailed) colorResource(id = R.color.red) else colorResource(id = R.color.subtext)
    val bgColor = if (isSelected) colorResource(id = R.color.surface_3) else colorResource(id = R.color.surface)

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it == SwipeToDismissBoxValue.EndToStart) {
                onDeleteClick()
                true
            } else false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = swipingEnabled,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colorResource(id = R.color.red))
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_delete),
                    contentDescription = "Delete",
                    tint = colorResource(id = R.color.bg)
                )
            }
        },
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(bgColor)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                
                if (fileItem.contentType?.startsWith("image/") == true) {
                    AsyncImage(
                        model = File(fileItem.filePath),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop,
                        placeholder = painterResource(id = fileItem.icon),
                        error = painterResource(id = fileItem.icon),
                        fallback = painterResource(id = fileItem.icon)
                    )
                } else {
                    Icon(
                        painter = painterResource(id = fileItem.icon),
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(40.dp)
                    )
                }
                
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(painterResource(R.drawable.mozac_ic_more_vertical_round_24), "More")
            }
            androidx.compose.material3.DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                if (fileItem.status is UhFileItem.Status.Completed) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.download_share_url)) },
                        onClick = { showMenu = false; onShareUrlClick() }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.download_share_file)) },
                        onClick = { showMenu = false; onShareFileClick() }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.download_rename_file)) },
                        onClick = { showMenu = false; onRenameClick() }
                    )
                } else {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.download_share_url)) },
                        onClick = { showMenu = false; onShareUrlClick() }
                    )
                }
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.download_delete_item), color = colorResource(id = R.color.red)) },
                    onClick = { showMenu = false; onDeleteClick() }
                )
            }
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
}
