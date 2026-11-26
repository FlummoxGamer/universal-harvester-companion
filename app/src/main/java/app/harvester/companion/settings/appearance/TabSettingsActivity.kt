package app.harvester.companion.settings.appearance

import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.browser.tabs.PrivateAccentColor
import app.harvester.companion.browser.tabs.PrivateLockManager
import app.harvester.companion.settings.components.SettingsCategory
import app.harvester.companion.settings.components.SettingsRow
import app.harvester.companion.settings.components.SettingsSwitchRow
import app.harvester.companion.settings.components.UhSettingsCardGroup
import app.harvester.companion.settings.components.UhSettingsDivider
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme

/**
 * Unified, full-screen edge-to-edge Tab Settings screen launched from both
 * Appearance -> Tabs and Tabs Tray Menu -> Tab settings.
 */
class TabSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        setContent {
            UHTheme {
                val context = LocalContext.current
                val tabViewMode by SettingsPrefs.tabViewModeFlow(context).collectAsState()
                val isListView = tabViewMode == 1

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
                    TabChoiceDialog(
                        title = "Auto-sleep background tabs",
                        options = listOf(0 to "Never", 30 to "30 seconds", 120 to "2 minutes", 300 to "5 minutes"),
                        selectedValue = sleepSeconds,
                        onSelect = {
                            sleepSeconds = it
                            SettingsPrefs.setInt(context, SettingsPrefs.K_TAB_SLEEP_SECONDS, it)
                            showSleepPicker = false
                        },
                        onDismiss = { showSleepPicker = false },
                    )
                }

                if (showInactivePicker) {
                    TabChoiceDialog(
                        title = "Move tabs to Inactive",
                        options = listOf(0 to "Off", 7 to "After 7 days", 14 to "After 14 days", 30 to "After 30 days"),
                        selectedValue = inactiveDays,
                        onSelect = {
                            inactiveDays = it
                            SettingsPrefs.setInt(context, SettingsPrefs.K_TAB_INACTIVE_DAYS, it)
                            showInactivePicker = false
                        },
                        onDismiss = { showInactivePicker = false },
                    )
                }

                if (showAutoClosePicker) {
                    TabChoiceDialog(
                        title = "Auto-close old tabs",
                        options = listOf(0 to "Never", 1 to "After 1 day", 7 to "After 1 week", 30 to "After 1 month"),
                        selectedValue = autoCloseDays,
                        onSelect = {
                            autoCloseDays = it
                            SettingsPrefs.setInt(context, SettingsPrefs.K_TAB_AUTO_CLOSE_DAYS, it)
                            showAutoClosePicker = false
                        },
                        onDismiss = { showAutoClosePicker = false },
                    )
                }

                UhSettingsScaffold(
                    title = "Tabs",
                    onBack = { finish() },
                ) {
                    SettingsCategory("Layout & Strips")
                    UhSettingsCardGroup {
                        TabLayoutPickerRow(
                            isListView = isListView,
                            onSelectList = { list ->
                                SettingsPrefs.setInt(context, SettingsPrefs.K_TAB_VIEW_MODE, if (list) 1 else 0)
                            },
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Group tab strip",
                            subtitle = "Show sleek docked pill switcher near address bar when browsing in a group",
                            key = SettingsPrefs.K_TAB_GROUP_STRIP_ENABLED,
                            default = true,
                            insideCard = true,
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Landscape desktop tab bar",
                            subtitle = "Show a docked horizontal tab strip at the top in landscape & tablet mode",
                            key = SettingsPrefs.K_TAB_LANDSCAPE_STRIP_ENABLED,
                            default = false,
                            insideCard = true,
                        )
                    }

                    SettingsCategory("Behavior")
                    UhSettingsCardGroup {
                        SettingsSwitchRow(
                            title = "Open new tabs next to current",
                            subtitle = "Place newly opened tabs right beside your active tab",
                            key = SettingsPrefs.K_TAB_OPEN_NEXT_TO_CURRENT,
                            default = true,
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
                            title = "Save recently closed tabs",
                            subtitle = "Keep a quick restore history for closed normal tabs (never records private tabs)",
                            key = SettingsPrefs.K_TAB_RECENTLY_CLOSED_ENABLED,
                            default = true,
                            insideCard = true,
                        )
                    }

                    SettingsCategory("Memory & Lifecycle")
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
                            subtitle = "Suspend idle tabs to save RAM & battery ($sleepLabel)",
                            iconRes = R.drawable.uh_ic_sleep_moon_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = { showSleepPicker = true },
                        )
                        UhSettingsDivider()
                        val inactiveLabel = if (inactiveDays == 0) "Off" else "After $inactiveDays days"
                        SettingsRow(
                            title = "Inactive tabs",
                            subtitle = "Group untouched normal tabs cleanly ($inactiveLabel)",
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
                            subtitle = "Archive unpinned old tabs automatically ($autoCloseLabel)",
                            iconRes = R.drawable.uh_ic_tab_modern_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = { showAutoClosePicker = true },
                        )
                    }

                    SettingsCategory("Private Browsing Security")
                    UhSettingsCardGroup {
                        SettingsSwitchRow(
                            title = "Block screenshots & Recents",
                            subtitle = "Apply FLAG_SECURE to hide Private tabs from screenshots and app switcher",
                            key = SettingsPrefs.K_TAB_PRIVATE_SECURE_SCREEN,
                            default = true,
                            insideCard = true,
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Lock Private tabs when leaving app",
                            subtitle = "Require fingerprint, face, or device PIN to resume Private tabs",
                            key = SettingsPrefs.K_TAB_PRIVATE_LOCK_ENABLED,
                            default = false,
                            insideCard = true,
                            onChanged = { enabled ->
                                if (enabled && !PrivateLockManager.isDeviceAuthAvailable(context)) {
                                    SettingsPrefs.setBool(context, SettingsPrefs.K_TAB_PRIVATE_LOCK_ENABLED, false)
                                    Toast.makeText(
                                        context,
                                        "Set up a device screen lock or biometric first",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    PrivateLockManager.onLockSettingChanged(enabled)
                                }
                            },
                        )
                        UhSettingsDivider()
                        HardenedPrivateSummaryRow()
                    }

                    Spacer(modifier = Modifier.height(28.dp))
                }
            }
        }
    }
}

@Composable
private fun TabLayoutPickerRow(
    isListView: Boolean,
    onSelectList: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column {
            Text(
                text = "Tab Switcher Layout",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Applies to Normal, Private, and Groups screens",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LayoutOptionChip(
                label = "Grid Cards",
                iconRes = R.drawable.uh_ic_tab_groups_24,
                selected = !isListView,
                onClick = { onSelectList(false) },
                modifier = Modifier.weight(1f),
            )
            LayoutOptionChip(
                label = "List View",
                iconRes = R.drawable.uh_ic_list_view_24,
                selected = isListView,
                onClick = { onSelectList(true) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LayoutOptionChip(
    label: String,
    iconRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val bg = if (selected) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerLow
    val border = if (selected) accent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val contentColor = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(width = if (selected) 1.4.dp else 0.8.dp, color = border, shape = RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.onSurface else contentColor,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
private fun HardenedPrivateSummaryRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(PrivateAccentColor.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = R.drawable.uh_ic_private_vibe_24),
                contentDescription = null,
                tint = PrivateAccentColor,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "Always-On Stealth Protections",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Strict tracker & 3P cookie isolation, HTTPS-Only, anti-fingerprinting, URL tracker stripping, RAM-only previews, zero search leaks & instant session wipe on close.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun TabChoiceDialog(
    title: String,
    options: List<Pair<Int, String>>,
    selectedValue: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(value) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedValue == value,
                            onClick = { onSelect(value) },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(22.dp),
    )
}
