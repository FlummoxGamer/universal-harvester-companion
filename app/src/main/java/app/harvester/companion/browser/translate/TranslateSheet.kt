package app.harvester.companion.browser.translate

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.painterResource
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
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val mgrState by UhTranslationManager.state.collectAsState()

    var currentPage by remember { mutableStateOf(TranslateSheetPage.Main) }

    val detectedDocLang = tabTranslationsState?.translationEngineState?.detectedLanguages?.documentLangTag
    val activePair = tabTranslationsState?.translationEngineState?.requestedTranslationPair
    val isTranslated = tabTranslationsState?.isTranslated == true
    val isProcessing = tabTranslationsState?.isTranslateProcessing == true ||
        tabTranslationsState?.isRestoreProcessing == true ||
        mgrState.isBusy

    var selectedFromCode by remember(detectedDocLang, activePair?.fromLanguage) {
        mutableStateOf(
            activePair?.fromLanguage?.ifBlank { null }
                ?: detectedDocLang?.ifBlank { null }
                ?: mgrState.fromLanguages.firstOrNull { it.code != mgrState.preferredTargetLang }?.code
                ?: "es"
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

    LaunchedEffect(mgrState.fromLanguages, detectedDocLang) {
        if (selectedFromCode.isBlank() && mgrState.fromLanguages.isNotEmpty()) {
            selectedFromCode = detectedDocLang?.takeIf { code -> mgrState.fromLanguages.any { it.code == code } }
                ?: mgrState.fromLanguages.firstOrNull { it.code != selectedToCode }?.code
                ?: mgrState.fromLanguages.first().code
        }
    }

    LaunchedEffect(selectedFromCode, selectedToCode) {
        if (selectedFromCode.isNotBlank() && selectedToCode.isNotBlank()) {
            UhTranslationManager.fetchPairDownloadSize(selectedFromCode, selectedToCode)
        }
    }

    val fromLabel = mgrState.fromLanguages.firstOrNull { it.code == selectedFromCode }?.localizedDisplayName
        ?: selectedFromCode.uppercase()
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
                        detectedDocLang = detectedDocLang,
                        fromCode = selectedFromCode,
                        toCode = selectedToCode,
                        fromLabel = fromLabel,
                        toLabel = toLabel,
                        isTranslated = isTranslated,
                        isProcessing = isProcessing,
                        onSelectFrom = { selectedFromCode = it },
                        onSelectTo = { selectedToCode = it },
                        onSwap = {
                            val tmp = selectedFromCode
                            selectedFromCode = selectedToCode
                            selectedToCode = tmp
                        },
                        onTranslate = {
                            UhTranslationManager.translateActiveTab(selectedFromCode, selectedToCode)
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
                    TranslateSettingsPanel(
                        mgrState = mgrState,
                        sourceLangCode = selectedFromCode,
                        sourceLangName = fromLabel,
                        currentHost = currentHost,
                        onBack = { currentPage = TranslateSheetPage.Main },
                        onOpenModels = { currentPage = TranslateSheetPage.Models },
                    )
                }

                TranslateSheetPage.Models -> {
                    OfflineModelsPanel(
                        models = mgrState.languageModels,
                        onBack = { currentPage = TranslateSheetPage.Settings },
                    )
                }
            }
        }
    }
}

@Composable
private fun TranslateMainPanel(
    mgrState: UhTranslationManager.State,
    detectedDocLang: String?,
    fromCode: String,
    toCode: String,
    fromLabel: String,
    toLabel: String,
    isTranslated: Boolean,
    isProcessing: Boolean,
    onSelectFrom: (String) -> Unit,
    onSelectTo: (String) -> Unit,
    onSwap: () -> Unit,
    onTranslate: () -> Unit,
    onShowOriginal: () -> Unit,
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
            .padding(bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
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
                        text = "Private on-device neural engine · No page redirects",
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

        if (mgrState.isLoadingLanguages && mgrState.fromLanguages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = "Loading translation languages…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            // Language selector card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // From dropdown
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
                                languages = mgrState.fromLanguages,
                                selectedCode = fromCode,
                                onSelect = {
                                    fromExpanded = false
                                    onSelectFrom(it)
                                },
                                onDismiss = { fromExpanded = false }
                            )
                        }

                        // Swap button
                        Surface(
                            onClick = onSwap,
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Text(
                                text = "⇄",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                            )
                        }

                        // To dropdown
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

                    val sizeHint = UhTranslationManager.formatBytes(mgrState.pairDownloadSizeBytes)
                    if (sizeHint.isNotBlank()) {
                        Text(
                            text = "First-time language pack download: ~$sizeHint (runs offline afterwards)",
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
                fontSize = 12.sp
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
                enabled = !isProcessing && mgrState.isEngineSupported && fromCode.isNotBlank() && toCode.isNotBlank() && fromCode != toCode,
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 26.dp),
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

        if (models.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Language packs will appear here once the translation engine finishes syncing.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(models, key = { it.language?.code ?: it.hashCode() }) { model ->
                    val lang = model.language ?: return@items
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
