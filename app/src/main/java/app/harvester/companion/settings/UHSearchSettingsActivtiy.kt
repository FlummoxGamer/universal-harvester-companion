package app.harvester.companion.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.SearchEnginePrefs
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.browser.search.UHSearchEnginePicker
import app.harvester.companion.settings.components.SettingsCategory
import app.harvester.companion.settings.components.SettingsRow
import app.harvester.companion.settings.components.SettingsSwitchRow
import app.harvester.companion.theme.UHTheme

class UHSearchSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UHTheme {
                SearchSettingsScreen()
            }
        }
    }
}

@Composable
fun SearchSettingsScreen() {
    val context = LocalContext.current
    var showEnginePicker by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var engineName by remember { mutableStateOf(SearchEnginePrefs.getLabel(context)) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(id = R.color.bg))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Search",
                color = colorResource(id = R.color.text),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (showEnginePicker) {
            UHSearchEnginePicker(
                onDismiss = { showEnginePicker = false },
                onSelect = { 
                    showEnginePicker = false
                    engineName = SearchEnginePrefs.getLabel(context)
                }
            )
        }

        if (showAddDialog) {
            AddCustomEngineDialog(
                onDismiss = { showAddDialog = false },
                onAdd = { name, url ->
                    if (SearchEnginePrefs.addCustom(context, name, url)) {
                        engineName = SearchEnginePrefs.getLabel(context)
                    }
                    showAddDialog = false
                }
            )
        }

        LazyColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
            item { SettingsCategory("Search Engine") }
            item { SettingsRow("Default Search Engine", engineName, onClick = { showEnginePicker = true }) }
            item { SettingsRow("Add Custom Engine", "Create your own search engine", onClick = { showAddDialog = true }) }

            item { SettingsCategory("Suggestions") }
            item { SettingsSwitchRow("Show Search Suggestions", SettingsPrefs.K_SEARCH_SUGGESTIONS, default = true) }
            item { SettingsSwitchRow("Allow in Private Mode", SettingsPrefs.K_SEARCH_SUGGESTIONS_PRIVATE, default = false) }
            item { SettingsSwitchRow("Show History", SettingsPrefs.K_SEARCH_HISTORY, default = true) }
            item { SettingsSwitchRow("Show Bookmarks", SettingsPrefs.K_SEARCH_BOOKMARKS, default = true) }
            item { SettingsSwitchRow("Show Remote Suggestions", SettingsPrefs.K_SEARCH_REMOTE, default = true) }
            
            item { SettingsCategory("Address Bar") }
            item { SettingsSwitchRow("Show Clipboard", SettingsPrefs.K_SEARCH_CLIPBOARD, default = true) }
            item { SettingsSwitchRow("Autocomplete URLs", SettingsPrefs.K_SEARCH_AUTOCOMPLETE, default = true) }
            item { SettingsSwitchRow("Show Voice Search", SettingsPrefs.K_SEARCH_VOICE, default = true) }
        }
    }
}

@Composable
fun AddCustomEngineDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Custom Engine") },
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
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name, url) }) {
                Text("Add", color = colorResource(id = R.color.accent))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colorResource(id = R.color.subtext))
            }
        },
        containerColor = colorResource(id = R.color.surface_2),
        shape = RoundedCornerShape(16.dp)
    )
}
