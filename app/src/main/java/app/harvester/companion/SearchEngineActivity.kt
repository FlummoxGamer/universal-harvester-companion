package app.harvester.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import app.harvester.companion.theme.UHTheme

class SearchEngineActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UHTheme {
                SearchEngineScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchEngineScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var engines by remember { mutableStateOf(SearchEnginePrefs.all(context)) }
    var currentId by remember { mutableStateOf(SearchEnginePrefs.getEngine(context)) }
    
    var showAddDialog by remember { mutableStateOf(false) }
    var deleteEngineId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(id = R.color.bg))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Search Engine",
                    color = colorResource(id = R.color.text),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            IconButton(onClick = { showAddDialog = true }) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Engine",
                    tint = colorResource(id = R.color.accent)
                )
            }
        }

        LazyColumn(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            items(engines) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (entry.id == currentId) colorResource(id = R.color.surface_3) else colorResource(id = R.color.surface))
                        .combinedClickable(
                            onClick = {
                                SearchEnginePrefs.setEngine(context, entry.id)
                                currentId = entry.id
                            },
                            onLongClick = {
                                if (entry.isCustom) {
                                    deleteEngineId = entry.id
                                }
                            }
                        )
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = entry.iconUrl,
                        contentDescription = entry.name,
                        modifier = Modifier.size(32.dp)
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 16.dp)
                    ) {
                        Text(
                            text = entry.name,
                            color = if (entry.id == currentId) colorResource(id = R.color.accent) else colorResource(id = R.color.text),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = entry.host,
                            color = colorResource(id = R.color.subtext),
                            fontSize = 12.sp
                        )
                    }
                    if (entry.id == currentId) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = colorResource(id = R.color.accent)
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddCustomEngineDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, url ->
                if (SearchEnginePrefs.addCustom(context, name, url)) {
                    engines = SearchEnginePrefs.all(context)
                }
                showAddDialog = false
            }
        )
    }

    if (deleteEngineId != null) {
        AlertDialog(
            onDismissRequest = { deleteEngineId = null },
            title = { Text("Delete Engine?") },
            text = { Text("Are you sure you want to remove this custom search engine?") },
            confirmButton = {
                TextButton(onClick = {
                    val id = deleteEngineId
                    if (id != null) {
                        SearchEnginePrefs.deleteCustom(context, id)
                        if (currentId == id) {
                            currentId = SearchEnginePrefs.ENGINE_BRAVE
                            SearchEnginePrefs.setEngine(context, currentId)
                        }
                        engines = SearchEnginePrefs.all(context)
                    }
                    deleteEngineId = null
                }) {
                    Text("Delete", color = colorResource(id = R.color.red))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteEngineId = null }) {
                    Text("Cancel", color = colorResource(id = R.color.subtext))
                }
            },
            containerColor = colorResource(id = R.color.surface_2)
        )
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
