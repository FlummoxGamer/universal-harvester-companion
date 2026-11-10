package app.harvester.companion.browser.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.harvester.companion.R
import app.harvester.companion.SettingsPrefs
import kotlinx.coroutines.delay

@Composable
fun UHSearchOverlay(
    url: String,
    tabCount: Int,
    iconState: Int,
    editText: String,
    suggestions: List<String>,
    onEditTextChanged: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onEditStart: () -> Unit,
    onClear: () -> Unit,
    onDismissRequest: () -> Unit,
    onSearchEngineClick: () -> Unit,
    onScriptMenuClick: () -> Unit,
    onMenuClick: () -> Unit,
    onTabCountClick: () -> Unit,
    onSuggestionClick: (String) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val isBarBottom = SettingsPrefs.getInt(context, SettingsPrefs.K_ADDRESS_BAR_POSITION, 1) == 1

    LaunchedEffect(Unit) {
        delay(100)
        keyboardController?.show()
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(modifier = Modifier.fillMaxSize().background(colorResource(id = R.color.bg))) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                if (!isBarBottom) {
                    UHAddressBar(
                        url = url,
                        isEditing = true,
                        tabCount = tabCount,
                        iconState = iconState,
                        isWallpaperActive = false,
                        editText = editText,
                        onEditTextChanged = onEditTextChanged,
                        onSubmit = {
                            if (it.isNotBlank()) {
                                focusManager.clearFocus()
                                onSubmit(it)
                            }
                        },
                        onEditStart = onEditStart,
                        onClear = onClear,
                        onSearchEngineClick = onSearchEngineClick,
                        onScriptMenuClick = onScriptMenuClick,
                        onMenuClick = onMenuClick,
                        onTabCountClick = onTabCountClick
                    )
                }

                if (suggestions.isNotEmpty()) {
                    UHSuggestionsPanel(
                        suggestions = suggestions,
                        onSuggestionClick = { suggestion ->
                            focusManager.clearFocus()
                            onSuggestionClick(suggestion)
                        },
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Box(modifier = Modifier.weight(1f))
                }

                if (isBarBottom) {
                    UHAddressBar(
                        url = url,
                        isEditing = true,
                        tabCount = tabCount,
                        iconState = iconState,
                        isWallpaperActive = false,
                        editText = editText,
                        onEditTextChanged = onEditTextChanged,
                        onSubmit = {
                            if (it.isNotBlank()) {
                                focusManager.clearFocus()
                                onSubmit(it)
                            }
                        },
                        onEditStart = onEditStart,
                        onClear = onClear,
                        onSearchEngineClick = onSearchEngineClick,
                        onScriptMenuClick = onScriptMenuClick,
                        onMenuClick = onMenuClick,
                        onTabCountClick = onTabCountClick
                    )
                }
            }
        }
    }
}
