package app.harvester.companion.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.harvester.companion.Shortcut
import app.harvester.companion.ShortcutStore

@Composable
fun ShortcutDialog(
    existing: Shortcut?,
    onDismiss: () -> Unit,
    onSave: (Shortcut) -> Unit
) {
    var url by remember { mutableStateOf(existing?.url ?: "") }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add Shortcut" else "Edit Shortcut") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL (Required)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val raw = url.trim()
                    if (raw.isBlank()) return@TextButton
                    val fixed = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
                    val finalName = if (name.isBlank()) ShortcutStore.shortNameFor(fixed) else name.trim()
                    val icon = ShortcutStore.iconUrlFor(fixed)
                    val currentId = existing?.id ?: java.util.UUID.randomUUID().toString()
                    onSave(Shortcut(id = currentId, name = finalName, url = fixed, iconUrl = icon))
                },
                enabled = url.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}
