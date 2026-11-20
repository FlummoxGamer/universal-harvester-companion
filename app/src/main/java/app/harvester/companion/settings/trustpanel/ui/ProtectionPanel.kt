package app.harvester.companion.settings.trustpanel.ui

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.components.menu.compose.MenuGroup
import app.harvester.companion.components.menu.compose.MenuItem
import app.harvester.companion.components.menu.compose.MenuScaffold
import app.harvester.companion.settings.trustpanel.store.AutoplayValue
import app.harvester.companion.settings.trustpanel.store.WebsiteInfoState
import app.harvester.companion.settings.trustpanel.store.WebsitePermission

@Composable
internal fun ProtectionPanel(
    websiteInfoState: WebsiteInfoState,
    icon: Bitmap?,
    isTrackingProtectionEnabled: Boolean,
    isGlobalTrackingProtectionEnabled: Boolean,
    isLocalPdf: Boolean,
    numberOfTrackersBlocked: Int,
    ublockInstalled: Boolean = false,
    ublockGlobalEnabled: Boolean = true,
    ublockSitePaused: Boolean = false,
    ublockVersion: String = "",
    ublockBlockedCount: Int = 0,
    onToggleUblockSite: () -> Unit = {},
    onOpenExtensions: () -> Unit = {},
    websitePermissions: List<WebsitePermission>,
    onTrackerBlockedMenuClick: () -> Unit,
    onTrackingProtectionToggleClick: () -> Unit,
    onClearSiteDataMenuClick: () -> Unit,
    onPrivacySecuritySettingsClick: () -> Unit,
    onAutoplayValueClick: (AutoplayValue) -> Unit,
    onToggleablePermissionClick: (WebsitePermission.Toggleable) -> Unit,
    onViewCertificateClick: () -> Unit,
) {
    MenuScaffold {
        ProtectionPanelBanner(
            isSecured = websiteInfoState.isSecured,
            isTrackingProtectionEnabled = isGlobalTrackingProtectionEnabled && (isTrackingProtectionEnabled || isLocalPdf),
            numberOfTrackersBlocked = numberOfTrackersBlocked + if (ublockInstalled && ublockGlobalEnabled && !ublockSitePaused) ublockBlockedCount else 0,
            onClick = onTrackerBlockedMenuClick.takeIf { numberOfTrackersBlocked > 0 },
        )

        Spacer(modifier = Modifier.height(6.dp))

        // uBlock Origin Adblocker card integrated directly into Trust Panel
        MenuGroup {
            val ublockStatusDesc = when {
                !ublockInstalled -> "Provisioning uBlock Origin..."
                !ublockGlobalEnabled -> "Disabled globally in Extensions"
                ublockSitePaused -> "Paused for this site"
                ublockVersion.isNotBlank() -> "Active (v$ublockVersion)"
                else -> "Active protection"
            }
            MenuItem(
                label = "uBlock Origin Adblocker",
                description = ublockStatusDesc,
                beforeIconPainter = painterResource(id = R.drawable.fx_ic_shield_lock_24),
                onClick = if (ublockInstalled && ublockGlobalEnabled) onToggleUblockSite else onOpenExtensions,
                afterContent = {
                    if (ublockInstalled && ublockGlobalEnabled) {
                        Switch(
                            checked = !ublockSitePaused,
                            onCheckedChange = { onToggleUblockSite() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.background,
                                checkedTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    } else {
                        Text(
                            text = "Manage",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { onOpenExtensions() }
                        )
                    }
                }
            )
        }

        MenuGroup {
            MenuItem(
                label = "Tracking Protection",
                description = if (isTrackingProtectionEnabled) "On for this site" else "Off for this site",
                beforeIconPainter = painterResource(id = R.drawable.ic_shield),
                onClick = onTrackingProtectionToggleClick,
                afterContent = {
                    Switch(
                        checked = isTrackingProtectionEnabled,
                        onCheckedChange = { onTrackingProtectionToggleClick() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.background,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            )
        }

        MenuGroup {
            MenuItem(
                label = if (websiteInfoState.isSecured) "Secure connection" else "Not secure",
                description = if (websiteInfoState.isSecured) "Tap to view TLS certificate" else "Unencrypted HTTP connection",
                beforeIconPainter = painterResource(
                    id = if (websiteInfoState.isSecured) R.drawable.uh_ic_lock else R.drawable.uh_ic_lock_open
                ),
                onClick = onViewCertificateClick,
            )
        }

        if (!isLocalPdf) {
            MenuGroup {
                MenuItem(
                    label = "Clear cookies and site data",
                    beforeIconPainter = painterResource(id = R.drawable.fx_ic_delete_24),
                    onClick = onClearSiteDataMenuClick,
                )
            }
        }

        if (websitePermissions.isNotEmpty()) {
            Text(
                text = "Permissions",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp, start = 16.dp)
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
                            is WebsitePermission.Toggleable -> if (websitePermission.isBlockedByAndroid) "Blocked by Android" else if (websitePermission.isEnabled) "Allowed" else "Blocked"
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
                                    textAlign = TextAlign.End
                                )
                            },
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 24.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Privacy Settings",
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { onPrivacySecuritySettingsClick() }
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Extensions",
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { onOpenExtensions() }
            )
        }
    }
}
