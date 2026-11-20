package app.harvester.companion.settings.privacy

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.harvester.companion.R
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.settings.components.SettingsCategory
import app.harvester.companion.settings.components.SettingsRow
import app.harvester.companion.settings.components.SettingsSwitchRow
import app.harvester.companion.settings.components.UhSettingsCardGroup
import app.harvester.companion.settings.components.UhSettingsDivider
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme

class PrivacyGuardActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContent {
            UHTheme {
                val context = LocalContext.current
                var cookieChoice by remember {
                    mutableIntStateOf(SettingsPrefs.getInt(context, SettingsPrefs.K_PG_COOKIES, 0))
                }
                var redirectChoice by remember {
                    mutableIntStateOf(SettingsPrefs.getInt(context, SettingsPrefs.K_PG_REDIRECT, 1))
                }
                var showCookieDialog by remember { mutableStateOf(false) }
                var showRedirectDialog by remember { mutableStateOf(false) }

                val cookieOptions = listOf("Don't block any cookies", "Block third-party cookies only")
                val redirectOptions = listOf("Stop redirecting to app", "Ask before redirecting", "Always allow")

                if (showCookieDialog) {
                    SingleChoiceDialog(
                        title = "Cookie Management",
                        options = cookieOptions,
                        selectedIndex = cookieChoice.coerceIn(0, cookieOptions.lastIndex),
                        onSelect = { idx ->
                            cookieChoice = idx
                            SettingsPrefs.setInt(context, SettingsPrefs.K_PG_COOKIES, idx)
                            showCookieDialog = false
                        },
                        onDismiss = { showCookieDialog = false }
                    )
                }

                if (showRedirectDialog) {
                    SingleChoiceDialog(
                        title = "Stop App Redirects",
                        options = redirectOptions,
                        selectedIndex = redirectChoice.coerceIn(0, redirectOptions.lastIndex),
                        onSelect = { idx ->
                            redirectChoice = idx
                            SettingsPrefs.setInt(context, SettingsPrefs.K_PG_REDIRECT, idx)
                            showRedirectDialog = false
                        },
                        onDismiss = { showRedirectDialog = false }
                    )
                }

                UhSettingsScaffold(
                    title = "Privacy Guard",
                    onBack = { finish() }
                ) {
                    SettingsCategory("Privacy Control")
                    UhSettingsCardGroup {
                        SettingsSwitchRow(
                            title = "Ads & Trackers Blocker",
                            key = SettingsPrefs.K_PG_ADS,
                            default = false,
                            iconRes = R.drawable.fx_ic_shield_lock_24,
                            insideCard = true
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Always Use HTTPS",
                            key = SettingsPrefs.K_PG_HTTPS,
                            default = false,
                            iconRes = R.drawable.fx_ic_lock_24,
                            insideCard = true
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Remove Fingerprint",
                            key = SettingsPrefs.K_PG_FINGERPRINT,
                            default = false,
                            iconRes = R.drawable.fx_ic_shield_lock_24,
                            insideCard = true
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Script Control",
                            key = SettingsPrefs.K_PG_SCRIPT,
                            default = false,
                            iconRes = R.drawable.fx_ic_scripts_24,
                            insideCard = true
                        )
                        UhSettingsDivider()
                        SettingsRow(
                            title = "Cookie Management",
                            subtitle = cookieOptions.getOrElse(cookieChoice) { cookieOptions.first() },
                            iconRes = R.drawable.fx_ic_storage_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = { showCookieDialog = true }
                        )
                        UhSettingsDivider()
                        SettingsRow(
                            title = "Stop App Redirects",
                            subtitle = redirectOptions.getOrElse(redirectChoice) { redirectOptions[1] },
                            iconRes = R.drawable.fx_ic_link_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = { showRedirectDialog = true }
                        )
                        UhSettingsDivider()
                        SettingsRow(
                            title = "Site Settings",
                            iconRes = R.drawable.fx_ic_settings_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = {
                                Toast.makeText(context, "Site Settings coming soon", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }

                    SettingsCategory("More")
                    UhSettingsCardGroup {
                        SettingsRow(
                            title = "Advanced",
                            subtitle = "Safe Browsing, DNT, GPC & Secure DNS",
                            iconRes = R.drawable.fx_ic_settings_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = {
                                context.startActivity(Intent(context, AdvancedActivity::class.java))
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun SingleChoiceDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column {
                options.forEachIndexed { index, label ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = index == selectedIndex, onClick = { onSelect(index) })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = MaterialTheme.colorScheme.primary)
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp)
    )
}
