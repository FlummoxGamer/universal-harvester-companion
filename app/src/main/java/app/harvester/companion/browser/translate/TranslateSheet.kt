package app.harvester.companion.browser.translate

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import mozilla.components.browser.state.state.TranslationsState
import mozilla.components.concept.engine.translate.Language
import mozilla.components.concept.engine.translate.LanguageModel
import mozilla.components.concept.engine.translate.LanguageSetting
import mozilla.components.concept.engine.translate.ModelState

private enum class TranslateSheetPage {
    Main,
    Settings,
    Models,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateSheet(
    tabTranslationsState: TranslationsState?,
    currentHost: String,
    activeTabId: String? = null,
    activeTabUrl: String? = null,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val mgrState by UhTranslationManager.state.collectAsState()

    var currentPage by remember { mutableStateOf(TranslateSheetPage.Main) }

    val detectedDocLang = tabTranslationsState?.translationEngineState?.detectedLanguages?.documentLangTag
    val activePair = tabTranslationsState?.translationEngineState?.requestedTranslationPair
    val isFastTranslated = UhTranslationManager.isFastTranslatedForTab(activeTabId, activeTabUrl)
    val isTranslated = tabTranslationsState?.isTranslated == true || isFastTranslated
    val isProcessing = tabTranslationsState?.isTranslateProcessing == true ||
        tabTranslationsState?.isRestoreProcessing == true ||
        mgrState.isBusy
    val hasImageOverlays = mgrState.imageOverlayCount > 0 &&
        (activeTabId == null || mgrState.imageOverlayTabId == activeTabId)

    var selectedFromCode by remember(detectedDocLang, activePair?.fromLanguage, mgrState.preferOffline) {
        mutableStateOf(
            activePair?.fromLanguage?.ifBlank { null }
                ?: detectedDocLang?.ifBlank { null }
                ?: if (!mgrState.preferOffline) "auto"
                else mgrState.fromLanguages.firstOrNull { it.code != mgrState.preferredTargetLang }?.code ?: "es"
        )
    }
    var selectedToCode by remember(activePair?.toLanguage, mgrState.preferredTargetLang) {
        mutableStateOf(
            activePair?.toLanguage?.ifBlank { null }
                ?: mgrState.preferredTargetLang.ifBlank { "en" }
        )
    }

    LaunchedEffect(Unit) {
        UhTranslationManager.refreshEngineCapabilities()
        UhTranslationManager.refreshCurrentTabSiteSetting()
    }

    LaunchedEffect(selectedFromCode, selectedToCode, mgrState.preferOffline) {
        if (mgrState.preferOffline && selectedFromCode.isNotBlank() && selectedFromCode != "auto" && selectedToCode.isNotBlank()) {
            UhTranslationManager.fetchPairDownloadSize(selectedFromCode, selectedToCode)
        }
    }

    val fromOptions = remember(mgrState.fromLanguages, mgrState.preferOffline) {
        if (!mgrState.preferOffline) {
            listOf(Language("auto", "Auto-Detect")) + mgrState.fromLanguages
        } else {
            mgrState.fromLanguages
        }
    }

    val fromLabel = when {
        selectedFromCode == "auto" -> "Auto-Detect"
        else -> mgrState.fromLanguages.firstOrNull { it.code == selectedFromCode }?.localizedDisplayName
            ?: selectedFromCode.uppercase()
    }
    val toLabel = mgrState.toLanguages.firstOrNull { it.code == selectedToCode }?.localizedDisplayName
        ?: selectedToCode.uppercase()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        AnimatedContent(
            targetState = currentPage,
            label = "TranslateSheetTransition"
        ) { page ->
            when (page) {
                TranslateSheetPage.Main -> {
                    TranslateMainPanel(
                        mgrState = mgrState,
                        fromOptions = fromOptions,
                        detectedDocLang = detectedDocLang,
                        fromCode = selectedFromCode,
                        toCode = selectedToCode,
                        fromLabel = fromLabel,
                        toLabel = toLabel,
                        isTranslated = isTranslated,
                        isProcessing = isProcessing,
                        hasImageOverlays = hasImageOverlays,
                        onSelectFrom = { selectedFromCode = it },
                        onSelectTo = { selectedToCode = it },
                        onSwap = {
                            if (selectedFromCode != "auto") {
                                val tmp = selectedFromCode
                                selectedFromCode = selectedToCode
                                selectedToCode = tmp
                            }
                        },
                        onTogglePreferOffline = { offline ->
                            UhTranslationManager.setPreferOffline(offline)
                            if (offline && selectedFromCode == "auto") {
                                selectedFromCode = detectedDocLang?.ifBlank { null }
                                    ?: mgrState.fromLanguages.firstOrNull { it.code != selectedToCode }?.code
                                    ?: "es"
                            }
                        },
                        onTranslate = {
                            UhTranslationManager.translateActiveTab(selectedFromCode, selectedToCode)
                        },
                        onShowOriginal = {
                            UhTranslationManager.restoreActiveTab()
                        },
                        onTranslateImages = {
                            if (hasImageOverlays) {
                                UhTranslationManager.clearImageTranslations()
                            } else {
                                UhTranslationManager.translateVisibleImages(selectedFromCode, selectedToCode)
                            }
                        },
                        onOpenSettings = { currentPage = TranslateSheetPage.Settings },
                        onOpenModels = { currentPage = TranslateSheetPage.Models },
                        onDismiss = onDismiss,
                    )
                }

                TranslateSheetPage.Settings -> {
                    TranslateSettingsPanel(
                        mgrState = mgrState,
                        sourceLangCode = if (selectedFromCode == "auto") (detectedDocLang ?: "es") else selectedFromCode,
                        sourceLangName = if (selectedFromCode == "auto") {
                            mgrState.fromLanguages.firstOrNull { it.code == detectedDocLang }?.localizedDisplayName ?: "Detected language"
                        } else fromLabel,
                        currentHost = currentHost,
                        onBack = { currentPage = TranslateSheetPage.Main },
                        onOpenModels = { currentPage = TranslateSheetPage.Models },
                    )
                }

                TranslateSheetPage.Models -> {
                    OfflineModelsPanel(
                        models = mgrState.languageModels,
                        onBack = { currentPage = TranslateSheetPage.Main },
                    )
                }
            }
        }
    }
}

@Composable
private fun TranslateMainPanel(
    mgrState: UhTranslationManager.State,
    fromOptions: List<Language>,
    detectedDocLang: String?,
    fromCode: String,
    toCode: String,
    fromLabel: String,
    toLabel: String,
    isTranslated: Boolean,
    isProcessing: Boolean,
    hasImageOverlays: Boolean,
    onSelectFrom: (String) -> Unit,
    onSelectTo: (String) -> Unit,
    onSwap: () -> Unit,
    onTogglePreferOffline: (Boolean) -> Unit,
    onTranslate: () -> Unit,
    onShowOriginal: () -> Unit,
    onTranslateImages: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModels: () -> Unit,
    onDismiss: () -> Unit,
) {
    var fromExpanded by remember { mutableStateOf(false) }
    var toExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(id = R.drawable.uh_ic_translate_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = if (isTranslated) "Page Translated" else "Translate Page",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (mgrState.preferOffline) {
                            "On-device neural engine · Works completely offline"
                        } else {
                            "Fast instant translation · In-page · Zero redirects"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
            }

            IconButton(onClick = onOpenSettings) {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_settings_24),
                    contentDescription = "Translation settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Engine mode segmented selector: Fast Online vs On-Device Offline
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                EngineModeSegment(
                    label = "Fast Online",
                    selected = !mgrState.preferOffline,
                    onClick = { onTogglePreferOffline(false) },
                    modifier = Modifier.weight(1f),
                )
                EngineModeSegment(
                    label = "On-Device Offline",
                    selected = mgrState.preferOffline,
                    onClick = { onTogglePreferOffline(true) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Language selector card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        LanguagePickerButton(
                            label = if (!detectedDocLang.isNullOrBlank() && detectedDocLang == fromCode) {
                                "From (Detected)"
                            } else {
                                "Translate from"
                            },
                            value = fromLabel,
                            onClick = { fromExpanded = true }
                        )
                        LanguageDropdown(
                            expanded = fromExpanded,
                            languages = fromOptions,
                            selectedCode = fromCode,
                            onSelect = {
                                fromExpanded = false
                                onSelectFrom(it)
                            },
                            onDismiss = { fromExpanded = false }
                        )
                    }

                    Surface(
                        onClick = onSwap,
                        enabled = fromCode != "auto",
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            text = "⇄",
                            color = if (fromCode != "auto") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                        )
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        LanguagePickerButton(
                            label = "Translate to",
                            value = toLabel,
                            onClick = { toExpanded = true }
                        )
                        LanguageDropdown(
                            expanded = toExpanded,
                            languages = mgrState.toLanguages,
                            selectedCode = toCode,
                            onSelect = {
                                toExpanded = false
                                onSelectTo(it)
                            },
                            onDismiss = { toExpanded = false }
                        )
                    }
                }

                if (mgrState.preferOffline) {
                    val sizeHint = UhTranslationManager.formatBytes(mgrState.pairDownloadSizeBytes)
                    if (sizeHint.isNotBlank()) {
                        Text(
                            text = "First-time offline pack: ~$sizeHint (runs locally afterwards)",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        if (!mgrState.errorMessage.isNullOrBlank()) {
            Text(
                text = mgrState.errorMessage,
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp
            )
        } else if (!mgrState.statusMessage.isNullOrBlank()) {
            Text(
                text = mgrState.statusMessage,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (isTranslated) {
                OutlinedButton(
                    onClick = {
                        onShowOriginal()
                        onDismiss()
                    },
                    enabled = !isProcessing,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Show Original", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Button(
                onClick = onTranslate,
                enabled = !isProcessing && toCode.isNotBlank() && (fromCode == "auto" || fromCode != toCode),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Translating…", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    Text(
                        text = if (isTranslated) "Re-translate" else "Translate Page",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // ML Kit Image OCR Translation action button
        OutlinedButton(
            onClick = onTranslateImages,
            enabled = !mgrState.isTranslatingImages,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (mgrState.isTranslatingImages) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Scanning Images (OCR)…",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                Icon(
                    painter = painterResource(id = R.drawable.uh_ic_camera_24),
                    contentDescription = null,
                    tint = if (hasImageOverlays) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (hasImageOverlays) {
                        "Clear Image Overlays (${mgrState.imageOverlayCount})"
                    } else {
                        "Translate Images on Screen (OCR)"
                    },
                    color = if (hasImageOverlays) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onOpenModels) {
                val downloadedCount = mgrState.languageModels.count { it.status == ModelState.DOWNLOADED }
                Text(
                    text = "Offline Packs ($downloadedCount downloaded)",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            TextButton(onClick = onOpenSettings) {
                Text(
                    text = "Language Rules →",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun EngineModeSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TranslateSettingsPanel(
    mgrState: UhTranslationManager.State,
    sourceLangCode: String,
    sourceLangName: String,
    currentHost: String,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
) {
    val currentSetting = mgrState.languageSettings[sourceLangCode] ?: LanguageSetting.OFFER
    val alwaysForLang = currentSetting == LanguageSetting.ALWAYS
    val neverForLang = currentSetting == LanguageSetting.NEVER

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(34.dp)) {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_back_24),
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Translation Settings",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                TranslateToggleRow(
                    title = "Prefer offline on-device engine",
                    subtitle = "Always translate locally using downloaded language packs",
                    checked = mgrState.preferOffline,
                    onCheckedChange = { UhTranslationManager.setPreferOffline(it) }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                )
                TranslateToggleRow(
                    title = "Always translate $sourceLangName",
                    subtitle = "Automatically translate pages written in $sourceLangName",
                    checked = alwaysForLang,
                    onCheckedChange = { checked ->
                        UhTranslationManager.setLanguageRule(
                            sourceLangCode,
                            if (checked) LanguageSetting.ALWAYS else LanguageSetting.OFFER
                        )
                    }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                )
                TranslateToggleRow(
                    title = "Never translate $sourceLangName",
                    subtitle = "Do not offer translation for $sourceLangName pages",
                    checked = neverForLang,
                    onCheckedChange = { checked ->
                        UhTranslationManager.setLanguageRule(
                            sourceLangCode,
                            if (checked) LanguageSetting.NEVER else LanguageSetting.OFFER
                        )
                    }
                )
                if (currentHost.isNotBlank()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    )
                    TranslateToggleRow(
                        title = "Never translate $currentHost",
                        subtitle = "Skip automatic translation prompts on this website",
                        checked = mgrState.currentSiteNeverTranslate,
                        onCheckedChange = { checked ->
                            UhTranslationManager.setNeverTranslateCurrentSite(checked)
                        }
                    )
                }
            }
        }

        Surface(
            onClick = onOpenModels,
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_downloads_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Manage Offline Language Packs",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    val downloadedCount = mgrState.languageModels.count { it.status == ModelState.DOWNLOADED }
                    Text(
                        text = "$downloadedCount language pack${if (downloadedCount == 1) "" else "s"} stored on device",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_chevron_right_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (mgrState.neverTranslateSites.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Never Translate Sites",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    mgrState.neverTranslateSites.forEach { origin ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = origin,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { UhTranslationManager.removeNeverTranslateSite(origin) }) {
                                Text("Remove", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineModelsPanel(
    models: List<LanguageModel>,
    onBack: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val filtered = remember(models, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) models
        else models.filter { m ->
            val name = m.language?.localizedDisplayName?.lowercase().orEmpty()
            val code = m.language?.code?.lowercase().orEmpty()
            name.contains(q) || code.contains(q)
        }
    }
    val downloadedModels = remember(filtered) {
        filtered.filter {
            it.status == ModelState.DOWNLOADED || it.status == ModelState.DELETION_IN_PROGRESS
        }
    }
    val availableModels = remember(filtered) {
        filtered.filter {
            it.status != ModelState.DOWNLOADED && it.status != ModelState.DELETION_IN_PROGRESS
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(34.dp)) {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_back_24),
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Text(
                    text = "Offline Language Packs",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Pre-download languages for instant offline translation",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
        }

        // Instant search box
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = R.drawable.fx_ic_search_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (searchQuery.isEmpty()) {
                    Text(
                        text = "Search languages…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (searchQuery.isNotEmpty()) {
                Text(
                    text = "Clear",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { searchQuery = "" }
                        .padding(start = 8.dp)
                )
            }
        }

        if (models.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Language packs will appear here once the on-device translation engine finishes syncing.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            // Fixed-height bounded LazyColumn eliminates nested-scroll fighting inside ModalBottomSheet
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(390.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (downloadedModels.isNotEmpty()) {
                    item(key = "hdr_downloaded") {
                        Text(
                            text = "DOWNLOADED (${downloadedModels.size})",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp, start = 4.dp)
                        )
                    }
                    items(
                        items = downloadedModels,
                        key = { "dl_${it.language?.code ?: it.hashCode()}" }
                    ) { model ->
                        OfflineModelRow(model = model)
                    }
                }

                if (availableModels.isNotEmpty()) {
                    item(key = "hdr_available") {
                        Text(
                            text = "AVAILABLE (${availableModels.size})",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp, start = 4.dp)
                        )
                    }
                    items(
                        items = availableModels,
                        key = { "av_${it.language?.code ?: it.hashCode()}" }
                    ) { model ->
                        OfflineModelRow(model = model)
                    }
                }

                if (downloadedModels.isEmpty() && availableModels.isEmpty()) {
                    item(key = "empty_search") {
                        Text(
                            text = "No languages match \"$searchQuery\".",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineModelRow(model: LanguageModel) {
    val lang = model.language ?: return
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = lang.localizedDisplayName ?: lang.code.uppercase(),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                val sizeStr = UhTranslationManager.formatBytes(model.size)
                val sub = when (model.status) {
                    ModelState.DOWNLOADED -> "Downloaded${if (sizeStr.isNotBlank()) " · $sizeStr" else ""}"
                    ModelState.DOWNLOAD_IN_PROGRESS -> "Downloading…"
                    ModelState.DELETION_IN_PROGRESS -> "Removing…"
                    ModelState.ERROR_DOWNLOAD -> "Download failed — tap to retry"
                    else -> if (sizeStr.isNotBlank()) sizeStr else "Available offline"
                }
                Text(
                    text = sub,
                    color = if (model.status == ModelState.ERROR_DOWNLOAD) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontSize = 11.sp
                )
            }

            when (model.status) {
                ModelState.DOWNLOAD_IN_PROGRESS,
                ModelState.DELETION_IN_PROGRESS -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                ModelState.DOWNLOADED -> {
                    IconButton(
                        onClick = { UhTranslationManager.deleteLanguageModel(lang.code) },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.fx_ic_delete_24),
                            contentDescription = "Delete language pack",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                else -> {
                    IconButton(
                        onClick = { UhTranslationManager.downloadLanguageModel(lang.code) },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.fx_ic_downloads_24),
                            contentDescription = "Download language pack",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguagePickerButton(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp
        )
        Spacer(modifier = Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "▾",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun LanguageDropdown(
    expanded: Boolean,
    languages: List<Language>,
    selectedCode: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.heightIn(max = 320.dp)
    ) {
        languages.forEach { lang ->
            val isSelected = lang.code == selectedCode
            DropdownMenuItem(
                text = {
                    Text(
                        text = lang.localizedDisplayName ?: lang.code.uppercase(),
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                },
                onClick = { onSelect(lang.code) }
            )
        }
    }
}

@Composable
private fun TranslateToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
