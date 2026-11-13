package app.harvester.companion.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.io.File
import app.harvester.companion.R
import app.harvester.companion.data.downloads.UhRenameFileError

@Composable
fun RenameAndChangeLocationDialogContent(
    dialogState: RenameAndChangeLocationDialogState,
    friendlyPath: String,
    title: String,
    onFileNameChange: (String) -> Unit,
    onDirectorySelect: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val (initialBaseName, extension) = remember {
        val file = File(dialogState.fileName)
        file.nameWithoutExtension to file.extension
    }

    var fileNameState by remember {
        mutableStateOf(TextFieldValue(text = initialBaseName, selection = TextRange(0, initialBaseName.length)))
    }

    val focusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }

    LaunchedEffect(isFocused) {
        if (isFocused) {
            val text = fileNameState.text
            val end = File(text).nameWithoutExtension.length
            fileNameState = fileNameState.copy(selection = TextRange(0, end))
        }
    }

    val currentError: UhRenameFileError? =
        when {
            fileNameState.text.contains("/") -> UhRenameFileError.InvalidFileName
            else -> null
        }

    val trimmedBaseName = fileNameState.text.trim()
    val fullName = if (extension.isEmpty()) trimmedBaseName else "$trimmedBaseName.$extension"
    val isConfirmEnabled = enableConfirmButton(fileName = fullName)

    Column(
        modifier = Modifier.padding(all = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {

            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(24.dp))

            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = fileNameState,
                    onValueChange = { newFileNameState ->
                        fileNameState = newFileNameState
                        val newBase = newFileNameState.text.trim()
                        val newFullName = if (extension.isEmpty()) newBase else "$newBase.$extension"
                        onFileNameChange(newFullName)
                    },
                    isError = currentError != null,
                    label = { Text(stringResource(R.string.download_rename_dialog_label)) },
                    suffix = { if (extension.isNotEmpty()) Text(".$extension") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focusState -> isFocused = focusState.isFocused },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { onDirectorySelect() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.mozac_ic_folder_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = friendlyPath,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.download_rename_dialog_cancel_button))
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(onClick = onConfirm, enabled = isConfirmEnabled) {
                Text(stringResource(R.string.download_rename_dialog_confirm_button))
            }
        }
    }
}

internal fun enableConfirmButton(fileName: String): Boolean {
    val isInvalidRename = fileName.isEmpty() || '/' in fileName || '\u0000' in fileName
    if (isInvalidRename) return false
    val base = File(fileName).nameWithoutExtension
    return base.isNotBlank()
}

data class RenameAndChangeLocationDialogState(
    val fileName: String = "",
    val directoryPath: String = "",
)
