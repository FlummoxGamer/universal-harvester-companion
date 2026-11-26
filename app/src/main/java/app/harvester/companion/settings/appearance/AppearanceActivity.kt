package app.harvester.companion.settings.appearance

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.ThemePrefs
import app.harvester.companion.privacy.UhPrivacyController
import app.harvester.companion.settings.components.SettingsCategory
import app.harvester.companion.settings.components.SettingsRow
import app.harvester.companion.settings.components.SettingsSwitchRow
import app.harvester.companion.settings.components.UhSettingsCardGroup
import app.harvester.companion.settings.components.UhSettingsDivider
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme

class AppearanceActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        setContent {
            UHTheme {
                val context = LocalContext.current
                val themeMode by ThemePrefs.modeFlow(context).collectAsState()
                val systemDark = isSystemInDarkTheme()
                val isDarkActive = themeMode == ThemePrefs.MODE_DARK ||
                    (themeMode == ThemePrefs.MODE_SYSTEM && systemDark)

                UhSettingsScaffold(
                    title = "Appearance",
                    onBack = { finish() },
                ) {
                    SettingsCategory("Theme")
                    UhSettingsCardGroup {
                        ThemeOptionRow(
                            title = "Follow System",
                            subtitle = "Match your device dark or light appearance",
                            selected = themeMode == ThemePrefs.MODE_SYSTEM,
                            onClick = {
                                ThemePrefs.setMode(context, ThemePrefs.MODE_SYSTEM)
                                UhPrivacyController.applyAllToEngine(context)
                            },
                        )
                        UhSettingsDivider()
                        ThemeOptionRow(
                            title = "Light",
                            selected = themeMode == ThemePrefs.MODE_LIGHT,
                            onClick = {
                                ThemePrefs.setMode(context, ThemePrefs.MODE_LIGHT)
                                UhPrivacyController.applyAllToEngine(context)
                            },
                        )
                        UhSettingsDivider()
                        ThemeOptionRow(
                            title = "Dark",
                            selected = themeMode == ThemePrefs.MODE_DARK,
                            onClick = {
                                ThemePrefs.setMode(context, ThemePrefs.MODE_DARK)
                                UhPrivacyController.applyAllToEngine(context)
                            },
                        )

                        // Only show AMOLED Dark Mode & Force Dark Mode on Websites when Dark Mode is active
                        AnimatedVisibility(visible = isDarkActive) {
                            Column {
                                UhSettingsDivider()
                                SettingsSwitchRow(
                                    title = "AMOLED Dark Mode",
                                    subtitle = "True pitch-black #000000 surfaces for the entire app and websites",
                                    key = SettingsPrefs.K_ENHANCE_DARK,
                                    default = false,
                                    insideCard = true,
                                    onChanged = { UhPrivacyController.applyAllToEngine(context) },
                                )
                                UhSettingsDivider()
                                SettingsSwitchRow(
                                    title = "Force Dark Mode on Websites",
                                    subtitle = "Smart dark styling for bright websites while preserving images and native dark pages",
                                    key = SettingsPrefs.K_FORCE_DARK_SITES,
                                    default = false,
                                    insideCard = true,
                                    onChanged = { UhPrivacyController.applyAllToEngine(context) },
                                )
                            }
                        }
                    }

                    SettingsCategory("Customize")
                    UhSettingsCardGroup {
                        SettingsRow(
                            title = "Home & Wallpaper",
                            subtitle = "Wallpapers, motion backgrounds, stats & recents",
                            iconRes = R.drawable.fx_ic_appearance_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = {
                                context.startActivity(Intent(context, CustomizeHomeActivity::class.java))
                            },
                        )
                        UhSettingsDivider()
                        SettingsRow(
                            title = "Customize Address Bar",
                            subtitle = "Position, auto-hide & URL formatting",
                            iconRes = R.drawable.fx_ic_settings_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = {
                                context.startActivity(Intent(context, CustomizeAddressBarActivity::class.java))
                            },
                        )
                        UhSettingsDivider()
                        SettingsRow(
                            title = "Tabs",
                            subtitle = "Layout, strips, groups, auto-sleep & private security",
                            iconRes = R.drawable.uh_ic_tab_modern_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = {
                                context.startActivity(Intent(context, TabSettingsActivity::class.java))
                            },
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun ThemeOptionRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
