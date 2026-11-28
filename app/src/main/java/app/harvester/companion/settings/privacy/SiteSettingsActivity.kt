package app.harvester.companion.settings.privacy

import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.UHApp
import app.harvester.companion.components.PermissionStorage
import app.harvester.companion.privacy.UhPrivacyController
import app.harvester.companion.privacy.UhShieldEngine
import app.harvester.companion.settings.PhoneFeature
import app.harvester.companion.settings.components.SettingsCategory
import app.harvester.companion.settings.components.SettingsRow
import app.harvester.companion.settings.components.UhSettingsCardGroup
import app.harvester.companion.settings.components.UhSettingsDivider
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme
import kotlinx.coroutines.launch
import mozilla.components.concept.engine.permission.SitePermissions

class SiteSettingsActivity : AppCompatActivity() {

    private data class DefaultPermItem(
        val feature: PhoneFeature,
        val title: String,
        val iconRes: Int,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        setContent {
            UHTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val permissionStorage = remember { PermissionStorage(context) }

                val shieldState by UhShieldEngine.state.collectAsState()
                val darkBypassHosts by UhPrivacyController.darkBypassHosts.collectAsState()
                val httpExceptionHosts by UhShieldEngine.httpExceptionHosts.collectAsState()
                val jsAllowAlwaysHosts by UhShieldEngine.jsAllowAlwaysHosts.collectAsState()

                var savedPermissions by remember { mutableStateOf<List<SitePermissions>>(emptyList()) }
                var searchQuery by remember { mutableStateOf("") }
                var showClearAllDialog by remember { mutableStateOf(false) }

                // Bump trigger whenever default permission values change
                var permRefreshTick by remember { mutableIntStateOf(0) }
                var editingFeature by remember { mutableStateOf<DefaultPermItem?>(null) }

                LaunchedEffect(Unit) {
                    savedPermissions = try {
                        permissionStorage.getAllSitePermissions().sortedBy { it.origin.lowercase() }
                    } catch (_: Exception) {
                        emptyList()
                    }
                }

                val defaultItems = remember {
                    listOf(
                        DefaultPermItem(PhoneFeature.CAMERA, "Camera", R.drawable.uh_ic_camera_24),
                        DefaultPermItem(PhoneFeature.MICROPHONE, "Microphone", R.drawable.ic_mic),
                        DefaultPermItem(PhoneFeature.LOCATION, "Location", R.drawable.ic_globe),
                        DefaultPermItem(PhoneFeature.NOTIFICATION, "Notifications", R.drawable.fx_ic_info_24),
                        DefaultPermItem(PhoneFeature.PERSISTENT_STORAGE, "Persistent Storage", R.drawable.fx_ic_storage_24),
                        DefaultPermItem(PhoneFeature.MEDIA_KEY_SYSTEM_ACCESS, "Protected Media (DRM)", R.drawable.fx_ic_video_24),
                    )
                }

                val actionOptions = listOf(
                    "Block" to "Deny automatically on all sites",
                    "Ask Every Time" to "Prompt when a site requests access",
                    "Allow" to "Grant automatically without prompting",
                )

                editingFeature?.let { item ->
                    val prefKey = "site_perm_${item.feature.name.lowercase()}"
                    val currentVal = SettingsPrefs.getInt(context, prefKey, 1).coerceIn(0, 2)
                    ChoiceDialogWithSummary(
                        title = "${item.title} Permission",
                        options = actionOptions,
                        selectedIndex = currentVal,
                        onSelect = { idx ->
                            SettingsPrefs.setInt(context, prefKey, idx)
                            (context.applicationContext as? UHApp)?.components?.sitePermissionRules?.notifyRulesChanged()
                            permRefreshTick++
                            editingFeature = null
                        },
                        onDismiss = { editingFeature = null },
                    )
                }

                if (showClearAllDialog) {
                    AlertDialog(
                        onDismissRequest = { showClearAllDialog = false },
                        title = { Text("Clear All Site Permissions?") },
                        text = { Text("All per-site camera, mic, location, and autoplay overrides will be reset to your default settings.") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showClearAllDialog = false
                                    scope.launch {
                                        try {
                                            permissionStorage.deleteAllSitePermissions()
                                            savedPermissions = emptyList()
                                            Toast.makeText(context, "All site permissions cleared", Toast.LENGTH_SHORT).show()
                                        } catch (_: Exception) {
                                        }
                                    }
                                },
                            ) {
                                Text("Clear All", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearAllDialog = false }) {
                                Text("Cancel")
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp),
                    )
                }

                val filteredSaved = remember(savedPermissions, searchQuery) {
                    val q = searchQuery.trim().lowercase()
                    if (q.isEmpty()) savedPermissions
                    else savedPermissions.filter { it.origin.lowercase().contains(q) }
                }

                UhSettingsScaffold(
                    title = "Site Settings",
                    onBack = { finish() },
                ) {
                    SettingsCategory("Default Site Permissions")
                    UhSettingsCardGroup {
                        defaultItems.forEachIndexed { idx, item ->
                            if (idx > 0) UhSettingsDivider()
                            val key = "site_perm_${item.feature.name.lowercase()}"
                            val currentVal = remember(permRefreshTick, key) {
                                SettingsPrefs.getInt(context, key, 1).coerceIn(0, 2)
                            }
                            SettingsRow(
                                title = item.title,
                                subtitle = actionOptions[currentVal].first,
                                iconRes = item.iconRes,
                                showChevron = true,
                                insideCard = true,
                                onClick = { editingFeature = item },
                            )
                        }
                    }

                    SettingsCategory("UH Shield Paused Sites")
                    UhSettingsCardGroup {
                        val pausedList = shieldState.pausedHosts.sorted()
                        if (pausedList.isEmpty()) {
                            EmptyExceptionRow("No paused sites — UH Shield is active everywhere.")
                        } else {
                            pausedList.forEachIndexed { idx, host ->
                                if (idx > 0) UhSettingsDivider()
                                HostExceptionRow(
                                    host = host,
                                    subtitle = "Shield paused",
                                    onRemove = {
                                        UhShieldEngine.removePausedHost(host)
                                        UhPrivacyController.pushRuntimeConfigToExtension()
                                    },
                                )
                            }
                        }
                    }

                    if (httpExceptionHosts.isNotEmpty()) {
                        SettingsCategory("HTTPS-Only Exceptions")
                        UhSettingsCardGroup {
                            httpExceptionHosts.sorted().forEachIndexed { idx, host ->
                                if (idx > 0) UhSettingsDivider()
                                HostExceptionRow(
                                    host = host,
                                    subtitle = "Allowed to load over HTTP",
                                    onRemove = {
                                        UhShieldEngine.removeHttpException(host)
                                        UhPrivacyController.applyAllToEngine(context)
                                    },
                                )
                            }
                        }
                    }

                    if (jsAllowAlwaysHosts.isNotEmpty()) {
                        SettingsCategory("Smart JavaScript Allowed Sites")
                        UhSettingsCardGroup {
                            jsAllowAlwaysHosts.sorted().forEachIndexed { idx, host ->
                                if (idx > 0) UhSettingsDivider()
                                HostExceptionRow(
                                    host = host,
                                    subtitle = "Third-party scripts always allowed",
                                    onRemove = { UhShieldEngine.revokeJsAlways(host) },
                                )
                            }
                        }
                    }

                    SettingsCategory("Saved Per-Site Permissions")
                    if (savedPermissions.size > 3) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Filter sites...") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                        )
                    }
                    UhSettingsCardGroup {
                        if (filteredSaved.isEmpty()) {
                            EmptyExceptionRow(
                                if (savedPermissions.isEmpty()) "No saved per-site permissions."
                                else "No matching sites found.",
                            )
                        } else {
                            filteredSaved.forEachIndexed { idx, perm ->
                                if (idx > 0) UhSettingsDivider()
                                HostExceptionRow(
                                    host = perm.origin,
                                    subtitle = summarizeSitePermissions(perm),
                                    onRemove = {
                                        scope.launch {
                                            try {
                                                permissionStorage.deleteSitePermissions(perm)
                                                savedPermissions = savedPermissions.filterNot { it.origin == perm.origin }
                                            } catch (_: Exception) {
                                            }
                                        }
                                    },
                                )
                            }
                            UhSettingsDivider()
                            SettingsRow(
                                title = "Clear All Saved Permissions",
                                titleColor = MaterialTheme.colorScheme.error,
                                iconRes = R.drawable.fx_ic_delete_24,
                                iconTint = MaterialTheme.colorScheme.error,
                                insideCard = true,
                                onClick = { showClearAllDialog = true },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    private fun summarizeSitePermissions(perm: SitePermissions): String {
        val parts = mutableListOf<String>()
        fun addIfSet(label: String, status: SitePermissions.Status) {
            when (status) {
                SitePermissions.Status.ALLOWED -> parts.add("$label: Allow")
                SitePermissions.Status.BLOCKED -> parts.add("$label: Block")
                else -> Unit
            }
        }
        addIfSet("Camera", perm.camera)
        addIfSet("Mic", perm.microphone)
        addIfSet("Location", perm.location)
        addIfSet("Notifications", perm.notification)
        addIfSet("Storage", perm.localStorage)
        return if (parts.isEmpty()) "Custom site rules" else parts.joinToString(" • ")
    }
}

@Composable
private fun EmptyExceptionRow(message: String) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

@Composable
private fun HostExceptionRow(
    host: String,
    subtitle: String,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = host,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        IconButton(onClick = onRemove, modifier = Modifier.size(34.dp)) {
            Icon(
                painter = painterResource(id = R.drawable.fx_ic_delete_24),
                contentDescription = "Remove",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
