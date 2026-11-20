package app.harvester.companion.data.downloads.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R

@Composable
fun UhDownloadSearchBar(
    query: String,
    onQueryChanged: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val textState = rememberTextFieldState(query)

    LaunchedEffect(query) {
        if (textState.text.toString() != query) {
            textState.edit {
                replace(0, length, query)
                placeCursorAtEnd()
            }
        }
    }

    LaunchedEffect(textState.text) {
        onQueryChanged(textState.text.toString())
    }

    LaunchedEffect(Unit) {
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onDismiss) {
            Icon(
                painter = painterResource(id = R.drawable.fx_ic_back_24),
                contentDescription = stringResource(R.string.download_navigate_back_description),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (textState.text.isEmpty()) {
                Text(
                    stringResource(R.string.download_search_placeholder),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 15.sp
                )
            }
            BasicTextField(
                state = textState,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
            )
        }

        if (textState.text.isNotEmpty()) {
            IconButton(onClick = {
                textState.edit {
                    replace(0, length, "")
                    placeCursorAtEnd()
                }
                onQueryChanged("")
            }) {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_close_24),
                    contentDescription = "Clear Search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
