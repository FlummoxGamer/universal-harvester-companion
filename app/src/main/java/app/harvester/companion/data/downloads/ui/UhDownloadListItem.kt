package app.harvester.companion.data.downloads.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
    var showMenu by remember { mutableStateOf(false) }
    val isFailed = fileItem.status == UhFileItem.Status.Failed
    val isWaiting = fileItem.status is UhFileItem.Status.WaitingForNetwork
    val subtitleColor = when {
        isFailed -> MaterialTheme.colorScheme.error
        isWaiting -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val bgColor = if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface

    val dismissState = rememberSwipeToDismissBoxState()

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onDeleteClick()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = swipingEnabled,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.error)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_delete_24),
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.background
                )
            }
        },
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(bgColor)
                .then(
                    if (isSelected) {
                        Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp))
                    } else Modifier
                )
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.fx_ic_check_24),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    } else if (fileItem.status is UhFileItem.Status.Completed && fileItem.contentType?.startsWith("image/") == true) {
                        AsyncImage(
                            model = if (fileItem.filePath.startsWith("content://")) fileItem.filePath else File(fileItem.filePath),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            placeholder = painterResource(id = fileItem.icon),
                            error = painterResource(id = fileItem.icon),
                            fallback = painterResource(id = fileItem.icon)
                        )
                    } else {
                        Icon(
                            painter = painterResource(id = if (isWaiting) R.drawable.fx_ic_waiting_network_24 else fileItem.icon),
                            contentDescription = null,
                            tint = if (isWaiting) MaterialTheme.colorScheme.primary else if (isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = fileItem.fileName ?: fileItem.url,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = fileItem.description,
                        color = subtitleColor,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (areIconsVisible) {
                    when (fileItem.status) {
                        is UhFileItem.Status.Downloading,
                        is UhFileItem.Status.Initiated -> {
                            IconButton(onClick = onPauseClick, modifier = Modifier.size(40.dp)) {
                                Icon(
                                    painterResource(R.drawable.fx_ic_pause_24),
                                    contentDescription = stringResource(R.string.download_pause_action),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        is UhFileItem.Status.Paused -> {
                            IconButton(onClick = onResumeClick, modifier = Modifier.size(40.dp)) {
                                Icon(
                                    painterResource(R.drawable.fx_ic_play_24),
                                    contentDescription = stringResource(R.string.download_resume_action),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        is UhFileItem.Status.WaitingForNetwork -> {
                            IconButton(onClick = onResumeClick, modifier = Modifier.size(40.dp)) {
                                Icon(
                                    painterResource(R.drawable.fx_ic_waiting_network_24),
                                    contentDescription = stringResource(R.string.download_waiting_for_network_status),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        is UhFileItem.Status.Failed -> {
                            IconButton(onClick = onRetryClick, modifier = Modifier.size(40.dp)) {
                                Icon(
                                    painterResource(R.drawable.fx_ic_retry_24),
                                    contentDescription = stringResource(R.string.download_retry_action),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        else -> {}
                    }

                    Box {
                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(40.dp)) {
                            Icon(
                                painterResource(R.drawable.fx_ic_more_24),
                                contentDescription = stringResource(R.string.content_description_menu),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            shape = RoundedCornerShape(16.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            if (fileItem.status is UhFileItem.Status.Completed) {
                                key("share_url") {
                                    DropdownMenuItem(
                                        leadingIcon = { Icon(painterResource(R.drawable.fx_ic_link_24), null, modifier = Modifier.size(18.dp)) },
                                        text = { Text(stringResource(R.string.download_share_url)) },
                                        onClick = { showMenu = false; onShareUrlClick() }
                                    )
                                }
                                key("share_file") {
                                    DropdownMenuItem(
                                        leadingIcon = { Icon(painterResource(R.drawable.fx_ic_share_24), null, modifier = Modifier.size(18.dp)) },
                                        text = { Text(stringResource(R.string.download_share_file)) },
                                        onClick = { showMenu = false; onShareFileClick() }
                                    )
                                }
                                key("rename") {
                                    DropdownMenuItem(
                                        leadingIcon = { Icon(painterResource(R.drawable.fx_ic_edit_24), null, modifier = Modifier.size(18.dp)) },
                                        text = { Text(stringResource(R.string.download_rename_file)) },
                                        onClick = { showMenu = false; onRenameClick() }
                                    )
                                }
                            } else {
                                key("share_url_not_completed") {
                                    DropdownMenuItem(
                                        leadingIcon = { Icon(painterResource(R.drawable.fx_ic_link_24), null, modifier = Modifier.size(18.dp)) },
                                        text = { Text(stringResource(R.string.download_share_url)) },
                                        onClick = { showMenu = false; onShareUrlClick() }
                                    )
                                }
                            }
                            key("delete") {
                                DropdownMenuItem(
                                    leadingIcon = {
                                        Icon(
                                            painterResource(R.drawable.fx_ic_delete_24),
                                            null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    text = { Text(stringResource(R.string.download_delete_item), color = MaterialTheme.colorScheme.error) },
                                    onClick = { showMenu = false; onDeleteClick() }
                                )
                            }
                        }
                    }
                }
            }

            val barProgress: Float? = when (val st = fileItem.status) {
                is UhFileItem.Status.Downloading -> st.progress
                is UhFileItem.Status.Paused -> st.progress
                is UhFileItem.Status.WaitingForNetwork -> st.progress
                is UhFileItem.Status.Initiated -> 0f
                else -> null
            }

            if (barProgress != null) {
                Spacer(modifier = Modifier.height(10.dp))
                val barColor = when (fileItem.status) {
                    is UhFileItem.Status.Paused -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                    is UhFileItem.Status.WaitingForNetwork -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    else -> MaterialTheme.colorScheme.primary
                }
                LinearProgressIndicator(
                    progress = { barProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                    color = barColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}
