package app.harvester.companion.browser.translate

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R

/**
 * Compact Brave-style floating Google Translate bar with:
 * - One-tap `[Source (Auto)] | [Target]` language switcher tabs (instant toggle between Original & Translated)
 * - Fast Offline/Online mode badge
 * - Quick Image OCR button, full language/settings sheet button, and Dismiss `×`
 *
 * Also provides [TranslateCornerOfferButton], a tiny non-intrusive corner translate icon shown ONLY
 * when the user explicitly turns ON "Offer to translate foreign pages" (OFF by default).
 */
@Composable
fun BraveTranslateBar(
    mgrState: UhTranslationManager.State,
    isPageTranslated: Boolean,
    isTabBusy: Boolean,
    hasImageOverlays: Boolean,
    onSelectOriginalTab: () -> Unit,
    onSelectTargetTab: () -> Unit,
    onOpenLanguageSheet: () -> Unit,
    onTranslateImages: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val visible = mgrState.barVisible ||
        isTabBusy ||
        mgrState.isTranslatingImages ||
        mgrState.pillPhase == TranslatePillPhase.DOWNLOADING_MODEL ||
        mgrState.pillPhase == TranslatePillPhase.TRANSLATING ||
        mgrState.pillPhase == TranslatePillPhase.TRANSLATING_IMAGES ||
        mgrState.pillPhase == TranslatePillPhase.ERROR

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier
    ) {
        val cs = MaterialTheme.colorScheme
        val sourceLabel = mgrState.sourceTabDisplayLabel()
        val targetLabel = mgrState.targetTabDisplayLabel()
        val busy = isTabBusy ||
            mgrState.isBusy ||
            mgrState.isTranslatingImages ||
            mgrState.pillPhase == TranslatePillPhase.DOWNLOADING_MODEL ||
            mgrState.pillPhase == TranslatePillPhase.TRANSLATING ||
            mgrState.pillPhase == TranslatePillPhase.TRANSLATING_IMAGES

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = cs.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
            border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.45f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Translate icon (opens full sheet / language picker)
                IconButton(
                    onClick = onOpenLanguageSheet,
                    modifier = Modifier.size(34.dp)
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = cs.primary
                        )
                    } else {
                        Icon(
                            painter = painterResource(id = R.drawable.uh_ic_translate_24),
                            contentDescription = "Translate options",
                            tint = cs.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Middle: either live progress/error message or Brave-style [Source | Target] tabs
                if (busy && mgrState.pillMessage.isNotBlank()) {
                    Text(
                        text = mgrState.pillMessage,
                        color = cs.onSurface,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                    )
                } else if (mgrState.pillPhase == TranslatePillPhase.ERROR && mgrState.pillMessage.isNotBlank()) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = mgrState.pillMessage,
                            color = cs.error,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (mgrState.pillCanRetry) {
                            Text(
                                text = "Retry",
                                color = cs.primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { UhTranslationManager.retryLastTranslation() }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                } else {
                    // Brave-style 2-tab toggle: [Source] | [Target]
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(cs.surface)
                            .padding(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val origSelected = !isPageTranslated
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(30.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (origSelected) cs.primaryContainer else cs.surface)
                                .clickable { onSelectOriginalTab() }
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = sourceLabel,
                                color = if (origSelected) cs.onPrimaryContainer else cs.onSurfaceVariant,
                                fontSize = 12.sp,
                                fontWeight = if (origSelected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(30.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (!origSelected) cs.primary else cs.surface)
                                .clickable { onSelectTargetTab() }
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = targetLabel,
                                color = if (!origSelected) cs.onPrimary else cs.onSurface,
                                fontSize = 12.sp,
                                fontWeight = if (!origSelected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Image OCR button
                IconButton(
                    onClick = onTranslateImages,
                    enabled = !mgrState.isTranslatingImages,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_camera_24),
                        contentDescription = if (hasImageOverlays) "Clear image translations" else "Translate images",
                        tint = if (hasImageOverlays) cs.error else cs.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // More / Language Picker & Offline Toggle button
                IconButton(
                    onClick = onOpenLanguageSheet,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.mozac_ic_more_vertical_round_24),
                        contentDescription = "Languages and settings",
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Dismiss bar button
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.fx_ic_close_24),
                        contentDescription = "Close translate bar",
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * Tiny non-intrusive corner Translate icon button shown ONLY when:
 * - User explicitly enabled "Offer to translate foreign pages" (OFF by default)
 * - Current page is detected as a foreign language
 * - Floating BraveTranslateBar is not already open
 */
@Composable
fun TranslateCornerOfferButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        val cs = MaterialTheme.colorScheme
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = cs.surfaceContainerHigh,
            tonalElevation = 5.dp,
            shadowElevation = 6.dp,
            border = BorderStroke(1.dp, cs.primary.copy(alpha = 0.35f)),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(id = R.drawable.uh_ic_translate_24),
                    contentDescription = "Translate page",
                    tint = cs.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
