package app.harvester.companion.data.downloads.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
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
        focusRequester.requestFocus()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colorResource(id = R.color.surface))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onDismiss) {
            Icon(
                painter = painterResource(id = R.drawable.ic_back),
                contentDescription = "Close Search",
                tint = colorResource(id = R.color.text)
            )
        }
        
        Box(
            modifier = Modifier
                .weight(1f)
                .background(colorResource(id = R.color.surface_2), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (textState.text.isEmpty()) {
                Text("Search downloads", color = colorResource(id = R.color.subtext), fontSize = 14.sp)
            }
            BasicTextField(
                state = textState,
                textStyle = TextStyle(color = colorResource(id = R.color.text), fontSize = 14.sp),
                cursorBrush = SolidColor(colorResource(id = R.color.accent)),
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
                    painter = painterResource(id = R.drawable.ic_close),
                    contentDescription = "Clear Search",
                    tint = colorResource(id = R.color.subtext),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
