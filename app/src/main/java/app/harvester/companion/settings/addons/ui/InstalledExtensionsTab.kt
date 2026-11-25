package app.harvester.companion.settings.addons.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.UHBrand
import app.harvester.companion.settings.addons.ExtensionInstallTask
import app.harvester.companion.settings.addons.ExtensionUpdateInfo
import app.harvester.companion.settings.addons.InstalledExtensionItem

@Composable
fun InstalledExtensionsTab(
    installedExtensions: List<InstalledExtensionItem>,
    activeTasks: Map<String, ExtensionInstallTask>,
    availableUpdates: Map<String, ExtensionUpdateInfo>,
    isCheckingUpdates: Boolean,
    onCheckAllUpdates: () -> Unit,
    onUpdateAll: () -> Unit,
    onUpdateSingle: (String) -> Unit,
    onCancelInstall: (String) -> Unit,
    onToggleExtensionEnabled: (InstalledExtensionItem, Boolean) -> Unit,
    onOpenExtensionDetails: (InstalledExtensionItem) -> Unit,
    onOpenExtensionOptions: (String) -> Unit,
    onExploreCatalog: () -> Unit,
) {
    val userExtensions = installedExtensions
    val enabledExtensions = userExtensions.filter { it.enabled }
    val disabledExtensions = userExtensions.filter { !it.enabled }
    val activeTaskList = activeTasks.values.toList()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {

        // Updates Banner or Check for Updates row
        if (availableUpdates.isNotEmpty()) {
            item(key = "updates_available_banner") {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.fx_ic_update_24),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${availableUpdates.size} update${if (availableUpdates.size == 1) "" else "s"} available",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = availableUpdates.values.joinToString(", ") { it.extensionName },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onUpdateAll,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("Update All", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        } else if (userExtensions.isNotEmpty()) {
            item(key = "check_updates_row") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${userExtensions.size} installed extension${if (userExtensions.size == 1) "" else "s"}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    TextButton(
                        onClick = onCheckAllUpdates,
                        enabled = !isCheckingUpdates,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        if (isCheckingUpdates) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(13.dp),
                                strokeWidth = 1.8.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Checking…", fontSize = 12.sp)
                        } else {
                            Icon(
                                painter = painterResource(id = R.drawable.fx_ic_update_24),
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Check for Updates", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // Active install / sideload tasks
        if (activeTaskList.isNotEmpty()) {
            item(key = "active_tasks_header") {
                SectionLabel("In Progress")
            }
            items(activeTaskList, key = { "task_${it.key}" }) { task ->
                ActiveInstallTaskCard(
                    task = task,
                    onCancel = { onCancelInstall(task.key) }
                )
            }
        }

        // Enabled extensions
        if (enabledExtensions.isNotEmpty()) {
            item(key = "enabled_header") {
                SectionLabel("Enabled (${enabledExtensions.size})")
            }
            items(enabledExtensions, key = { it.id }) { item ->
                InstalledExtensionCard(
                    item = item,
                    isUpdating = activeTasks.containsKey(item.id),
                    onClick = { onOpenExtensionDetails(item) },
                    onToggleEnabled = { onToggleExtensionEnabled(item, it) },
                    onUpdate = { onUpdateSingle(item.id) },
                    onOpenOptions = item.optionsPageUrl?.takeIf { it.isNotBlank() }?.let { url ->
                        { onOpenExtensionOptions(url) }
                    }
                )
            }
        }

        // Disabled extensions
        if (disabledExtensions.isNotEmpty()) {
            item(key = "disabled_header") {
                SectionLabel("Disabled (${disabledExtensions.size})")
            }
            items(disabledExtensions, key = { it.id }) { item ->
                InstalledExtensionCard(
                    item = item,
                    isUpdating = activeTasks.containsKey(item.id),
                    onClick = { onOpenExtensionDetails(item) },
                    onToggleEnabled = { onToggleExtensionEnabled(item, it) },
                    onUpdate = { onUpdateSingle(item.id) },
                    onOpenOptions = item.optionsPageUrl?.takeIf { it.isNotBlank() }?.let { url ->
                        { onOpenExtensionOptions(url) }
                    }
                )
            }
        }

        // Empty state when no third-party extensions are installed yet
        if (userExtensions.isEmpty() && activeTaskList.isEmpty()) {
            item(key = "installed_empty_state") {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.fx_ic_extensions_24),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(34.dp)
                        )
                        Text(
                            text = "No extensions installed",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Browse curated extensions or sideload a .xpi file to customize ${UHBrand.NAME}.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        FilledTonalButton(
                            onClick = onExploreCatalog,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Explore ${UHBrand.NAME} Extensions", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

@Composable
private fun ActiveInstallTaskCard(
    task: ExtensionInstallTask,
    onCancel: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = task.stage.label,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp
                )
            }
            TextButton(onClick = onCancel) {
                Text("Cancel", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun InstalledExtensionCard(
    item: InstalledExtensionItem,
    isUpdating: Boolean,
    onClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onUpdate: () -> Unit,
    onOpenOptions: (() -> Unit)?,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                InstalledExtensionIcon(extension = item.extensionRef, sizeDp = 42)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = item.name,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (item.allowedInPrivateBrowsing) {
                            Icon(
                                painter = painterResource(id = R.drawable.uh_ic_private_mode_24),
                                contentDescription = "Allowed in Private Browsing",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = buildString {
                            if (item.version.isNotBlank()) append("v${item.version}")
                            if (item.creatorName.isNotBlank()) {
                                if (isNotEmpty()) append(" · ")
                                append(item.creatorName)
                            }
                            if (item.isUnsupported) {
                                if (isNotEmpty()) append(" · ")
                                append("Unsupported")
                            }
                        }.ifBlank { item.id },
                        color = if (item.isUnsupported) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (onOpenOptions != null && item.enabled) {
                    IconButton(
                        onClick = onOpenOptions,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.fx_ic_settings_24),
                            contentDescription = "Extension settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Switch(
                    checked = item.enabled,
                    onCheckedChange = onToggleEnabled
                )
            }

            if (item.updateInfo != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.09f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Update available: v${item.updateInfo.latestVersion}",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (isUpdating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            text = "Update →",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable(onClick = onUpdate)
                        )
                    }
                }
            }
        }
    }
}
