package app.harvester.companion.settings

import android.graphics.Color
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import app.harvester.companion.R
import app.harvester.companion.SearchEnginePrefs
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.browser.search.UHSearchEnginePicker
import app.harvester.companion.settings.components.SettingsCategory
import app.harvester.companion.settings.components.SettingsRow
import app.harvester.companion.settings.components.SettingsSwitchRow
import app.harvester.companion.settings.components.UhSettingsCardGroup
import app.harvester.companion.settings.components.UhSettingsDivider
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme

class UHSearchSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        setContent {
            UHTheme {
                SearchSettingsScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
fun SearchSettingsScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    var showEnginePicker by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    val activeEngine by SearchEnginePrefs.activeEngineFlow(context).collectAsState()

    if (showEnginePicker) {
        UHSearchEnginePicker(
            onDismiss = { showEnginePicker = false },
            onSelect = { showEnginePicker = false }
        )
    }

    if (showAddDialog) {
        AddCustomEngineDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, url ->
                SearchEnginePrefs.addCustom(context, name, url)
                showAddDialog = false
            }
        )
    }

    UhSettingsScaffold(
        title = "Search",
        onBack = onBack
    ) {
        SettingsCategory("Search Engine")
        UhSettingsCardGroup {
            SettingsRow(
                title = "Default Search Engine",
                subtitle = activeEngine.name,
                iconRes = R.drawable.fx_ic_search_24,
                showChevron = true,
                insideCard = true,
                onClick = { showEnginePicker = true }
            )
            UhSettingsDivider()
            SettingsRow(
                title = "Add Custom Engine",
                subtitle = "Create your own search engine",
                iconRes = R.drawable.fx_ic_link_24,
                showChevron = true,
                insideCard = true,
                onClick = { showAddDialog = true }
            )
        }

        SettingsCategory("Suggestions")
        UhSettingsCardGroup {
            SettingsSwitchRow(
                title = "Show Search Suggestions",
                key = SettingsPrefs.K_SEARCH_SUGGESTIONS,
                default = true,
                insideCard = true
            )
            UhSettingsDivider()
            SettingsSwitchRow(
                title = "Allow in Private Mode",
                key = SettingsPrefs.K_SEARCH_SUGGESTIONS_PRIVATE,
                default = false,
                insideCard = true
            )
            UhSettingsDivider()
            SettingsSwitchRow(
                title = "Show History",
                key = SettingsPrefs.K_SEARCH_HISTORY,
                default = true,
                insideCard = true
            )
            UhSettingsDivider()
            SettingsSwitchRow(
                title = "Show Bookmarks",
                key = SettingsPrefs.K_SEARCH_BOOKMARKS,
                default = true,
                insideCard = true
            )
            UhSettingsDivider()
            SettingsSwitchRow(
                title = "Show Remote Suggestions",
                key = SettingsPrefs.K_SEARCH_REMOTE,
                default = true,
                insideCard = true
            )
        }

        SettingsCategory("Address Bar")
        UhSettingsCardGroup {
            SettingsSwitchRow(
                title = "Show Clipboard",
                key = SettingsPrefs.K_SEARCH_CLIPBOARD,
                default = true,
                insideCard = true
            )
            UhSettingsDivider()
            SettingsSwitchRow(
                title = "Autocomplete URLs",
                key = SettingsPrefs.K_SEARCH_AUTOCOMPLETE,
                default = true,
                insideCard = true
            )
            UhSettingsDivider()
            SettingsSwitchRow(
                title = "Show Voice Search",
                key = SettingsPrefs.K_SEARCH_VOICE,
                default = true,
                insideCard = true
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun AddCustomEngineDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Custom Engine", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("https://example.com") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name, url) }) {
                Text("Add", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp)
    )
}
