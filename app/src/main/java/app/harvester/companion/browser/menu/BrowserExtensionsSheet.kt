package app.harvester.companion.browser.menu

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.UHApp
import app.harvester.companion.settings.addons.BUILTIN_INTERNAL_EXTENSION_IDS
import app.harvester.companion.settings.addons.ExtensionPermissionsFormatter
import app.harvester.companion.settings.addons.ExtensionsActivity
import app.harvester.companion.settings.addons.InstalledExtensionItem
import app.harvester.companion.settings.addons.UBlockOriginManager
import app.harvester.companion.settings.addons.UhExtensionManager
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.WebExtensionState
import mozilla.components.concept.engine.webextension.Action
import mozilla.components.concept.engine.webextension.WebExtension

private data class ExtensionSheetEntry(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val allowedInPrivateBrowsing: Boolean,
    val action: Action?,
    val meta: InstalledExtensionItem?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserExtensionsSheet(
    onOpenUrlInTab: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val components = (context.applicationContext as UHApp).components
    val browserState by components.store.stateFlow.collectAsState()
    val installedMeta by UhExtensionManager.installedExtensions.collectAsState()
    val ublockState by UBlockOriginManager.state.collectAsState()
    val activeTasks by UhExtensionManager.activeTasks.collectAsState()

    LaunchedEffect(Unit) {
        UhExtensionManager.refreshInstalledExtensions()
    }

    val selectedTab = browserState.selectedTab
    val isPrivateTab = selectedTab?.content?.private == true
    val tabExtensionOverrides = selectedTab?.extensionState.orEmpty()
    val metaById = remember(installedMeta) { installedMeta.associateBy { it.id } }

    // Merge installed metadata + live browser store extension state so both enabled & disabled add-ons appear cleanly
    val allEntries = remember(installedMeta, browserState.extensions, tabExtensionOverrides) {
        val ids = linkedSetOf<String>()
        installedMeta.forEach { if (it.id !in BUILTIN_INTERNAL_EXTENSION_IDS) ids.add(it.id) }
        browserState.extensions.values.forEach { ext ->
            if (!ext.isBuiltIn && ext.id !in BUILTIN_INTERNAL_EXTENSION_IDS) ids.add(ext.id)
        }

        ids.mapNotNull { id ->
            val storeExt = browserState.extensions[id]
            val meta = metaById[id]
            if (storeExt == null && meta == null) return@mapNotNull null
            val mergedAction = storeExt?.let { resolveMergedAction(it, tabExtensionOverrides[id]) }
            ExtensionSheetEntry(
                id = id,
                name = meta?.name?.ifBlank { storeExt?.name ?: id }
                    ?: storeExt?.name?.ifBlank { id }
                    ?: id,
                enabled = meta?.enabled ?: storeExt?.enabled ?: false,
                allowedInPrivateBrowsing = meta?.allowedInPrivateBrowsing ?: storeExt?.allowedInPrivateBrowsing ?: false,
                action = mergedAction,
                meta = meta,
            )
        }.sortedWith(
            compareByDescending<ExtensionSheetEntry> { it.id == UBlockOriginManager.UBLOCK_ID }
                .thenByDescending { it.enabled }
                .thenBy { it.name.lowercase() }
        )
    }

    var selectedDetailId by remember { mutableStateOf<String?>(null) }
    var confirmUninstallItem by remember { mutableStateOf<InstalledExtensionItem?>(null) }

    val selectedDetailEntry = selectedDetailId?.let { id -> allEntries.firstOrNull { it.id == id } }
    LaunchedEffect(selectedDetailId, selectedDetailEntry) {
        if (selectedDetailId != null && selectedDetailEntry == null) {
            selectedDetailId = null
        }
    }

    if (confirmUninstallItem != null) {
        val target = confirmUninstallItem!!
        AlertDialog(
            onDismissRequest = { confirmUninstallItem = null },
            title = { Text("Remove ${target.name}?", color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Text(
                    text = "This extension and its saved data will be removed from the browser.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmUninstallItem = null
                        selectedDetailId = null
                        UhExtensionManager.uninstall(target)
                    }
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstallItem = null }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(20.dp)
        )
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        AnimatedContent(
            targetState = selectedDetailEntry,
            transitionSpec = {
                if (targetState != null) {
                    (slideInHorizontally { it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it / 4 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { it / 4 } + fadeOut())
                }
            },
            label = "extensions_sheet_nav"
        ) { detailEntry ->
            if (detailEntry == null) {
                ExtensionsListPage(
                    entries = allEntries,
                    isPrivateTab = isPrivateTab,
                    isUblockUpdating = ublockState.isUpdating,
                    onRunPopupOrOpenDetails = { entry ->
                        val action = entry.action
                        val canRunAction = entry.enabled &&
                            (!isPrivateTab || entry.allowedInPrivateBrowsing) &&
                            action != null &&
                            action.enabled != false
                        if (canRunAction && action != null) {
                            onDismiss()
                            action.onClick()
                        } else {
                            selectedDetailId = entry.id
                        }
                    },
                    onOpenDetails = { entry ->
                        selectedDetailId = entry.id
                    },
                    onToggleEnabled = { entry, checked ->
                        entry.meta?.let { UhExtensionManager.setEnabled(it, checked) }
                    },
                    onOpenManager = {
                        onDismiss()
                        context.startActivity(Intent(context, ExtensionsActivity::class.java))
                    },
                )
            } else {
                val isUpdating = activeTasks.containsKey(detailEntry.id)
                ExtensionDetailPage(
                    entry = detailEntry,
                    isPrivateTab = isPrivateTab,
                    isUpdating = isUpdating,
                    onBack = { selectedDetailId = null },
                    onOpenPopup = {
                        val action = detailEntry.action
                        if (action != null && action.enabled != false) {
                            onDismiss()
                            action.onClick()
                        }
                    },
                    onToggleEnabled = { checked ->
                        detailEntry.meta?.let { UhExtensionManager.setEnabled(it, checked) }
                    },
                    onTogglePrivate = { allowed ->
                        detailEntry.meta?.let { UhExtensionManager.setAllowedInPrivateBrowsing(it, allowed) }
                    },
                    onOpenSettings = { url ->
                        onDismiss()
                        onOpenUrlInTab(url)
                    },
                    onOpenHomepage = { url ->
                        onDismiss()
                        onOpenUrlInTab(url)
                    },
                    onUpdate = {
                        UhExtensionManager.updateExtension(detailEntry.id)
                    },
                    onRequestUninstall = {
                        detailEntry.meta?.let { confirmUninstallItem = it }
                    },
                )
            }
        }
    }
}

@Composable
private fun ExtensionsListPage(
    entries: List<ExtensionSheetEntry>,
    isPrivateTab: Boolean,
    isUblockUpdating: Boolean,
    onRunPopupOrOpenDetails: (ExtensionSheetEntry) -> Unit,
    onOpenDetails: (ExtensionSheetEntry) -> Unit,
    onToggleEnabled: (ExtensionSheetEntry, Boolean) -> Unit,
    onOpenManager: () -> Unit,
) {
    val activeCount = entries.count { it.enabled && (!isPrivateTab || it.allowedInPrivateBrowsing) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .padding(bottom = 24.dp)
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Extensions",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isPrivateTab) {
                        "$activeCount active in Private Browsing · ${entries.size} installed"
                    } else {
                        "$activeCount active · ${entries.size} installed"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }

            FilledTonalButton(
                onClick = onOpenManager,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.uh_ic_plus_24),
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text("Add / Manage", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (entries.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.fx_ic_extensions_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp)
                    )
                    Text(
                        text = if (isUblockUpdating) "Setting up uBlock Origin Shield…" else "No extensions installed",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Install extensions from the Extensions Manager to customize browsing.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = onOpenManager,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Open Extensions Manager", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 430.dp)
            ) {
                items(entries, key = { it.id }) { entry ->
                    BrowserExtensionUnifiedRow(
                        entry = entry,
                        isPrivateTab = isPrivateTab,
                        onRowClick = { onRunPopupOrOpenDetails(entry) },
                        onDetailsClick = { onOpenDetails(entry) },
                        onToggleEnabled = { checked -> onToggleEnabled(entry, checked) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowserExtensionUnifiedRow(
    entry: ExtensionSheetEntry,
    isPrivateTab: Boolean,
    onRowClick: () -> Unit,
    onDetailsClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    val action = entry.action
    val badgeText = if (entry.enabled) action?.badgeText?.trim().orEmpty() else ""
    val hasPopupAction = entry.enabled &&
        (!isPrivateTab || entry.allowedInPrivateBrowsing) &&
        action != null &&
        action.enabled != false

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onRowClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Unobstructed crisp icon (ZERO overlapping dot)
            ExtensionSheetIcon(
                id = entry.id,
                name = entry.name,
                action = action,
                extensionRef = entry.meta?.extensionRef,
                enabled = entry.enabled,
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = entry.name,
                        color = if (entry.enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (badgeText.isNotEmpty()) {
                        val badgeBg = action?.badgeBackgroundColor?.let { Color(it) }
                            ?: MaterialTheme.colorScheme.primary
                        val badgeFg = action?.badgeTextColor?.let { Color(it) }
                            ?: MaterialTheme.colorScheme.onPrimary
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(badgeBg)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = badgeText.take(6),
                                color = badgeFg,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }

                    if (entry.meta?.updateInfo != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "Update",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                val subtitle = when {
                    !entry.enabled -> "Disabled · Tap for settings"
                    isPrivateTab && !entry.allowedInPrivateBrowsing -> "Blocked in Private Browsing · Tap to allow"
                    hasPopupAction && action != null -> action.title?.takeIf {
                        it.isNotBlank() && !it.equals(entry.name, ignoreCase = true)
                    } ?: "Tap to open popup"
                    !entry.meta?.optionsPageUrl.isNullOrBlank() -> "Active · Tap for settings"
                    else -> "Active"
                }

                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (entry.meta != null) {
                Switch(
                    checked = entry.enabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.scale(0.80f),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.background,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            IconButton(
                onClick = onDetailsClick,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_chevron_right_24),
                    contentDescription = "Extension details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun ExtensionDetailPage(
    entry: ExtensionSheetEntry,
    isPrivateTab: Boolean,
    isUpdating: Boolean,
    onBack: () -> Unit,
    onOpenPopup: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onTogglePrivate: (Boolean) -> Unit,
    onOpenSettings: (String) -> Unit,
    onOpenHomepage: (String) -> Unit,
    onUpdate: () -> Unit,
    onRequestUninstall: () -> Unit,
) {
    val meta = entry.meta
    val action = entry.action
    val canRunAction = entry.enabled &&
        (!isPrivateTab || entry.allowedInPrivateBrowsing) &&
        action != null &&
        action.enabled != false

    val formattedPermissions = remember(meta) {
        if (meta == null) emptyList()
        else ExtensionPermissionsFormatter.formatPermissions(
            permissions = meta.requiredPermissions + meta.grantedOptionalPermissions,
            origins = meta.requiredOrigins + meta.grantedOptionalOrigins,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(bottom = 26.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header with Back button + Icon + Title
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.uh_ic_back_24),
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            ExtensionSheetIcon(
                id = entry.id,
                name = entry.name,
                action = action,
                extensionRef = meta?.extensionRef,
                enabled = entry.enabled,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val sub = buildString {
                    val ver = meta?.version
                    if (!ver.isNullOrBlank()) append("v$ver")
                    val creator = meta?.creatorName
                    if (!creator.isNullOrBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(creator)
                    }
                }.ifBlank { entry.id }
                Text(
                    text = sub,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        val desc = meta?.description
        if (!desc.isNullOrBlank()) {
            Text(
                text = desc,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }

        // Open Popup primary action if available
        if (canRunAction) {
            FilledTonalButton(
                onClick = onOpenPopup,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_extensions_24),
                    contentDescription = null,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open Extension Popup", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // Available update banner
        val update = meta?.updateInfo
        if (update != null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Update available",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "v${update.currentVersion} → v${update.latestVersion}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                    Button(
                        onClick = onUpdate,
                        enabled = !isUpdating,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (isUpdating) "Updating…" else "Update",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Settings & Toggles card
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enabled",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (entry.enabled) "Running across open tabs" else "Extension is paused",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = entry.enabled,
                        onCheckedChange = onToggleEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.background,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Allow in Private Browsing",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Permit this extension to run in private tabs",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = entry.allowedInPrivateBrowsing,
                        onCheckedChange = onTogglePrivate,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.background,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }

                val optionsUrl = meta?.optionsPageUrl
                if (!optionsUrl.isNullOrBlank()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)
                    )
                    DetailNavRow(
                        iconRes = R.drawable.fx_ic_settings_24,
                        title = "Extension Settings",
                        subtitle = "Configure extension options",
                        onClick = { onOpenSettings(optionsUrl) }
                    )
                }

                val homepage = meta?.homepageUrl
                if (!homepage.isNullOrBlank()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)
                    )
                    DetailNavRow(
                        iconRes = R.drawable.uh_ic_open_in_app_24,
                        title = "Developer Website",
                        subtitle = homepage.removePrefix("https://").removePrefix("http://"),
                        onClick = { onOpenHomepage(homepage) }
                    )
                }
            }
        }

        // Permissions card
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Permissions",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                if (formattedPermissions.isEmpty()) {
                    Text(
                        text = "No special permissions required.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                } else {
                    formattedPermissions.forEach { perm ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (perm.isSensitive) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                    )
                            )
                            Text(
                                text = perm.title,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }

        // Uninstall button
        if (meta != null) {
            OutlinedButton(
                onClick = onRequestUninstall,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_delete_24),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Remove Extension", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun DetailNavRow(
    iconRes: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = title,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(19.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            painter = painterResource(id = R.drawable.fx_ic_chevron_right_24),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun ExtensionSheetIcon(
    id: String,
    name: String,
    action: Action?,
    extensionRef: WebExtension?,
    enabled: Boolean,
) {
    val iconBitmap by produceState<Bitmap?>(initialValue = null, key1 = id, key2 = action?.loadIcon, key3 = extensionRef) {
        value = try {
            action?.loadIcon?.invoke(96) ?: extensionRef?.loadIcon(96)
        } catch (_: Exception) {
            null
        }
    }

    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center
    ) {
        val bmp = iconBitmap
        if (bmp != null && !bmp.isRecycled) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                alpha = if (enabled) 1f else 0.45f,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        } else {
            Icon(
                painter = painterResource(
                    id = if (id == UBlockOriginManager.UBLOCK_ID) {
                        R.drawable.uh_ic_shield_checkmark_24
                    } else {
                        R.drawable.fx_ic_extensions_24
                    }
                ),
                contentDescription = name,
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun resolveMergedAction(
    globalState: WebExtensionState,
    tabState: WebExtensionState?,
): Action? {
    val mergedBrowserAction = globalState.browserAction?.copyWithOverride(tabState?.browserAction)
        ?: tabState?.browserAction
    if (mergedBrowserAction != null) return mergedBrowserAction

    return globalState.pageAction?.copyWithOverride(tabState?.pageAction)
        ?: tabState?.pageAction
}
