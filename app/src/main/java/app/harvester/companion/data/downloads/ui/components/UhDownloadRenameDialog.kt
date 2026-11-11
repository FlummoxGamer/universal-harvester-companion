package app.harvester.companion.data.downloads.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import app.harvester.companion.R

@Composable
fun UhDownloadRenameDialog(
    originalName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var newName by remember { mutableStateOf(originalName) }
    val isError = newName.isBlank() || newName == originalName || newName.contains("/")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Download") },
        text = {
            Column {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    isError = isError,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isError && newName != originalName) {
                    Text(
                        text = "Invalid name",
                        color = colorResource(id = R.color.red),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(newName) },
                enabled = !isError
            ) {
                Text("Save", color = colorResource(id = R.color.accent))
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
