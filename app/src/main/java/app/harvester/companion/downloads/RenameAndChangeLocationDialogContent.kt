package app.harvester.companion.downloads

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.data.downloads.UhRenameFileError
import java.io.File

@Composable
fun RenameAndChangeLocationDialogContent(
    dialogState: RenameAndChangeLocationDialogState,
    friendlyPath: String,
    title: String,
    onFileNameChange: (String) -> Unit,
    onRememberChange: (Boolean) -> Unit,
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
            fileNameState = fileNameState.copy(selection = TextRange(0, text.length))
        }
    }

    val currentError: UhRenameFileError? =
        when {
            fileNameState.text.contains("/") || fileNameState.text.contains("\u0000") -> UhRenameFileError.InvalidFileName
            else -> null
        }

    val trimmedBaseName = fileNameState.text.trim()
    val fullName = if (extension.isEmpty()) trimmedBaseName else "$trimmedBaseName.$extension"
    val isConfirmEnabled = enableConfirmButton(fileName = fullName)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(14.dp))

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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (isConfirmEnabled) onConfirm()
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState -> isFocused = focusState.isFocused },
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.download_save_in_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .clickable { onDirectorySelect() }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_folder_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = friendlyPath,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.download_change_folder),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onRememberChange(!dialogState.rememberAsDefault) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = dialogState.rememberAsDefault,
                    onCheckedChange = onRememberChange
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.download_remember_folder_checkbox),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
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
    val rememberAsDefault: Boolean = false,
)
