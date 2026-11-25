package app.harvester.companion.settings.trustpanel.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.components.menu.compose.MenuGroup
import app.harvester.companion.components.menu.compose.MenuItem
import app.harvester.companion.components.menu.compose.MenuScaffold
import app.harvester.companion.privacy.ProtectionStatsPanel
import app.harvester.companion.privacy.ShieldLevel
import app.harvester.companion.privacy.ShieldStatsSnapshot
import app.harvester.companion.settings.trustpanel.store.AutoplayValue
import app.harvester.companion.settings.trustpanel.store.WebsiteInfoState
import app.harvester.companion.settings.trustpanel.store.WebsitePermission

@Composable
internal fun ProtectionPanel(
    websiteInfoState: WebsiteInfoState,
    siteHost: String,
    siteStats: ShieldStatsSnapshot,
    lifetimeStats: ShieldStatsSnapshot,
    shieldLevel: ShieldLevel,
    isShieldPausedForSite: Boolean,
    showWebDarkToggle: Boolean,
    isWebDarkEnabledForSite: Boolean,
    numberOfGeckoTrackersBlocked: Int,
    requestLogTotalCount: Int,
    requestLogBlockedCount: Int,
    websitePermissions: List<WebsitePermission>,
    onToggleShieldForSite: () -> Unit,
    onToggleWebDarkForSite: () -> Unit,
    onBlockElementClick: () -> Unit,
    onRequestInspectorClick: () -> Unit,
    onForgetSiteClick: () -> Unit,
    onTrackerDetailsClick: () -> Unit,
    onClearSiteDataMenuClick: () -> Unit,
    onPrivacyGuardClick: () -> Unit,
    onSiteSettingsClick: () -> Unit,
    onAutoplayValueClick: (AutoplayValue) -> Unit,
    onToggleablePermissionClick: (WebsitePermission.Toggleable) -> Unit,
    onViewCertificateClick: () -> Unit,
) {
    var showAllTimeStats by remember { mutableStateOf(false) }

    MenuScaffold {
        // Host header
        if (siteHost.isNotBlank()) {
            Text(
                text = siteHost,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        // Clean 3-metric Stats Panel (This Site / All Time)
        ProtectionStatsPanel(
            stats = if (showAllTimeStats) lifetimeStats else siteStats,
            headerTitle = if (showAllTimeStats) "All-Time Protection" else "Blocked on This Site",
            showScopeToggle = true,
            isAllTimeSelected = showAllTimeStats,
            onScopeToggle = { showAllTimeStats = it },
            onCardClick = onTrackerDetailsClick.takeIf { numberOfGeckoTrackersBlocked > 0 },
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Per-Site UH Shield Pause Control (single authoritative switch)
        MenuGroup {
            val isGloballyOff = shieldLevel == ShieldLevel.OFF
            val shieldDesc = when {
                isGloballyOff -> "Turned off globally in Privacy Guard"
                isShieldPausedForSite -> "Paused for this site — tap to resume"
                else -> "Active (${shieldLevel.label}) — blocking ads & trackers"
            }
            MenuItem(
                label = "UH Shield for This Site",
                description = shieldDesc,
                beforeIconPainter = painterResource(id = R.drawable.fx_ic_shield_lock_24),
                onClick = onToggleShieldForSite,
                afterContent = {
                    if (isGloballyOff) {
                        Text(
                            text = "Enable",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        Switch(
                            checked = !isShieldPausedForSite,
                            onCheckedChange = { onToggleShieldForSite() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.background,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                },
            )
        }

        // Quick Privacy Power Tools (Block Element, Live Request Inspector, 1-Tap Forget Site)
        if (siteHost.isNotBlank()) {
            MenuGroup {
                MenuItem(
                    label = "Block Element",
                    description = "Tap any ad, banner, or overlay on this page to hide it",
                    beforeIconPainter = painterResource(id = R.drawable.fx_ic_edit_24),
                    onClick = onBlockElementClick,
                )
                MenuItem(
                    label = "Live Request Inspector",
                    description = if (requestLogTotalCount > 0) {
                        "$requestLogBlockedCount blocked • $requestLogTotalCount requests captured"
                    } else {
                        "Inspect live network requests & create 1-tap rules"
                    },
                    beforeIconPainter = painterResource(id = R.drawable.fx_ic_logs_24),
                    onClick = onRequestInspectorClick,
                )
                MenuItem(
                    label = "Forget This Site",
                    description = "Instantly shred cookies, cache & storage for $siteHost",
                    beforeIconPainter = painterResource(id = R.drawable.fx_ic_delete_24),
                    onClick = onForgetSiteClick,
                )
            }
        }

        // Optional Per-Site Force Dark Mode bypass (only visible when Dark Mode + Force Web Dark are active)
        if (showWebDarkToggle && siteHost.isNotBlank()) {
            MenuGroup {
                MenuItem(
                    label = "Dark Mode on This Site",
                    description = if (isWebDarkEnabledForSite) "Smart dark styling active" else "Bypassed — using site's original theme",
                    beforeIconPainter = painterResource(id = R.drawable.fx_ic_appearance_24),
                    onClick = onToggleWebDarkForSite,
                    afterContent = {
                        Switch(
                            checked = isWebDarkEnabledForSite,
                            onCheckedChange = { onToggleWebDarkForSite() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.background,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                )
            }
        }

        // Connection Security / TLS Certificate
        MenuGroup {
            MenuItem(
                label = if (websiteInfoState.isSecured) "Secure connection" else "Connection not secure",
                description = if (websiteInfoState.isSecured) "Tap to inspect TLS certificate" else "Unencrypted HTTP connection",
                beforeIconPainter = painterResource(
                    id = if (websiteInfoState.isSecured) R.drawable.uh_ic_lock else R.drawable.uh_ic_lock_open,
                ),
                onClick = onViewCertificateClick,
            )
        }

        // Clear Cookies & Site Data
        MenuGroup {
            MenuItem(
                label = "Clear cookies & site data",
                description = "Sign out and clear local storage for this site",
                beforeIconPainter = painterResource(id = R.drawable.fx_ic_delete_24),
                onClick = onClearSiteDataMenuClick,
            )
        }

        // Per-Site Permissions
        if (websitePermissions.isNotEmpty()) {
            Text(
                text = "Site Permissions",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp, start = 16.dp),
            )
            MenuGroup {
                websitePermissions.forEach { websitePermission ->
                    key(websitePermission.deviceFeature) {
                        val stateDescription = when (websitePermission) {
                            is WebsitePermission.Autoplay -> when (websitePermission.autoplayValue) {
                                AutoplayValue.AUTOPLAY_ALLOW_ALL -> "Allowed"
                                AutoplayValue.AUTOPLAY_BLOCK_AUDIBLE -> "Block Audio"
                                AutoplayValue.AUTOPLAY_BLOCK_ALL -> "Blocked"
                            }
                            is WebsitePermission.Toggleable -> if (websitePermission.isBlockedByAndroid) {
                                "Blocked by Android"
                            } else if (websitePermission.isEnabled) {
                                "Allowed"
                            } else {
                                "Blocked"
                            }
                        }
                        MenuItem(
                            label = stringResource(id = websitePermission.deviceFeature.getLabelId()),
                            beforeIconPainter = painterResource(id = websitePermission.deviceFeature.getIconId()),
                            stateDescription = stateDescription,
                            onClick = {
                                when (websitePermission) {
                                    is WebsitePermission.Toggleable -> onToggleablePermissionClick(websitePermission)
                                    is WebsitePermission.Autoplay -> onAutoplayValueClick(websitePermission.autoplayValue)
                                }
                            },
                            afterContent = {
                                Text(
                                    text = stateDescription,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.End,
                                )
                            },
                        )
                    }
                }
            }
        }

        // Footer links to Global Privacy Guard & Site Settings
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 24.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Privacy Guard",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { onPrivacyGuardClick() },
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Site Settings",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { onSiteSettingsClick() },
            )
        }
    }
}
