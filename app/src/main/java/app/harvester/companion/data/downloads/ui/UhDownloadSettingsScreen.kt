package app.harvester.companion.data.downloads.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.settings.components.SettingsCategory
import app.harvester.companion.settings.components.SettingsRow
import app.harvester.companion.settings.components.SettingsSwitchRow
import app.harvester.companion.settings.downloads.DefaultAndroidFileUtils
import app.harvester.companion.settings.downloads.DefaultDownloadLocationFormatter
import app.harvester.companion.settings.downloads.MissingUriPermission
import app.harvester.companion.theme.UHTheme

@Composable
fun UhDownloadSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var askBeforeDownload by remember { mutableStateOf(SettingsPrefs.getBool(context, SettingsPrefs.K_ASK_DL, true)) }
    var deleteBehavior by remember { mutableStateOf(SettingsPrefs.getString(context, SettingsPrefs.K_DELETE_BEHAVIOR, "ask")) }
    
    val downloadLocationFormatter = remember { DefaultDownloadLocationFormatter(DefaultAndroidFileUtils(context)) }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            SettingsPrefs.setString(context, "custom_download_uri", uri.toString())
        }
    }

    UHTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colorResource(id = R.color.bg))
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(id = R.drawable.ic_back), contentDescription = "Back", tint = colorResource(id = R.color.text))
                }
                Text(
                    text = "Download Settings",
                    color = colorResource(id = R.color.text),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
            ) {
                SettingsCategory("General")
                SettingsSwitchRow("Ask before downloading", SettingsPrefs.K_ASK_DL, default = true)

                SettingsCategory("Storage")
                
                val currentPath = SettingsPrefs.getString(context, "custom_download_uri", "").ifBlank { "/storage/emulated/0/Download" }
                val displayPath = try {
                    downloadLocationFormatter.getFriendlyPath(currentPath)
                } catch (e: MissingUriPermission) {
                    "/storage/emulated/0/Download"
                }
                
                SettingsRow(
                    title = "Download Location",
                    subtitle = displayPath,
                    onClick = { folderLauncher.launch(null) }
                )

                val stat = android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path)
                val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
                val totalBytes = stat.blockCountLong * stat.blockSizeLong
                val usedBytes = totalBytes - freeBytes
                val progress = usedBytes.toFloat() / totalBytes.toFloat()

                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text("Internal Storage", color = colorResource(id = R.color.text), fontSize = 14.sp)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        color = if (progress > 0.9f) colorResource(id = R.color.red) else colorResource(id = R.color.accent),
                        trackColor = colorResource(id = R.color.surface_2)
                    )
                    val freeGb = freeBytes / (1024f * 1024f * 1024f)
                    Text("${String.format("%.1f", freeGb)} GB free", color = colorResource(id = R.color.subtext), fontSize = 12.sp)
                }

                SettingsCategory("Delete Behavior")
                Text("Choose what happens when you delete a download.", color = colorResource(id = R.color.subtext), fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                
                DeleteBehaviorRow("Delete from device", "Permanently remove the file", deleteBehavior == "device") {
                    deleteBehavior = "device"
                    SettingsPrefs.setString(context, SettingsPrefs.K_DELETE_BEHAVIOR, "device")
                }
                DeleteBehaviorRow("Remove from history", "Keep the file on your device", deleteBehavior == "history") {
                    deleteBehavior = "history"
                    SettingsPrefs.setString(context, SettingsPrefs.K_DELETE_BEHAVIOR, "history")
                }
                DeleteBehaviorRow("Ask every time", "Show a prompt when deleting", deleteBehavior == "ask") {
                    deleteBehavior = "ask"
                    SettingsPrefs.setString(context, SettingsPrefs.K_DELETE_BEHAVIOR, "ask")
                }
            }
        }
    }
}

@Composable
private fun DeleteBehaviorRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(title, color = colorResource(id = R.color.text), fontSize = 15.sp)
            Text(subtitle, color = colorResource(id = R.color.subtext), fontSize = 12.sp)
        }
    }
}
