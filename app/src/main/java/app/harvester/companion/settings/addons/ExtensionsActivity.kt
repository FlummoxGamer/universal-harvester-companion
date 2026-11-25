package app.harvester.companion.settings.addons

import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import app.harvester.companion.HomeActivity
import app.harvester.companion.R
import app.harvester.companion.UHBrand
import app.harvester.companion.settings.addons.ui.CatalogAddonDetailSheet
import app.harvester.companion.settings.addons.ui.DiscoverExtensionsTab
import app.harvester.companion.settings.addons.ui.ExtensionPermissionPromptSheet
import app.harvester.companion.settings.addons.ui.InstallFromUrlDialog
import app.harvester.companion.settings.addons.ui.InstallSourceSheet
import app.harvester.companion.settings.addons.ui.InstalledExtensionDetailSheet
import app.harvester.companion.settings.addons.ui.InstalledExtensionsTab
import app.harvester.companion.settings.addons.ui.SegmentTabButton
import app.harvester.companion.settings.components.UhSettingsScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class ExtensionsTab {
    Discover,
    Installed,
}

class ExtensionsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )

        setContent {
            val context = LocalContext.current
            val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            val snackbarHostState = remember { SnackbarHostState() }

            var selectedTab by remember { mutableStateOf(ExtensionsTab.Discover) }
            var searchQuery by remember { mutableStateOf("") }
            var selectedCategory by remember { mutableStateOf(CatalogCategory.FEATURED) }
            var selectedSort by remember { mutableStateOf(CatalogSort.POPULAR) }
            var reloadToken by remember { mutableIntStateOf(0) }

            var discoverAddons by remember { mutableStateOf<List<AmoAddon>>(emptyList()) }
            var isLoadingDiscover by remember { mutableStateOf(true) }
            var discoverError by remember { mutableStateOf<String?>(null) }

            var selectedCatalogAddon by remember { mutableStateOf<AmoAddon?>(null) }
            var selectedInstalledId by remember { mutableStateOf<String?>(null) }
            var showInstallSourceSheet by remember { mutableStateOf(false) }
            var showInstallUrlDialog by remember { mutableStateOf(false) }

            val installedExtensions by UhExtensionManager.installedExtensions.collectAsState()
            val installedIds by UhExtensionManager.installedIds.collectAsState()
            val activeTasks by UhExtensionManager.activeTasks.collectAsState()
            val pendingPrompt by UhExtensionManager.pendingPrompt.collectAsState()
            val availableUpdates by UhExtensionManager.availableUpdates.collectAsState()
            val isCheckingUpdates by UhExtensionManager.isCheckingUpdates.collectAsState()

            val pickXpiLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri != null) {
                    val fileName = resolveFileDisplayName(uri) ?: "Local extension"
                    selectedTab = ExtensionsTab.Installed
                    UhExtensionManager.installFromLocalUri(context, uri, fileName)
                }
            }

            LaunchedEffect(Unit) {
                UhExtensionManager.refreshInstalledExtensions()
                UhExtensionManager.checkForUpdates(force = false)
            }

            LaunchedEffect(Unit) {
                UhExtensionManager.events.collect { event ->
                    snackbarHostState.showSnackbar(event.message)
                }
            }

            LaunchedEffect(searchQuery, selectedCategory, selectedSort, reloadToken) {
                if (searchQuery.isNotBlank()) {
                    delay(320)
                }
                isLoadingDiscover = true
                discoverError = null
                val result = withContext(Dispatchers.IO) {
                    if (searchQuery.isBlank()) {
                        AmoApiClient.fetchCatalog(
                            category = selectedCategory,
                            sort = selectedSort,
                            pageSize = 24
                        )
                    } else {
                        AmoApiClient.searchAddons(
                            query = searchQuery,
                            sort = selectedSort,
                            pageSize = 24
                        )
                    }
                }
                when (result) {
                    is CatalogLoadResult.Success -> {
                        discoverAddons = result.addons
                        discoverError = null
                    }
                    is CatalogLoadResult.Error -> {
                        discoverAddons = emptyList()
                        discoverError = result.message
                    }
                }
                isLoadingDiscover = false
            }

            val openInBrowser: (String) -> Unit = { url ->
                if (url.isNotBlank()) {
                    startActivity(
                        Intent(this, HomeActivity::class.java).apply {
                            action = Intent.ACTION_VIEW
                            data = Uri.parse(url)
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                    )
                }
            }

            val userInstalledCount = installedExtensions.size

            UhSettingsScaffold(
                title = "Extensions",
                subtitle = "Enhance ${UHBrand.NAME} with privacy tools and add-ons",
                onBack = { finish() },
                scrollable = false,
                actions = {
                    IconButton(onClick = { showInstallSourceSheet = true }) {
                        Icon(
                            painter = painterResource(id = R.drawable.uh_ic_plus_24),
                            contentDescription = "Install extension from file or URL",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                snackbarHost = {
                    SnackbarHost(hostState = snackbarHostState) { data ->
                        Snackbar(
                            snackbarData = data,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            shape = RoundedCornerShape(14.dp)
                        )
                    }
                }
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Segmented Tab Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        SegmentTabButton(
                            label = "Discover",
                            selected = selectedTab == ExtensionsTab.Discover,
                            onClick = { selectedTab = ExtensionsTab.Discover },
                            modifier = Modifier.weight(1f)
                        )
                        SegmentTabButton(
                            label = "Installed ($userInstalledCount)",
                            selected = selectedTab == ExtensionsTab.Installed,
                            onClick = {
                                selectedTab = ExtensionsTab.Installed
                                UhExtensionManager.refreshInstalledExtensions()
                            },
                            badgeCount = availableUpdates.size,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    when (selectedTab) {
                        ExtensionsTab.Discover -> {
                            DiscoverExtensionsTab(
                                isLandscape = isLandscape,
                                searchQuery = searchQuery,
                                onSearchQueryChange = { searchQuery = it },
                                selectedCategory = selectedCategory,
                                onSelectCategory = { selectedCategory = it },
                                selectedSort = selectedSort,
                                onSelectSort = { selectedSort = it },
                                addons = discoverAddons,
                                isLoading = isLoadingDiscover,
                                errorMessage = discoverError,
                                onRetry = { reloadToken++ },
                                installedIds = installedIds,
                                activeTasks = activeTasks,
                                onInstallAddon = { addon ->
                                    UhExtensionManager.installFromUrl(
                                        url = addon.xpiUrl,
                                        key = addon.guid,
                                        name = addon.name
                                    )
                                },
                                onCancelInstall = { UhExtensionManager.cancelInstall(it) },
                                onAddonClick = { selectedCatalogAddon = it },
                                onOpenInstalledAddon = { guid ->
                                    selectedTab = ExtensionsTab.Installed
                                    selectedInstalledId = guid
                                }
                            )
                        }

                        ExtensionsTab.Installed -> {
                            InstalledExtensionsTab(
                                installedExtensions = installedExtensions,
                                activeTasks = activeTasks,
                                availableUpdates = availableUpdates,
                                isCheckingUpdates = isCheckingUpdates,
                                onCheckAllUpdates = { UhExtensionManager.checkForUpdates(force = true) },
                                onUpdateAll = { UhExtensionManager.updateAllExtensions() },
                                onUpdateSingle = { UhExtensionManager.updateExtension(it) },
                                onCancelInstall = { UhExtensionManager.cancelInstall(it) },
                                onToggleExtensionEnabled = { item, enabled ->
                                    UhExtensionManager.setEnabled(item, enabled)
                                },
                                onOpenExtensionDetails = { selectedInstalledId = it.id },
                                onOpenExtensionOptions = openInBrowser,
                                onExploreCatalog = { selectedTab = ExtensionsTab.Discover }
                            )
                        }
                    }
                }
            }

            if (showInstallSourceSheet) {
                InstallSourceSheet(
                    onPickLocalXpi = {
                        pickXpiLauncher.launch(
                            arrayOf(
                                "application/x-xpinstall",
                                "application/zip",
                                "application/octet-stream",
                                "*/*"
                            )
                        )
                    },
                    onInstallFromUrl = { showInstallUrlDialog = true },
                    onDismiss = { showInstallSourceSheet = false }
                )
            }

            if (showInstallUrlDialog) {
                InstallFromUrlDialog(
                    onConfirm = { url ->
                        showInstallUrlDialog = false
                        selectedTab = ExtensionsTab.Installed
                        val name = Uri.parse(url).lastPathSegment?.removeSuffix(".xpi")?.ifBlank { "Extension" } ?: "Extension"
                        UhExtensionManager.installFromUrl(url = url, key = url, name = name)
                    },
                    onDismiss = { showInstallUrlDialog = false }
                )
            }

            val catalogDetail = selectedCatalogAddon
            if (catalogDetail != null) {
                CatalogAddonDetailSheet(
                    addon = catalogDetail,
                    isInstalled = catalogDetail.guid in installedIds,
                    activeTask = activeTasks[catalogDetail.guid],
                    onInstall = {
                        UhExtensionManager.installFromUrl(
                            url = catalogDetail.xpiUrl,
                            key = catalogDetail.guid,
                            name = catalogDetail.name
                        )
                    },
                    onCancelInstall = { UhExtensionManager.cancelInstall(catalogDetail.guid) },
                    onOpenInstalledDetails = {
                        selectedTab = ExtensionsTab.Installed
                        selectedInstalledId = catalogDetail.guid
                    },
                    onOpenHomepage = openInBrowser,
                    onDismiss = { selectedCatalogAddon = null }
                )
            }

            val installedDetail = selectedInstalledId?.let { id ->
                installedExtensions.firstOrNull { it.id == id }
            }
            if (installedDetail != null) {
                InstalledExtensionDetailSheet(
                    item = installedDetail,
                    isUpdating = activeTasks.containsKey(installedDetail.id),
                    isCheckingUpdates = isCheckingUpdates,
                    onToggleEnabled = { UhExtensionManager.setEnabled(installedDetail, it) },
                    onTogglePrivateBrowsing = { UhExtensionManager.setAllowedInPrivateBrowsing(installedDetail, it) },
                    onOpenOptions = openInBrowser,
                    onOpenHomepage = openInBrowser,
                    onUpdateNow = { UhExtensionManager.updateExtension(installedDetail.id) },
                    onCheckUpdates = { UhExtensionManager.checkForUpdates(force = true) },
                    onUninstall = { UhExtensionManager.uninstall(installedDetail) },
                    onDismiss = { selectedInstalledId = null }
                )
            }

            val activePrompt = pendingPrompt
            if (activePrompt != null) {
                ExtensionPermissionPromptSheet(prompt = activePrompt)
            }
        }
    }

    private fun resolveFileDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx)?.removeSuffix(".xpi") else null
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
