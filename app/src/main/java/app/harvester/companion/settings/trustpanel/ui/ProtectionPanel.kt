package app.harvester.companion.settings.trustpanel.ui

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
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
            numberOfTrackersBlocked = numberOfTrackersBlocked,
            onClick = onTrackerBlockedMenuClick.takeIf { numberOfTrackersBlocked > 0 },
        )

        MenuGroup {
            MenuItem(
                label = "Tracking Protection",
                description = if (isTrackingProtectionEnabled) "On for this site" else "Off for this site",
                beforeIconPainter = painterResource(id = R.drawable.ic_shield),
                onClick = onTrackingProtectionToggleClick,
            )
        }

        MenuGroup {
            MenuItem(
                label = if (websiteInfoState.isSecured) "Secure connection" else "Not secure",
                description = if (websiteInfoState.isSecured) "Verified connection" else "Connection is not secure",
                beforeIconPainter = painterResource(id = R.drawable.ic_lock),
                onClick = onViewCertificateClick,
            )
        }

        if (!isLocalPdf) {
            MenuGroup {
                MenuItem(
                    label = "Clear cookies and site data",
                    beforeIconPainter = painterResource(id = R.drawable.ic_delete),
                    onClick = onClearSiteDataMenuClick,
                )
            }
        }

        if (websitePermissions.isNotEmpty()) {
            Text(
                text = "Permissions",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp, start = 16.dp)
            )
            MenuGroup {
                websitePermissions.forEach { websitePermission ->
                    val stateDescription = when (websitePermission) {
                        is WebsitePermission.Autoplay -> AutoplayValue.entries.find { it == websitePermission.autoplayValue }?.title?.let { stringResource(it) } ?: ""
                        is WebsitePermission.Toggleable -> if (websitePermission.isBlockedByAndroid) "Blocked by Android" else if (websitePermission.isEnabled) "Allowed" else "Blocked"
                    }
                    MenuItem(
                        label = stringResource(id = websitePermission.deviceFeature.getLabelId()),
                        beforeIconPainter = painterResource(id = websitePermission.deviceFeature.getIconId()),
                        stateDescription = stateDescription,
                        afterContent = {
                            Text(
                                text = stateDescription,
                                color = MaterialTheme.colorScheme.tertiary,
                                textAlign = TextAlign.End,
                                modifier = Modifier.clickable {
                                    if (websitePermission is WebsitePermission.Toggleable) onToggleablePermissionClick(websitePermission)
                                }
                            )
                        },
                    )
                }
            }
        }

        Text(
            text = "Privacy Settings",
            color = MaterialTheme.colorScheme.tertiary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.padding(top = 16.dp, bottom = 32.dp, start = 16.dp)
                .clickable { onPrivacySecuritySettingsClick() },
        )
    }
}
