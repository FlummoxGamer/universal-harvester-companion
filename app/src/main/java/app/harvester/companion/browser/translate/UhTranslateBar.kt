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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
 * Compact floating UH Translate status pill.
 *
 * Shows live progress while translating (or downloading an offline pack) and brief completion/error
 * feedback, then automatically disappears once translation finishes so the webpage stays clean.
 * Uses the unified Settings surface palette (`background` container + `surface` action pills).
 */
@Composable
fun UhTranslateStatusPill(
    mgrState: UhTranslationManager.State,
    isTabBusy: Boolean,
    onOpenTranslateSheet: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isWorking = isTabBusy ||
        mgrState.isBusy ||
        mgrState.pillPhase == TranslatePillPhase.DOWNLOADING_MODEL ||
        mgrState.pillPhase == TranslatePillPhase.TRANSLATING
    val isError = mgrState.pillPhase == TranslatePillPhase.ERROR
    val isBriefDone = mgrState.pillPhase == TranslatePillPhase.COMPLETED && mgrState.pillMessage.isNotBlank()

    val visible = isWorking || isError || isBriefDone

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier
    ) {
        val cs = MaterialTheme.colorScheme
        val message = when {
            mgrState.pillMessage.isNotBlank() -> mgrState.pillMessage
            isWorking -> "Translating page…"
            else -> "UH Translate"
        }

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = cs.background,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(
                1.dp,
                if (isError) cs.error.copy(alpha = 0.45f) else cs.outline.copy(alpha = 0.55f)
            ),
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .widthIn(max = 360.dp)
                .clickable { onOpenTranslateSheet() }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isWorking) {
                    if (mgrState.pillProgress > 0.05f && mgrState.pillProgress < 0.99f) {
                        CircularProgressIndicator(
                            progress = { mgrState.pillProgress },
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.2.dp,
                            color = cs.primary,
                            trackColor = cs.surface,
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.2.dp,
                            color = cs.primary,
                        )
                    }
                } else {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_translate_24),
                        contentDescription = "UH Translate",
                        tint = if (isError) cs.error else cs.primary,
                        modifier = Modifier.size(17.dp)
                    )
                }

                Text(
                    text = message,
                    color = if (isError) cs.error else cs.onSurface,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (isError && mgrState.pillCanRetry) {
                    Text(
                        text = "Retry",
                        color = cs.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(cs.surface)
                            .clickable(onClick = onRetry)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.fx_ic_close_24),
                        contentDescription = "Dismiss",
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

/**
 * Tiny non-intrusive corner UH Translate button shown ONLY when:
 * - User explicitly enabled "Offer to translate foreign pages" (OFF by default)
 * - Current page is detected as a foreign language
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
            color = cs.background,
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
            border = BorderStroke(1.dp, cs.outline.copy(alpha = 0.65f)),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(id = R.drawable.uh_ic_translate_24),
                    contentDescription = "UH Translate",
                    tint = cs.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
