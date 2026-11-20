package app.harvester.companion.data.downloads.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.UHLog
import app.harvester.companion.core.downloads.DownloadLocationResolver
import app.harvester.companion.settings.components.SettingsCategory
import app.harvester.companion.settings.components.SettingsRow
import app.harvester.companion.settings.components.SettingsSwitchRow
import app.harvester.companion.settings.components.UhSettingsCardGroup
import app.harvester.companion.settings.components.UhSettingsDivider
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.settings.downloads.DefaultAndroidFileUtils
import app.harvester.companion.settings.downloads.DefaultDownloadLocationFormatter
import app.harvester.companion.settings.downloads.MissingUriPermission

@Composable
fun UhDownloadSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var deleteBehavior by remember { mutableStateOf(SettingsPrefs.getString(context, SettingsPrefs.K_DELETE_BEHAVIOR, "ask")) }
    val customDownloadUri by SettingsPrefs.customDownloadUriFlow(context).collectAsState()
    val downloadLocationFormatter = remember { DefaultDownloadLocationFormatter(DefaultAndroidFileUtils(context)) }

    BackHandler(onBack = onBack)

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                SettingsPrefs.setString(context, "custom_download_uri", uri.toString())
            } catch (e: SecurityException) {
                UHLog.throwable("download_error", "Failed to persist custom download folder permission", e)
            }
        }
    }

    val currentPath = customDownloadUri.ifBlank {
        DownloadLocationResolver.resolveDefaultConfiguredDirectory(context)
    }
    val displayPath = try {
        downloadLocationFormatter.getFriendlyPath(currentPath)
    } catch (_: MissingUriPermission) {
        DownloadLocationResolver.resolveDefaultConfiguredDirectory(context)
    }

    val stat = android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path)
    val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
    val totalBytes = stat.blockCountLong * stat.blockSizeLong
    val usedBytes = totalBytes - freeBytes
    val progress = if (totalBytes > 0L) usedBytes.toFloat() / totalBytes.toFloat() else 0f
    val freeGb = freeBytes / (1024f * 1024f * 1024f)

    UhSettingsScaffold(
        title = "Download Settings",
        onBack = onBack
    ) {
        SettingsCategory("General")
        UhSettingsCardGroup {
            SettingsSwitchRow(
                title = "Ask before downloading",
                subtitle = "Choose filename and folder before saving",
                key = SettingsPrefs.K_ASK_DL,
                default = true,
                iconRes = R.drawable.fx_ic_downloads_24,
                insideCard = true
            )
        }

        SettingsCategory("Storage")
        UhSettingsCardGroup {
            SettingsRow(
                title = "Download Location",
                subtitle = displayPath,
                iconRes = R.drawable.fx_ic_folder_24,
                showChevron = true,
                insideCard = true,
                onClick = {
                    try {
                        folderLauncher.launch(null)
                    } catch (e: Exception) {
                        UHLog.throwable("download_error", "Failed opening folder picker", e)
                    }
                }
            )
            UhSettingsDivider()
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text("Internal Storage", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(CircleShape),
                    color = if (progress > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Text("${String.format("%.1f", freeGb)} GB free", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }

        SettingsCategory("Delete Behavior")
        UhSettingsCardGroup {
            DeleteBehaviorRow("Delete from device", "Permanently remove the file", deleteBehavior == "device") {
                deleteBehavior = "device"
                SettingsPrefs.setString(context, SettingsPrefs.K_DELETE_BEHAVIOR, "device")
            }
            UhSettingsDivider()
            DeleteBehaviorRow("Remove from history", "Keep the file on your device", deleteBehavior == "history") {
                deleteBehavior = "history"
                SettingsPrefs.setString(context, SettingsPrefs.K_DELETE_BEHAVIOR, "history")
            }
            UhSettingsDivider()
            DeleteBehaviorRow("Ask every time", "Show a prompt when deleting", deleteBehavior == "ask") {
                deleteBehavior = "ask"
                SettingsPrefs.setString(context, SettingsPrefs.K_DELETE_BEHAVIOR, "ask")
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun DeleteBehaviorRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}
