package app.harvester.companion.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.SearchEnginePrefs
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.browser.search.UHSearchEnginePicker
import app.harvester.companion.theme.UHTheme

class UHSearchSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UHTheme {
                SearchSettingsScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
fun SearchSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var showEnginePicker by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var engineName by remember { mutableStateOf(SearchEnginePrefs.getLabel(context)) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(id = R.color.bg))
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Search Settings",
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

        SettingsCategory("Search Engine")
        SettingsRow("Default Search Engine", engineName, onClick = { showEnginePicker = true })
        SettingsRow("Add Custom Engine", "Create your own search engine", onClick = { showAddDialog = true })

        SettingsCategory("Suggestions")
        SettingsSwitchRow("Show Search Suggestions", SettingsPrefs.K_SEARCH_SUGGESTIONS, default = true)
        SettingsSwitchRow("Allow in Private Mode", SettingsPrefs.K_SEARCH_SUGGESTIONS_PRIVATE, default = false)
        SettingsSwitchRow("Show History", SettingsPrefs.K_SEARCH_HISTORY, default = true)
        SettingsSwitchRow("Show Bookmarks", SettingsPrefs.K_SEARCH_BOOKMARKS, default = true)
        SettingsSwitchRow("Show Remote Suggestions", SettingsPrefs.K_SEARCH_REMOTE, default = true)
        
        SettingsCategory("Address Bar")
        SettingsSwitchRow("Show Clipboard", SettingsPrefs.K_SEARCH_CLIPBOARD, default = true)
        SettingsSwitchRow("Autocomplete URLs", SettingsPrefs.K_SEARCH_AUTOCOMPLETE, default = true)
        SettingsSwitchRow("Show Voice Search", SettingsPrefs.K_SEARCH_VOICE, default = true)
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
        containerColor = colorResource(id = R.color.surface_2)
    )
}

@Composable
fun SettingsCategory(title: String) {
    Text(
        text = title.uppercase(),
        color = colorResource(id = R.color.subtext),
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
    )
}

@Composable
fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colorResource(id = R.color.surface))
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = colorResource(id = R.color.text), fontSize = 16.sp)
            Text(text = subtitle, color = colorResource(id = R.color.subtext), fontSize = 12.sp)
        }
    }
}

@Composable
fun SettingsSwitchRow(title: String, key: String, default: Boolean) {
    val context = LocalContext.current
    var isChecked by remember { mutableStateOf(SettingsPrefs.getBool(context, key, default)) }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colorResource(id = R.color.surface))
            .clickable { 
                isChecked = !isChecked
                SettingsPrefs.setBool(context, key, isChecked)
            }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = colorResource(id = R.color.text),
            fontSize = 16.sp,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = isChecked,
            onCheckedChange = { 
                isChecked = it
                SettingsPrefs.setBool(context, key, it)
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = colorResource(id = R.color.bg),
                checkedTrackColor = colorResource(id = R.color.accent)
            )
        )
    }
}
