package app.harvester.companion.browser.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R

@Composable
fun HarvesterToolbar(
    url: String,
    onNavigate: (String) -> Unit
) {
    var text by remember { mutableStateOf(url) }

    LaunchedEffect(url) {
        if (text.isEmpty() || url != "about:blank") {
            text = url
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .background(Color(0xFF0A0F1A))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(52.dp)
                .background(color = Color(0xFF1E2329), shape = RoundedCornerShape(16.dp))
                .padding(start = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {}) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_code),
                    contentDescription = "Scripts",
                    tint = Color(0xFFE6E9EE),
                    modifier = Modifier.size(24.dp)
                )
            }

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_search),
                    contentDescription = "Search",
                    tint = Color(0xFFE6E9EE),
                    modifier = Modifier.size(24.dp)
                )
            }

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = Color(0xFFE6E9EE), fontSize = 14.sp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        if (text.isNotBlank()) onNavigate(text)
                    }),
                    decorationBox = { innerTextField ->
                        if (text.isEmpty()) {
                            Text("Search or type URL", color = Color(0xFF8A96A8), fontSize = 14.sp)
                        }
                        innerTextField()
                    }
                )
            }

            if (text.isNotEmpty()) {
                IconButton(onClick = { text = "" }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_close),
                        contentDescription = "Clear",
                        tint = Color(0xFF8A96A8)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .size(24.dp)
                .background(Color.Transparent, shape = RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("1", color = Color(0xFFE6E9EE), fontSize = 12.sp)
        }

        IconButton(onClick = {}, modifier = Modifier.size(36.dp)) {
            Icon(
                painter = painterResource(id = R.drawable.ic_menu),
                contentDescription = "Menu",
                tint = Color(0xFFE6E9EE)
            )
        }
    }
}
