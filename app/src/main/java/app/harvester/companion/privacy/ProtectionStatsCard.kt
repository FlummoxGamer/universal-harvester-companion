package app.harvester.companion.privacy

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import java.util.Locale

fun formatCompactCount(count: Long): String {
    return when {
        count < 1_000L -> count.toString()
        count < 1_000_000L -> {
            val k = count / 1_000.0
            if (k >= 100.0) String.format(Locale.US, "%.0fK", k)
            else String.format(Locale.US, "%.1fK", k).removeSuffix(".0K").let {
                if (it.endsWith("K")) it else "${it}K"
            }
        }
        else -> {
            val m = count / 1_000_000.0
            String.format(Locale.US, "%.1fM", m)
        }
    }
}

/**
 * Clean 3-metric protection stats card used in Privacy Guard and the rewritten Trust Panel.
 * Displays Ads Blocked, Trackers Blocked, and Estimated Time Saved.
 */
@Composable
fun ProtectionStatsPanel(
    stats: ShieldStatsSnapshot,
    modifier: Modifier = Modifier,
    headerTitle: String = "UH Shield Stats",
    showScopeToggle: Boolean = false,
    isAllTimeSelected: Boolean = true,
    onScopeToggle: ((Boolean) -> Unit)? = null,
    onResetClick: (() -> Unit)? = null,
    onCardClick: (() -> Unit)? = null,
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val baseModifier = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(20.dp))
        .background(surfaceColor)
        .border(1.dp, borderColor, RoundedCornerShape(20.dp))
        .let { if (onCardClick != null) it.clickable { onCardClick() } else it }
        .padding(horizontal = 16.dp, vertical = 14.dp)

    Column(modifier = baseModifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_shield_lock_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = headerTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (showScopeToggle && onScopeToggle != null) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f))
                        .padding(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ScopeChip(
                        label = "This Site",
                        selected = !isAllTimeSelected,
                        onClick = { onScopeToggle(false) },
                    )
                    ScopeChip(
                        label = "All Time",
                        selected = isAllTimeSelected,
                        onClick = { onScopeToggle(true) },
                    )
                }
            } else if (onResetClick != null) {
                Text(
                    text = "Reset",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onResetClick() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatColumn(
                value = formatCompactCount(stats.adsBlocked),
                label = "Ads Blocked",
                valueColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            VerticalMetricDivider()
            StatColumn(
                value = formatCompactCount(stats.trackersBlocked),
                label = "Trackers",
                valueColor = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f),
            )
            VerticalMetricDivider()
            StatColumn(
                value = stats.formattedTimeSaved(),
                label = "Time Saved",
                valueColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ScopeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun StatColumn(
    value: String,
    label: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            color = valueColor,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun VerticalMetricDivider(
    color: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
) {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(28.dp)
            .background(color),
    )
}

/**
 * Sleek translucent protection stats bar designed to replace the "UH" wordmark on Home
 * when the user enables "Show Protection Stats on Home" in Customize Home.
 */
@Composable
fun HomeProtectionStatsBar(
    stats: ShieldStatsSnapshot,
    textColor: Color,
    hasWallpaper: Boolean,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val containerBg = if (hasWallpaper) {
        Color.Black.copy(alpha = 0.34f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f)
    }
    val borderColor = if (hasWallpaper) {
        Color.White.copy(alpha = 0.16f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    }
    val primaryValColor = if (hasWallpaper) Color.White else MaterialTheme.colorScheme.primary
    val secondaryValColor = if (hasWallpaper) Color.White else textColor
    val labelColor = if (hasWallpaper) Color.White.copy(alpha = 0.78f) else textColor.copy(alpha = 0.65f)
    val dividerColor = if (hasWallpaper) Color.White.copy(alpha = 0.18f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    val cardMod = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(if (compact) 16.dp else 20.dp))
        .background(containerBg)
        .border(1.dp, borderColor, RoundedCornerShape(if (compact) 16.dp else 20.dp))
        .let { if (onClick != null) it.clickable { onClick() } else it }
        .padding(
            horizontal = if (compact) 14.dp else 18.dp,
            vertical = if (compact) 10.dp else 14.dp,
        )

    Row(
        modifier = cardMod,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeStatMetric(
            value = formatCompactCount(stats.adsBlocked),
            label = "Ads Blocked",
            valueColor = primaryValColor,
            labelColor = labelColor,
            compact = compact,
            modifier = Modifier.weight(1f),
        )
        VerticalMetricDivider(color = dividerColor)
        HomeStatMetric(
            value = formatCompactCount(stats.trackersBlocked),
            label = "Trackers",
            valueColor = primaryValColor,
            labelColor = labelColor,
            compact = compact,
            modifier = Modifier.weight(1f),
        )
        VerticalMetricDivider(color = dividerColor)
        HomeStatMetric(
            value = stats.formattedTimeSaved(),
            label = "Time Saved",
            valueColor = secondaryValColor,
            labelColor = labelColor,
            compact = compact,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HomeStatMetric(
    value: String,
    label: String,
    valueColor: Color,
    labelColor: Color,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            color = valueColor,
            fontSize = if (compact) 17.sp else 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(1.dp))
        Text(
            text = label,
            color = labelColor,
            fontSize = if (compact) 10.sp else 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
