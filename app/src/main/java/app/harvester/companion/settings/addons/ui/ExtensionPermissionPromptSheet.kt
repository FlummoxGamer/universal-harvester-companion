package app.harvester.companion.settings.addons.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.UHBrand
import app.harvester.companion.settings.addons.ExtensionPermissionsFormatter
import app.harvester.companion.settings.addons.PendingExtensionPrompt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionPermissionPromptSheet(
    prompt: PendingExtensionPrompt,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var allowInPrivate by remember(prompt.id) { mutableStateOf(true) }
    var allowDataCollection by remember(prompt.id) { mutableStateOf(false) }

    val formattedPermissions = remember(prompt.id) {
        ExtensionPermissionsFormatter.formatPermissions(prompt.permissions, prompt.origins)
    }
    val dataCollectionItems = remember(prompt.id) {
        ExtensionPermissionsFormatter.formatDataCollection(prompt.dataCollectionPermissions)
    }

    val headerTitle = when (prompt) {
        is PendingExtensionPrompt.Install -> "Add ${prompt.extensionName}?"
        is PendingExtensionPrompt.Update -> "Update ${prompt.extensionName}?"
        is PendingExtensionPrompt.Optional -> "${prompt.extensionName} requests permissions"
    }

    val confirmLabel = when (prompt) {
        is PendingExtensionPrompt.Install -> "Add to ${UHBrand.NAME}"
        is PendingExtensionPrompt.Update -> "Allow & Update"
        is PendingExtensionPrompt.Optional -> "Allow"
    }

    val onDeny = {
        when (prompt) {
            is PendingExtensionPrompt.Install -> prompt.respond(granted = false)
            is PendingExtensionPrompt.Update -> prompt.respond(granted = false)
            is PendingExtensionPrompt.Optional -> prompt.respond(granted = false)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDeny,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                InstalledExtensionIcon(extension = prompt.extensionRef, sizeDp = 48)
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = headerTitle,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (prompt.extensionVersion.isNotBlank()) {
                        Text(
                            text = "Version ${prompt.extensionVersion}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (formattedPermissions.isEmpty()) {
                            "No special permissions required"
                        } else {
                            "This extension will be able to:"
                        },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (formattedPermissions.isEmpty()) {
                        Text(
                            text = "This package does not request access to your browsing data or sensitive browser APIs.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    } else {
                        formattedPermissions.forEach { perm ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 6.dp)
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (perm.isSensitive) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = perm.title,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                }
            }

            if (dataCollectionItems.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Data Collection Disclosure",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        dataCollectionItems.forEach { item ->
                            Text(
                                text = "• $item",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }
                        if (prompt is PendingExtensionPrompt.Install) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Allow optional technical data",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = allowDataCollection,
                                    onCheckedChange = { allowDataCollection = it }
                                )
                            }
                        }
                    }
                }
            }

            if (prompt is PendingExtensionPrompt.Install) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.uh_ic_private_mode_24),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Allow in Private Browsing",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Run this extension in private tabs",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = allowInPrivate,
                            onCheckedChange = { allowInPrivate = it }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDeny,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = {
                        when (prompt) {
                            is PendingExtensionPrompt.Install -> {
                                prompt.respond(
                                    granted = true,
                                    allowInPrivate = allowInPrivate,
                                    allowDataCollection = allowDataCollection,
                                )
                            }
                            is PendingExtensionPrompt.Update -> prompt.respond(granted = true)
                            is PendingExtensionPrompt.Optional -> prompt.respond(granted = true)
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(confirmLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
