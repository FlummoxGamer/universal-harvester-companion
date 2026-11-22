package app.harvester.companion.settings.privacy

import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.privacy.FilterListStatus
import app.harvester.companion.privacy.FilterPackTier
import app.harvester.companion.privacy.UhShieldEngine
import app.harvester.companion.settings.components.SettingsCategory
import app.harvester.companion.settings.components.UhSettingsCardGroup
import app.harvester.companion.settings.components.UhSettingsDivider
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FilterListsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        setContent {
            UHTheme {
                val context = LocalContext.current
                val state by UhShieldEngine.state.collectAsState()

                var showNuclearSection by remember { mutableStateOf(false) }
                var showCustomRulesSection by remember { mutableStateOf(false) }
                var showAddListDialog by remember { mutableStateOf(false) }
                var customNameInput by remember { mutableStateOf("") }
                var customUrlInput by remember { mutableStateOf("") }
                var customRulesDraft by remember(state.customUserRules) {
                    mutableStateOf(state.customUserRules)
                }

                val coreLists = remember(state.lists) {
                    state.lists.filter { it.def.tier == FilterPackTier.CORE }
                }
                val extraLists = remember(state.lists) {
                    state.lists.filter {
                        it.def.tier == FilterPackTier.NUCLEAR ||
                            it.def.tier == FilterPackTier.SOCIAL ||
                            it.def.tier == FilterPackTier.REGIONAL
                    }
                }
                val customLists = remember(state.lists) {
                    state.lists.filter { it.def.tier == FilterPackTier.CUSTOM }
                }
                val extraEnabledCount = extraLists.count { it.enabled }

                if (showAddListDialog) {
                    AlertDialog(
                        onDismissRequest = { showAddListDialog = false },
                        title = { Text("Add Custom Filter List") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    text = "Supports Adblock Plus, uBlock Origin, and Hosts filter lists (.txt).",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                )
                                OutlinedTextField(
                                    value = customNameInput,
                                    onValueChange = { customNameInput = it },
                                    label = { Text("List Name (optional)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = customUrlInput,
                                    onValueChange = { customUrlInput = it },
                                    label = { Text("Filter List URL (https://...)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val added = UhShieldEngine.addCustomFilterList(customNameInput, customUrlInput)
                                    if (added) {
                                        customNameInput = ""
                                        customUrlInput = ""
                                        showAddListDialog = false
                                        Toast.makeText(context, "Filter list added and syncing", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Enter a valid unique https:// URL", Toast.LENGTH_SHORT).show()
                                    }
                                },
                            ) {
                                Text("Add", color = MaterialTheme.colorScheme.primary)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showAddListDialog = false }) {
                                Text("Cancel")
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp),
                    )
                }

                val lastSyncLabel = remember(state.lastSyncMs) {
                    if (state.lastSyncMs <= 0L) "Built-in Day-0 rules active"
                    else {
                        val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                        "Last updated ${fmt.format(Date(state.lastSyncMs))}"
                    }
                }

                UhSettingsScaffold(
                    title = "Filter Lists",
                    onBack = { finish() },
                ) {
                    // Status & Update hero card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                RoundedCornerShape(20.dp),
                            )
                            .padding(16.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${state.totalCompiledRules} Active Rules",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = lastSyncLabel,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                )
                                if (!state.lastSyncError.isNullOrBlank()) {
                                    Text(
                                        text = "Some lists skipped: ${state.lastSyncError}",
                                        color = MaterialTheme.colorScheme.error,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Button(
                                onClick = { UhShieldEngine.syncEnabledLists(force = true) },
                                enabled = !state.isSyncing,
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                if (state.isSyncing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Updating")
                                } else {
                                    Icon(
                                        painter = painterResource(id = R.drawable.fx_ic_update_24),
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Update")
                                }
                            }
                        }
                    }

                    SettingsCategory("Full Power Default Pack")
                    UhSettingsCardGroup {
                        coreLists.forEachIndexed { index, status ->
                            if (index > 0) UhSettingsDivider()
                            FilterListRow(
                                status = status,
                                onToggle = { enabled -> UhShieldEngine.setListEnabled(status.def.id, enabled) },
                            )
                        }
                    }

                    // Collapsible Nuclear & Extra Packs section to keep UI super clean
                    SettingsCategory("Extra & Nuclear Packs")
                    UhSettingsCardGroup {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showNuclearSection = !showNuclearSection }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.fx_ic_shield_lock_24),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Nuclear, Social & Regional Packs",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = if (extraEnabledCount > 0) {
                                        "$extraEnabledCount extra packs enabled — tap to customize"
                                    } else {
                                        "Optional heavy blocks: HaGeZi Pro, OISD, Paywalls & Regional"
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                )
                            }
                            Text(
                                text = if (showNuclearSection) "Hide" else "Show",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        AnimatedVisibility(visible = showNuclearSection) {
                            Column {
                                extraLists.forEach { status ->
                                    UhSettingsDivider()
                                    FilterListRow(
                                        status = status,
                                        badgeText = when (status.def.tier) {
                                            FilterPackTier.NUCLEAR -> "NUCLEAR"
                                            FilterPackTier.SOCIAL -> "SOCIAL"
                                            FilterPackTier.REGIONAL -> "REGIONAL"
                                            else -> null
                                        },
                                        onToggle = { enabled -> UhShieldEngine.setListEnabled(status.def.id, enabled) },
                                    )
                                }
                            }
                        }
                    }

                    // Custom Filter Lists
                    SettingsCategory("Custom Filter Lists")
                    UhSettingsCardGroup {
                        customLists.forEach { status ->
                            FilterListRow(
                                status = status,
                                onToggle = { enabled -> UhShieldEngine.setListEnabled(status.def.id, enabled) },
                                onDelete = { UhShieldEngine.removeCustomFilterList(status.def.id) },
                            )
                            UhSettingsDivider()
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showAddListDialog = true }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_add_rounded),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                text = "Add Custom Filter List URL",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }

                    // Custom User Rules
                    SettingsCategory("Custom User Rules")
                    UhSettingsCardGroup {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showCustomRulesSection = !showCustomRulesSection }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.fx_ic_scripts_24),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "My Rules Editor",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                val lineCount = state.customUserRules.lines().count {
                                    val t = it.trim()
                                    t.isNotEmpty() && !t.startsWith("!") && !t.startsWith("# ")
                                }
                                Text(
                                    text = if (lineCount > 0) "$lineCount custom rules active" else "Add ||domain^, @@||allow^, or ##.selector rules",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                )
                            }
                            Text(
                                text = if (showCustomRulesSection) "Close" else "Edit",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        AnimatedVisibility(visible = showCustomRulesSection) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                OutlinedTextField(
                                    value = customRulesDraft,
                                    onValueChange = { customRulesDraft = it },
                                    placeholder = {
                                        Text(
                                            "! Example rules:\n||ads.example.com^\n@@||trusted.example.com^\n##.annoying-banner",
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    },
                                    minLines = 4,
                                    maxLines = 10,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            UhShieldEngine.saveCustomUserRules(customRulesDraft)
                                            Toast.makeText(context, "Custom rules saved & applied", Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                    ) {
                                        Text("Save & Apply")
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun FilterListRow(
    status: FilterListStatus,
    badgeText: String? = null,
    onToggle: (Boolean) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!status.enabled) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = status.def.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badgeText != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = badgeText,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = status.def.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (status.ruleCount > 0) {
                Text(
                    text = "${status.ruleCount} rules loaded",
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                )
            }
        }

        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_delete_24),
                    contentDescription = "Remove list",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        }

        Switch(
            checked = status.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.background,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}
