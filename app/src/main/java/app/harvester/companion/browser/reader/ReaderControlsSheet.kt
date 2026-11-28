package app.harvester.companion.browser.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Material 3 Reader Mode Controls Sheet:
 * - Multi-language Read Aloud with free AI Neural voices + Offline TTS fallback, verified Female/Male filter
 * - 4 reading themes (Paper, Sepia, Dark, OLED) + accurate Reader Brightness slider with Extra-Dim night range
 * - 4 bookish font families (Book Serif, Classic, Clean Sans, Mono) + font size slider
 * - Line spacing & page margins
 * - Book Pages mode, Fullscreen Reading, Show Images, Read Aloud toggle, Auto-Reader per domain
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderControlsSheet(
    state: ReaderUiState,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val prefs = state.prefs
    val detectedLangShort = state.detectedLang.substringBefore("-").uppercase(Locale.ROOT).ifBlank { "EN" }
    val detectedLangDisplay = remember(state.detectedLang) {
        val loc = Locale.forLanguageTag(state.detectedLang.ifBlank { "en" })
        loc.getDisplayLanguage(Locale.ENGLISH).replaceFirstChar { it.uppercase() }.ifBlank { detectedLangShort }
    }
    // Seed initial slider position from actual current device brightness when in Auto mode
    val systemSliderSeed = remember(prefs.brightness < 0f) {
        ReaderModeManager.readSystemSliderBrightness(context)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.uh_ic_reader_mode_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Reader Mode",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = detectedLangDisplay,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (state.readMins > 0) {
                        Text(
                            text = "${state.readMins} min read • ${state.wordCount} words",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                TextButton(
                    onClick = {
                        onDismiss()
                        ReaderModeManager.exitReader()
                    }
                ) {
                    Text(
                        text = "Exit",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Read Aloud Player + AI Neural & Offline Voice Picker
            if (prefs.ttsEnabled && state.sentences.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Listen / Read Aloud",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                val progressLabel = if (state.ttsIndex >= 0) {
                                    "Sentence ${state.ttsIndex + 1} of ${state.sentences.size}"
                                } else {
                                    "AI Neural voices with offline fallback"
                                }
                                Text(
                                    text = progressLabel,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.5.sp
                                )
                            }

                            IconButton(
                                onClick = { ReaderModeManager.skipTts(context, -1) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.uh_ic_back_24),
                                    contentDescription = "Previous sentence",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .clickable { ReaderModeManager.togglePlayPauseTts(context) }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = if (state.ttsPlaying) "❚❚" else "▶",
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            IconButton(
                                onClick = { ReaderModeManager.skipTts(context, 1) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.uh_ic_forward_24),
                                    contentDescription = "Next sentence",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Speed pills
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                                val selected = kotlin.math.abs(prefs.ttsSpeed - spd) < 0.05f
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceContainerHigh
                                        )
                                        .clickable { ReaderModeManager.setTtsSpeed(spd) }
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${spd}×",
                                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        // Voice Gender selector (Auto / Female / Male)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ReaderVoiceGender.entries.forEach { gender ->
                                val selected = prefs.voiceGender == gender
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceContainerHigh
                                        )
                                        .border(
                                            width = if (selected) 1.dp else 0.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary
                                            else Color.Transparent,
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .clickable { ReaderModeManager.setVoiceGender(gender) }
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = gender.label,
                                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.5.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        // Verified Neural & Offline Voice Picker
                        val filteredVoices = when (prefs.voiceGender) {
                            ReaderVoiceGender.AUTO -> state.availableVoices
                            else -> state.availableVoices.filter { it.gender == prefs.voiceGender }
                                .ifEmpty { state.availableVoices }
                        }
                        if (filteredVoices.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                filteredVoices.forEach { voice ->
                                    val isChosen = prefs.voiceName == voice.name ||
                                        (prefs.voiceName.isBlank() && voice == filteredVoices.first())
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(
                                                if (isChosen) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.surfaceContainerHigh
                                            )
                                            .clickable { ReaderModeManager.selectVoiceOption(voice) }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = voice.label,
                                            color = if (isChosen) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.onSurface,
                                            fontSize = 11.sp,
                                            fontWeight = if (isChosen) FontWeight.Bold else FontWeight.Medium,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Theme Swatches + Accurate Reader Brightness Slider with Extra-Dim Night Zone
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Page Theme",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ReaderTheme.entries.forEach { theme ->
                            val selected = prefs.theme == theme
                            val (swatchBg, swatchFg) = when (theme) {
                                ReaderTheme.LIGHT -> Color(0xFFFAF9F6) to Color(0xFF1C1B18)
                                ReaderTheme.SEPIA -> Color(0xFFF5ECD8) to Color(0xFF322618)
                                ReaderTheme.DARK -> Color(0xFF17181C) to Color(0xFFE4E2DC)
                                ReaderTheme.OLED -> Color(0xFF000000) to Color(0xFFE4E6EA)
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(swatchBg)
                                    .border(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .clickable {
                                        ReaderModeManager.updatePrefs { it.copy(theme = theme) }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = theme.label,
                                    color = swatchFg,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    // Reader Brightness Slider (0%..20% Extra-Dim Night Scrim, 20%..100% Hardware Brightness)
                    val isAutoBrightness = prefs.brightness < 0f
                    val sliderBrightness = if (isAutoBrightness) {
                        systemSliderSeed
                    } else {
                        prefs.brightness.coerceIn(0.0f, 1.0f)
                    }
                    val isExtraDim = !isAutoBrightness && sliderBrightness < 0.20f

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Brightness",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Slider(
                                value = sliderBrightness,
                                onValueChange = { v -> ReaderModeManager.setBrightness(v) },
                                valueRange = 0.0f..1.0f,
                                modifier = Modifier.weight(1f)
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(
                                        if (isAutoBrightness) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceContainerHigh
                                    )
                                    .clickable { ReaderModeManager.setBrightness(-1f) }
                                    .padding(horizontal = 9.dp, vertical = 5.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                val badgeText = when {
                                    isAutoBrightness -> "Auto"
                                    isExtraDim -> "Dim ${(sliderBrightness * 100f).roundToInt()}%"
                                    else -> "${(sliderBrightness * 100f).roundToInt()}%"
                                }
                                Text(
                                    text = badgeText,
                                    color = if (isAutoBrightness) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        if (isExtraDim) {
                            Text(
                                text = "Extra-low night dim active below system minimum",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            // Typography: 4 Bookish Font Families + Size Slider
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Book Typography",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ReaderFont.entries.forEach { font ->
                            val selected = prefs.font == font
                            val composeFont = when (font) {
                                ReaderFont.SERIF -> FontFamily.Serif
                                ReaderFont.EDITORIAL -> FontFamily.Serif
                                ReaderFont.SANS -> FontFamily.SansSerif
                                ReaderFont.MONO -> FontFamily.Monospace
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceContainerHigh
                                    )
                                    .clickable {
                                        ReaderModeManager.updatePrefs { it.copy(font = font) }
                                    }
                                    .padding(vertical = 9.dp, horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = font.label,
                                    fontFamily = composeFont,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "A",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Slider(
                            value = prefs.fontSizePx.toFloat(),
                            onValueChange = { v ->
                                ReaderModeManager.updatePrefs { it.copy(fontSizePx = v.roundToInt().coerceIn(14, 28)) }
                            },
                            valueRange = 14f..28f,
                            steps = 13,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${prefs.fontSizePx}px",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Layout: Line Spacing + Page Margins
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Line Spacing",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ReaderLineSpacing.entries.forEach { sp ->
                            val selected = prefs.lineSpacing == sp
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceContainerHigh
                                    )
                                    .clickable {
                                        ReaderModeManager.updatePrefs { it.copy(lineSpacing = sp) }
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = sp.label,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Text(
                        text = "Page Margins",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ReaderMargin.entries.forEach { m ->
                            val selected = prefs.margin == m
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceContainerHigh
                                    )
                                    .clickable {
                                        ReaderModeManager.updatePrefs { it.copy(margin = m) }
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = m.label,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            // Reading Mode, Fullscreen & Options
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ReaderToggleRow(
                        title = "Book Pages Mode",
                        subtitle = "Swipe or tap edges to turn pages horizontally",
                        checked = prefs.pageMode,
                        onCheckedChange = { enabled ->
                            ReaderModeManager.updatePrefs { it.copy(pageMode = enabled) }
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)
                    )
                    ReaderToggleRow(
                        title = "Fullscreen Reading",
                        subtitle = "Hide reader bars and browser toolbar while reading",
                        checked = prefs.isFullscreen,
                        onCheckedChange = { enabled ->
                            ReaderModeManager.updatePrefs { it.copy(isFullscreen = enabled) }
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)
                    )
                    ReaderToggleRow(
                        title = "Show Images",
                        subtitle = "Turn off for pure distraction-free text",
                        checked = prefs.showImages,
                        onCheckedChange = { show ->
                            ReaderModeManager.updatePrefs { it.copy(showImages = show) }
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)
                    )
                    ReaderToggleRow(
                        title = "Read Aloud (TTS)",
                        subtitle = "Show voice playback controls in Reader Mode",
                        checked = prefs.ttsEnabled,
                        onCheckedChange = { enabled ->
                            ReaderModeManager.setTtsEnabled(enabled)
                        }
                    )

                    if (state.currentHost.isNotBlank()) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)
                        )
                        ReaderToggleRow(
                            title = "Auto-Reader for ${state.currentHost}",
                            subtitle = "Automatically open Reader Mode on this site",
                            checked = state.isAutoReaderForCurrentHost,
                            onCheckedChange = { enabled ->
                                ReaderModeManager.toggleAutoReaderForCurrentHost(enabled)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
