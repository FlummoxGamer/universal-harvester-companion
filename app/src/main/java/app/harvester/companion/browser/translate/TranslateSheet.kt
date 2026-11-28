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

    val isTranslated = mgrState.isCurrentTabTranslated(
        tabId = activeTabId,
        tabUrl = activeTabUrl,
        geckoTranslated = tabTranslationsState?.isTranslated == true
    )
    val isProcessing = tabTranslationsState?.isTranslateProcessing == true ||
        tabTranslationsState?.isRestoreProcessing == true ||
        mgrState.isBusy

    val effectiveFrom = mgrState.effectiveFromCode()
    val effectiveTo = mgrState.preferredTargetLang.ifBlank { "en" }

    LaunchedEffect(Unit) {
        UhTranslationManager.refreshEngineCapabilities()
        UhTranslationManager.refreshCurrentTabSiteSetting()
    }

    LaunchedEffect(effectiveFrom, effectiveTo, mgrState.useOfflineEngine) {
        if (mgrState.useOfflineEngine) {
            UhTranslationManager.fetchPairDownloadSize(effectiveFrom, effectiveTo)
        }
    }

    val fromLabel = mgrState.sourceTabDisplayLabel()
    val toLabel = mgrState.targetTabDisplayLabel()
    val resolvedFromForOfflineCheck = if (mgrState.isAutoDetect) {
        mgrState.detectedDocLang ?: "en"
    } else {
        mgrState.manualFromLang ?: "en"
    }
    val isPairReadyOffline = UhTranslationManager.isModelDownloaded(resolvedFromForOfflineCheck) &&
        UhTranslationManager.isModelDownloaded(effectiveTo)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
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
                        fromCode = effectiveFrom,
                        toCode = effectiveTo,
                        fromLabel = fromLabel,
                        toLabel = toLabel,
                        isPairReadyOffline = isPairReadyOffline,
                        isTranslated = isTranslated,
                        isProcessing = isProcessing,
                        onSelectAutoDetect = { UhTranslationManager.setSourceAutoDetect() },
                        onSelectManualFrom = { UhTranslationManager.setManualSourceLanguage(it) },
                        onSelectTo = { UhTranslationManager.setPreferredTargetLanguage(it) },
                        onSwap = { UhTranslationManager.swapLanguages() },
                        onToggleOffline = { UhTranslationManager.setUseOfflineEngine(it) },
                        onTranslate = {
                            UhTranslationManager.translateActiveTab(effectiveFrom, effectiveTo)
                            onDismiss()
                        },
                        onShowOriginal = {
                            UhTranslationManager.restoreActiveTab()
                        },
                        onOpenSettings = { currentPage = TranslateSheetPage.Settings },
                        onOpenModels = { currentPage = TranslateSheetPage.Models },
                        onDismiss = onDismiss,
                    )
                }

                TranslateSheetPage.Settings -> {
                    val ruleLangCode = mgrState.resolvedSourceLangForRules()
                    TranslateSettingsPanel(
                        mgrState = mgrState,
                        sourceLangCode = ruleLangCode,
                        sourceLangName = UhTranslationManager.languageDisplayName(ruleLangCode),
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
    fromCode: String,
    toCode: String,
    fromLabel: String,
    toLabel: String,
    isPairReadyOffline: Boolean,
    isTranslated: Boolean,
    isProcessing: Boolean,
    onSelectAutoDetect: () -> Unit,
    onSelectManualFrom: (String) -> Unit,
    onSelectTo: (String) -> Unit,
    onSwap: () -> Unit,
    onToggleOffline: (Boolean) -> Unit,
    onTranslate: () -> Unit,
    onShowOriginal: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModels: () -> Unit,
    onDismiss: () -> Unit,
) {
    var fromExpanded by remember { mutableStateOf(false) }
    var toExpanded by remember { mutableStateOf(false) }

    val fromList = mgrState.activeFromLanguageList()
    val toList = mgrState.activeToLanguageList()
    val sameExplicitLang = !mgrState.isAutoDetect && fromCode.equals(toCode, ignoreCase = true)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
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
                        text = if (isTranslated) "UH Translate · Active" else "UH Translate",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (mgrState.useOfflineEngine) {
                            "Offline Mode · Private on-device packs"
                        } else {
                            "Fast online full-page translation · 55+ languages"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.5.sp
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

        // Language selector card (responsive before translating; explicit pick turns off Auto-detect)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        LanguagePickerButton(
                            label = if (mgrState.isAutoDetect) "From (Auto-detect)" else "Translate from",
                            value = fromLabel,
                            onClick = { fromExpanded = true }
                        )
                        SourceLanguageDropdown(
                            expanded = fromExpanded,
                            languages = fromList,
                            isAutoDetect = mgrState.isAutoDetect,
                            detectedCode = mgrState.detectedDocLang,
                            selectedManualCode = mgrState.manualFromLang,
                            onSelectAuto = {
                                fromExpanded = false
                                onSelectAutoDetect()
                            },
                            onSelectLanguage = { code ->
                                fromExpanded = false
                                onSelectManualFrom(code)
                            },
                            onDismiss = { fromExpanded = false }
                        )
                    }

                    Surface(
                        onClick = onSwap,
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.background,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                        )
                    ) {
                        Text(
                            text = "⇄",
                            color = MaterialTheme.colorScheme.primary,
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
                        TargetLanguageDropdown(
                            expanded = toExpanded,
                            languages = toList,
                            selectedCode = toCode,
                            onSelect = {
                                toExpanded = false
                                onSelectTo(it)
                            },
                            onDismiss = { toExpanded = false }
                        )
                    }
                }

                // Online vs Offline Mode compact switch right inside the card
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleOffline(!mgrState.useOfflineEngine) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Offline Mode",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        val modeSubtitle = if (mgrState.useOfflineEngine) {
                            if (isPairReadyOffline) {
                                "Ready offline · Saved pack on device"
                            } else {
                                val sizeHint = UhTranslationManager.formatBytes(mgrState.pairDownloadSizeBytes)
                                if (sizeHint.isNotBlank()) "Downloads ~$sizeHint once and saves to Offline Packs"
                                else "Uses private downloaded language packs"
                            }
                        } else {
                            "Off · Using instant online UH Translate"
                        }
                        Text(
                            text = modeSubtitle,
                            color = if (mgrState.useOfflineEngine && isPairReadyOffline) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = mgrState.useOfflineEngine,
                        onCheckedChange = onToggleOffline
                    )
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
                enabled = !isProcessing && toCode.isNotBlank() && !sameExplicitLang,
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

        // Offline Packs shortcut
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onOpenModels) {
                val downloadedCount = mgrState.languageModels.count { it.status == ModelState.DOWNLOADED }
                Text(
                    text = "Offline Packs ($downloadedCount saved)",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
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
                    title = "Offer to translate foreign pages",
                    subtitle = "Show a small corner translate icon when another language is detected",
                    checked = mgrState.autoOfferEnabled,
                    onCheckedChange = { UhTranslationManager.setAutoOfferEnabled(it) }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                )
                TranslateToggleRow(
                    title = "Offline Translation Mode",
                    subtitle = "Use downloaded on-device language packs instead of online UH Translate",
                    checked = mgrState.useOfflineEngine,
                    onCheckedChange = { UhTranslationManager.setUseOfflineEngine(it) }
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
                    subtitle = "Skip translation prompts for $sourceLangName pages",
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
                        subtitle = "Skip translation prompts on this website",
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
                        text = "$downloadedCount language pack${if (downloadedCount == 1) "" else "s"} saved on device",
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
                    text = "Saved packs stay on your device for private offline translation",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
        }

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
                    text = "Offline language packs will appear here once synced.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
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
                            text = "SAVED ON DEVICE (${downloadedModels.size})",
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
                    ModelState.DOWNLOADED -> "Saved offline${if (sizeStr.isNotBlank()) " · $sizeStr" else ""}"
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
            .background(MaterialTheme.colorScheme.background)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
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

/**
 * Source language dropdown:
 * - First item is always "Auto-detect" (showing detected language hint if known).
 * - Every language appears normally below it; picking ANY explicit language turns OFF Auto-detect immediately!
 */
@Composable
private fun SourceLanguageDropdown(
    expanded: Boolean,
    languages: List<Language>,
    isAutoDetect: Boolean,
    detectedCode: String?,
    selectedManualCode: String?,
    onSelectAuto: () -> Unit,
    onSelectLanguage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.heightIn(max = 340.dp)
    ) {
        val autoLabel = if (!detectedCode.isNullOrBlank()) {
            "Auto-detect (${UhTranslationManager.languageDisplayName(detectedCode)})"
        } else {
            "Auto-detect"
        }
        DropdownMenuItem(
            text = {
                Text(
                    text = autoLabel,
                    fontWeight = if (isAutoDetect) FontWeight.Bold else FontWeight.Medium,
                    color = if (isAutoDetect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            },
            onClick = onSelectAuto
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
        languages.forEach { lang ->
            val isExplicitSelected = !isAutoDetect && lang.code.equals(selectedManualCode, ignoreCase = true)
            DropdownMenuItem(
                text = {
                    Text(
                        text = lang.localizedDisplayName ?: lang.code.uppercase(),
                        fontWeight = if (isExplicitSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isExplicitSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                },
                onClick = { onSelectLanguage(lang.code) }
            )
        }
    }
}

@Composable
private fun TargetLanguageDropdown(
    expanded: Boolean,
    languages: List<Language>,
    selectedCode: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.heightIn(max = 340.dp)
    ) {
        languages.forEach { lang ->
            val isSelected = lang.code.equals(selectedCode, ignoreCase = true)
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
