package app.harvester.companion.settings.data

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.harvester.companion.R
import app.harvester.companion.UHApp
import app.harvester.companion.UHLog
import app.harvester.companion.settings.components.SettingsCategory
import app.harvester.companion.settings.components.SettingsRow
import app.harvester.companion.settings.components.UhSettingsCardGroup
import app.harvester.companion.settings.components.UhSettingsDivider
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.concept.engine.Engine
import java.io.File

class ManageDataActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )

        setContent {
            UHTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val components = remember { (context.applicationContext as UHApp).components }
                var showClearSessionsConfirm by remember { mutableStateOf(false) }
                var showClearAllConfirm by remember { mutableStateOf(false) }
                var showClearHistoryConfirm by remember { mutableStateOf(false) }

                fun clearGeckoData(
                    browsingData: Engine.BrowsingData,
                    successMessage: String,
                    alsoClearLegacyWebViewCookies: Boolean = false,
                    alsoClearLegacyWebStorage: Boolean = false,
                    alsoClearHttpCache: Boolean = false,
                ) {
                    try {
                        components.engine.clearData(
                            data = browsingData,
                            onSuccess = {
                                if (alsoClearLegacyWebViewCookies) {
                                    try {
                                        CookieManager.getInstance().removeAllCookies(null)
                                        CookieManager.getInstance().flush()
                                    } catch (_: Exception) {}
                                }
                                if (alsoClearLegacyWebStorage) {
                                    try {
                                        WebStorage.getInstance().deleteAllData()
                                    } catch (_: Exception) {}
                                }
                                if (alsoClearHttpCache) {
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            File(context.cacheDir, "shared_media").deleteRecursively()
                                        } catch (_: Exception) {}
                                    }
                                }
                                runOnUiThread {
                                    Toast.makeText(context, successMessage, Toast.LENGTH_SHORT).show()
                                }
                            },
                            onError = { err ->
                                UHLog.w("data", "GeckoView clearData failed: ${err.message}")
                                runOnUiThread {
                                    Toast.makeText(context, "Failed to clear data", Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    } catch (e: Exception) {
                        UHLog.w("data", "clearGeckoData exception: ${e.message}")
                        Toast.makeText(context, "Failed to clear data", Toast.LENGTH_SHORT).show()
                    }
                }

                if (showClearHistoryConfirm) {
                    AlertDialog(
                        onDismissRequest = { showClearHistoryConfirm = false },
                        title = { Text("Clear browsing history?", color = MaterialTheme.colorScheme.onSurface) },
                        text = {
                            Text(
                                "Permanently deletes all visited sites and search history from UH Browser.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showClearHistoryConfirm = false
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            components.placesHistoryStorage.deleteEverything()
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(context, "Browsing history cleared", Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            UHLog.w("data", "Clear history failed: ${e.message}")
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(context, "Failed to clear history", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                },
                            ) {
                                Text("Clear", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearHistoryConfirm = false }) {
                                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp),
                    )
                }

                if (showClearSessionsConfirm) {
                    AlertDialog(
                        onDismissRequest = { showClearSessionsConfirm = false },
                        title = { Text("Clear captured sessions?", color = MaterialTheme.colorScheme.onSurface) },
                        text = {
                            Text(
                                "Removes all session folders from app storage.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showClearSessionsConfirm = false
                                    scope.launch(Dispatchers.IO) {
                                        val ok = try {
                                            File(filesDir, "sessions").deleteRecursively()
                                            true
                                        } catch (_: Exception) {
                                            false
                                        }
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(
                                                context,
                                                if (ok) "Sessions cleared" else "Failed",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                                },
                            ) {
                                Text("Clear", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearSessionsConfirm = false }) {
                                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp),
                    )
                }

                if (showClearAllConfirm) {
                    AlertDialog(
                        onDismissRequest = { showClearAllConfirm = false },
                        title = { Text("Clear all browsing data?", color = MaterialTheme.colorScheme.onSurface) },
                        text = {
                            Text(
                                "Clears all GeckoView cookies, site storage, network caches, auth sessions, and browsing history.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showClearAllConfirm = false
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            components.placesHistoryStorage.deleteEverything()
                                        } catch (_: Exception) {}
                                    }
                                    clearGeckoData(
                                        browsingData = Engine.BrowsingData.all(),
                                        successMessage = "All browsing data cleared",
                                        alsoClearLegacyWebViewCookies = true,
                                        alsoClearLegacyWebStorage = true,
                                        alsoClearHttpCache = true,
                                    )
                                },
                            ) {
                                Text("Clear All", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearAllConfirm = false }) {
                                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp),
                    )
                }

                UhSettingsScaffold(
                    title = "Manage Personal Data",
                    onBack = { finish() },
                ) {
                    SettingsCategory("Site & Browser Data")
                    UhSettingsCardGroup {
                        SettingsRow(
                            title = "Clear cookies & logins",
                            subtitle = "Remove GeckoView cookies, site sessions & auth tokens",
                            iconRes = R.drawable.uh_ic_cookies_24,
                            insideCard = true,
                            onClick = {
                                clearGeckoData(
                                    browsingData = Engine.BrowsingData.select(
                                        Engine.BrowsingData.COOKIES,
                                        Engine.BrowsingData.AUTH_SESSIONS,
                                    ),
                                    successMessage = "Cookies & sessions cleared",
                                    alsoClearLegacyWebViewCookies = true,
                                )
                            },
                        )
                        UhSettingsDivider()
                        SettingsRow(
                            title = "Clear cache & site storage",
                            subtitle = "Clear GeckoView disk/memory caches, IndexedDB & localStorage",
                            iconRes = R.drawable.fx_ic_storage_24,
                            insideCard = true,
                            onClick = {
                                clearGeckoData(
                                    browsingData = Engine.BrowsingData.select(
                                        Engine.BrowsingData.ALL_CACHES,
                                        Engine.BrowsingData.DOM_STORAGES,
                                    ),
                                    successMessage = "Cache & site storage cleared",
                                    alsoClearLegacyWebStorage = true,
                                    alsoClearHttpCache = true,
                                )
                            },
                        )
                        UhSettingsDivider()
                        SettingsRow(
                            title = "Clear browsing history",
                            subtitle = "Delete visited URLs and search history",
                            iconRes = R.drawable.ic_history,
                            insideCard = true,
                            onClick = { showClearHistoryConfirm = true },
                        )
                        UhSettingsDivider()
                        SettingsRow(
                            title = "Clear all browsing data",
                            subtitle = "Wipe cookies, caches, site data & history at once",
                            iconRes = R.drawable.fx_ic_delete_24,
                            iconTint = MaterialTheme.colorScheme.error,
                            titleColor = MaterialTheme.colorScheme.error,
                            insideCard = true,
                            onClick = { showClearAllConfirm = true },
                        )
                    }

                    SettingsCategory("Tools & Script Storage")
                    UhSettingsCardGroup {
                        SettingsRow(
                            title = "Clear userscript storage",
                            subtitle = "Reset saved script GM values",
                            iconRes = R.drawable.fx_ic_scripts_24,
                            insideCard = true,
                            onClick = {
                                try {
                                    getSharedPreferences("uh_gm", MODE_PRIVATE).edit().clear().apply()
                                    Toast.makeText(context, "Script storage cleared", Toast.LENGTH_SHORT).show()
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Failed", Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                        UhSettingsDivider()
                        SettingsRow(
                            title = "Clear all captured session files",
                            subtitle = "Delete saved capture session folders",
                            iconRes = R.drawable.fx_ic_delete_24,
                            iconTint = MaterialTheme.colorScheme.error,
                            titleColor = MaterialTheme.colorScheme.error,
                            insideCard = true,
                            onClick = { showClearSessionsConfirm = true },
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}
