package app.harvester.companion.settings.addons.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.settings.addons.UBlockOriginManager

@Composable
fun UBlockOriginShieldCard(
    state: UBlockOriginManager.State,
    onToggleEnabled: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    onCancelUpdate: () -> Unit,
    onOpenOptions: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val optionsUrl = state.optionsPageUrl
    val canOpenDashboard = state.isInstalled && !optionsUrl.isNullOrBlank() && onOpenOptions != null

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (canOpenDashboard && optionsUrl != null && onOpenOptions != null) {
                    Modifier.clickable {
                        onOpenOptions(optionsUrl)
                    }
                } else {
                    Modifier
                }
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(
                        id = if (state.isEnabled) R.drawable.uh_ic_shield_checkmark_24 else R.drawable.uh_ic_shield_slash_24
                    ),
                    contentDescription = null,
                    tint = if (state.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = "uBlock Origin",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "Shield",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }

                val statusText = when {
                    state.isUpdating -> "Updating package…"
                    !state.lastError.isNullOrBlank() -> state.lastError
                    state.isInstalled && !state.isEnabled -> "Paused globally"
                    state.isInstalled -> buildString {
                        append(if (state.version.isNotBlank()) "v${state.version}" else "Active")
                        if (state.totalBlockedCount > 0) append(" · ${state.totalBlockedCount} blocked")
                        if (canOpenDashboard) append(" · Tap for dashboard")
                    }
                    else -> "Tap Install to enable"
                }

                Text(
                    text = statusText,
                    color = if (!state.lastError.isNullOrBlank() && !state.isUpdating) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (state.isUpdating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                IconButton(
                    onClick = onCancelUpdate,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.fx_ic_close_24),
                        contentDescription = "Cancel update",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
            } else {
                TextButton(
                    onClick = onCheckUpdate,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = when {
                            !state.lastError.isNullOrBlank() -> "Retry"
                            state.isInstalled -> "Update"
                            else -> "Install"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (canOpenDashboard && optionsUrl != null && onOpenOptions != null) {
                IconButton(
                    onClick = { onOpenOptions(optionsUrl) },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.fx_ic_settings_24),
                        contentDescription = "Open uBlock dashboard",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Switch(
                checked = state.isEnabled,
                onCheckedChange = onToggleEnabled,
                modifier = Modifier.scale(0.84f)
            )
        }
    }
}
