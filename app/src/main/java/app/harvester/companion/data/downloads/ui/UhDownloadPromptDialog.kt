package app.harvester.companion.data.downloads.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.SettingsPrefs
import androidx.compose.ui.platform.LocalContext

@Composable
fun UhDownloadPromptDialog(
    fileName: String,
    fileSize: String,
    fileUrl: String,
    onDismiss: () -> Unit,
    onDownload: (String) -> Unit
) {
    val context = LocalContext.current
    var currentName by remember { mutableStateOf(fileName) }
    var showRenameDialog by remember { mutableStateOf(false) }
    
    val locationPath = SettingsPrefs.getString(context, "custom_download_uri", "").ifBlank { "/storage/emulated/0/Download" }

    if (showRenameDialog) {
        var renameText by remember { mutableStateOf(currentName) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename File") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) currentName = renameText
                    showRenameDialog = false
                }) { Text("Save", color = colorResource(id = R.color.accent)) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colorResource(id = R.color.surface_2),
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "UH Downloader",
                    color = colorResource(id = R.color.accent),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colorResource(id = R.color.surface_3)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_uh_file_default),
                            contentDescription = null,
                            tint = colorResource(id = R.color.text)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentName,
                            color = colorResource(id = R.color.text),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            text = fileSize,
                            color = colorResource(id = R.color.subtext),
                            fontSize = 13.sp
                        )
                    }
                    Icon(
                        painter = painterResource(id = R.drawable.ic_pencil),
                        contentDescription = "Rename",
                        tint = colorResource(id = R.color.subtext),
                        modifier = Modifier
                            .size(24.dp)
                            .clickable { showRenameDialog = true }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colorResource(id = R.color.surface_3))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_uh_file_download),
                        contentDescription = null,
                        tint = colorResource(id = R.color.text)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Download", color = colorResource(id = R.color.text), fontSize = 15.sp)
                        Text(locationPath, color = colorResource(id = R.color.subtext), fontSize = 12.sp)
                    }
                    Icon(
                        painter = painterResource(id = R.drawable.ic_forward),
                        contentDescription = "Download",
                        tint = colorResource(id = R.color.accent),
                        modifier = Modifier
                            .size(24.dp)
                            .clickable { onDownload(currentName) }
                    )
                }
            }
        },
        confirmButton = {}
    )
}
