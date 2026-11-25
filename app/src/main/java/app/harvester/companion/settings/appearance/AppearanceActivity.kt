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
import app.harvester.companion.R
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.ThemePrefs
import app.harvester.companion.browser.tabs.PrivateLockManager
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

                var showSleepPicker by remember { mutableStateOf(false) }
                var sleepSeconds by remember {
                    mutableIntStateOf(SettingsPrefs.getInt(context, SettingsPrefs.K_TAB_SLEEP_SECONDS, 120))
                }
                var showInactivePicker by remember { mutableStateOf(false) }
                var inactiveDays by remember {
                    mutableIntStateOf(SettingsPrefs.getInt(context, SettingsPrefs.K_TAB_INACTIVE_DAYS, 14))
                }
                var showAutoClosePicker by remember { mutableStateOf(false) }
                var autoCloseDays by remember {
                    mutableIntStateOf(SettingsPrefs.getInt(context, SettingsPrefs.K_TAB_AUTO_CLOSE_DAYS, 0))
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
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = sleepSeconds == sec,
                                            onClick = {
                                                sleepSeconds = sec
                                                SettingsPrefs.setInt(context, SettingsPrefs.K_TAB_SLEEP_SECONDS, sec)
                                                showSleepPicker = false
                                            },
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
                        shape = RoundedCornerShape(20.dp),
                    )
                }

                if (showInactivePicker) {
                    val options = listOf(0 to "Off", 7 to "After 7 days", 14 to "After 14 days", 30 to "After 30 days")
                    AlertDialog(
                        onDismissRequest = { showInactivePicker = false },
                        title = { Text("Move tabs to Inactive", color = MaterialTheme.colorScheme.onSurface) },
                        text = {
                            Column {
                                options.forEach { (days, label) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                inactiveDays = days
                                                SettingsPrefs.setInt(context, SettingsPrefs.K_TAB_INACTIVE_DAYS, days)
                                                showInactivePicker = false
                                            }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = inactiveDays == days,
                                            onClick = {
                                                inactiveDays = days
                                                SettingsPrefs.setInt(context, SettingsPrefs.K_TAB_INACTIVE_DAYS, days)
                                                showInactivePicker = false
                                            },
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showInactivePicker = false }) {
                                Text("Close")
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp),
                    )
                }

                if (showAutoClosePicker) {
                    val options = listOf(0 to "Never", 1 to "After 1 day", 7 to "After 1 week", 30 to "After 1 month")
                    AlertDialog(
                        onDismissRequest = { showAutoClosePicker = false },
                        title = { Text("Auto-close old tabs", color = MaterialTheme.colorScheme.onSurface) },
                        text = {
                            Column {
                                options.forEach { (days, label) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                autoCloseDays = days
                                                SettingsPrefs.setInt(context, SettingsPrefs.K_TAB_AUTO_CLOSE_DAYS, days)
                                                showAutoClosePicker = false
                                            }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = autoCloseDays == days,
                                            onClick = {
                                                autoCloseDays = days
                                                SettingsPrefs.setInt(context, SettingsPrefs.K_TAB_AUTO_CLOSE_DAYS, days)
                                                showAutoClosePicker = false
                                            },
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showAutoClosePicker = false }) {
                                Text("Close")
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp),
                    )
                }

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
                            onClick = { showSleepPicker = true },
                        )
                        UhSettingsDivider()
                        val inactiveLabel = when (inactiveDays) {
                            0 -> "Off"
                            else -> "After $inactiveDays days"
                        }
                        SettingsRow(
                            title = "Inactive tabs",
                            subtitle = "Group untouched normal tabs ($inactiveLabel)",
                            iconRes = R.drawable.ic_history,
                            showChevron = true,
                            insideCard = true,
                            onClick = { showInactivePicker = true },
                        )
                        UhSettingsDivider()
                        val autoCloseLabel = when (autoCloseDays) {
                            1 -> "After 1 day"
                            7 -> "After 1 week"
                            30 -> "After 1 month"
                            else -> "Never"
                        }
                        SettingsRow(
                            title = "Auto-close old tabs",
                            subtitle = "Archive unpinned old tabs to Recently Closed ($autoCloseLabel)",
                            iconRes = R.drawable.uh_ic_sleep_moon_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = { showAutoClosePicker = true },
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Group tab strip",
                            subtitle = "Show quick group switcher strip near address bar",
                            key = SettingsPrefs.K_TAB_GROUP_STRIP_ENABLED,
                            default = true,
                            insideCard = true,
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Landscape horizontal tab strip",
                            subtitle = "Show a compact desktop-style top tab strip in landscape",
                            key = SettingsPrefs.K_TAB_LANDSCAPE_STRIP_ENABLED,
                            default = false,
                            insideCard = true,
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Jump to opener tab on close",
                            subtitle = "Return to the parent tab when closing a child tab",
                            key = SettingsPrefs.K_TAB_JUMP_TO_PARENT,
                            default = true,
                            insideCard = true,
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Open new tabs next to current",
                            subtitle = "Insert newly created tabs beside the active tab",
                            key = SettingsPrefs.K_TAB_OPEN_NEXT_TO_CURRENT,
                            default = false,
                            insideCard = true,
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Block screenshots in Private mode",
                            subtitle = "Hide Private tabs from screenshots and app recents",
                            key = SettingsPrefs.K_TAB_PRIVATE_SECURE_SCREEN,
                            default = true,
                            insideCard = true,
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Lock Private tabs when leaving app",
                            subtitle = "Require fingerprint, face, or device PIN to reopen Private tabs",
                            key = SettingsPrefs.K_TAB_PRIVATE_LOCK_ENABLED,
                            default = false,
                            insideCard = true,
                            onChanged = { enabled -> PrivateLockManager.onLockSettingChanged(enabled) },
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
