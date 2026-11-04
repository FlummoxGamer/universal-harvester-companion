package app.harvester.companion.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import app.harvester.companion.R
import app.harvester.companion.SearchEnginePrefs

@Composable
fun HarvesterToolbar(
    url: String,
    isWallpaperActive: Boolean,
    tabCount: Int,
    onNavigate: (String) -> Unit
) {
    var text by remember { mutableStateOf(url) }
    val context = LocalContext.current
    val bottomBarColor = if (isWallpaperActive) Color.Transparent else colorResource(id = R.color.bg)
    val searchEngineEntry = remember(context) { SearchEnginePrefs.currentEntry(ctx = context) }

    LaunchedEffect(url) {
        if (url != "about:blank") text = url
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .background(bottomBarColor)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colorResource(id = R.color.surface_2))
                .border(1.dp, colorResource(id = R.color.border), RoundedCornerShape(16.dp))
                .padding(start = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {},
                modifier = Modifier.size(24.dp).padding(start = 4.dp, end = 8.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_code),
                    contentDescription = "Scripts",
                    tint = colorResource(id = R.color.text)
                )
            }

            Box(
                modifier = Modifier.size(36.dp),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = searchEngineEntry.iconUrl,
                    contentDescription = "Search Engine",
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
                    textStyle = TextStyle(color = colorResource(id = R.color.text), fontSize = 14.sp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        if (text.isNotBlank()) onNavigate(text)
                    }),
                    decorationBox = { innerTextField ->
                        if (text.isEmpty()) {
                            Text("Search or type URL", color = colorResource(id = R.color.subtext), fontSize = 14.sp)
                        }
                        innerTextField()
                    }
                )
                if (text.isNotEmpty()) {
                    IconButton(
                        onClick = { text = "" },
                        modifier = Modifier.align(Alignment.CenterEnd).size(28.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_close),
                            contentDescription = "Clear",
                            tint = colorResource(id = R.color.subtext)
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(24.dp)
                    .border(2.dp, colorResource(id = R.color.text), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("$tabCount", color = colorResource(id = R.color.text), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            IconButton(onClick = {}, modifier = Modifier.size(36.dp)) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_menu),
                    contentDescription = "Menu",
                    tint = colorResource(id = R.color.text)
                )
            }
        }
    }
}
