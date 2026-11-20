package app.harvester.companion.settings.data

import android.graphics.Color
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Toast
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import app.harvester.companion.R
import app.harvester.companion.settings.components.SettingsCategory
import app.harvester.companion.settings.components.SettingsRow
import app.harvester.companion.settings.components.UhSettingsCardGroup
import app.harvester.companion.settings.components.UhSettingsDivider
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme
import java.io.File

class ManageDataActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContent {
            UHTheme {
                val context = LocalContext.current
                var showClearSessionsConfirm by remember { mutableStateOf(false) }

                if (showClearSessionsConfirm) {
                    AlertDialog(
                        onDismissRequest = { showClearSessionsConfirm = false },
                        title = { Text("Clear captured sessions?", color = MaterialTheme.colorScheme.onSurface) },
                        text = {
                            Text(
                                "Removes all session folders from app storage.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showClearSessionsConfirm = false
                                    try {
                                        File(filesDir, "sessions").deleteRecursively()
                                        Toast.makeText(context, "Sessions cleared", Toast.LENGTH_SHORT).show()
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "Failed", Toast.LENGTH_SHORT).show()
                                    }
                                }
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
                        shape = RoundedCornerShape(20.dp)
                    )
                }

                UhSettingsScaffold(
                    title = "Manage Personal Data",
                    onBack = { finish() }
                ) {
                    SettingsCategory("Site & Browser Data")
                    UhSettingsCardGroup {
                        SettingsRow(
                            title = "Clear cookies",
                            subtitle = "Remove stored login sessions and cookies",
                            iconRes = R.drawable.fx_ic_delete_24,
                            insideCard = true,
                            onClick = {
                                try {
                                    CookieManager.getInstance().removeAllCookies(null)
                                    CookieManager.getInstance().flush()
                                    Toast.makeText(context, "Cookies cleared", Toast.LENGTH_SHORT).show()
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Failed", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                        UhSettingsDivider()
                        SettingsRow(
                            title = "Clear cache",
                            subtitle = "Clear local web storage and site caches",
                            iconRes = R.drawable.fx_ic_storage_24,
                            insideCard = true,
                            onClick = {
                                try {
                                    WebStorage.getInstance().deleteAllData()
                                    Toast.makeText(context, "Cache cleared", Toast.LENGTH_SHORT).show()
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Failed", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                        UhSettingsDivider()
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
                            }
                        )
                        UhSettingsDivider()
                        SettingsRow(
                            title = "Clear all captured session files",
                            subtitle = "Delete saved capture session folders",
                            iconRes = R.drawable.fx_ic_delete_24,
                            iconTint = MaterialTheme.colorScheme.error,
                            titleColor = MaterialTheme.colorScheme.error,
                            insideCard = true,
                            onClick = { showClearSessionsConfirm = true }
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}
