package app.harvester.companion.browser.menu

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.GeckoWebExtensionBridge
import app.harvester.companion.MenuCommandStore
import app.harvester.companion.R
import app.harvester.companion.SessionRecorder
import app.harvester.companion.UserscriptManager
import app.harvester.companion.UserscriptStore
import app.harvester.companion.settings.addons.ScriptEditorActivity
import app.harvester.companion.settings.addons.ScriptsActivity
import java.net.URI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptsSheet(
    currentUrl: String,
    onOpenCaptureSheet: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var refreshTick by remember { mutableIntStateOf(0) }
    var showAllScripts by remember { mutableStateOf(false) }
    var showPasteDialog by remember { mutableStateOf(false) }

    val domain = remember(currentUrl) {
        try {
            URI(currentUrl).host?.removePrefix("www.").orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    val allScripts = remember(refreshTick) { UserscriptManager.loadScripts(context) }
    val matchedScripts = remember(currentUrl, refreshTick, allScripts) {
        if (currentUrl.isBlank() || currentUrl == "about:blank") emptyList()
        else UserscriptManager.matchingScripts(context, currentUrl)
    }
    val commands = remember(refreshTick) { MenuCommandStore.all() }
    val isCaptureActive = remember(refreshTick) { SessionRecorder.isActive() }

    if (showPasteDialog) {
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text("Install Userscript", color = MaterialTheme.colorScheme.onSurface) },
            text = {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    placeholder = {
                        Text("// ==UserScript==\n// @name My Script\n// @match *://*/*\n// ==/UserScript==")
                    },
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPasteDialog = false
                        if (code.isNotBlank() && UserscriptManager.installFromText(context, code)) {
                            GeckoWebExtensionBridge.reloadScriptsOnActiveTab()
                            Toast.makeText(context, "Script installed", Toast.LENGTH_SHORT).show()
                            refreshTick++
                        } else {
                            Toast.makeText(context, "Invalid or empty script", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Install", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasteDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(20.dp)
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 6.dp)
                    .navigationBarsPadding()
            ) {
                // Header: title + domain badge + capture quick toggle pill
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Page Scripts",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (domain.isNotBlank()) {
                            Text(
                                text = domain,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isCaptureActive) Color(0xFF4ADE80).copy(alpha = 0.16f)
                                else MaterialTheme.colorScheme.surface
                            )
                            .border(
                                1.dp,
                                if (isCaptureActive) Color(0xFF4ADE80) else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                                RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                onDismiss()
                                onOpenCaptureSheet()
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (isCaptureActive) Color(0xFF4ADE80) else Color(0xFF8A96A8))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isCaptureActive) "Capture (${SessionRecorder.count()})" else "Capture",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Live Menu Commands (GM_registerMenuCommand)
                if (commands.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Script Commands",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        commands.forEach { cmd ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .clickable {
                                        GeckoWebExtensionBridge.runMenuCommand(cmd.id)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.uh_ic_lightning_24),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = cmd.name,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = cmd.script,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Matched Scripts section
                Text(
                    text = if (domain.isNotBlank()) "Matching This Site (${matchedScripts.size})" else "Installed Scripts (${allScripts.size})",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))

                val primaryList = if (domain.isNotBlank()) matchedScripts else allScripts
                if (primaryList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(14.dp)
                    ) {
                        Text(
                            text = if (domain.isNotBlank()) "No userscripts match $domain yet." else "No userscripts installed yet.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        primaryList.forEach { script ->
                            ScriptSheetRow(
                                script = script,
                                enabled = UserscriptStore.isEnabled(context, script.name),
                                onToggle = { checked ->
                                    UserscriptStore.setEnabled(context, script.name, checked)
                                    refreshTick++
                                },
                                onEdit = {
                                    onDismiss()
                                    context.startActivity(
                                        Intent(context, ScriptEditorActivity::class.java)
                                            .putExtra("name", script.name)
                                    )
                                }
                            )
                        }
                    }
                }

                // Expandable All Installed Scripts when browsing a site
                if (domain.isNotBlank() && allScripts.size > matchedScripts.size) {
                    Spacer(modifier = Modifier.height(10.dp))
                    TextButton(
                        onClick = { showAllScripts = !showAllScripts },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (showAllScripts) "Hide other installed (${allScripts.size - matchedScripts.size})"
                            else "Show all installed (${allScripts.size})",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (showAllScripts) {
                        val unmatched = allScripts.filter { s -> matchedScripts.none { it.name == s.name } }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            unmatched.forEach { script ->
                                ScriptSheetRow(
                                    script = script,
                                    enabled = UserscriptStore.isEnabled(context, script.name),
                                    onToggle = { checked ->
                                        UserscriptStore.setEnabled(context, script.name, checked)
                                        refreshTick++
                                    },
                                    onEdit = {
                                        onDismiss()
                                        context.startActivity(
                                            Intent(context, ScriptEditorActivity::class.java)
                                                .putExtra("name", script.name)
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Quick action row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (domain.isNotBlank()) {
                        Button(
                            onClick = {
                                GeckoWebExtensionBridge.reloadScriptsOnActiveTab()
                                Toast.makeText(context, "Scripts re-injected", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Apply", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    OutlinedButton(
                        onClick = { showPasteDialog = true },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("New Script", fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            context.startActivity(Intent(context, ScriptsActivity::class.java))
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Manage All", fontSize = 13.sp)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun ScriptSheetRow(
    script: UserscriptManager.Script,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = R.drawable.fx_ic_scripts_24),
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = script.displayName.ifBlank { script.name },
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    if (script.version.isNotBlank()) append("v${script.version} · ")
                    append(script.runAt)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                fontFamily = FontFamily.Default,
                maxLines = 1
            )
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
            Icon(
                painter = painterResource(id = R.drawable.fx_ic_edit_24),
                contentDescription = "Edit Script",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.background,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}
