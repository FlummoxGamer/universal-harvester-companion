package app.harvester.companion.settings

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import app.harvester.companion.BuildConfig
import app.harvester.companion.R
import app.harvester.companion.UpdateChecker
import app.harvester.companion.data.downloads.ui.UhDownloadSettingsScreen
import app.harvester.companion.settings.accessibility.AccessibilityActivity
import app.harvester.companion.settings.addons.ExtensionsActivity
import app.harvester.companion.settings.addons.ScriptsActivity
import app.harvester.companion.settings.appearance.AppearanceActivity
import app.harvester.companion.settings.components.SettingsCategory
import app.harvester.companion.settings.components.SettingsRow
import app.harvester.companion.settings.components.UhSettingsCardGroup
import app.harvester.companion.settings.components.UhSettingsDivider
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.settings.data.ManageDataActivity
import app.harvester.companion.settings.privacy.PrivacyGuardActivity
import app.harvester.companion.settings.tools.LogsActivity
import app.harvester.companion.settings.video.VideoOptionsActivity
import app.harvester.companion.theme.UHTheme

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContent {
            UHTheme {
                val context = LocalContext.current
                var showDownloadSettings by remember { mutableStateOf(false) }

                if (showDownloadSettings) {
                    UhDownloadSettingsScreen(onBack = { showDownloadSettings = false })
                } else {
                    UhSettingsScaffold(
                        title = "Settings",
                        subtitle = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        onBack = { finish() }
                    ) {
                        SettingsCategory("General")
                        UhSettingsCardGroup {
                            SettingsRow(
                                title = "Search Engine",
                                iconRes = R.drawable.fx_ic_search_24,
                                showChevron = true,
                                insideCard = true,
                                onClick = {
                                    context.startActivity(Intent(context, UHSearchSettingsActivity::class.java))
                                }
                            )
                            UhSettingsDivider()
                            SettingsRow(
                                title = "Appearance",
                                subtitle = "Theme, home wallpaper & address bar",
                                iconRes = R.drawable.fx_ic_appearance_24,
                                showChevron = true,
                                insideCard = true,
                                onClick = {
                                    context.startActivity(Intent(context, AppearanceActivity::class.java))
                                }
                            )
                            UhSettingsDivider()
                            SettingsRow(
                                title = "Scripts",
                                iconRes = R.drawable.fx_ic_scripts_24,
                                showChevron = true,
                                insideCard = true,
                                onClick = {
                                    context.startActivity(Intent(context, ScriptsActivity::class.java))
                                }
                            )
                            UhSettingsDivider()
                            SettingsRow(
                                title = "Extensions",
                                iconRes = R.drawable.fx_ic_extensions_24,
                                showChevron = true,
                                insideCard = true,
                                onClick = {
                                    context.startActivity(Intent(context, ExtensionsActivity::class.java))
                                }
                            )
                            UhSettingsDivider()
                            SettingsRow(
                                title = "Video Options",
                                iconRes = R.drawable.fx_ic_video_24,
                                showChevron = true,
                                insideCard = true,
                                onClick = {
                                    context.startActivity(Intent(context, VideoOptionsActivity::class.java))
                                }
                            )
                            UhSettingsDivider()
                            SettingsRow(
                                title = "Download Settings",
                                iconRes = R.drawable.fx_ic_downloads_24,
                                showChevron = true,
                                insideCard = true,
                                onClick = { showDownloadSettings = true }
                            )
                            UhSettingsDivider()
                            SettingsRow(
                                title = "Accessibility",
                                iconRes = R.drawable.fx_ic_accessibility_24,
                                showChevron = true,
                                insideCard = true,
                                onClick = {
                                    context.startActivity(Intent(context, AccessibilityActivity::class.java))
                                }
                            )
                        }

                        SettingsCategory("Privacy & Security")
                        UhSettingsCardGroup {
                            SettingsRow(
                                title = "Privacy Guard",
                                iconRes = R.drawable.fx_ic_shield_lock_24,
                                showChevron = true,
                                insideCard = true,
                                onClick = {
                                    context.startActivity(Intent(context, PrivacyGuardActivity::class.java))
                                }
                            )
                            UhSettingsDivider()
                            SettingsRow(
                                title = "Lock Access",
                                iconRes = R.drawable.fx_ic_lock_24,
                                showChevron = true,
                                insideCard = true,
                                onClick = {
                                    Toast.makeText(context, "Lock Access coming soon", Toast.LENGTH_SHORT).show()
                                }
                            )
                            UhSettingsDivider()
                            SettingsRow(
                                title = "Manage Personal Data",
                                iconRes = R.drawable.fx_ic_storage_24,
                                showChevron = true,
                                insideCard = true,
                                onClick = {
                                    context.startActivity(Intent(context, ManageDataActivity::class.java))
                                }
                            )
                        }

                        SettingsCategory("About")
                        UhSettingsCardGroup {
                            SettingsRow(
                                title = "Version",
                                subtitle = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                iconRes = R.drawable.fx_ic_info_24,
                                showChevron = true,
                                insideCard = true,
                                onClick = {
                                    Toast.makeText(
                                        context,
                                        "UH ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            )
                            UhSettingsDivider()
                            SettingsRow(
                                title = "Check for update",
                                iconRes = R.drawable.fx_ic_update_24,
                                showChevron = true,
                                insideCard = true,
                                onClick = { UpdateChecker.checkAndPrompt(this@SettingsActivity) }
                            )
                            UhSettingsDivider()
                            SettingsRow(
                                title = "Logs",
                                iconRes = R.drawable.fx_ic_logs_24,
                                showChevron = true,
                                insideCard = true,
                                onClick = {
                                    context.startActivity(Intent(context, LogsActivity::class.java))
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        UpdateChecker.resumePendingInstallIfPossible(this)
    }
}
