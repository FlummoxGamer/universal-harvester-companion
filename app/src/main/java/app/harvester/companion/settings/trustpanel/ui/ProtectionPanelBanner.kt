package app.harvester.companion.settings.trustpanel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import app.harvester.companion.R

private val GradientSubtleStop2Light = Color(0xFFE9DAFB)
private val GradientSubtleStop3Light = Color(0xFFFFE3CE)
private val GradientSubtleStop2Dark = Color(0xFFAB71FF)
private val GradientSubtleStop3Dark = Color(0xFFFF8A50)

@Immutable
private data class ProtectionPanelBannerContent(
    val iconId: Int,
    val title: String,
    val description: String?,
    val backgroundColor: Color,
    val isGradient: Boolean = false,
)

@Composable
private fun protectionPanelBannerContent(
    isSecured: Boolean,
    isTrackingProtectionEnabled: Boolean,
    numberOfTrackersBlocked: Int,
): ProtectionPanelBannerContent {
    val defaultBackground = MaterialTheme.colorScheme.surfaceVariant
    val appName = "UH"
    val protectedTitle = stringResource(id = R.string.protection_panel_banner_protected_title, appName)
    return when {
        !isSecured ->
            ProtectionPanelBannerContent(
                iconId = R.drawable.ic_shield,
                title = stringResource(id = R.string.protection_panel_banner_not_secure_title),
                description = stringResource(id = R.string.protection_panel_banner_not_secure_description),
                backgroundColor = defaultBackground,
            )
        !isTrackingProtectionEnabled ->
            ProtectionPanelBannerContent(
                iconId = R.drawable.ic_shield,
                title = stringResource(id = R.string.protection_panel_banner_not_protected_title),
                description = stringResource(id = R.string.protection_panel_banner_not_protected_description, appName),
                backgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        else ->
            ProtectionPanelBannerContent(
                iconId = R.drawable.ic_shield,
                title = protectedTitle,
                description = if (numberOfTrackersBlocked > 0) {
                    pluralStringResource(id = R.plurals.protection_panel_banner_protected_blocked_trackers_description, count = numberOfTrackersBlocked, numberOfTrackersBlocked)
                } else {
                    stringResource(id = R.string.protection_panel_banner_protected_no_blocked_trackers_description)
                },
                backgroundColor = defaultBackground,
                isGradient = true,
            )
    }
}

@Composable
internal fun ProtectionPanelBanner(
    isSecured: Boolean,
    isTrackingProtectionEnabled: Boolean,
    numberOfTrackersBlocked: Int,
    onClick: (() -> Unit)? = null,
) {
    val content = protectionPanelBannerContent(isSecured, isTrackingProtectionEnabled, numberOfTrackersBlocked)
    val mergedContentDescription = if (content.description == null) content.title else "${content.title}. ${content.description}"
    val bannerModifier = Modifier.fillMaxWidth()
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .clearAndSetSemantics {
            contentDescription = mergedContentDescription
            if (onClick != null) {
                role = Role.Button
                onClick { onClick(); true }
            }
        }

    if (content.isGradient) {
        ProtectionPanelGradientBanner(title = content.title, description = content.description, modifier = bannerModifier)
    } else {
        Card(
            modifier = bannerModifier,
            colors = CardDefaults.cardColors(containerColor = content.backgroundColor),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            ProtectionPanelBannerRow(content = content, showChevron = onClick != null)
        }
    }
}

@Composable
private fun BannerTexts(title: String, description: String?) {
    Text(text = title, style = MaterialTheme.typography.titleMedium)
    if (description != null) {
        Text(text = description, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ProtectionPanelBannerRow(content: ProtectionPanelBannerContent, showChevron: Boolean) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(id = content.iconId),
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                BannerTexts(title = content.title, description = content.description)
            }
        }
    }
}

@Composable
private fun ProtectionPanelGradientBanner(title: String, description: String?, modifier: Modifier = Modifier) {
    val (stop2, stop3) = if (isSystemInDarkTheme()) {
        GradientSubtleStop2Dark to GradientSubtleStop3Dark
    } else {
        GradientSubtleStop2Light to GradientSubtleStop3Light
    }
    Box(
        modifier = Modifier.clip(MaterialTheme.shapes.extraLarge)
            .background(brush = Brush.horizontalGradient(listOf(stop2, stop3)))
            .then(modifier)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BannerTexts(title = title, description = description)
            }
            Icon(
                painter = painterResource(id = R.drawable.ic_shield),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
