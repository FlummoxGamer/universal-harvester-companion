package app.harvester.companion.settings.privacy

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import app.harvester.companion.R
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.privacy.ProtectionStatsPanel
import app.harvester.companion.privacy.ShieldLevel
import app.harvester.companion.privacy.UhPrivacyController
import app.harvester.companion.privacy.UhShieldEngine
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
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        setContent {
            UHTheme {
                val context = LocalContext.current
                val shieldState by UhShieldEngine.state.collectAsState()

                var httpsMode by remember {
                    val raw = SettingsPrefs.getInt(context, SettingsPrefs.K_PG_HTTPS_MODE, -1)
                    val effective = if (raw >= 0) raw else if (SettingsPrefs.getBool(context, SettingsPrefs.K_PG_HTTPS, true)) 2 else 0
                    mutableIntStateOf(effective)
                }
                var cookieChoice by remember {
                    mutableIntStateOf(SettingsPrefs.getInt(context, SettingsPrefs.K_PG_COOKIES, 0))
                }
                var redirectChoice by remember {
                    mutableIntStateOf(SettingsPrefs.getInt(context, SettingsPrefs.K_PG_REDIRECT, 1))
                }

                var showShieldDialog by remember { mutableStateOf(false) }
                var showHttpsDialog by remember { mutableStateOf(false) }
                var showCookieDialog by remember { mutableStateOf(false) }
                var showRedirectDialog by remember { mutableStateOf(false) }
                var showResetStatsDialog by remember { mutableStateOf(false) }

                val shieldLevels = listOf(ShieldLevel.AGGRESSIVE, ShieldLevel.STANDARD, ShieldLevel.OFF)
                val httpsOptions = listOf(
                    "Off" to "Allow unencrypted HTTP connections",
                    "Private Tabs Only" to "Upgrade connections to HTTPS in Private Browsing",
                    "Always (HTTPS-Only)" to "Upgrade every website to HTTPS automatically",
                )
                val cookieOptions = listOf(
                    "Total Cookie Protection (Recommended)" to "Isolate cookies per site to stop cross-site tracking",
                    "Block All Third-Party Cookies" to "Only allow first-party site cookies",
                    "Block All Cookies" to "Maximum privacy — may break logins on some sites",
                    "Allow All Cookies" to "No cookie restrictions",
                )
                val redirectOptions = listOf(
                    "Block App Redirects" to "Keep links inside UH instead of launching external apps",
                    "Ask Before Opening App" to "Prompt before switching to an installed app",
                    "Always Allow" to "Open supported app links automatically",
                )

                if (showShieldDialog) {
                    ChoiceDialogWithSummary(
                        title = "UH Shield Level",
                        options = shieldLevels.map { it.label to it.summary },
                        selectedIndex = shieldLevels.indexOf(shieldState.level).coerceAtLeast(0),
                        onSelect = { idx ->
                            val chosen = shieldLevels[idx]
                            UhShieldEngine.setLevel(chosen)
                            UhPrivacyController.applyAllToEngine(context)
                            showShieldDialog = false
                        },
                        onDismiss = { showShieldDialog = false },
                    )
                }

                if (showHttpsDialog) {
                    ChoiceDialogWithSummary(
                        title = "Always Use HTTPS",
                        options = httpsOptions,
                        selectedIndex = httpsMode.coerceIn(0, httpsOptions.lastIndex),
                        onSelect = { idx ->
                            httpsMode = idx
                            SettingsPrefs.setInt(context, SettingsPrefs.K_PG_HTTPS_MODE, idx)
                            SettingsPrefs.setBool(context, SettingsPrefs.K_PG_HTTPS, idx > 0)
                            UhPrivacyController.applyAllToEngine(context)
                            showHttpsDialog = false
                        },
                        onDismiss = { showHttpsDialog = false },
                    )
                }

                if (showCookieDialog) {
                    ChoiceDialogWithSummary(
                        title = "Cookie Protection",
                        options = cookieOptions,
                        selectedIndex = cookieChoice.coerceIn(0, cookieOptions.lastIndex),
                        onSelect = { idx ->
                            cookieChoice = idx
                            SettingsPrefs.setInt(context, SettingsPrefs.K_PG_COOKIES, idx)
                            UhPrivacyController.applyAllToEngine(context)
                            showCookieDialog = false
                        },
                        onDismiss = { showCookieDialog = false },
                    )
                }

                if (showRedirectDialog) {
                    ChoiceDialogWithSummary(
                        title = "App Redirects",
                        options = redirectOptions,
                        selectedIndex = redirectChoice.coerceIn(0, redirectOptions.lastIndex),
                        onSelect = { idx ->
                            redirectChoice = idx
                            SettingsPrefs.setInt(context, SettingsPrefs.K_PG_REDIRECT, idx)
                            showRedirectDialog = false
                        },
                        onDismiss = { showRedirectDialog = false },
                    )
                }

                if (showResetStatsDialog) {
                    AlertDialog(
                        onDismissRequest = { showResetStatsDialog = false },
                        title = { Text("Reset Protection Stats?") },
                        text = { Text("This clears all recorded ads blocked, trackers blocked, and time saved counters.") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    UhShieldEngine.resetLifetimeStats()
                                    showResetStatsDialog = false
                                },
                            ) {
                                Text("Reset", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showResetStatsDialog = false }) {
                                Text("Cancel")
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp),
                    )
                }

                val enabledCategoriesCount = shieldState.categories.count { it.enabled }
                val numberFmt = remember { java.text.NumberFormat.getIntegerInstance() }
                val activeRulesText = if (shieldState.totalCompiledRules > 0) {
                    "$enabledCategoriesCount active packs • ${numberFmt.format(shieldState.totalCompiledRules)} rules"
                } else {
                    "$enabledCategoriesCount active protection packs"
                }

                UhSettingsScaffold(
                    title = "Privacy Guard",
                    onBack = { finish() },
                ) {
                    ProtectionStatsPanel(
                        stats = shieldState.lifetimeStats,
                        headerTitle = "Lifetime Protection",
                        onResetClick = { showResetStatsDialog = true },
                    )

                    SettingsCategory("UH Shield (Global)")
                    UhSettingsCardGroup {
                        SettingsRow(
                            title = "Blocking Strength",
                            subtitle = "${shieldState.level.label} — ${shieldState.level.summary}",
                            iconRes = R.drawable.uh_ic_pg_shield_level_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = { showShieldDialog = true },
                        )
                        UhSettingsDivider()
                        SettingsRow(
                            title = "Filter Lists & Customization",
                            subtitle = activeRulesText,
                            iconRes = R.drawable.uh_ic_pg_filter_lists_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = {
                                context.startActivity(Intent(context, FilterListsActivity::class.java))
                            },
                        )
                    }

                    SettingsCategory("Global Protections")
                    UhSettingsCardGroup {
                        SettingsRow(
                            title = "Always Use HTTPS",
                            subtitle = httpsOptions.getOrElse(httpsMode) { httpsOptions[2] }.first,
                            iconRes = R.drawable.uh_ic_pg_https_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = { showHttpsDialog = true },
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Auto-Reject Cookie Banners",
                            subtitle = "Automatically dismiss and reject intrusive consent popups",
                            key = SettingsPrefs.K_PG_COOKIE_BANNERS,
                            default = true,
                            iconRes = R.drawable.uh_ic_pg_cookie_banner_24,
                            insideCard = true,
                            onChanged = { UhPrivacyController.applyAllToEngine(context) },
                        )
                        UhSettingsDivider()
                        SettingsRow(
                            title = "Cookie Protection",
                            subtitle = cookieOptions.getOrElse(cookieChoice) { cookieOptions.first() }.first,
                            iconRes = R.drawable.uh_ic_pg_cookie_jar_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = { showCookieDialog = true },
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Anti-Fingerprinting",
                            subtitle = "Randomizes canvas, WebGL, audio & fonts with unified timezone, locale & header alignment",
                            key = SettingsPrefs.K_PG_FINGERPRINT,
                            default = true,
                            iconRes = R.drawable.uh_ic_pg_fingerprint_24,
                            insideCard = true,
                            onChanged = { UhPrivacyController.applyAllToEngine(context) },
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Auto-Shred Closed Sites",
                            subtitle = "Automatically wipe cookies & site storage when you close a site's last tab",
                            key = SettingsPrefs.K_PG_AUTO_SHRED,
                            default = false,
                            iconRes = R.drawable.uh_ic_pg_cookie_jar_24,
                            insideCard = true,
                            onChanged = { UhPrivacyController.applyAllToEngine(context) },
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Strip URL Trackers",
                            subtitle = "Remove utm, fbclid, gclid & tracking params on browse and copy",
                            key = SettingsPrefs.K_PG_CLEAR_URLS,
                            default = true,
                            iconRes = R.drawable.uh_ic_pg_url_clean_24,
                            insideCard = true,
                            onChanged = { UhPrivacyController.applyAllToEngine(context) },
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Popup & Ad-Tab Blocker",
                            subtitle = "Blocks popunders, click-hijack overlays & betting redirects without breaking normal sites",
                            key = SettingsPrefs.K_PG_POPUP_BLOCK,
                            default = true,
                            iconRes = R.drawable.uh_ic_pg_popup_block_24,
                            insideCard = true,
                            onChanged = { UhPrivacyController.applyAllToEngine(context) },
                        )
                        UhSettingsDivider()
                        SettingsRow(
                            title = "App Redirects",
                            subtitle = redirectOptions.getOrElse(redirectChoice) { redirectOptions[1] }.first,
                            iconRes = R.drawable.uh_ic_pg_app_redirect_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = { showRedirectDialog = true },
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Smart JavaScript Blocking",
                            subtitle = "Silently blocks untrusted 3rd-party scripts while keeping CDNs, logins & media smooth",
                            key = SettingsPrefs.K_PG_SCRIPT,
                            default = false,
                            iconRes = R.drawable.uh_ic_pg_js_block_24,
                            insideCard = true,
                            onChanged = { UhPrivacyController.applyAllToEngine(context) },
                        )
                    }

                    SettingsCategory("Site Controls & Network")
                    UhSettingsCardGroup {
                        SettingsRow(
                            title = "Site Settings",
                            subtitle = "Default permissions, paused Shield sites & per-site rules",
                            iconRes = R.drawable.uh_ic_pg_site_controls_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = {
                                context.startActivity(Intent(context, SiteSettingsActivity::class.java))
                            },
                        )
                        UhSettingsDivider()
                        SettingsRow(
                            title = "Advanced Protections",
                            subtitle = "Safe Browsing, De-AMP, GPC, Secure DNS & WebRTC",
                            iconRes = R.drawable.uh_ic_pg_advanced_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = {
                                context.startActivity(Intent(context, AdvancedActivity::class.java))
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
internal fun ChoiceDialogWithSummary(
    title: String,
    options: List<Pair<String, String>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column {
                options.forEachIndexed { index, (label, desc) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = index == selectedIndex, onClick = { onSelect(index) })
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = label,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            if (desc.isNotBlank()) {
                                Text(
                                    text = desc,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                )
                            }
                        }
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
        shape = RoundedCornerShape(20.dp),
    )
}
