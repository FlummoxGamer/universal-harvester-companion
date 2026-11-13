package app.harvester.companion.settings.trustpanel.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.harvester.companion.R

@Composable
fun ClearSiteDataDialog(
    baseDomain: String,
    onClearSiteDataClick: () -> Unit,
    onCancelClick: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(text = "Clear site data?") },
        text = { Text(text = "This will clear all cookies and site data for $baseDomain.") },
        confirmButton = {
            TextButton(onClick = { onClearSiteDataClick() }) {
                Text("Clear")
            }
        },
        dismissButton = {
            TextButton(onClick = { onCancelClick() }) {
                Text("Cancel")
            }
        },
    )
}
