package app.harvester.companion.browser.menu

import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import app.harvester.companion.settings.addons.ExtensionsActivity
import app.harvester.companion.settings.addons.UBlockOriginManager
import app.harvester.companion.settings.addons.UhExtensionManager
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.WebExtensionState
import mozilla.components.concept.engine.webextension.Action

private data class ExtensionMenuEntry(
    val id: String,
    val name: String,
    val action: Action?,
    val optionsPageUrl: String?,
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

    val selectedTab = browserState.selectedTab
    val isPrivateTab = selectedTab?.content?.private == true
    val tabExtensionOverrides = selectedTab?.extensionState.orEmpty()
    val metaById = installedMeta.associateBy { it.id }

    val activeEntries = browserState.extensions.values
        .filter { ext ->
            ext.enabled &&
                !ext.isBuiltIn &&
                ext.id !in BUILTIN_INTERNAL_EXTENSION_IDS &&
                (!isPrivateTab || ext.allowedInPrivateBrowsing)
        }
        .map { ext ->
            val tabOverride = tabExtensionOverrides[ext.id]
            val mergedAction = resolveMergedAction(ext, tabOverride)
            val fallbackMeta = metaById[ext.id]
            ExtensionMenuEntry(
                id = ext.id,
                name = ext.name?.ifBlank { fallbackMeta?.name ?: ext.id } ?: fallbackMeta?.name ?: ext.id,
                action = mergedAction,
                optionsPageUrl = fallbackMeta?.optionsPageUrl,
            )
        }
        .sortedWith(
            compareByDescending<ExtensionMenuEntry> { it.id == UBlockOriginManager.UBLOCK_ID }
                .thenBy { it.name.lowercase() }
        )

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 26.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Extensions",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isPrivateTab) {
                            "Showing extensions enabled for Private Browsing"
                        } else {
                            "${activeEntries.size} active extension${if (activeEntries.size == 1) "" else "s"}"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }

                TextButton(
                    onClick = {
                        onDismiss()
                        context.startActivity(Intent(context, ExtensionsActivity::class.java))
                    }
                ) {
                    Text(
                        text = "Manage",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (activeEntries.isEmpty()) {
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
                            text = if (isPrivateTab) {
                                "No extensions enabled in Private Browsing"
                            } else if (ublockState.isUpdating) {
                                "Setting up uBlock Origin Shield…"
                            } else {
                                "No active extensions"
                            },
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Add extensions from the Extensions Manager to customize browsing.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        FilledTonalButton(
                            onClick = {
                                onDismiss()
                                context.startActivity(Intent(context, ExtensionsActivity::class.java))
                            },
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
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(activeEntries, key = { it.id }) { entry ->
                        BrowserExtensionActionRow(
                            entry = entry,
                            onClick = {
                                val action = entry.action
                                val optionsUrl = entry.optionsPageUrl
                                onDismiss()
                                if (action != null && action.enabled != false) {
                                    action.onClick()
                                } else if (!optionsUrl.isNullOrBlank()) {
                                    onOpenUrlInTab(optionsUrl)
                                } else {
                                    context.startActivity(Intent(context, ExtensionsActivity::class.java))
                                }
                            }
                        )
                    }
                }
            }
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

@Composable
private fun BrowserExtensionActionRow(
    entry: ExtensionMenuEntry,
    onClick: () -> Unit,
) {
    val action = entry.action
    val isEnabled = action == null || action.enabled != false
    val iconBitmap by produceState<Bitmap?>(initialValue = null, key1 = entry.id, key2 = action?.loadIcon) {
        value = try {
            action?.loadIcon?.invoke(96)
        } catch (_: Exception) {
            null
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = isEnabled, onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center
            ) {
                val bmp = iconBitmap
                if (bmp != null && !bmp.isRecycled) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = entry.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(
                                id = if (entry.id == UBlockOriginManager.UBLOCK_ID) {
                                    R.drawable.uh_ic_shield_checkmark_24
                                } else {
                                    R.drawable.fx_ic_extensions_24
                                }
                            ),
                            contentDescription = entry.name,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                val badgeText = action?.badgeText?.trim().orEmpty()
                if (badgeText.isNotEmpty()) {
                    val badgeBg = action?.badgeBackgroundColor?.let { Color(it) }
                        ?: MaterialTheme.colorScheme.primary
                    val badgeFg = action?.badgeTextColor?.let { Color(it) }
                        ?: MaterialTheme.colorScheme.onPrimary
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .clip(CircleShape)
                            .background(badgeBg)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = badgeText.take(4),
                            color = badgeFg,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    color = if (isEnabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = action?.title?.takeIf { it.isNotBlank() && !it.equals(entry.name, ignoreCase = true) }
                    ?: if (action != null) "Tap to open popup" else if (!entry.optionsPageUrl.isNullOrBlank()) "Open settings" else "Active"
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
}
