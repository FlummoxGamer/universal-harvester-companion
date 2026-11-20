package app.harvester.companion.settings.addons

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.harvester.companion.GeckoEngine
import app.harvester.companion.HomeActivity
import app.harvester.companion.R
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.util.Locale

private val BUILTIN_INTERNAL_IDS = setOf(
    "uh-bridge@harvester.app",
    "header-injector@harvester.app",
)

private enum class ExtensionsTab {
    Discover,
    Installed,
}

class ExtensionsActivity : AppCompatActivity() {

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContent {
            UHTheme {
                val context = LocalContext.current
                val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

                var selectedTab by remember { mutableStateOf(ExtensionsTab.Discover) }
                var extensions by remember { mutableStateOf<List<WebExtension>>(emptyList()) }
                var installedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
                var installingIds by remember { mutableStateOf<Set<String>>(emptySet()) }

                var searchQuery by remember { mutableStateOf("") }
                var discoverAddons by remember { mutableStateOf<List<AmoAddon>>(emptyList()) }
                var isLoadingDiscover by remember { mutableStateOf(true) }
                var selectedAddonDetail by remember { mutableStateOf<AmoAddon?>(null) }

                var showInstallUrlDialog by remember { mutableStateOf(false) }
                var confirmUninstall by remember { mutableStateOf<WebExtension?>(null) }

                val ublockState by UBlockOriginManager.state.collectAsState()

                fun loadInstalledExtensions() {
                    val runtime = GeckoEngine.getRuntime(context) ?: return
                    UBlockOriginManager.syncInstalledState()
                    runtime.webExtensionController.list().accept({ extList ->
                        val filtered = extList?.filter {
                            it.id !in BUILTIN_INTERNAL_IDS && !it.isBuiltIn
                        } ?: emptyList()
                        val ids = extList?.map { it.id }?.toSet() ?: emptySet()
                        runOnUiThread {
                            extensions = filtered
                            installedIds = ids
                        }
                    }, {
                        runOnUiThread {
                            Toast.makeText(context, "Failed to load extensions", Toast.LENGTH_SHORT).show()
                        }
                    })
                }

                fun installXpi(url: String, key: String, onSuccessMsg: String = "Installed") {
                    val runtime = GeckoEngine.getRuntime(context) ?: return
                    installingIds = installingIds + key
                    runtime.webExtensionController.install(url).accept({ ext ->
                        runOnUiThread {
                            if (ext != null) {
                                runtime.webExtensionController.setAllowedInPrivateBrowsing(ext, true)
                            }
                            installingIds = installingIds - key
                            Toast.makeText(context, onSuccessMsg, Toast.LENGTH_SHORT).show()
                            loadInstalledExtensions()
                        }
                    }, { err ->
                        runOnUiThread {
                            installingIds = installingIds - key
                            Toast.makeText(context, "Install failed: ${err?.message ?: "Error"}", Toast.LENGTH_SHORT).show()
                        }
                    })
                }

                LaunchedEffect(Unit) {
                    loadInstalledExtensions()
                }

                LaunchedEffect(searchQuery) {
                    isLoadingDiscover = true
                    if (searchQuery.isNotBlank()) {
                        delay(280)
                    }
                    val list = withContext(Dispatchers.IO) {
                        val trimmed = searchQuery.trim()
                        if (trimmed.isBlank()) {
                            AmoApiClient.fetchFeatured(pageSize = 24)
                        } else {
                            AmoApiClient.searchAddons(trimmed, pageSize = 24)
                        }
                    }
                    discoverAddons = list
                    isLoadingDiscover = false
                }

                if (showInstallUrlDialog) {
                    var urlInput by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { showInstallUrlDialog = false },
                        title = { Text("Install from URL", color = MaterialTheme.colorScheme.onSurface) },
                        text = {
                            OutlinedTextField(
                                value = urlInput,
                                onValueChange = { urlInput = it },
                                label = { Text("Extension URL (.xpi)") },
                                placeholder = { Text("https://addons.mozilla.org/...") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val trimmed = urlInput.trim()
                                    showInstallUrlDialog = false
                                    if (trimmed.isNotBlank()) {
                                        installXpi(trimmed, key = trimmed)
                                    }
                                }
                            ) {
                                Text("Install", color = MaterialTheme.colorScheme.primary)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showInstallUrlDialog = false }) {
                                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp)
                    )
                }

                confirmUninstall?.let { ext ->
                    val extTitle = ext.metaData?.name ?: ext.id
                    AlertDialog(
                        onDismissRequest = { confirmUninstall = null },
                        title = { Text("Uninstall $extTitle?", color = MaterialTheme.colorScheme.onSurface) },
                        text = {
                            Text(
                                text = "Removes this add-on and its extension data from UH.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    confirmUninstall = null
                                    val runtime = GeckoEngine.getRuntime(context) ?: return@TextButton
                                    runtime.webExtensionController.uninstall(ext).accept({
                                        runOnUiThread {
                                            Toast.makeText(context, "Uninstalled", Toast.LENGTH_SHORT).show()
                                            loadInstalledExtensions()
                                        }
                                    }, {
                                        runOnUiThread {
                                            Toast.makeText(context, "Failed to uninstall", Toast.LENGTH_SHORT).show()
                                        }
                                    })
                                }
                            ) {
                                Text("Uninstall", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmUninstall = null }) {
                                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp)
                    )
                }

                selectedAddonDetail?.let { addon ->
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    val isAddonInstalled = addon.guid in installedIds
                    val isAddonInstalling = addon.guid in installingIds
                    ModalBottomSheet(
                        onDismissRequest = { selectedAddonDetail = null },
                        sheetState = sheetState,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp, vertical = 8.dp)
                                .padding(bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AddonIconThumb(iconUrl = addon.iconUrl, sizeDp = 52)
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = addon.name,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${addon.author} · v${addon.version}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = "★ ${formatRating(addon.averageRating)} (${addon.reviewCount}) · ${formatUsers(addon.weeklyUsers)} users",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            if (addon.description.isNotBlank()) {
                                Text(
                                    text = addon.description,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp
                                )
                            }

                            if (addon.permissions.isNotEmpty()) {
                                Text(
                                    text = "Permissions",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    addon.permissions.take(12).forEach { perm ->
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerHighest
                                        ) {
                                            Text(
                                                text = perm,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 11.sp,
                                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (addon.homepageUrl.isNotBlank()) {
                                    OutlinedButton(
                                        onClick = {
                                            selectedAddonDetail = null
                                            context.startActivity(
                                                Intent(context, HomeActivity::class.java).apply {
                                                    action = Intent.ACTION_VIEW
                                                    data = Uri.parse(addon.homepageUrl)
                                                }
                                            )
                                        },
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.fx_ic_link_24),
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Homepage", fontSize = 13.sp)
                                    }
                                }
                                Button(
                                    onClick = {
                                        if (!isAddonInstalled && !isAddonInstalling) {
                                            installXpi(addon.xpiUrl, key = addon.guid, onSuccessMsg = "${addon.name} installed")
                                        }
                                    },
                                    enabled = !isAddonInstalled && !isAddonInstalling,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (isAddonInstalling) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Installing…", fontSize = 13.sp)
                                    } else {
                                        Text(
                                            text = if (isAddonInstalled) "Installed" else "Install Extension",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                UhSettingsScaffold(
                    title = "Extensions",
                    scrollable = false,
                    onBack = { finish() },
                    actions = {
                        OutlinedButton(
                            onClick = { showInstallUrlDialog = true },
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.fx_ic_link_24),
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("URL", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Top segment switcher: Discover | Installed
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                SegmentButton(
                                    label = "Discover",
                                    selected = selectedTab == ExtensionsTab.Discover,
                                    onClick = { selectedTab = ExtensionsTab.Discover },
                                    modifier = Modifier.weight(1f)
                                )
                                SegmentButton(
                                    label = "Installed (${extensions.size})",
                                    selected = selectedTab == ExtensionsTab.Installed,
                                    onClick = { selectedTab = ExtensionsTab.Installed },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        if (selectedTab == ExtensionsTab.Discover) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Search Firefox Add-ons…", fontSize = 14.sp) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(id = R.drawable.fx_ic_search_24),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.fx_ic_close_24),
                                                contentDescription = "Clear search",
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            )

                            val columns = if (isLandscape) 2 else 1
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(columns),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    UBlockOriginShieldCard(
                                        state = ublockState,
                                        onToggleEnabled = { enabled ->
                                            UBlockOriginManager.setGloballyEnabled(enabled)
                                        },
                                        onCheckUpdate = {
                                            UBlockOriginManager.checkAndInstallOrUpdate(force = true)
                                        }
                                    )
                                }

                                if (isLoadingDiscover) {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 40.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                        }
                                    }
                                } else if (discoverAddons.isEmpty()) {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 40.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "No matching add-ons found",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 14.sp
                                            )
                                        }
                                    }
                                } else {
                                    items(discoverAddons, key = { it.guid }) { addon ->
                                        AmoDiscoverCard(
                                            addon = addon,
                                            isInstalled = addon.guid in installedIds,
                                            isInstalling = addon.guid in installingIds,
                                            onOpenDetail = { selectedAddonDetail = addon },
                                            onInstall = {
                                                installXpi(
                                                    addon.xpiUrl,
                                                    key = addon.guid,
                                                    onSuccessMsg = "${addon.name} installed"
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                item(key = "ublock_shield_pinned") {
                                    UBlockOriginShieldCard(
                                        state = ublockState,
                                        onToggleEnabled = { enabled ->
                                            UBlockOriginManager.setGloballyEnabled(enabled)
                                        },
                                        onCheckUpdate = {
                                            UBlockOriginManager.checkAndInstallOrUpdate(force = true)
                                        }
                                    )
                                }

                                if (extensions.isEmpty()) {
                                    item(key = "empty_installed") {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 44.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Icon(
                                                    painter = painterResource(id = R.drawable.fx_ic_extensions_24),
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                    modifier = Modifier.size(42.dp)
                                                )
                                                Spacer(modifier = Modifier.height(10.dp))
                                                Text(
                                                    text = "No add-ons installed yet",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    fontSize = 15.sp
                                                )
                                                Spacer(modifier = Modifier.height(8.dp))
                                                FilledTonalButton(
                                                    onClick = { selectedTab = ExtensionsTab.Discover },
                                                    shape = RoundedCornerShape(14.dp)
                                                ) {
                                                    Text("Browse Add-ons")
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    items(extensions, key = { it.id }) { ext ->
                                        val enabled = ext.metaData?.enabled ?: true
                                        InstalledExtensionCard(
                                            ext = ext,
                                            enabled = enabled,
                                            onToggleEnabled = { nextEnabled ->
                                                val runtime = GeckoEngine.getRuntime(context) ?: return@InstalledExtensionCard
                                                val task = if (nextEnabled) {
                                                    runtime.webExtensionController.enable(
                                                        ext,
                                                        WebExtensionController.EnableSource.USER
                                                    )
                                                } else {
                                                    runtime.webExtensionController.disable(
                                                        ext,
                                                        WebExtensionController.EnableSource.USER
                                                    )
                                                }
                                                task.accept({
                                                    runOnUiThread { loadInstalledExtensions() }
                                                }, {
                                                    runOnUiThread {
                                                        Toast.makeText(context, "Could not update extension state", Toast.LENGTH_SHORT).show()
                                                    }
                                                })
                                            },
                                            onOpenOptions = { url ->
                                                context.startActivity(
                                                    Intent(context, HomeActivity::class.java).apply {
                                                        action = Intent.ACTION_VIEW
                                                        data = Uri.parse(url)
                                                    }
                                                )
                                            },
                                            onUninstall = { confirmUninstall = ext }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.padding(vertical = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun UBlockOriginShieldCard(
    state: UBlockOriginManager.State,
    onToggleEnabled: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(
                        id = if (state.isEnabled) R.drawable.uh_ic_shield_checkmark_24 else R.drawable.uh_ic_shield_slash_24
                    ),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "uBlock Origin Shield",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val statusSub = buildString {
                    if (state.isUpdating) {
                        append("Updating package…")
                    } else if (state.isInstalled) {
                        append(if (state.version.isNotBlank()) "v${state.version}" else "Active")
                    } else {
                        append("Built-in adblocker pending")
                    }
                    if (state.totalBlockedCount > 0) {
                        append(" · ${state.totalBlockedCount} blocked")
                    }
                }
                Text(
                    text = statusSub,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            TextButton(
                onClick = onCheckUpdate,
                enabled = !state.isUpdating,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                if (state.isUpdating) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        text = if (state.isInstalled) "Update" else "Install",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Switch(
                checked = state.isEnabled,
                onCheckedChange = onToggleEnabled,
            )
        }
    }
}

@Composable
private fun AmoDiscoverCard(
    addon: AmoAddon,
    isInstalled: Boolean,
    isInstalling: Boolean,
    onOpenDetail: () -> Unit,
    onInstall: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onOpenDetail)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AddonIconThumb(iconUrl = addon.iconUrl, sizeDp = 42)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = addon.name,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (addon.isRecommended) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = "Recommended",
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Text(
                    text = addon.summary.ifBlank { addon.author },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${addon.author} · ★ ${formatRating(addon.averageRating)} · ${formatUsers(addon.weeklyUsers)} users",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            FilledTonalButton(
                onClick = onInstall,
                enabled = !isInstalled && !isInstalling,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                if (isInstalling) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        text = if (isInstalled) "Installed" else "Install",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun AddonIconThumb(iconUrl: String, sizeDp: Int) {
    if (iconUrl.isNotBlank()) {
        AsyncImage(
            model = iconUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        )
    } else {
        Box(
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.fx_ic_extensions_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size((sizeDp * 0.58f).dp)
            )
        }
    }
}

@Composable
private fun InstalledExtensionCard(
    ext: WebExtension,
    enabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onOpenOptions: (String) -> Unit,
    onUninstall: () -> Unit,
) {
    val meta = ext.metaData
    val iconBitmap by produceState<Bitmap?>(initialValue = null, key1 = ext.id) {
        meta?.icon?.getBitmap(64)?.accept(
            { bmp -> value = bmp },
            {}
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
        } else {
            Icon(
                painter = painterResource(id = R.drawable.fx_ic_extensions_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = meta?.name ?: ext.id,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${meta?.version ?: "1.0"} · ${meta?.description ?: "No description"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        val optionsUrl = meta?.optionsPageUrl
        if (!optionsUrl.isNullOrBlank()) {
            IconButton(onClick = { onOpenOptions(optionsUrl) }) {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_settings_24),
                    contentDescription = "Extension options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Switch(
            checked = enabled,
            onCheckedChange = onToggleEnabled,
        )

        IconButton(onClick = onUninstall) {
            Icon(
                painter = painterResource(id = R.drawable.fx_ic_delete_24),
                contentDescription = "Uninstall extension",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun formatRating(rating: Double): String {
    return if (rating <= 0.0) "—" else String.format(Locale.US, "%.1f", rating)
}

private fun formatUsers(users: Int): String {
    return when {
        users >= 1_000_000 -> String.format(Locale.US, "%.1fM", users / 1_000_000.0)
        users >= 1_000 -> String.format(Locale.US, "%.1fk", users / 1_000.0)
        users > 0 -> users.toString()
        else -> "0"
    }
}
