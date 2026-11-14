package app.harvester.companion.browser.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.harvester.companion.R
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
    onLockClick: () -> Unit,
    onScriptMenuClick: () -> Unit,
    onMenuClick: () -> Unit,
    onTabCountClick: () -> Unit,
    onSuggestionClick: (String) -> Unit,
    searchSuggestionsContent: @Composable () -> Unit = {}
) {
    
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        delay(300)
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
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .windowInsetsPadding(WindowInsets.ime)
            ) {
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
                    onLockClick = onLockClick,
                    onScriptMenuClick = onScriptMenuClick,
                    onMenuClick = onMenuClick,
                    onTabCountClick = onTabCountClick
                )

                searchSuggestionsContent()
            }
        }
    }
}
