package app.harvester.companion.settings.appearance

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.harvester.companion.R
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.ThemePrefs
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContent {
            UHTheme {
                val context = LocalContext.current
                val themeMode by ThemePrefs.modeFlow(context).collectAsState()
                var showSleepPicker by remember { mutableStateOf(false) }
                var sleepSeconds by remember {
                    mutableIntStateOf(SettingsPrefs.getInt(context, SettingsPrefs.K_TAB_SLEEP_SECONDS, 120))
                }

                if (showSleepPicker) {
                    val options = listOf(0 to "Never", 30 to "30 seconds", 120 to "2 minutes", 300 to "5 minutes")
                    AlertDialog(
                        onDismissRequest = { showSleepPicker = false },
                        title = { Text("Auto-sleep inactive tabs", color = MaterialTheme.colorScheme.onSurface) },
                        text = {
                            Column {
                                options.forEach { (sec, label) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                sleepSeconds = sec
                                                SettingsPrefs.setInt(context, SettingsPrefs.K_TAB_SLEEP_SECONDS, sec)
                                                showSleepPicker = false
                                            }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = sleepSeconds == sec,
                                            onClick = {
                                                sleepSeconds = sec
                                                SettingsPrefs.setInt(context, SettingsPrefs.K_TAB_SLEEP_SECONDS, sec)
                                                showSleepPicker = false
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showSleepPicker = false }) {
                                Text("Close")
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp)
                    )
                }

                UhSettingsScaffold(
                    title = "Appearance",
                    onBack = { finish() }
                ) {
                    SettingsCategory("Theme")
                    UhSettingsCardGroup {
                        ThemeOptionRow(
                            title = "Follow System",
                            subtitle = "Match your device dark or light appearance",
                            selected = themeMode == ThemePrefs.MODE_SYSTEM,
                            onClick = { ThemePrefs.setMode(context, ThemePrefs.MODE_SYSTEM) }
                        )
                        UhSettingsDivider()
                        ThemeOptionRow(
                            title = "Light",
                            selected = themeMode == ThemePrefs.MODE_LIGHT,
                            onClick = { ThemePrefs.setMode(context, ThemePrefs.MODE_LIGHT) }
                        )
                        UhSettingsDivider()
                        ThemeOptionRow(
                            title = "Dark",
                            selected = themeMode == ThemePrefs.MODE_DARK,
                            onClick = { ThemePrefs.setMode(context, ThemePrefs.MODE_DARK) }
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Enhance Dark Theme",
                            subtitle = "Apply deeper contrast across dark surfaces",
                            key = SettingsPrefs.K_ENHANCE_DARK,
                            default = false,
                            insideCard = true
                        )
                    }

                    SettingsCategory("Customize")
                    UhSettingsCardGroup {
                        SettingsRow(
                            title = "Home & Wallpaper",
                            subtitle = "Wallpapers, motion backgrounds & recents",
                            iconRes = R.drawable.fx_ic_appearance_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = {
                                context.startActivity(Intent(context, CustomizeHomeActivity::class.java))
                            }
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
                            }
                        )
                    }

                    SettingsCategory("Tabs")
                    UhSettingsCardGroup {
                        val sleepLabel = when (sleepSeconds) {
                            0 -> "Never"
                            30 -> "30 seconds"
                            120 -> "2 minutes"
                            300 -> "5 minutes"
                            else -> "${sleepSeconds}s"
                        }
                        SettingsRow(
                            title = "Auto-sleep background tabs",
                            subtitle = "Suspend inactive tabs to save memory ($sleepLabel)",
                            iconRes = R.drawable.uh_ic_tab_modern_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = { showSleepPicker = true }
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Confirm swipe-to-close",
                            subtitle = "Ask before closing tabs by swiping",
                            key = SettingsPrefs.K_TAB_CONFIRM_SWIPE,
                            default = false,
                            insideCard = true
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Auto-collapse inactive groups",
                            subtitle = "Collapse tab groups when opening Tabs tray",
                            key = SettingsPrefs.K_TAB_AUTO_COLLAPSE_GROUPS,
                            default = false,
                            insideCard = true
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
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }
    }
}
